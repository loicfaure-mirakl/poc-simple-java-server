package com.example.app.bike;

import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.CreateReservationCommand;
import com.example.app.reservation.domain.Reservation;
import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

public class BikeModule {
    private BikeModule() {
    }

    public static EndpointGroup routes(DSLContext dsl, CommandHandler<CreateReservationCommand, Reservation> createReservationCommand) {
        BikeRepository repository = new BikeRepositoryImpl(dsl);
        BikeController controller = new BikeController(
                new BikeQueryService(repository),
                new CreateBikeCommandHandler(repository),
                new ReserveBikeCommandHandler(repository, createReservationCommand));
        return controller::registerRoutes;
    }
}
