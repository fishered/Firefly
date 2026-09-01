package com.firefly.schedule;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class DependencyGateTest {
    @Test void gateTracksFiniteDeadlineAndStatus() {
        Instant business = Instant.parse("2026-09-01T10:00:00Z");
        DependencyGate gate = new DependencyGate("job@" + business, "job", business,
                business.plusSeconds(1), business.plusSeconds(60), 0,
                DependencyGateStatus.WAITING, "pending");
        assertEquals(DependencyGateStatus.RELEASED, gate.withStatus(DependencyGateStatus.RELEASED, "ok").status());
        assertEquals(2, gate.next(business.plusSeconds(2), 2).waitAttempts());
    }
}
