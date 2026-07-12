package com.example.app.station;

import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import com.example.app.station.waitlist.WaitList;
import com.example.app.station.waitlist.WaitListCommand;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.UUID;

import static io.javalin.apibuilder.ApiBuilder.post;

public class StationController {

    private final CommandHandler<ReserveStationBikeCommand, Reservation> reserveStationBikeCommandHandler;
    private final CommandHandler<WaitListCommand, WaitList> joinWaitListCommandHandler;

    public StationController(CommandHandler<ReserveStationBikeCommand, Reservation> reserveStationBikeCommandHandler, CommandHandler<WaitListCommand, WaitList> joinWaitListCommandHandler) {
        this.reserveStationBikeCommandHandler = reserveStationBikeCommandHandler;
        this.joinWaitListCommandHandler = joinWaitListCommandHandler;
    }

    void registerRoutes() {
        post("/stations/{id}/reserve", this::reserveBike);
        post("/stations/{id}/waitlist", this::joinWaitList);
    }

    private void joinWaitList(Context context) {
        UUID stationId = UUID.fromString(context.pathParam("id"));
        WaitListCommand command = new WaitListCommand(stationId);
        WaitList  waitList = joinWaitListCommandHandler.handle(command);
        context.status(HttpStatus.CREATED).json(waitList);
    }

    private void reserveBike(Context context) {
        UUID stationId = UUID.fromString(context.pathParam("id"));
        ReserveStationBikeCommand command = new ReserveStationBikeCommand(stationId);
        Reservation reservation = reserveStationBikeCommandHandler.handle(command);
        context.status(HttpStatus.CREATED).json(reservation);
    }
}
