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
 * Verifies Level 3 (see BIKE_FEATURES.md): finishing a reservation must only succeed from
 * CREATED, must release the bike back to AVAILABLE, and the CREATED -> {CANCELLED, COMPLETED}
 * transition must be mutually exclusive even when cancel and finish race on the same
 * reservation (see BIKE_CONCURRENCY_HINTS.md — enforce the state machine in the SQL predicate).
 */
@Testcontainers
class Level3ReservationFinishConcurrencyTest {

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
    void finishingCreatedReservationSucceedsAndReleasesBike() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());

            var response = client.put("/reservations/" + reservation.id() + "/finish");

            assertThat(response.code()).isEqualTo(200);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.RETURNED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    @Test
    void finishingUnknownReservationReturns404() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.put("/reservations/" + UUID.randomUUID() + "/finish");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void finishingAlreadyCompletedReservationReturns409() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            client.put("/reservations/" + reservation.id() + "/finish");

            var response = client.put("/reservations/" + reservation.id() + "/finish");

            assertThat(response.code()).isEqualTo(409);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.RETURNED);
        });
    }

    @Test
    void finishingCancelledReservationReturns409() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            client.put("/reservations/" + reservation.id() + "/cancel");

            var response = client.put("/reservations/" + reservation.id() + "/finish");

            assertThat(response.code()).isEqualTo(409);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
        });
    }

    @Test
    void onlyOneConcurrentFinishWinsForSameReservation() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());

            List<Integer> statusCodes = raceConcurrently(20,
                    i -> client.put("/reservations/" + reservation.id() + "/finish").code());

            assertThat(statusCodes).filteredOn(code -> code == 200).hasSize(1);
            assertThat(statusCodes).filteredOn(code -> code == 409).hasSize(statusCodes.size() - 1);
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.RETURNED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);

            // the bike must have been released exactly once and be reservable again
            var reReserve = client.post("/bikes/" + bike.id() + "/reserve");
            assertThat(reReserve.code()).isEqualTo(201);
        });
    }

    @Test
    void onlyOneOfConcurrentCancelOrFinishWinsForSameReservation() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());

            List<Integer> statusCodes = raceConcurrently(20,
                    i -> i % 2 == 0
                            ? client.put("/reservations/" + reservation.id() + "/cancel").code()
                            : client.put("/reservations/" + reservation.id() + "/finish").code());

            assertThat(statusCodes).filteredOn(code -> code == 200).hasSize(1);
            assertThat(statusCodes).filteredOn(code -> code == 409).hasSize(statusCodes.size() - 1);

            ReservationStatus finalStatus = reservationStatus(reservation.id());
            assertThat(finalStatus).isIn(ReservationStatus.CANCELLED, ReservationStatus.RETURNED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);

            var reReserve = client.post("/bikes/" + bike.id() + "/reserve");
            assertThat(reReserve.code()).isEqualTo(201);
        });
    }

    private List<Integer> raceConcurrently(int attempts, java.util.function.IntFunction<Integer> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

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

        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
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
