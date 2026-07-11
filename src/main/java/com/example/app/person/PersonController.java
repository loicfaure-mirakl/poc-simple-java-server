package com.example.app.person;

import com.example.app.cqrs.CommandHandler;
import com.example.app.jooq.generated.tables.pojos.Person;
import com.example.app.person.command.CreatePersonCommand;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

class PersonController {

    private final CommandHandler<CreatePersonCommand, Person> createPerson;
    private final PersonQueryService queries;

    PersonController(CommandHandler<CreatePersonCommand, Person> createPerson, PersonQueryService queries) {
        this.createPerson = createPerson;
        this.queries = queries;
    }

    void registerRoutes() {
        get("/persons", this::handleListPersons);
        get("/persons/{id}", this::handleGetPerson);
        post("/persons", this::handleCreatePerson);
    }

    private void handleListPersons(Context ctx) {
        ctx.json(queries.findAll());
    }

    private void handleGetPerson(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        queries.findById(id).ifPresentOrElse(ctx::json, () -> ctx.status(HttpStatus.NOT_FOUND));
    }

    private void handleCreatePerson(Context ctx) {
        CreatePersonRequest request = ctx.bodyAsClass(CreatePersonRequest.class);
        Person created = createPerson.handle(new CreatePersonCommand(request.name(), request.email()));
        ctx.status(HttpStatus.CREATED).json(created);
    }
}
