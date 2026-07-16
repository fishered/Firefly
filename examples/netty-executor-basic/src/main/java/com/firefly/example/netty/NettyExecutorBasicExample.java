package com.firefly.example.netty;

import com.firefly.executor.netty.NettyExecutorClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Business-side remote executor example that connects to a running Firefly server.
 */
public final class NettyExecutorBasicExample {
    private NettyExecutorBasicExample() {
    }

    public static void main(String[] args) throws Exception {
        String host = option(args, "host", "127.0.0.1");
        int port = Integer.parseInt(option(args, "port", "9700"));
        String adminHost = option(args, "adminHost", "127.0.0.1");
        int adminPort = Integer.parseInt(option(args, "adminPort", "9710"));
        String executorName = option(args, "executorName", "example-executor");
        String handlerName = option(args, "handlerName", "exampleHandler");
        String jobId = option(args, "jobId", "remote-example-job");
        String cron = option(args, "cron", "*/5 * * * * *");
        String zoneId = option(args, "zoneId", "Asia/Shanghai");
        boolean createJob = Boolean.parseBoolean(option(args, "createJob", "true"));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        try (NettyExecutorClient client = NettyExecutorClient.builder()
                .schedulerHost(host)
                .schedulerPort(port)
                .executorName(executorName)
                .serviceName("netty-executor-basic")
                .heartbeatInterval(Duration.ofSeconds(5))
                .build()
                .registerHandler(handlerName, context -> {
                    int count = executions.incrementAndGet();
                    System.out.println("remote executor fired count=" + count
                            + ", job=" + context.jobId()
                            + ", handler=" + context.handlerName()
                            + ", scheduled=" + context.scheduledFireTime()
                            + ", actual=" + context.actualFireTime()
                            + ", params=" + context.parameters());
                    latch.countDown();
                })) {
            startClient(client, host, port);
            System.out.println("remote executor registered: executor=" + executorName
                    + ", handler=" + handlerName
                    + ", gateway=" + host + ":" + port);
            if (createJob) {
                createRemoteJob(adminHost, adminPort, jobId, executorName, handlerName, cron, zoneId);
            } else {
                System.out.println("job auto-create disabled; create a remote job through Admin API manually.");
            }
            System.out.println("waiting for scheduler to dispatch job=" + jobId + " ...");
            if (!latch.await(120, TimeUnit.SECONDS)) {
                System.out.println("no execution received in 120s. Check server logs, cron, executor name, and handler name.");
            }
        }
    }

    private static void startClient(NettyExecutorClient client, String host, int port) throws InterruptedException {
        try {
            client.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to connect Firefly Netty executor gateway at " + host + ":" + port
                    + ". Start server first with: .\\gradlew.bat :server:launcher:run --args=\"--firefly.config=config/firefly-server.properties\"", e);
        }
    }

    private static void createRemoteJob(
            String adminHost,
            int adminPort,
            String jobId,
            String executorName,
            String handlerName,
            String cron,
            String zoneId
    ) throws IOException, InterruptedException {
        String body = "{"
                + "\"id\":\"" + escape(jobId) + "\","
                + "\"executorName\":\"" + escape(executorName) + "\","
                + "\"handlerName\":\"" + escape(handlerName) + "\","
                + "\"cron\":\"" + escape(cron) + "\","
                + "\"zoneId\":\"" + escape(zoneId) + "\","
                + "\"param.source\":\"netty-executor-basic\""
                + "}";
        URI uri = URI.create("http://" + adminHost + ":" + adminPort + "/api/jobs");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 201) {
            throw new IllegalStateException("failed to create remote job through Admin API: status="
                    + response.statusCode() + ", body=" + response.body());
        }
        System.out.println("remote job created: id=" + jobId
                + ", executor=" + executorName
                + ", handler=" + handlerName
                + ", cron=" + cron
                + ", admin=http://" + adminHost + ":" + adminPort + "/api/jobs");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String option(String[] args, String name, String defaultValue) {
        String prefix = "--" + name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return defaultValue;
    }
}
