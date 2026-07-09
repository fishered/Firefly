package com.firefly.example.netty;

import com.firefly.executor.netty.NettyExecutorClient;

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

    public static void main(String[] args) throws InterruptedException {
        String host = option(args, "host", "127.0.0.1");
        int port = Integer.parseInt(option(args, "port", "9700"));
        String executorName = option(args, "executorName", "example-executor");
        String handlerName = option(args, "handlerName", "exampleHandler");
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
            client.start();
            System.out.println("remote executor registered: executor=" + executorName
                    + ", handler=" + handlerName
                    + ", gateway=" + host + ":" + port);
            System.out.println("waiting for server API to create and schedule a job...");
            latch.await(120, TimeUnit.SECONDS);
        }
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
