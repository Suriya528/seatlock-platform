package com.suriya.seatlock.demo;

import com.suriya.seatlock.booking.locking.LockingStrategyType;
import java.util.Map;

public record RaceSimulationResult(
    Long eventId,
    Long seatId,
    String seatLabel,
    int concurrency,
    Map<String, Object> winner,
    int successfulHolds,
    int conflicts,
    long doubleBookedSeats,
    long durationMs,
    LockingStrategyType lockingStrategy,
    String auditVerification,
    String status
) {}
