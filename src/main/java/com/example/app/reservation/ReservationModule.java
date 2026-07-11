package com.example.app.reservation;

import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

import java.time.Clock;

public class ReservationModule {
    private ReservationModule() {
    }

    public record Routes(EndpointGroup endpoints, CommandHandler<CreateReservationCommand, Reservation> createReservationCommand) {
    }

    public static Routes routes(DSLContext dsl, Clock clock) {
        ReservationRepository repository = new ReservationRepositoryImpl(dsl, clock);
        var createReservationCommand = new CreateReservationCommandHandler(repository);
        ReservationController controller = new ReservationController(
                new ReservationQueryService(repository),
                createReservationCommand,
                new UpdateReservationStatusCommandHandler(repository));
        return new Routes(controller::registerRoutes, createReservationCommand);
    }
}
