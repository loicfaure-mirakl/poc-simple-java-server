package com.example.app.waitlist;

import com.example.app.App;
import com.example.app.config.Database;
import com.example.app.reservation.domain.Reservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.UUID;

import static com.example.app.jooq.generated.Tables.BIKE;
import static com.example.app.jooq.generated.Tables.RESERVATION;
import static com.example.app.jooq.generated.Tables.STATION;
import static com.example.app.jooq.generated.Tables.WAITLIST_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WaitlistFixVerificationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    private static DSLContext dsl;
    private static Clock clock;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void initDatabase() {
        dsl = Database.init(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        clock = Clock.systemUTC();
    }

    @BeforeEach
    void clearTables() {
        dsl.deleteFrom(WAITLIST_ENTRY).execute();
        dsl.deleteFrom(RESERVATION).execute();
        dsl.deleteFrom(BIKE).execute();
        dsl.deleteFrom(STATION).execute();
    }

    private Javalin app() {
        return App.createApp(dsl, clock);
    }

    @Test
    void cancellingWithNobodyOnTheWaitlistMustReturnTheBikeToTheAvailablePool() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 1);
            addAvailableBike(stationId, "bike-1");

            var reserveResponse = client.post("/stations/" + stationId + "/reserve");
            Reservation reservation = json.readValue(reserveResponse.body().string(), Reservation.class);

            // nobody is waiting at this station
            var cancelResponse = client.put("/reservations/" + reservation.id() + "/cancel");
            assertThat(cancelResponse.code()).isEqualTo(200);

            // with nobody on the waitlist, the freed bike must go back to the general pool —
            // not get silently re-claimed into an orphaned reservation nobody can ever reference
            assertThat(availableCount(stationId)).isEqualTo(1);

            // the bike must be reservable again by a brand new request
            var secondReserveResponse = client.post("/stations/" + stationId + "/reserve");
            assertThat(secondReserveResponse.code()).isEqualTo(201);
        });
    }

    @Test
    void joiningTheWaitlistWithABikeAlreadyFreeIsAssignedImmediately() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            UUID stationId = createStation("station-1", 1);
            addAvailableBike(stationId, "bike-1");
            // nobody has reserved it — the bike is free right now when this join happens

            var joinResponse = client.post("/stations/" + stationId + "/waitlist");
            assertThat(joinResponse.code()).isEqualTo(201);

            JsonNode entry = json.readTree(joinResponse.body().string());
            assertThat(entry.get("status").asText()).isEqualTo("ASSIGNED");
            assertThat(entry.get("reservationId").isNull()).isFalse();

            Reservation assigned = json.readValue(
                    client.get("/reservations/" + entry.get("reservationId").asText()).body().string(), Reservation.class);
            assertThat(assigned.status().name()).isEqualTo("CREATED");
            assertThat(availableCount(stationId)).isEqualTo(0);
        });
    }

    private int availableCount(UUID stationId) {
        return dsl.select(STATION.AVAILABLE_COUNT)
                .from(STATION)
                .where(STATION.ID.eq(stationId))
                .fetchOne(STATION.AVAILABLE_COUNT);
    }

    private UUID createStation(String name, int capacity) {
        return dsl.insertInto(STATION)
                .set(STATION.ID, UUID.randomUUID())
                .set(STATION.NAME, name)
                .set(STATION.CAPACITY, capacity)
                .set(STATION.AVAILABLE_COUNT, 0)
                .returning(STATION.ID)
                .fetchOne(STATION.ID);
    }

    private UUID addAvailableBike(UUID stationId, String code) {
        UUID bikeId = UUID.randomUUID();
        dsl.insertInto(BIKE)
                .set(BIKE.ID, bikeId)
                .set(BIKE.CODE, code)
                .set(BIKE.STATUS, "AVAILABLE")
                .set(BIKE.STATION_ID, stationId)
                .execute();
        dsl.update(STATION)
                .set(STATION.AVAILABLE_COUNT, STATION.AVAILABLE_COUNT.add(1))
                .where(STATION.ID.eq(stationId))
                .execute();
        return bikeId;
    }
}
