package com.example.app.reservation.domain;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.example.app.reservation.domain.ReservationStatus.CANCELLED;
import static com.example.app.reservation.domain.ReservationStatus.CREATED;
import static com.example.app.reservation.domain.ReservationStatus.RETURNED;

class ReservationStatusTest {

    @ParameterizedTest(name = "{0} can transition to {1}")
    @MethodSource("transitionProvider")
    void canTransitionTo(Transition transition) {
       Assertions.assertThat(transition.from.canTransitionTo(transition.to))
               .isEqualTo(validTransition().contains(transition));
    }

    private static List<Transition> validTransition() {
        return List.of(new Transition(CREATED, CANCELLED),
                new Transition(CREATED, RETURNED));
    }

    private static List<Transition> transitionProvider() {
        var all = new ArrayList<Transition>();
        for (ReservationStatus status: ReservationStatus.values()) {
            for (ReservationStatus newStatus: ReservationStatus.values()) {
                all.add(new Transition(status, newStatus));
            }
        }
        return all;
    }

    record Transition(ReservationStatus from, ReservationStatus to) {

    }
}
