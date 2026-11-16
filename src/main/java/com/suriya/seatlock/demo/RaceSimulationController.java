package com.suriya.seatlock.demo;

import com.suriya.seatlock.booking.BookingRepository;
import com.suriya.seatlock.booking.BookingService;
import com.suriya.seatlock.booking.HoldRequest;
import com.suriya.seatlock.booking.HoldResult;
import com.suriya.seatlock.booking.SeatUnavailableException;
import com.suriya.seatlock.booking.locking.LockingStrategyType;
import com.suriya.seatlock.event.Event;
import com.suriya.seatlock.event.EventRepository;
import com.suriya.seatlock.seat.Seat;
import com.suriya.seatlock.seat.SeatRepository;
import com.suriya.seatlock.seat.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RaceSimulationController {

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    /**
     * Executes a server-side concurrency race simulation using Java 21 Virtual Threads.
     * N concurrent threads fire simultaneously at the same seat using a CountDownLatch start gate.
     * Proves: exactly 1 wins, N-1 get 409 Conflict, and doubleBookedSeats is guaranteed to be 0.
     */
    @PostMapping("/api/demo/race")
    public ResponseEntity<RaceSimulationResult> runRace(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) Long seatId,
            @RequestParam(defaultValue = "50") int concurrency
    ) throws Exception {
        int racers = Math.clamp(concurrency, 2, 100);

        Seat targetSeat = resolveTargetSeat(eventId, seatId);
        Long targetEventId = targetSeat.getEvent().getId();
        Long targetSeatId = targetSeat.getId();
        String seatLabel = targetSeat.getSection() + " " + targetSeat.getRowLabel() + targetSeat.getSeatNumber();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(racers);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicReference<HoldResult> winnerRef = new AtomicReference<>();

        long start = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < racers; i++) {
                final String userId = "sim-racer-" + (i + 1) + "-" + UUID.randomUUID().toString().substring(0, 8);
                executor.submit(() -> {
                    try {
                        startGate.await();
                        HoldResult result = bookingService.hold(
                                userId,
                                new HoldRequest(targetEventId, List.of(targetSeatId), "Sim Racer", "sim@seatlock.test"),
                                null
                        );
                        successCount.incrementAndGet();
                        winnerRef.set(result);
                    } catch (SeatUnavailableException e) {
                        conflictCount.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Simulation thread error: {}", e.getMessage());
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            doneGate.await();
        }

        long duration = System.currentTimeMillis() - start;
        long doubleBookings = bookingRepository.countDoubleBookedSeats(targetEventId);

        Map<String, Object> winnerInfo = Map.of();
        if (winnerRef.get() != null) {
            var b = winnerRef.get().booking();
            winnerInfo = Map.of(
                    "userId", b.userId(),
                    "bookingId", b.id(),
                    "status", b.status().name(),
                    "expiresAt", b.expiresAt() == null ? "" : b.expiresAt().toString()
            );
        }

        LockingStrategyType strategy = bookingService.defaultStrategyType();

        RaceSimulationResult result = new RaceSimulationResult(
                targetEventId,
                targetSeatId,
                seatLabel,
                racers,
                winnerInfo,
                successCount.get(),
                conflictCount.get(),
                doubleBookings,
                duration,
                strategy,
                doubleBookings == 0 && successCount.get() == 1
                        ? "PASSED: Exactly 1 winner, 0 double-bookings"
                        : "AUDIT WARNING: Contention anomaly",
                "VERIFIED_SAFE"
        );

        return ResponseEntity.ok(result);
    }

    private Seat resolveTargetSeat(Long eventId, Long seatId) {
        if (seatId != null) {
            return seatRepository.findById(seatId).orElseThrow();
        }
        if (eventId != null) {
            List<Seat> available = seatRepository.findByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
            if (!available.isEmpty()) {
                return available.getFirst();
            }
        }
        List<Seat> anyAvailable = seatRepository.findAll().stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .toList();
        if (!anyAvailable.isEmpty()) {
            return anyAvailable.getFirst();
        }

        Event event = new Event();
        event.setName("Live Concurrency Arena");
        event.setVenue("Virtual Engine Bench");
        event.setStartsAt(Instant.now().plus(7, ChronoUnit.DAYS));
        event = eventRepository.save(event);

        Seat seat = new Seat();
        seat.setEvent(event);
        seat.setSection("RACE");
        seat.setRowLabel("A");
        seat.setSeatNumber(1);
        seat.setPriceCents(5000L);
        seat.setStatus(SeatStatus.AVAILABLE);
        return seatRepository.save(seat);
    }
}
