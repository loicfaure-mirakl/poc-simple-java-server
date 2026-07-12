package com.example.app.station.waitlist;

import java.util.Optional;
import java.util.UUID;

public class WaitlistQueryService {
    private final WaitListRepository waitListRepository;
    public WaitlistQueryService(WaitListRepository waitListRepository) {
        this.waitListRepository = waitListRepository;
    }


    public Optional<WaitList> find(UUID id) {
        return waitListRepository.findById(id);
    }

}
