package io.github.nishi.firefly.plugin.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.nishi.firefly.plugin.FireflyPlugin;
import io.github.nishi.firefly.plugin.FireflyPluginContext;
import io.github.nishi.firefly.store.ScheduledJobRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Exposes Firefly state in Prometheus text format as an optional plugin.
 */
public final class PrometheusMetricsPlugin implements FireflyPlugin {
    private final PrometheusMetricsOptions options;
    private HttpServer server;
    private FireflyPluginContext context;

    public PrometheusMetricsPlugin() {
        this(PrometheusMetricsOptions.defaults());
    }

    public PrometheusMetricsPlugin(PrometheusMetricsOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return "metrics-prometheus";
    }

    @Override
    public void start(FireflyPluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            server.createContext(options.path(), this::handleMetrics);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start Firefly Prometheus metrics plugin", e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        String body = render();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String render() {
        List<ScheduledJobRecord> jobs = context.jobRepository()
                .map(repository -> repository.list())
                .orElse(List.of());
        long enabledJobs = jobs.stream().filter(record -> record.definition().enabled()).count();
        int onlineNodes = context.nodeRegistry()
                .map(registry -> registry.listOnline(context.clock().instant(), options.heartbeatTimeout()).size())
                .orElse(0);
        long nextFireEpochSeconds = jobs.stream()
                .map(ScheduledJobRecord::nextFireTime)
                .min(Instant::compareTo)
                .map(Instant::getEpochSecond)
                .orElse(0L);

        /**
         * Metrics are derived from plugin context snapshots. The scheduler core does
         * not know Prometheus exists, which keeps monitoring replaceable.
         */
        return """
                # HELP firefly_plugin_up Whether the Firefly Prometheus metrics plugin is running.
                # TYPE firefly_plugin_up gauge
                firefly_plugin_up{plugin="metrics-prometheus"} 1
                # HELP firefly_jobs_total Number of known Firefly jobs.
                # TYPE firefly_jobs_total gauge
                firefly_jobs_total %d
                # HELP firefly_jobs_enabled Number of enabled Firefly jobs.
                # TYPE firefly_jobs_enabled gauge
                firefly_jobs_enabled %d
                # HELP firefly_nodes_online Number of online Firefly nodes visible to the plugin.
                # TYPE firefly_nodes_online gauge
                firefly_nodes_online %d
                # HELP firefly_next_fire_time_epoch_seconds Earliest next fire time across known jobs.
                # TYPE firefly_next_fire_time_epoch_seconds gauge
                firefly_next_fire_time_epoch_seconds %d
                """.formatted(jobs.size(), enabledJobs, onlineNodes, nextFireEpochSeconds);
    }
}
