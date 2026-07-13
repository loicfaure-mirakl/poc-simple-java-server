package com.example.app.bike;

import com.example.app.App;
import com.example.app.bike.domain.Bike;
import com.example.app.bike.domain.Status;
import com.example.app.config.Database;
import com.example.app.reservation.domain.Reservation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.testtools.HttpClient;
import io.javalin.testtools.JavalinTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.example.app.jooq.generated.Tables.BIKE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Level 7 (see BIKE_FEATURES.md): reserving several specific bikes in one request must
 * be all-or-nothing, and concurrent batch requests whose bike sets overlap must never deadlock
 * regardless of the order bike IDs are listed in — the fix is locking bikes in a consistent
 * order (e.g. sorted), not the order the caller happened to list them
 * (see BIKE_CONCURRENCY_HINTS.md — Level 7).
 *
 * Target contract this test assumes (nothing else in the codebase references it yet):
 *   POST /bikes/batch-reserve  body {"bikeIds": [...]}
 *     -> 201 with a JSON array of Reservation, one per bike, all created together
 *     -> 404 if any bike ID is unknown, nothing reserved
 *     -> 409 if any bike isn't AVAILABLE, nothing reserved
 * Deliberately reuses the existing Reservation shape (no new grouping entity) since no later
 * level needs batch reservations tracked as a group.
 */
@Testcontainers
class Level7BatchReservationConcurrencyTest {

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
        dsl.deleteFrom(com.example.app.jooq.generated.Tables.WAITLIST_ENTRY).execute();
        dsl.deleteFrom(com.example.app.jooq.generated.Tables.RESERVATION).execute();
        dsl.deleteFrom(BIKE).execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @Test
    void batchReservingAvailableBikesSucceedsForAll() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bikeA = createBike(client, "bike-a");
            Bike bikeB = createBike(client, "bike-b");
            Bike bikeC = createBike(client, "bike-c");

            var response = batchReserve(client, List.of(bikeA.id(), bikeB.id(), bikeC.id()));

            assertThat(response.code()).isEqualTo(201);
            List<Reservation> reservations = json.readValue(response.body(), new TypeReference<>() {
            });
            assertThat(reservations).hasSize(3);
            assertThat(reservations).extracting(Reservation::bikeId)
                    .containsExactlyInAnyOrder(bikeA.id(), bikeB.id(), bikeC.id());
            assertThat(bikeStatus(bikeA.id())).isEqualTo(Status.RESERVED);
            assertThat(bikeStatus(bikeB.id())).isEqualTo(Status.RESERVED);
            assertThat(bikeStatus(bikeC.id())).isEqualTo(Status.RESERVED);
        });
    }

    @Test
    void batchReservingFailsAtomicallyWhenOneBikeUnavailable() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bikeA = createBike(client, "bike-a");
            Bike bikeB = createBike(client, "bike-b");
            Bike bikeC = createBike(client, "bike-c");
            client.post("/bikes/" + bikeB.id() + "/reserve"); // bikeB is no longer AVAILABLE

            var response = batchReserve(client, List.of(bikeA.id(), bikeB.id(), bikeC.id()));

            assertThat(response.code()).isEqualTo(409);
            // the two bikes that WERE available must not have been reserved by this failed batch
            assertThat(bikeStatus(bikeA.id())).isEqualTo(Status.AVAILABLE);
            assertThat(bikeStatus(bikeC.id())).isEqualTo(Status.AVAILABLE);
            assertThat(reservationCountFor(bikeA.id())).isZero();
            assertThat(reservationCountFor(bikeC.id())).isZero();
        });
    }

    @Test
    void batchReservingUnknownBikeReturns404AndRollsBackEverything() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bikeA = createBike(client, "bike-a");
            Bike bikeB = createBike(client, "bike-b");

            var response = batchReserve(client, List.of(bikeA.id(), UUID.randomUUID(), bikeB.id()));

            assertThat(response.code()).isEqualTo(404);
            assertThat(bikeStatus(bikeA.id())).isEqualTo(Status.AVAILABLE);
            assertThat(bikeStatus(bikeB.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    @RepeatedTest(5)
    void onlyOneOfOverlappingConcurrentBatchesWins() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bikeA = createBike(client, "bike-a");
            Bike bikeB = createBike(client, "bike-b");

            // same two bikes, opposite listing order — a naive implementation that locks rows
            // in request-array order (instead of a canonical order) risks a genuine Postgres
            // deadlock here instead of a clean win/lose outcome
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<Integer> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return batchReserve(client, List.of(bikeA.id(), bikeB.id())).code();
            });
            Future<Integer> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return batchReserve(client, List.of(bikeB.id(), bikeA.id())).code();
            });

            ready.await();
            start.countDown();

            List<Integer> codes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            pool.shutdown();

            // never a 500 (an unhandled deadlock exception surfacing to the caller)
            assertThat(codes).allMatch(code -> code == 201 || code == 409);
            assertThat(codes).filteredOn(code -> code == 201).hasSize(1);
            assertThat(bikeStatus(bikeA.id())).isEqualTo(Status.RESERVED);
            assertThat(bikeStatus(bikeB.id())).isEqualTo(Status.RESERVED);
        });
    }

    @Test
    void highContentionOverlappingBatchesNeverDeadlockOrDoubleReserve() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            int bikeCount = 6;
            List<UUID> bikeIds = new ArrayList<>();
            for (int i = 0; i < bikeCount; i++) {
                bikeIds.add(createBike(client, "bike-" + i).id());
            }

            int attempts = 30;
            Random random = new Random(42);
            List<List<UUID>> requests = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                List<UUID> subset = new ArrayList<>(bikeIds);
                Collections.shuffle(subset, random);
                requests.add(subset.subList(0, 2 + random.nextInt(3))); // 2..4 overlapping bikes, randomized order
            }

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<BatchResult>> futures = new ArrayList<>();

            for (List<UUID> request : requests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var response = batchReserve(client, request);
                    return new BatchResult(request, response.code(), response.body());
                }));
            }

            ready.await();
            start.countDown();

            List<BatchResult> results = new ArrayList<>();
            for (Future<BatchResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(results).as("no unexpected status code (e.g. 500 from an unhandled deadlock)")
                    .allMatch(r -> r.code == 201 || r.code == 404 || r.code == 409);

            List<BatchResult> wins = results.stream().filter(r -> r.code == 201).collect(Collectors.toList());
            Set<UUID> everyWonBike = ConcurrentHashMap.newKeySet();
            for (BatchResult win : wins) {
                for (UUID bikeId : win.request) {
                    assertThat(everyWonBike.add(bikeId))
                            .as("bike %s was awarded to more than one winning batch", bikeId)
                            .isTrue();
                }
            }

            long actuallyReserved = dsl.selectCount().from(BIKE).where(BIKE.STATUS.eq("RESERVED")).fetchOne(0, Integer.class);
            assertThat((long) everyWonBike.size()).isEqualTo(actuallyReserved);
        });
    }

    private record BatchResult(List<UUID> request, int code, String body) {
    }

    private Response batchReserve(HttpClient client, List<UUID> bikeIds) throws Exception {
        String body = json.writeValueAsString(Map.of("bikeIds", bikeIds.stream().map(UUID::toString).toList()));
        var response = client.post("/bikes/batch-reserve", body);
        return new Response(response.code(), response.body().string());
    }

    private record Response(int code, String body) {
    }

    private Bike createBike(HttpClient client, String code) throws Exception {
        var response = client.post("/bikes", "{\"code\":\"" + code + "\"}");
        return json.readValue(response.body().string(), Bike.class);
    }

    private Status bikeStatus(UUID bikeId) {
        return dsl.selectFrom(BIKE)
                .where(BIKE.ID.eq(bikeId))
                .fetchOptionalInto(Bike.class)
                .map(Bike::status)
                .orElseThrow();
    }

    private int reservationCountFor(UUID bikeId) {
        return dsl.selectCount()
                .from(com.example.app.jooq.generated.Tables.RESERVATION)
                .where(com.example.app.jooq.generated.Tables.RESERVATION.BIKE_ID.eq(bikeId))
                .fetchOne(0, int.class);
    }
}
