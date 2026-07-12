package com.example.app.station;

import com.example.app.bike.BikeRepository;
import com.example.app.booking.ReserveStationBikeUseCase;
import com.example.app.reservation.CreateReservationCommandHandler;
import com.example.app.reservation.ReservationRepository;
import com.example.app.station.waitlist.JoinWaitListCommandHandler;
import com.example.app.station.waitlist.WaitListCommandHandler;
import com.example.app.station.waitlist.WaitListRepository;
import com.example.app.station.waitlist.WaitListRepositoryImpl;
import com.example.app.station.waitlist.WaitlistController;
import com.example.app.station.waitlist.WaitlistQueryService;
import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

import java.time.Clock;

public class StationModule {
    private StationModule() {
    }

    public static EndpointGroup routes(DSLContext dslContext, BikeRepository bikeRepository, ReservationRepository reservationRepository, Clock clock) {
        StationRepository stationRepository = new StationRepositoryImpl(dslContext);
        WaitListRepository waitListRepository = new WaitListRepositoryImpl(dslContext, clock);
        ReserveStationBikeUseCase reserveStationBikeUseCase = new ReserveStationBikeUseCase(
                bikeRepository,
                new CreateReservationCommandHandler(reservationRepository),
                stationRepository
        );
        WaitlistQueryService waitListService = new WaitlistQueryService(waitListRepository);
        WaitListCommandHandler waitListCommandHandler = new WaitListCommandHandler(reserveStationBikeUseCase, waitListRepository);
        JoinWaitListCommandHandler  joinWaitListCommandHandler = new JoinWaitListCommandHandler(waitListRepository, stationRepository, waitListCommandHandler);
        StationController controller = new StationController(reserveStationBikeUseCase, joinWaitListCommandHandler);
        // Should be module exposure
        WaitlistController waitlistController = new WaitlistController(waitListService);
        return () -> {
            controller.registerRoutes();
            waitlistController.registerRoutes();
        };
    }
}
