package com.example.app.bike;

import com.example.app.App;
import com.example.app.bike.domain.Bike;
import com.example.app.bike.domain.Status;
import com.example.app.config.Database;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Level 1 (see BIKE_FEATURES.md): reserving a bike must only succeed while it is
 * AVAILABLE, and under concurrent reservation attempts on the same bike exactly one must win.
 */
@Testcontainers
class Level1BikeReservationConcurrencyTest {

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
        dsl.truncate(RESERVATION).execute();
        dsl.truncate(BIKE).cascade().execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @Test
    void reservingAvailableBikeSucceedsAndMarksBikeReserved() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");

            var response = client.post("/bikes/" + bike.id() + "/reserve");

            assertThat(response.code()).isEqualTo(201);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.RESERVED);
            assertThat(reservationCountFor(bike.id())).isEqualTo(1);
        });
    }

    @Test
    void reservingUnknownBikeReturns404() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.post("/bikes/" + UUID.randomUUID() + "/reserve");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void reservingAlreadyReservedBikeReturns409() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            client.post("/bikes/" + bike.id() + "/reserve");

            var response = client.post("/bikes/" + bike.id() + "/reserve");

            assertThat(response.code()).isEqualTo(409);
            assertThat(reservationCountFor(bike.id())).isEqualTo(1);
        });
    }

    @Test
    void onlyOneConcurrentReservationWinsForSameBike() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            int attempts = 20;

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();

            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return client.post("/bikes/" + bike.id() + "/reserve").code();
                }));
            }

            ready.await();
            start.countDown();

            List<Integer> statusCodes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statusCodes.add(future.get(10, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(statusCodes).filteredOn(code -> code == 201).hasSize(1);
            assertThat(statusCodes).filteredOn(code -> code == 409).hasSize(attempts - 1);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.RESERVED);
            assertThat(reservationCountFor(bike.id())).isEqualTo(1);
        });
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
                .from(RESERVATION)
                .where(RESERVATION.BIKE_ID.eq(bikeId))
                .fetchOne(0, int.class);
    }
}
