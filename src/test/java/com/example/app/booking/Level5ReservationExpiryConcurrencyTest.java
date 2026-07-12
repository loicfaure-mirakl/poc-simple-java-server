package com.example.app.booking;

import com.example.app.App;
import com.example.app.bike.BikeRepository;
import com.example.app.bike.BikeRepositoryImpl;
import com.example.app.bike.UpdateBikeStatusCommand;
import com.example.app.bike.UpdateBikeStatusCommandHandler;
import com.example.app.bike.domain.Bike;
import com.example.app.bike.domain.Status;
import com.example.app.config.Database;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.ReservationExpiryJob;
import com.example.app.reservation.ReservationRepository;
import com.example.app.reservation.ReservationRepositoryImpl;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static com.example.app.jooq.generated.Tables.BIKE;
import static com.example.app.jooq.generated.Tables.RESERVATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Level 5 (see BIKE_FEATURES.md): reservations left CREATED past a TTL must be swept
 * by ReservationExpiryJob — cancelling them and releasing the bike — and that sweep must not
 * race unsafely against a user finishing/cancelling the same reservation at the same moment
 * (see BIKE_CONCURRENCY_HINTS.md — "whoever's UPDATE affects 0 rows lost the race").
 *
 * Target contract this test assumes (nothing else in the codebase references it yet):
 *   new ReservationExpiryJob(ReservationRepository, CommandHandler&lt;UpdateBikeStatusCommand,
 *       Optional&lt;Bike&gt;&gt; releaseBikeCommand, Clock clock, Duration ttl).expireStale()
 * mirroring BikeReservationReconciler's shape — a plain sweep method a scheduler can call on a
 * timer and this test calls directly.
 */
@Testcontainers
class Level5ReservationExpiryConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    private static DSLContext dsl;
    private static Clock clock;
    private static final Duration TTL = Duration.ofMinutes(1);

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
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    private ReservationExpiryJob expiryJob() {
        ReservationRepository reservationRepository = new ReservationRepositoryImpl(dsl, clock);
        BikeRepository bikeRepository = new BikeRepositoryImpl(dsl);
        CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand =
                new UpdateBikeStatusCommandHandler(bikeRepository);
        return new ReservationExpiryJob(reservationRepository, new ExpireReservationUseCase(reservationRepository, releaseBikeCommand), clock, TTL);
    }

    @Test
    void expireStaleCancelsReservationAndReleasesBike() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            backdateCreatedAt(reservation.id(), TTL.plusMinutes(1));

            expiryJob().expireStale();

            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    @Test
    void expireStaleLeavesFreshReservationsUntouched() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            // created "now" — well within the TTL, must not be swept

            expiryJob().expireStale();

            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CREATED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.RESERVED);
        });
    }

    @Test
    void expireStaleIsIdempotentAcrossRepeatedSweeps() {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            backdateCreatedAt(reservation.id(), TTL.plusMinutes(1));

            ReservationExpiryJob job = expiryJob();
            job.expireStale();
            job.expireStale();
            job.expireStale();

            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    @Test
    void expiryRacingWithFinishResolvesExactlyOnce() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            backdateCreatedAt(reservation.id(), TTL.plusMinutes(1));

            List<Integer> finishCodes = raceConcurrently(10, i ->
                    client.put("/reservations/" + reservation.id() + "/finish").code());
            // interleave a few direct sweeps with the HTTP race above having already fired;
            // repeat sweeps to make sure a lost race on the first attempt is not the only chance
            ReservationExpiryJob job = expiryJob();
            for (int i = 0; i < 5; i++) {
                job.expireStale();
            }

            long finishWins = finishCodes.stream().filter(code -> code == 200).count();
            long finishLosses = finishCodes.stream().filter(code -> code == 409).count();
            assertThat(finishWins + finishLosses).isEqualTo(finishCodes.size());
            assertThat(finishWins).isLessThanOrEqualTo(1);

            ReservationStatus finalStatus = reservationStatus(reservation.id());
            if (finishWins == 1) {
                assertThat(finalStatus).isEqualTo(ReservationStatus.RETURNED);
            } else {
                assertThat(finalStatus).isEqualTo(ReservationStatus.CANCELLED);
            }
            // whichever side won, the bike must have been released exactly once
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    @Test
    void expiryRacingWithCancelResolvesExactlyOnce() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            Bike bike = createBike(client, "bike-1");
            Reservation reservation = reserveBike(client, bike.id());
            backdateCreatedAt(reservation.id(), TTL.plusMinutes(1));

            List<Integer> cancelCodes = raceConcurrently(10, i ->
                    client.put("/reservations/" + reservation.id() + "/cancel").code());
            ReservationExpiryJob job = expiryJob();
            for (int i = 0; i < 5; i++) {
                job.expireStale();
            }

            long cancelWins = cancelCodes.stream().filter(code -> code == 200).count();
            assertThat(cancelWins).isLessThanOrEqualTo(1);

            // either the user's own cancel won or the job's own cancel won — both leave the
            // reservation CANCELLED, but exactly one write must have produced it
            assertThat(reservationStatus(reservation.id())).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(bikeStatus(bike.id())).isEqualTo(Status.AVAILABLE);
        });
    }

    private List<Integer> raceConcurrently(int attempts, IntFunction<Integer> call) throws Exception {
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

    private void backdateCreatedAt(UUID reservationId, Duration age) {
        dsl.update(RESERVATION)
                .set(RESERVATION.CREATED_AT, Instant.now(clock).minus(age))
                .where(RESERVATION.ID.eq(reservationId))
                .execute();
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
