package com.example.app.reservation;

import com.example.app.reservation.domain.Reservation;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.ReservationStatus;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

public class ReservationController {
    private final ReservationQueryService reservationQueryService;
    private final CommandHandler<CreateReservationCommand, Reservation> createReservationCommand;
    private final CommandHandler<UpdateReservationStatusCommand, Optional<Reservation>> updateReservationCommandHandler;

    public ReservationController(ReservationQueryService reservationQueryService,
                                 CommandHandler<CreateReservationCommand, Reservation> createReservationCommand,
                                 CommandHandler<UpdateReservationStatusCommand, Optional<Reservation>> updateReservationCommandHandler) {
        this.reservationQueryService = reservationQueryService;
        this.createReservationCommand = createReservationCommand;
        this.updateReservationCommandHandler = updateReservationCommandHandler;
    }

    void registerRoutes() {
        get("/reservations", this::getReservations);
        get("/reservations/{id}", this::findReservation);
        post("/reservations", this::createReservations);
        put("/reservations/{id}/cancel", this::cancelReservation);
        put("/reservations/{id}/finish", this::finishReservation);
    }

    private void cancelReservation(Context context) {
        UUID reservationId = UUID.fromString(context.pathParam("id"));

        updateReservationCommandHandler.handle(new UpdateReservationStatusCommand(reservationId, ReservationStatus.CANCELLED))
                .ifPresentOrElse(
                        context::json,
                        () -> context.status(404).result("Reservation not found")
                );
    }

    private void finishReservation(Context context) {
        UUID reservationId = UUID.fromString(context.pathParam("id"));

        updateReservationCommandHandler.handle(new UpdateReservationStatusCommand(reservationId, ReservationStatus.COMPLETED))
                .ifPresentOrElse(
                        context::json,
                        () -> context.status(404).result("Reservation not found")
                );
    }

    private void findReservation(Context context) {
        UUID reservationId = UUID.fromString(context.pathParam("id"));
        reservationQueryService.findById(reservationId).ifPresentOrElse(
                context::json,
                () -> context.status(404).result("Reservation not found")
        );
    }

    private void getReservations(Context ctx) {
        ctx.json(reservationQueryService.findAll());
    }

    void createReservations(Context ctx) {
        CreateReservationRequest request = ctx.bodyAsClass(CreateReservationRequest.class);
        Reservation created = createReservationCommand.handle(new CreateReservationCommand(request.bikeId()));
        ctx.status(HttpStatus.CREATED).json(created);
    }
}
