package com.suriya.seatlock.demo;

import com.suriya.seatlock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RaceSimulationTest extends IntegrationTest {

    @Test
    void serverSideVirtualThreadRaceYieldsExactlyOneWinnerAndZeroDoubleBookings() {
        long eventId = createEvent("VIP", 1, 10, 5000);
        long seatId = seatIds(eventId).getFirst();

        ResponseEntity<JsonNode> response = post(
                "/api/demo/race?eventId=" + eventId + "&seatId=" + seatId + "&concurrency=50",
                null,
                null,
                Map.of()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("concurrency").asInt()).isEqualTo(50);
        assertThat(body.get("successfulHolds").asInt()).isEqualTo(1);
        assertThat(body.get("conflicts").asInt()).isEqualTo(49);
        assertThat(body.get("doubleBookedSeats").asLong()).isEqualTo(0);
        assertThat(body.get("status").asString()).isEqualTo("VERIFIED_SAFE");
        assertThat(body.get("auditVerification").asString()).contains("PASSED");
    }
}
