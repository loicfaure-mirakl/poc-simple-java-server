package com.example.app.reservation;

import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

import java.time.Clock;

public class ReservationModule {
    private ReservationModule() {
    }

    public static EndpointGroup routes(DSLContext dsl, Clock clock) {
        ReservationRepository repository = new ReservationRepositoryImpl(dsl, clock);
        ReservationController controller = new ReservationController(
                new ReservationQueryService(repository),
                new CreateReservationCommandHandler(repository),
                new UpdateReservationStatusCommandHandler(repository));
        return controller::registerRoutes;
    }
}
