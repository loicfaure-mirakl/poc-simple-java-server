package com.example.app;

import com.example.app.bike.BikeModule;
import com.example.app.config.AppConfig;
import com.example.app.config.Database;
import com.example.app.error.ConflictException;
import com.example.app.error.NotFoundException;
import com.example.app.person.PersonModule;
import com.example.app.reservation.ReservationModule;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;

import static io.javalin.apibuilder.ApiBuilder.get;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        DSLContext dsl = Database.init(AppConfig.dbUrl(), AppConfig.dbUser(), AppConfig.dbPassword());
        Clock clock = Clock.systemUTC();
        Javalin app = createApp(dsl, clock);

        int port = AppConfig.serverPort();
        app.start(port);
        log.info("Server started on port {}", port);
    }

    public static Javalin createApp(DSLContext dsl, Clock clock) {
        var reservationRoutes = ReservationModule.routes(dsl, clock);
        return Javalin.create(config -> {
            config.routes.apiBuilder(() -> {
                get("/health", ctx -> ctx.json(Map.of("status", "ok")));
                PersonModule.routes(dsl).addEndpoints();
                BikeModule.routes(dsl, reservationRoutes.createReservationCommand()).addEndpoints();
                reservationRoutes.endpoints().addEndpoints();
            });
            config.routes.exception(NotFoundException.class, (e, ctx) ->
                    ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", e.getMessage())));
            config.routes.exception(ConflictException.class, (e, ctx) ->
                    ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));
            config.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage())));
        });
    }
}
