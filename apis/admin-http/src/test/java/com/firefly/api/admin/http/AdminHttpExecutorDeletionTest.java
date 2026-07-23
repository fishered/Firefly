package com.firefly.api.admin.http;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobDestination;
import com.firefly.domain.JobGroupDefinition;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpExecutorDeletionTest {
    @Test
    void deletesOnlyUnreferencedDefinitionsWithoutOnlineInstances() throws Exception {
        int port = freePort();
        Instant now = Instant.now();
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        InMemoryJobRepository jobs = new InMemoryJobRepository();
        InMemoryExecutorRegistry instances = new InMemoryExecutorRegistry();

        saveExecutors(catalog, "unused", "job-bound", "group-bound", "online", "offline");
        jobs.save(JobDefinition.builder()
                .id("remote-job")
                .name("Remote job")
                .handlerName("run")
                .destination(JobDestination.remote("job-bound"))
                .schedule(new CronSchedule("0 * * * * *"))
                .build(), now);
        catalog.saveJobGroup(JobGroupDefinition.builder()
                .id("remote-group")
                .name("Remote group")
                .executorName("group-bound")
                .build());
        instances.register(instance("online", "online-1", now));
        instances.register(instance("offline", "offline-1", now));
        instances.markOffline("offline", "offline-1");

        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder()
                .schedulerCatalog(catalog)
                .jobRepository(jobs)
                .executorRegistry(instances)
                .build());
        try {
            HttpResponse<String> unused = delete(port, "unused");
            assertEquals(200, unused.statusCode());
            assertTrue(unused.body().contains("\"status\":\"deleted\""));
            assertFalse(catalog.findExecutor("unused").isPresent());

            HttpResponse<String> jobBound = delete(port, "job-bound");
            assertEquals(409, jobBound.statusCode());
            assertTrue(jobBound.body().contains("\"error\":\"executor_has_jobs\""));
            assertTrue(catalog.findExecutor("job-bound").isPresent());

            HttpResponse<String> groupBound = delete(port, "group-bound");
            assertEquals(409, groupBound.statusCode());
            assertTrue(groupBound.body().contains("\"error\":\"executor_has_job_groups\""));
            assertTrue(catalog.findExecutor("group-bound").isPresent());

            HttpResponse<String> online = delete(port, "online");
            assertEquals(409, online.statusCode());
            assertTrue(online.body().contains("\"error\":\"executor_has_online_instances\""));
            assertTrue(catalog.findExecutor("online").isPresent());

            assertEquals(200, delete(port, "offline").statusCode());
            assertFalse(catalog.findExecutor("offline").isPresent());
            assertEquals(404, delete(port, "missing").statusCode());
        } finally {
            plugin.close();
        }
    }

    private void saveExecutors(InMemorySchedulerCatalog catalog, String... names) {
        for (String name : names) {
            catalog.saveExecutor(ExecutorDefinition.builder().name(name).build());
        }
    }

    private ExecutorInstance instance(String executorName, String instanceId, Instant now) {
        return ExecutorInstance.builder()
                .executorName(executorName)
                .instanceId(instanceId)
                .serviceName("test-service")
                .registeredAt(now)
                .lastHeartbeatAt(now)
                .build();
    }

    private HttpResponse<String> delete(int port, String executorName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/api/executor-definitions/" + executorName))
                .DELETE()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
