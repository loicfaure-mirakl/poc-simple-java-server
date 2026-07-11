package com.example.app.person;

import com.example.app.person.command.CreatePersonCommandHandler;
import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

public final class PersonModule {

    private PersonModule() {
    }

    public static EndpointGroup routes(DSLContext dsl) {
        PersonRepository repository = new JooqPersonRepository(dsl);
        PersonController controller = new PersonController(
                new CreatePersonCommandHandler(repository),
                new PersonQueryService(repository));
        return controller::registerRoutes;
    }
}
