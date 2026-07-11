package com.example.app;

import com.example.app.bike.BikeModule;
import com.example.app.config.AppConfig;
import com.example.app.config.Database;
import com.example.app.person.PersonModule;
import com.example.app.reservation.ReservationModule;
import io.javalin.Javalin;
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
        return Javalin.create(config -> {
            config.routes.apiBuilder(() -> {
                get("/health", ctx -> ctx.json(Map.of("status", "ok")));
                PersonModule.routes(dsl).addEndpoints();
                BikeModule.routes(dsl).addEndpoints();
                ReservationModule.routes(dsl, clock).addEndpoints();
            });
        });
    }
}
