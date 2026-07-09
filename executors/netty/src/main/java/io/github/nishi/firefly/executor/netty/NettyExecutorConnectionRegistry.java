package io.github.nishi.firefly.executor.netty;

import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks live Netty channels by logical executor for remote dispatch routing.
 */
public final class NettyExecutorConnectionRegistry {
    private final Map<ConnectionKey, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();

    public void register(String executorName, String instanceId, Channel channel) {
        channels.put(new ConnectionKey(executorName, instanceId), channel);
    }

    public Optional<ConnectionKey> unregister(Channel channel) {
        Optional<ConnectionKey> key = channels.entrySet().stream()
                .filter(entry -> entry.getValue().equals(channel))
                .map(Map.Entry::getKey)
                .findFirst();
        key.ifPresent(channels::remove);
        return key;
    }

    public Optional<Channel> select(String executorName) {
        List<Channel> candidates = channels.entrySet().stream()
                .filter(entry -> entry.getKey().executorName().equals(executorName))
                .sorted(Comparator.comparing(entry -> entry.getKey().instanceId()))
                .map(Map.Entry::getValue)
                .filter(Channel::isActive)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(cursor.getAndIncrement(), candidates.size());
        return Optional.of(candidates.get(index));
    }

    public List<ConnectionKey> list() {
        return new ArrayList<>(channels.keySet());
    }

    public record ConnectionKey(String executorName, String instanceId) {
    }
}
