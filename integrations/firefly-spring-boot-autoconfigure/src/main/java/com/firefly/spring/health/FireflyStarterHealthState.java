package com.firefly.spring.health;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Shared starter health state updated by optional runtime integrations. */
public final class FireflyStarterHealthState {
    private final Clock clock;
    private final AtomicReference<JobRegistration> jobRegistration = new AtomicReference<>(
            JobRegistration.notAttempted()
    );

    public FireflyStarterHealthState() {
        this(Clock.systemUTC());
    }

    FireflyStarterHealthState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JobRegistration jobRegistration() {
        return jobRegistration.get();
    }

    public void jobRegistrationDisabled() {
        jobRegistration.set(new JobRegistration(JobRegistrationStatus.DISABLED, 0, 0, List.of(), now()));
    }

    public void jobRegistrationSkippedNoJobs() {
        jobRegistration.set(new JobRegistration(JobRegistrationStatus.NO_JOBS, 0, 0, List.of(), now()));
    }

    public void jobRegistrationSucceeded(int synchronizedJobs) {
        jobRegistration.set(new JobRegistration(
                JobRegistrationStatus.SUCCESS,
                synchronizedJobs,
                0,
                List.of(),
                now()
        ));
    }

    public void jobRegistrationFailed(int synchronizedJobs, List<String> failures) {
        List<String> safeFailures = failures == null ? List.of() : List.copyOf(failures);
        jobRegistration.set(new JobRegistration(
                JobRegistrationStatus.FAILED,
                synchronizedJobs,
                safeFailures.size(),
                safeFailures,
                now()
        ));
    }

    private Instant now() {
        return clock.instant();
    }

    public enum JobRegistrationStatus {
        NOT_ATTEMPTED,
        DISABLED,
        NO_JOBS,
        SUCCESS,
        FAILED
    }

    public record JobRegistration(
            JobRegistrationStatus status,
            int synchronizedJobs,
            int failedJobs,
            List<String> failures,
            Instant checkedAt
    ) {
        public JobRegistration {
            Objects.requireNonNull(status, "status");
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        private static JobRegistration notAttempted() {
            return new JobRegistration(JobRegistrationStatus.NOT_ATTEMPTED, 0, 0, List.of(), null);
        }
    }
}
