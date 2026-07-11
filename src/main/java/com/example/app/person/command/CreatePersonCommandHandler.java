package com.example.app.person.command;

import com.example.app.cqrs.CommandHandler;
import com.example.app.jooq.generated.tables.pojos.Person;
import com.example.app.person.PersonRepository;

public class CreatePersonCommandHandler implements CommandHandler<CreatePersonCommand, Person> {

    private final PersonRepository repository;

    public CreatePersonCommandHandler(PersonRepository repository) {
        this.repository = repository;
    }

    @Override
    public Person handle(CreatePersonCommand command) {
        return repository.create(command.name(), command.email());
    }
}
