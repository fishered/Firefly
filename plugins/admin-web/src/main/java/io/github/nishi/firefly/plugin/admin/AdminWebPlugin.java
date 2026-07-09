package io.github.nishi.firefly.plugin.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.nishi.firefly.cluster.FireflyNode;
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
 * Provides a tiny operational page without introducing a web framework into Firefly core.
 */
public final class AdminWebPlugin implements FireflyPlugin {
    private final AdminWebOptions options;
    private HttpServer server;
    private FireflyPluginContext context;

    public AdminWebPlugin() {
        this(AdminWebOptions.defaults());
    }

    public AdminWebPlugin(AdminWebOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return "admin-web";
    }

    @Override
    public void start(FireflyPluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            server.createContext("/", this::handleIndex);
            server.createContext("/api/health", this::handleHealth);
            server.createContext("/api/jobs", this::handleJobs);
            server.createContext("/api/nodes", this::handleNodes);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start Firefly admin web plugin", e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Firefly Admin</title>
                  <style>
                    body{font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;color:#202124;background:#f7f8fa}
                    header{background:#111827;color:white;padding:20px 28px}
                    main{max-width:1120px;margin:0 auto;padding:24px}
                    section{background:white;border:1px solid #e5e7eb;border-radius:8px;margin-bottom:18px;padding:18px}
                    table{width:100%%;border-collapse:collapse;font-size:14px}
                    th,td{text-align:left;border-bottom:1px solid #eef0f3;padding:10px}
                    th{color:#4b5563;font-weight:600}
                    .muted{color:#6b7280}
                  </style>
                </head>
                <body>
                  <header><h1>Firefly Admin</h1><div class="muted">lightweight scheduler operations</div></header>
                  <main>
                    <section><h2>Jobs</h2>%s</section>
                    <section><h2>Online Nodes</h2>%s</section>
                  </main>
                </body>
                </html>
                """.formatted(jobTable(jobs()), nodeTable(nodes()));
        respond(exchange, "text/html; charset=utf-8", html);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, "application/json; charset=utf-8", "{\"status\":\"UP\",\"plugin\":\"admin-web\"}");
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        respond(exchange, "application/json; charset=utf-8", AdminWebJson.jobs(jobs()));
    }

    private void handleNodes(HttpExchange exchange) throws IOException {
        respond(exchange, "application/json; charset=utf-8", AdminWebJson.nodes(nodes()));
    }

    private List<ScheduledJobRecord> jobs() {
        return context.jobRepository().map(repository -> repository.list()).orElse(List.of());
    }

    private List<FireflyNode> nodes() {
        Instant now = context.clock().instant();
        return context.nodeRegistry()
                .map(registry -> registry.listOnline(now, options.heartbeatTimeout()))
                .orElse(List.of());
    }

    private String jobTable(List<ScheduledJobRecord> jobs) {
        if (jobs.isEmpty()) {
            return "<p class=\"muted\">No jobs registered.</p>";
        }
        StringBuilder rows = new StringBuilder();
        for (ScheduledJobRecord job : jobs) {
            rows.append("<tr><td>").append(escape(job.definition().id()))
                    .append("</td><td>").append(escape(job.definition().groupId()))
                    .append("</td><td>").append(escape(job.definition().handlerName()))
                    .append("</td><td>").append(escape(job.definition().zoneId().getId()))
                    .append("</td><td>").append(escape(job.nextFireTime().toString()))
                    .append("</td></tr>");
        }
        return """
                <table>
                  <thead><tr><th>ID</th><th>Group</th><th>Handler</th><th>Zone</th><th>Next Fire Time</th></tr></thead>
                  <tbody>%s</tbody>
                </table>
                """.formatted(rows);
    }

    private String nodeTable(List<FireflyNode> nodes) {
        if (nodes.isEmpty()) {
            return "<p class=\"muted\">No online nodes.</p>";
        }
        StringBuilder rows = new StringBuilder();
        for (FireflyNode node : nodes) {
            rows.append("<tr><td>").append(escape(node.nodeId()))
                    .append("</td><td>").append(escape(node.roles().toString()))
                    .append("</td><td>").append(escape(node.lastHeartbeatAt().toString()))
                    .append("</td></tr>");
        }
        return """
                <table>
                  <thead><tr><th>Node</th><th>Roles</th><th>Last Heartbeat</th></tr></thead>
                  <tbody>%s</tbody>
                </table>
                """.formatted(rows);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
