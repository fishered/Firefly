package com.firefly.executor.netty;

import com.firefly.domain.ExecutorRoutingStrategy;
import io.netty.channel.Channel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks live Netty channels by logical executor for remote dispatch routing.
 */
public final class NettyExecutorConnectionRegistry {
    private final Map<InstanceKey, ConnectionTarget> channels = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    public void register(String executorName, String instanceId, Channel channel) {
        register(executorName, instanceId, instanceId, channel);
    }

    public void register(String executorName, String instanceId, String sessionId, Channel channel) {
        InstanceKey key = new InstanceKey(executorName, instanceId);
        ConnectionTarget replacement = new ConnectionTarget(executorName, instanceId, sessionId, channel);
        ConnectionTarget previous = channels.put(key, replacement);
        if (previous != null && previous.channel() != channel && previous.channel().isOpen()) {
            previous.channel().close();
        }
    }

    public Optional<ConnectionKey> unregister(Channel channel) {
        Optional<Map.Entry<InstanceKey, ConnectionTarget>> entry = channels.entrySet().stream()
                .filter(candidate -> candidate.getValue().channel().equals(channel))
                .findFirst();
        entry.ifPresent(candidate -> channels.remove(candidate.getKey(), candidate.getValue()));
        return entry.map(candidate -> candidate.getValue().key());
    }

    public Optional<Channel> select(String executorName) {
        return select(executorName, ExecutorRoutingStrategy.ROUND_ROBIN, "").map(ConnectionTarget::channel);
    }

    public Optional<ConnectionTarget> select(
            String executorName,
            ExecutorRoutingStrategy strategy,
            String routingKey
    ) {
        List<ConnectionTarget> candidates = list(executorName);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (strategy == ExecutorRoutingStrategy.CONSISTENT_HASH) {
            return candidates.stream().max((left, right) -> Long.compareUnsigned(
                    rendezvousScore(routingKey, left.instanceId()),
                    rendezvousScore(routingKey, right.instanceId())
            ));
        }
        int index = strategy == ExecutorRoutingStrategy.RANDOM
                ? ThreadLocalRandom.current().nextInt(candidates.size())
                : Math.floorMod(cursors.computeIfAbsent(executorName, ignored -> new AtomicInteger())
                        .getAndIncrement(), candidates.size());
        return Optional.of(candidates.get(index));
    }

    public List<ConnectionKey> list() {
        return channels.values().stream().map(ConnectionTarget::key).toList();
    }

    public List<ConnectionTarget> list(String executorName) {
        return channels.values().stream()
                .filter(target -> target.executorName().equals(executorName))
                .filter(target -> target.channel().isActive())
                .sorted(Comparator.comparing(ConnectionTarget::instanceId))
                .toList();
    }

    public Optional<ConnectionTarget> find(String executorName, String instanceId) {
        ConnectionTarget target = channels.get(new InstanceKey(executorName, instanceId));
        return target != null && target.channel().isActive() ? Optional.of(target) : Optional.empty();
    }

    public boolean isCurrent(Channel channel, String instanceId, String sessionId) {
        return channels.values().stream().anyMatch(target -> target.channel().equals(channel)
                && target.instanceId().equals(instanceId) && target.sessionId().equals(sessionId));
    }

    private long rendezvousScore(String routingKey, String instanceId) {
        long hash = 0xcbf29ce484222325L;
        String value = routingKey + '\u0000' + instanceId;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private record InstanceKey(String executorName, String instanceId) {
    }

    public record ConnectionKey(String executorName, String instanceId, String sessionId) {
    }

    public record ConnectionTarget(String executorName, String instanceId, String sessionId, Channel channel) {
        ConnectionKey key() {
            return new ConnectionKey(executorName, instanceId, sessionId);
        }
    }
}
