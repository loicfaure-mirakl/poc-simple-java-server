package com.example.app.person;

import com.example.app.jooq.generated.tables.pojos.Person;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

import static com.example.app.jooq.generated.Tables.PERSON;

class JooqPersonRepository implements PersonRepository {

    private final DSLContext dsl;

    JooqPersonRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Person> findAll() {
        return dsl.selectFrom(PERSON).fetchInto(Person.class);
    }

    @Override
    public Optional<Person> findById(long id) {
        return Optional.ofNullable(dsl.selectFrom(PERSON).where(PERSON.ID.eq(id)).fetchOneInto(Person.class));
    }

    @Override
    public Person create(String name, String email) {
        long id = dsl.insertInto(PERSON)
                .set(PERSON.NAME, name)
                .set(PERSON.EMAIL, email)
                .returningResult(PERSON.ID)
                .fetchOne()
                .value1();
        return findById(id).orElseThrow();
    }
}
