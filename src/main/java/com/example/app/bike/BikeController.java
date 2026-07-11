package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.UUID;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public class BikeController {
    private final BikeQueryService bikeQueryService;
    private final CommandHandler<CreateBikeCommand, Bike> createBikeCommand;

    public BikeController(BikeQueryService bikeQueryService, CommandHandler<CreateBikeCommand, Bike> createBikeCommand) {
        this.bikeQueryService = bikeQueryService;
        this.createBikeCommand = createBikeCommand;
    }

    void registerRoutes() {
        get("/bikes", this::getBikes);
        get("/bikes/{id}", this::findBike);
        post("/bikes", this::createBikes);
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
