package com.example.app.waitlist;

import com.example.app.App;
import com.example.app.config.Database;
import com.example.app.reservation.domain.Reservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.testtools.HttpClient;
import io.javalin.testtools.JavalinTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.app.jooq.generated.Tables.BIKE;
import static com.example.app.jooq.generated.Tables.RESERVATION;
import static com.example.app.jooq.generated.Tables.STATION;
import static com.example.app.jooq.generated.Tables.WAITLIST_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Level 6 (see BIKE_FEATURES.md): a station's waitlist must hand out freed bikes in
 * strict FIFO order, and under concurrent releases (two bikes freed at once with several
 * waiters queued) each freed bike must go to a distinct waiter, in queue order, with no
 * double-assignment (see BIKE_CONCURRENCY_HINTS.md — FOR UPDATE SKIP LOCKED on the queue head).
 *
 * Target contract this test assumes (nothing else in the codebase references it yet), chosen
 * per your call to design the waitlist per-station and assign inline at release time (no
 * separate claim step, no outbox — the release path itself hands the bike to the queue head):
 *   POST /stations/{id}/waitlist  -> 201 {"id", "stationId", "status": "WAITING", "reservationId": null}
 *   GET  /waitlist/{id}           -> 200 same shape, "status": "ASSIGNED" and "reservationId" set
 *                                     once a release has handed this entry a bike; 404 if unknown
 * Deliberately avoids importing any hand-written domain/DTO class for the waitlist — only the
 * JSON field names above are assumed — so this test doesn't collide with however you shape the
 * actual WaitlistEntry/repository/controller, only with the wire contract.
 */
@Testcontainers
class Level6WaitlistConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    private static DSLContext dsl;
    private static Clock clock;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void initDatabase() {
        dsl = Database.init(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        clock = Clock.systemUTC();
    }

    @BeforeEach
    void clearTables() {
        dsl.deleteFrom(WAITLIST_ENTRY).execute();
        dsl.deleteFrom(RESERVATION).execute();
        dsl.deleteFrom(BIKE).execute();
        dsl.deleteFrom(STATION).execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @Test
    void joiningWaitlistReturnsWaitingEntry() {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 1);

            var response = client.post("/stations/" + stationId + "/waitlist");

            assertThat(response.code()).isEqualTo(201);
            JsonNode entry = json.readTree(response.body().string());
            assertThat(entry.get("stationId").asText()).isEqualTo(stationId.toString());
            assertThat(entry.get("status").asText()).isEqualTo("WAITING");
            assertThat(entry.get("reservationId").isNull()).isTrue();
        });
    }

    @Test
    void joiningWaitlistForUnknownStationReturns404() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.post("/stations/" + UUID.randomUUID() + "/waitlist");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void releasingABikeAssignsItToTheWaitingEntry() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 1);
            addAvailableBike(stationId, "bike-1");
            UUID reservationId = reserveAtStation(client, stationId);

            UUID entryId = joinWaitlist(client, stationId);
            assertThat(pollStatus(client, entryId)).isEqualTo("WAITING");

            var cancelResponse = client.put("/reservations/" + reservationId + "/cancel");
            assertThat(cancelResponse.code()).isEqualTo(200);

            JsonNode entry = json.readTree(client.get("/waitlist/" + entryId).body().string());
            assertThat(entry.get("status").asText()).isEqualTo("ASSIGNED");
            UUID assignedReservationId = UUID.fromString(entry.get("reservationId").asText());
            assertThat(assignedReservationId).isNotEqualTo(reservationId);

            Reservation assigned = json.readValue(
                    client.get("/reservations/" + assignedReservationId).body().string(), Reservation.class);
            assertThat(assigned.status().name()).isEqualTo("CREATED");

            // the freed bike went straight to the waiter, not back to the general pool
            assertThat(availableCount(stationId)).isEqualTo(0);
        });
    }

    @Test
    void waitlistEntriesAreAssignedInFifoOrder() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 1);
            addAvailableBike(stationId, "bike-1");
            UUID reservationId = reserveAtStation(client, stationId);

            UUID first = joinWaitlist(client, stationId);
            UUID second = joinWaitlist(client, stationId);
            UUID third = joinWaitlist(client, stationId);

            client.put("/reservations/" + reservationId + "/cancel");

            assertThat(pollStatus(client, first)).isEqualTo("ASSIGNED");
            assertThat(pollStatus(client, second)).isEqualTo("WAITING");
            assertThat(pollStatus(client, third)).isEqualTo("WAITING");
        });
    }

    @Test
    void concurrentReleasesAssignDistinctWaitersInFifoOrderWithoutDoubleAssignment() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 2);
            addAvailableBike(stationId, "bike-1");
            addAvailableBike(stationId, "bike-2");
            UUID reservation1 = reserveAtStation(client, stationId);
            UUID reservation2 = reserveAtStation(client, stationId);

            List<UUID> waiters = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                waiters.add(joinWaitlist(client, stationId));
            }

            raceConcurrently(List.of(reservation1, reservation2), reservationId ->
                    client.put("/reservations/" + reservationId + "/cancel").code());

            assertThat(pollStatus(client, waiters.get(0))).isEqualTo("ASSIGNED");
            assertThat(pollStatus(client, waiters.get(1))).isEqualTo("ASSIGNED");
            assertThat(pollStatus(client, waiters.get(2))).isEqualTo("WAITING");
            assertThat(pollStatus(client, waiters.get(3))).isEqualTo("WAITING");
            assertThat(pollStatus(client, waiters.get(4))).isEqualTo("WAITING");

            UUID assignedReservation1 = pollAssignedReservationId(client, waiters.get(0));
            UUID assignedReservation2 = pollAssignedReservationId(client, waiters.get(1));
            assertThat(assignedReservation1).isNotEqualTo(assignedReservation2);

            Reservation r1 = json.readValue(client.get("/reservations/" + assignedReservation1).body().string(), Reservation.class);
            Reservation r2 = json.readValue(client.get("/reservations/" + assignedReservation2).body().string(), Reservation.class);
            assertThat(r1.bikeId()).isNotEqualTo(r2.bikeId());

            assertThat(availableCount(stationId)).isEqualTo(0);
        });
    }

    private void raceConcurrently(List<UUID> items, java.util.function.Function<UUID, Integer> call) throws Exception {
        int attempts = items.size();
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (UUID item : items) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return call.apply(item);
            }));
        }

        ready.await();
        start.countDown();

        for (Future<Integer> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private String pollStatus(HttpClient client, UUID entryId) throws Exception {
        var response = client.get("/waitlist/" + entryId);
        return json.readTree(response.body().string()).get("status").asText();
    }

    private UUID pollAssignedReservationId(HttpClient client, UUID entryId) throws Exception {
        var response = client.get("/waitlist/" + entryId);
        return UUID.fromString(json.readTree(response.body().string()).get("reservationId").asText());
    }

    private UUID joinWaitlist(HttpClient client, UUID stationId) throws Exception {
        var response = client.post("/stations/" + stationId + "/waitlist");
        return UUID.fromString(json.readTree(response.body().string()).get("id").asText());
    }

    private UUID reserveAtStation(HttpClient client, UUID stationId) throws Exception {
        var response = client.post("/stations/" + stationId + "/reserve");
        Reservation reservation = json.readValue(response.body().string(), Reservation.class);
        return reservation.id();
    }

    private UUID createStation(String name, int capacity) {
        return dsl.insertInto(STATION)
                .set(STATION.ID, UUID.randomUUID())
                .set(STATION.NAME, name)
                .set(STATION.CAPACITY, capacity)
                .set(STATION.AVAILABLE_COUNT, 0)
                .returning(STATION.ID)
                .fetchOne(STATION.ID);
    }

    private UUID addAvailableBike(UUID stationId, String code) {
        UUID bikeId = UUID.randomUUID();
        dsl.insertInto(BIKE)
                .set(BIKE.ID, bikeId)
                .set(BIKE.CODE, code)
                .set(BIKE.STATUS, "AVAILABLE")
                .set(BIKE.STATION_ID, stationId)
                .execute();
        dsl.update(STATION)
                .set(STATION.AVAILABLE_COUNT, STATION.AVAILABLE_COUNT.add(1))
                .where(STATION.ID.eq(stationId))
                .execute();
        return bikeId;
    }

    private int availableCount(UUID stationId) {
        return dsl.select(STATION.AVAILABLE_COUNT)
                .from(STATION)
                .where(STATION.ID.eq(stationId))
                .fetchOne(STATION.AVAILABLE_COUNT);
    }
}
