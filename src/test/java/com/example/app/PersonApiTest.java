package com.example.app;

import com.example.app.config.Database;
import com.example.app.jooq.generated.tables.pojos.Person;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.example.app.jooq.generated.Tables.PERSON;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PersonApiTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    private static DSLContext dsl;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void initDatabase() {
        dsl = Database.init(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeEach
    void clearPersons() {
        dsl.truncate(PERSON).restartIdentity().execute();
    }

    private Javalin app() {
        return App.createApp(dsl);
    }

    @Test
    void healthCheckReturnsOk() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.get("/health");
            assertThat(response.code()).isEqualTo(200);
        });
    }

    @Test
    void listIsEmptyByDefault() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.get("/persons");
            assertThat(response.code()).isEqualTo(200);
            assertThat(readList(response.body().string())).isEmpty();
        });
    }

    @Test
    void unknownPersonReturns404() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.get("/persons/42");
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void createdPersonIsReturnedWithGeneratedId() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.post("/persons", "{\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}");

            assertThat(response.code()).isEqualTo(201);
            Person created = json.readValue(response.body().string(), Person.class);
            assertThat(created.getId()).isNotNull();
            assertThat(created.getName()).isEqualTo("Ada Lovelace");
            assertThat(created.getEmail()).isEqualTo("ada@example.com");
        });
    }

    @Test
    void createdPersonCanBeFetchedById() {
        JavalinTest.test(app(), (server, client) -> {
            var createResponse = client.post("/persons", "{\"name\":\"Grace Hopper\",\"email\":\"grace@example.com\"}");
            Person created = json.readValue(createResponse.body().string(), Person.class);

            var getResponse = client.get("/persons/" + created.getId());

            assertThat(getResponse.code()).isEqualTo(200);
            Person fetched = json.readValue(getResponse.body().string(), Person.class);
            assertThat(fetched).isEqualTo(created);
        });
    }

    @Test
    void createdPersonAppearsInList() {
        JavalinTest.test(app(), (server, client) -> {
            client.post("/persons", "{\"name\":\"Alan Turing\",\"email\":\"alan@example.com\"}");

            var listResponse = client.get("/persons");

            assertThat(listResponse.code()).isEqualTo(200);
            assertThat(readList(listResponse.body().string())).extracting(Person::getEmail).contains("alan@example.com");
        });
    }

    private List<Person> readList(String body) throws Exception {
        return json.readValue(body, new TypeReference<List<Person>>() {
        });
    }
}
