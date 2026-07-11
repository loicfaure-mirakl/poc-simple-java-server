package com.example.app.person;

import com.example.app.jooq.generated.tables.pojos.Person;

import java.util.List;
import java.util.Optional;

public interface PersonRepository {

    List<Person> findAll();

    Optional<Person> findById(long id);

    Person create(String name, String email);
}
