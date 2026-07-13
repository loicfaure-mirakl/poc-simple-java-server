package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.booking.BatchReservationCommand;
import com.example.app.booking.ReserveBikeCommand;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public class BikeController {
    private final BikeQueryService bikeQueryService;
    private final CommandHandler<CreateBikeCommand, Bike> createBikeCommand;
    private final CommandHandler<ReserveBikeCommand, Reservation> reserveBikeHandler;
    private final CommandHandler<BatchReservationCommand, List<Reservation>> batchReserveCommandHandler;

    public BikeController(BikeQueryService bikeQueryService, CommandHandler<CreateBikeCommand, Bike> createBikeCommand, CommandHandler<ReserveBikeCommand, Reservation> reserveBikeHandler, CommandHandler<BatchReservationCommand, List<Reservation>> batchReserveCommandHandler) {
        this.bikeQueryService = bikeQueryService;
        this.createBikeCommand = createBikeCommand;
        this.reserveBikeHandler = reserveBikeHandler;
        this.batchReserveCommandHandler = batchReserveCommandHandler;
    }

    void registerRoutes() {
        get("/bikes", this::getBikes);
        get("/bikes/{id}", this::findBike);
        post("/bikes", this::createBikes);
        post("/bikes/{id}/reserve", this::reserve);
        post("/bikes/batch-reserve", this::batchReserve);
    }

    private void batchReserve(@NotNull Context context) {
        var request = context.bodyAsClass(BatchReservationRequest.class);
        var reservations = batchReserveCommandHandler.handle(new BatchReservationCommand(request.bikeIds()));
        context.json(reservations).status(HttpStatus.MULTI_STATUS);
    }

    private void reserve(Context context) {
        UUID bikeId = UUID.fromString(context.pathParam("id"));
        var reservation = reserveBikeHandler.handle(new ReserveBikeCommand(bikeId));
        context.status(HttpStatus.CREATED).json(reservation);
    }

    private void findBike(Context context) {
        UUID bikeId = UUID.fromString(context.pathParam("id"));
        bikeQueryService.findById(bikeId).ifPresentOrElse(
                context::json,
                () -> context.status(404).result("Bike not found")
        );
    }

    private void getBikes(Context ctx) {
        ctx.json(bikeQueryService.findAll());
    }

    void createBikes(Context ctx) {
        CreateBikeRequest request = ctx.bodyAsClass(CreateBikeRequest.class);
        Bike created = createBikeCommand.handle(new CreateBikeCommand(request.code()));
        ctx.status(HttpStatus.CREATED).json(created);
    }
}
