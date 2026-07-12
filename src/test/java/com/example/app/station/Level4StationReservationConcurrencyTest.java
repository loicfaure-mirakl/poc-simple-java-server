package com.example.app.station;

import com.example.app.App;
import com.example.app.config.Database;
import com.example.app.reservation.domain.Reservation;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static com.example.app.jooq.generated.Tables.BIKE;
import static com.example.app.jooq.generated.Tables.RESERVATION;
import static com.example.app.jooq.generated.Tables.STATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Level 4 (see BIKE_FEATURES.md): reserving "any available bike" at a station is a
 * check-then-act across two rows (the station's available count and the individual bike row) —
 * the classic TOCTOU trap called out in BIKE_CONCURRENCY_HINTS.md. Under N concurrent reserve
 * attempts on a station with only K available bikes, exactly K must win, each getting a
 * different bike, and the station's available count must land exactly on zero.
 *
 * Fixtures are inserted directly via jOOQ against the STATION/BIKE tables so this test doesn't
 * depend on how the station package/domain ends up being implemented — only on the schema and
 * on the single endpoint under test: POST /stations/{id}/reserve.
 */
@Testcontainers
class Level4StationReservationConcurrencyTest {

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
        dsl.deleteFrom(RESERVATION).execute();
        dsl.deleteFrom(BIKE).execute();
        dsl.deleteFrom(STATION).execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @Test
    void reservingFromStationWithAvailableBikeSucceeds() {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 2);
            UUID bikeId = addAvailableBike(stationId, "bike-1");

            var response = client.post("/stations/" + stationId + "/reserve");

            assertThat(response.code()).isEqualTo(201);
            Reservation reservation = json.readValue(response.body().string(), Reservation.class);
            assertThat(reservation.bikeId()).isEqualTo(bikeId);
            assertThat(bikeStatus(bikeId)).isEqualTo("RESERVED");
            assertThat(availableCount(stationId)).isEqualTo(0);
        });
    }

    @Test
    void reservingFromUnknownStationReturns404() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.post("/stations/" + UUID.randomUUID() + "/reserve");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void reservingFromStationWithNoAvailableBikesReturns409() {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 2);

            var response = client.post("/stations/" + stationId + "/reserve");

            assertThat(response.code()).isEqualTo(409);
        });
    }

    @Test
    void concurrentReservationsAtStationHandOutEachBikeAtMostOnce() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 5);
            Set<UUID> availableBikes = Set.of(
                    addAvailableBike(stationId, "bike-1"),
                    addAvailableBike(stationId, "bike-2"),
                    addAvailableBike(stationId, "bike-3"));
            int attempts = 10;

            List<CallResult> results = raceConcurrently(attempts,
                    i -> callReserve(client, stationId));

            List<CallResult> wins = results.stream().filter(r -> r.code == 201).collect(Collectors.toList());
            List<CallResult> losses = results.stream().filter(r -> r.code == 409).collect(Collectors.toList());

            assertThat(wins).hasSize(availableBikes.size());
            assertThat(losses).hasSize(attempts - availableBikes.size());

            List<UUID> wonBikeIds = wins.stream()
                    .map(r -> readReservation(r.body).bikeId())
                    .collect(Collectors.toList());

            // every winner got a different bike, and every winning bike really was one of the
            // ones we set up as available for this station
            assertThat(wonBikeIds).doesNotHaveDuplicates();
            assertThat(wonBikeIds).allMatch(availableBikes::contains);

            for (UUID bikeId : availableBikes) {
                assertThat(bikeStatus(bikeId)).isEqualTo("RESERVED");
            }
            assertThat(availableCount(stationId)).isEqualTo(0);
        });
    }

    private record CallResult(int code, String body) {
    }

    private CallResult callReserve(HttpClient client, UUID stationId) {
        var response = client.post("/stations/" + stationId + "/reserve");
        return new CallResult(response.code(), response.body().string());
    }

    private Reservation readReservation(String body) {
        try {
            return json.readValue(body, Reservation.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<CallResult> raceConcurrently(int attempts, IntFunction<CallResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallResult>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return call.apply(index);
            }));
        }

        ready.await();
        start.countDown();

        List<CallResult> results = new ArrayList<>();
        for (Future<CallResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
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

    private String bikeStatus(UUID bikeId) {
        return dsl.select(BIKE.STATUS)
                .from(BIKE)
                .where(BIKE.ID.eq(bikeId))
                .fetchOne(BIKE.STATUS);
    }

    private int availableCount(UUID stationId) {
        return dsl.select(STATION.AVAILABLE_COUNT)
                .from(STATION)
                .where(STATION.ID.eq(stationId))
                .fetchOne(STATION.AVAILABLE_COUNT);
    }
}
