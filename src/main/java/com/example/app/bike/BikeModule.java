package com.example.app.bike;

import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

public class BikeModule {
    private BikeModule() {
    }

    public static EndpointGroup routes(DSLContext dsl) {
        BikeRepository repository = new BikeRepositoryImpl(dsl);
        BikeController controller = new BikeController(
                new BikeQueryService(repository),
                new CreateBikeCommandHandler(repository));
        return controller::registerRoutes;
    }
}
