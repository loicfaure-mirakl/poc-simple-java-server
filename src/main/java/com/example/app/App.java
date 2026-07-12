package com.example.app;

import com.example.app.bike.BikeModule;
import com.example.app.bike.BikeRepository;
import com.example.app.bike.BikeRepositoryImpl;
import com.example.app.bike.UpdateBikeStatusCommand;
import com.example.app.bike.UpdateBikeStatusCommandHandler;
import com.example.app.bike.domain.Bike;
import com.example.app.booking.BikeReservationReconciler;
import com.example.app.booking.ExpireReservationUseCase;
import com.example.app.config.AppConfig;
import com.example.app.config.Database;
import com.example.app.cqrs.CommandHandler;
import com.example.app.error.ConflictException;
import com.example.app.error.NotFoundException;
import com.example.app.person.PersonModule;
import com.example.app.reservation.CreateReservationCommand;
import com.example.app.reservation.CreateReservationCommandHandler;
import com.example.app.reservation.ReservationExpiryJob;
import com.example.app.reservation.ReservationModule;
import com.example.app.reservation.ReservationRepository;
import com.example.app.reservation.ReservationRepositoryImpl;
import com.example.app.reservation.domain.Reservation;
import com.example.app.station.StationModule;
import io.javalin.Javalin;
import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.HttpStatus;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.javalin.apibuilder.ApiBuilder.get;
import static java.time.temporal.ChronoUnit.MINUTES;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        DSLContext dsl = Database.init(AppConfig.dbUrl(), AppConfig.dbUser(), AppConfig.dbPassword());
        Clock clock = Clock.systemUTC();

        BikeRepository bikeRepository = new BikeRepositoryImpl(dsl);
        ReservationRepository reservationRepository = new ReservationRepositoryImpl(dsl, clock);

        Javalin app = createApp(dsl, clock, bikeRepository, reservationRepository);

        int port = AppConfig.serverPort();
        app.start(port);
        log.info("Server started on port {}", port);
    }

    public static Javalin createApp(DSLContext dsl, Clock clock) {
        return createApp(dsl, clock, new BikeRepositoryImpl(dsl), new ReservationRepositoryImpl(dsl, clock));
    }

    public static Javalin createApp(DSLContext dsl, Clock clock, BikeRepository bikeRepository, ReservationRepository reservationRepository) {
        // Bike's "reserve" needs Reservation's create command, and Reservation's "cancel" needs
        // Bike's release command — a genuine two-way dependency between the two modules. Building
        // both cross-cutting handlers here (from the shared repository instances) instead of inside
        // either module's own routes() breaks the cycle without constructing anything twice.
        CommandHandler<CreateReservationCommand, Reservation> createReservationCommand =
                new CreateReservationCommandHandler(reservationRepository);
        CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand =
                new UpdateBikeStatusCommandHandler(bikeRepository);

        EndpointGroup bikeRoutes = BikeModule.routes(bikeRepository, createReservationCommand);
        EndpointGroup reservationRoutes = ReservationModule.routes(reservationRepository, createReservationCommand, releaseBikeCommand);
        EndpointGroup stationRoutes = StationModule.routes(dsl, bikeRepository, reservationRepository);

        var reconciler = new BikeReservationReconciler(reservationRepository, bikeRepository);
        var reservationExpiryJob = new ReservationExpiryJob(reservationRepository, new ExpireReservationUseCase(reservationRepository, releaseBikeCommand), clock, Duration.of(5, MINUTES));
        Executors.newSingleThreadScheduledExecutor()
                .scheduleWithFixedDelay(reconciler::reconcileCancelledReservations, 5, 5, TimeUnit.SECONDS);
        Executors.newSingleThreadScheduledExecutor()
                .scheduleWithFixedDelay(reservationExpiryJob::expireStale, 5, 5, TimeUnit.SECONDS);

        return Javalin.create(config -> {
            config.routes.apiBuilder(() -> {
                get("/health", ctx -> ctx.json(Map.of("status", "ok")));
                PersonModule.routes(dsl).addEndpoints();
                bikeRoutes.addEndpoints();
                reservationRoutes.addEndpoints();
                stationRoutes.addEndpoints();
            });
            config.routes.exception(NotFoundException.class, (e, ctx) ->
                    ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", e.getMessage())));
            config.routes.exception(ConflictException.class, (e, ctx) ->
                    ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));
            config.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage())));
        });
    }
}
