package com.example.app.station;

import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.UUID;

import static io.javalin.apibuilder.ApiBuilder.post;

public class StationController {

    private final CommandHandler<ReserveStationBikeCommand, Reservation> reserveStationBikeCommandHandler;

    public StationController(CommandHandler<ReserveStationBikeCommand, Reservation> reserveStationBikeCommandHandler) {
        this.reserveStationBikeCommandHandler = reserveStationBikeCommandHandler;
    }

    void registerRoutes() {
        post("/stations/{id}/reserve", this::reserveBike);
    }

    private void reserveBike(Context context) {
        UUID stationId = UUID.fromString(context.pathParam("id"));
        ReserveStationBikeCommand command = new ReserveStationBikeCommand(stationId);
        Reservation reservation = reserveStationBikeCommandHandler.handle(command);
        context.status(HttpStatus.CREATED).json(reservation);
    }
}
