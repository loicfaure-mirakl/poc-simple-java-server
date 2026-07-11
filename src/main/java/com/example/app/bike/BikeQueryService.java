package com.example.app.bike;

import com.example.app.bike.domain.Bike;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BikeQueryService {

    private final BikeRepository bikeRepository;

    public BikeQueryService(BikeRepository bikeRepository) {
        this.bikeRepository = bikeRepository;
    }


    public Optional<Bike> findById(UUID bikeId) {
        return bikeRepository.findById(bikeId);
    }

    public List<Bike> findAll() {
        return bikeRepository.findAll();
    }
}
