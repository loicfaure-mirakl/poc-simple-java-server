package com.example.app.reservation;

import com.example.app.bike.UpdateBikeStatusCommand;
import com.example.app.bike.domain.Bike;
import com.example.app.booking.CancelReservationUseCase;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import io.javalin.apibuilder.EndpointGroup;

import java.util.Optional;

public class ReservationModule {
    private ReservationModule() {
    }

    public static EndpointGroup routes(ReservationRepository repository,
                                        CommandHandler<CreateReservationCommand, Reservation> createReservationCommand,
                                        CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand) {
        ReservationController controller = new ReservationController(
                new ReservationQueryService(repository),
                createReservationCommand,
                new CancelReservationUseCase(repository, releaseBikeCommand),
                new UpdateReservationStatusCommandHandler(repository));
        return controller::registerRoutes;
    }
}
