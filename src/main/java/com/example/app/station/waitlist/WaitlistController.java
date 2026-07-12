package com.example.app.station.waitlist;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

import static io.javalin.apibuilder.ApiBuilder.get;

public class WaitlistController {

    private final WaitlistQueryService waitlistQueryService;

    public WaitlistController(WaitlistQueryService waitlistQueryService) {
        this.waitlistQueryService = waitlistQueryService;
    }

    public void registerRoutes() {
        get("/waitlist/{id}/", this::waitlist);
    }

    private void waitlist(@NotNull Context context) {
        UUID waitlistId = UUID.fromString(context.pathParam("id"));
        waitlistQueryService.find(waitlistId)
                .ifPresentOrElse(context::json,
                        () -> context.status(HttpStatus.NOT_FOUND));
    }
}
