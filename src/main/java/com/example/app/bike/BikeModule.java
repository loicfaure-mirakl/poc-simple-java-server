package com.example.app.bike;

import com.example.app.booking.ReserveBikeUseCase;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.CreateReservationCommand;
import com.example.app.reservation.domain.Reservation;
import io.javalin.apibuilder.EndpointGroup;

public class BikeModule {
    private BikeModule() {
    }

    public static EndpointGroup routes(BikeRepository repository, CommandHandler<CreateReservationCommand, Reservation> createReservationCommand) {
        BikeController controller = new BikeController(
                new BikeQueryService(repository),
                new CreateBikeCommandHandler(repository),
                new ReserveBikeUseCase(repository, createReservationCommand));
        return controller::registerRoutes;
    }
}
