package com.example.app.station;

import com.example.app.App;
import com.example.app.config.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.app.jooq.generated.Tables.BIKE;
import static com.example.app.jooq.generated.Tables.STATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scratch stress test — NOT part of the permanent suite. Maximizes contention (many bikes,
 * many concurrent claimants, all racing on the same station) to check whether jOOQ's subquery
 * emulation of UPDATE...LIMIT 1 can let two concurrent requests pick the same candidate row,
 * which would produce a false 409 for a request that should have won a still-available bike,
 * and leave the station's available_count inconsistent with the number of bikes actually
 * marked RESERVED.
 */
@Testcontainers
class Level4StationStressTest {

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
        dsl.deleteFrom(com.example.app.jooq.generated.Tables.RESERVATION).execute();
        dsl.deleteFrom(BIKE).execute();
        dsl.deleteFrom(STATION).execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @RepeatedTest(2)
    void highContentionStationReservationStaysConsistent() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            int bikeCount = 30;
            UUID stationId = createStation("stress-station", bikeCount);
            List<UUID> bikeIds = new ArrayList<>();
            for (int i = 0; i < bikeCount; i++) {
                bikeIds.add(addAvailableBike(stationId, "bike-" + i));
            }
            int attempts = bikeCount * 3; // 3x oversubscribed

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();

            Set<UUID> wonBikeIds = ConcurrentHashMap.newKeySet();

            List<String> unexpectedBodies = new ArrayList<>();

            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var response = client.post("/stations/" + stationId + "/reserve");
                    var body = response.body().string();
                    if (response.code() == 201) {
                        UUID bikeId = json.readTree(body).get("bikeId") == null
                                ? null
                                : UUID.fromString(json.readTree(body).get("bikeId").asText());
                        if (bikeId != null) {
                            wonBikeIds.add(bikeId);
                        }
                    } else if (response.code() != 409) {
                        synchronized (unexpectedBodies) {
                            unexpectedBodies.add(response.code() + ": " + body);
                        }
                    }
                    return response.code();
                }));
            }

            ready.await();
            start.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();

            if (!unexpectedBodies.isEmpty()) {
                System.out.println("=== UNEXPECTED RESPONSES (first 5) ===");
                unexpectedBodies.stream().limit(5).forEach(System.out::println);
                System.out.println("=======================================");
            }

            long wins = codes.stream().filter(c -> c == 201).count();
            long losses = codes.stream().filter(c -> c == 409).count();
            long other = codes.size() - wins - losses;

            int availableCountAfter = dsl.select(STATION.AVAILABLE_COUNT)
                    .from(STATION).where(STATION.ID.eq(stationId)).fetchOne(STATION.AVAILABLE_COUNT);

            long actuallyReservedBikes = dsl.selectCount().from(BIKE)
                    .where(BIKE.STATION_ID.eq(stationId)).and(BIKE.STATUS.eq("RESERVED"))
                    .fetchOne(0, Integer.class);

            System.out.println("wins=" + wins + " losses=" + losses + " other=" + other
                    + " distinctWonBikes=" + wonBikeIds.size()
                    + " availableCountAfter=" + availableCountAfter
                    + " actuallyReservedBikes=" + actuallyReservedBikes);

            assertThat(other).as("no unexpected status codes (e.g. 500 from TooManyRows)").isZero();
            assertThat(wins).as("exactly one win per bike").isEqualTo(bikeCount);
            assertThat(wonBikeIds).as("every win got a distinct bike").hasSize(bikeCount);
            assertThat(availableCountAfter).as("available_count matches physically reserved bikes").isEqualTo(0);
            assertThat(actuallyReservedBikes).as("all bikes actually got reserved, none orphaned").isEqualTo(bikeCount);
        });
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
}
