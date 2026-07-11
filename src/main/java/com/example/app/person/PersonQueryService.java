package com.example.app.person;

import com.example.app.jooq.generated.tables.pojos.Person;

import java.util.List;
import java.util.Optional;

class PersonQueryService {

    private final PersonRepository repository;

    PersonQueryService(PersonRepository repository) {
        this.repository = repository;
    }

    List<Person> findAll() {
        return repository.findAll();
    }

    Optional<Person> findById(long id) {
        return repository.findById(id);
    }
}
