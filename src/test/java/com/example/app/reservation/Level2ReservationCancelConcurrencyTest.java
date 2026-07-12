package com.example.app.reservation;

import com.example.app.App;
import com.example.app.bike.domain.Bike;
import com.example.app.bike.domain.Status;
import com.example.app.config.Database;
import com.example.app.reservation.domain.Reservation;
import com.example.app.reservation.domain.ReservationStatus;
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
 * Verifies Level 2 (see BIKE_FEATURES.md): cancelling a reservation must only succeed from
 * CREATED, must release the bike back to AVAILABLE, and under concurrent cancel attempts on the
 * same reservation exactly one must win (see BIKE_CONCURRENCY_HINTS.md — idempotency section).
 */
@Testcontainers
class Level2ReservationCancelConcurrencyTest {

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
        dsl.truncate(RESERVATION).cascade().execute();
        dsl.truncate(BIKE).cascade().execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @Test
    void cancellingCreatedReservationSucceedsAndReleasesBike() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());

            var response = client.put("/reservations/" + reservation.id() + "/cancel");

            assertThat(response.code()).isEqualTo(200);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    @Test
    void cancellingUnknownReservationReturns404() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.put("/reservations/" + UUID.randomUUID() + "/cancel");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void cancellingAlreadyCancelledReservationReturns409() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            client.put("/reservations/" + reservation.id() + "/cancel");

            var response = client.put("/reservations/" + reservation.id() + "/cancel");

            assertThat(response.code()).isEqualTo(409);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
        });
    }

    @Test
    void cancellingCompletedReservationReturns409() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            client.put("/reservations/" + reservation.id() + "/finish");

            var response = client.put("/reservations/" + reservation.id() + "/cancel");

            assertThat(response.code()).isEqualTo(409);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.RETURNED);
        });
    }

    @Test
    void onlyOneConcurrentCancelWinsForSameReservation() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            int attempts = 20;

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();

            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return client.put("/reservations/" + reservation.id() + "/cancel").code();
                }));
            }

            ready.await();
            start.countDown();

            List<Integer> statusCodes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statusCodes.add(future.get(10, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(statusCodes).filteredOn(code -> code == 200).hasSize(1);
            assertThat(statusCodes).filteredOn(code -> code == 409).hasSize(attempts - 1);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);

            // the bike must have been released exactly once and be reservable again
            var reReserve = client.post("/bikes/" + bike.id() + "/reserve");
            assertThat(reReserve.code()).isEqualTo(201);
        });
    }

    private Bike createBike(HttpClient client, String code) throws Exception {
        var response = client.post("/bikes", "{\"code\":\"" + code + "\"}");
        return json.readValue(response.body().string(), Bike.class);
    }

    private Reservation reserveBike(HttpClient client, UUID bikeId) throws Exception {
        var response = client.post("/bikes/" + bikeId + "/reserve");
        return json.readValue(response.body().string(), Reservation.class);
    }

    private Status bikeStatus(UUID bikeId) {
        return dsl.selectFrom(BIKE)
                .where(BIKE.ID.eq(bikeId))
                .fetchOptionalInto(Bike.class)
                .map(Bike::status)
                .orElseThrow();
    }

    private ReservationStatus reservationStatus(UUID reservationId) {
        return dsl.selectFrom(RESERVATION)
                .where(RESERVATION.ID.eq(reservationId))
                .fetchOptionalInto(Reservation.class)
                .map(Reservation::status)
                .orElseThrow();
    }
}
