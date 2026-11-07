package com.firefly.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Persistent weighted fair queue with strict effective-priority bands and time-based aging.
 *
 * <p>Every {@code agingInterval} raises a waiting entry by one level, capped at level 9.
 * Consequently a level-0 entry joins the highest-priority fair set after at most nine intervals;
 * it is then selected according to its tenant weight even under a continuous critical workload.
 */
public final class WeightedFairQueue<T> {
    private static final long FAIRNESS_QUANTUM = 1_000_000_000L;
    private final Duration agingInterval;
    private final ToIntFunction<String> weightResolver;
    private final Map<String, TenantState<T>> tenants = new HashMap<>();
    private final Map<String, Long> virtualFinishes;
    private final Set<String> entryIds = new HashSet<>();
    private long virtualTime;
    private long sequence;
    private int size;

    public WeightedFairQueue(Duration agingInterval, ToIntFunction<String> weightResolver) {
        this(agingInterval, weightResolver, Map.of(), 0);
    }

    /** Restores the fairness clock so independent claim batches share one weighted schedule. */
    public WeightedFairQueue(
            Duration agingInterval,
            ToIntFunction<String> weightResolver,
            Map<String, Long> virtualFinishes,
            long virtualTime
    ) {
        this.agingInterval = Objects.requireNonNull(agingInterval, "agingInterval");
        this.weightResolver = Objects.requireNonNull(weightResolver, "weightResolver");
        this.virtualFinishes = new HashMap<>(Objects.requireNonNull(virtualFinishes, "virtualFinishes"));
        this.virtualTime = virtualTime;
        if (agingInterval.isZero() || agingInterval.isNegative()) {
            throw new IllegalArgumentException("agingInterval must be positive");
        }
        if (virtualTime < 0 || this.virtualFinishes.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("virtual fairness state must not be negative");
        }
    }

    /** Returns false for an idempotent duplicate entry id. */
    public synchronized boolean offer(TenantQueueEntry<T> entry) {
        Objects.requireNonNull(entry, "entry");
        if (!entryIds.add(entry.entryId())) return false;
        TenantState<T> state = tenants.computeIfAbsent(entry.tenantId(), TenantState::new);
        state.bands.get(entry.priority()).addLast(new Queued<>(entry, sequence++));
        size++;
        return true;
    }

    public synchronized Optional<TenantQueueEntry<T>> poll(Instant now) {
        Objects.requireNonNull(now, "now");
        if (size == 0) return Optional.empty();

        List<Candidate<T>> candidates = new ArrayList<>(tenants.size());
        int highestEffectivePriority = TenantPriority.MIN_LEVEL;
        for (TenantState<T> state : tenants.values()) {
            Candidate<T> candidate = bestCandidate(state, now);
            if (candidate == null) continue;
            candidates.add(candidate);
            highestEffectivePriority = Math.max(highestEffectivePriority, candidate.effectivePriority());
        }

        List<Candidate<T>> eligible = new ArrayList<>();
        for (Candidate<T> candidate : candidates) {
            if (candidate.effectivePriority() != highestEffectivePriority) continue;
            eligible.add(candidate);
        }

        Candidate<T> selected = eligible.getFirst();
        for (int index = 1; index < eligible.size(); index++) {
            Candidate<T> candidate = eligible.get(index);
            long candidateFinish = effectiveVirtualFinish(candidate.state().tenantId);
            long selectedFinish = effectiveVirtualFinish(selected.state().tenantId);
            if (candidateFinish < selectedFinish
                    || candidateFinish == selectedFinish
                    && candidate.state().tenantId.compareTo(selected.state().tenantId) < 0) {
                selected = candidate;
            }
        }
        advanceVirtualClock(selected.state().tenantId);
        selected.state().bands.get(selected.queued().entry().priority()).remove(selected.queued());
        entryIds.remove(selected.queued().entry().entryId());
        size--;
        if (selected.state().empty()) tenants.remove(selected.state().tenantId);
        return Optional.of(selected.queued().entry());
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized Map<String, Long> virtualFinishes() {
        return Map.copyOf(virtualFinishes);
    }

    public synchronized long virtualTime() {
        return virtualTime;
    }

    private long effectiveVirtualFinish(String tenantId) {
        return Math.max(virtualTime, virtualFinishes.getOrDefault(tenantId, virtualTime));
    }

    private void advanceVirtualClock(String tenantId) {
        int weight = Math.max(1, weightResolver.applyAsInt(tenantId));
        long increment = Math.max(1, (FAIRNESS_QUANTUM + weight - 1L) / weight);
        long start = effectiveVirtualFinish(tenantId);
        if (start > Long.MAX_VALUE - increment) {
            normalizeVirtualClock();
            start = effectiveVirtualFinish(tenantId);
        }
        virtualTime = start;
        virtualFinishes.put(tenantId, Math.addExact(start, increment));
    }

    private void normalizeVirtualClock() {
        long baseline = virtualTime;
        virtualFinishes.replaceAll((ignored, finish) -> Math.max(0, finish - baseline));
        virtualTime = 0;
    }

    private Candidate<T> bestCandidate(TenantState<T> state, Instant now) {
        Candidate<T> best = null;
        for (int priority = TenantPriority.MIN_LEVEL; priority <= TenantPriority.MAX_LEVEL; priority++) {
            Queued<T> queued = state.bands.get(priority).peekFirst();
            if (queued == null) continue;
            int effective = effectivePriority(queued.entry(), now);
            Candidate<T> candidate = new Candidate<>(state, queued, effective);
            if (best == null || better(candidate, best)) best = candidate;
        }
        return best;
    }

    private static <T> boolean better(Candidate<T> candidate, Candidate<T> current) {
        if (candidate.effectivePriority() != current.effectivePriority()) {
            return candidate.effectivePriority() > current.effectivePriority();
        }
        int time = candidate.queued().entry().enqueuedAt().compareTo(current.queued().entry().enqueuedAt());
        if (time != 0) return time < 0;
        if (candidate.queued().entry().priority() != current.queued().entry().priority()) {
            return candidate.queued().entry().priority() > current.queued().entry().priority();
        }
        return candidate.queued().sequence() < current.queued().sequence();
    }

    private int effectivePriority(TenantQueueEntry<T> entry, Instant now) {
        if (!now.isAfter(entry.enqueuedAt())) return entry.priority();
        long waitedNanos;
        try {
            waitedNanos = Duration.between(entry.enqueuedAt(), now).toNanos();
        } catch (ArithmeticException overflow) {
            return TenantPriority.MAX_LEVEL;
        }
        long levels = waitedNanos / agingInterval.toNanos();
        return (int) Math.min(TenantPriority.MAX_LEVEL, entry.priority() + levels);
    }

    private static final class TenantState<T> {
        private final String tenantId;
        private final List<ArrayDeque<Queued<T>>> bands = new ArrayList<>(TenantPriority.MAX_LEVEL + 1);
        private TenantState(String tenantId) {
            this.tenantId = tenantId;
            for (int priority = TenantPriority.MIN_LEVEL; priority <= TenantPriority.MAX_LEVEL; priority++) {
                bands.add(new ArrayDeque<>());
            }
        }

        private boolean empty() {
            return bands.stream().allMatch(ArrayDeque::isEmpty);
        }
    }

    private record Queued<T>(TenantQueueEntry<T> entry, long sequence) { }

    private record Candidate<T>(TenantState<T> state, Queued<T> queued, int effectivePriority) { }
}
