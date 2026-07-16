package com.firefly.api.admin.http;

import com.firefly.cluster.FireflyNode;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorInstance;
import com.firefly.store.ScheduledJobRecord;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionTargetRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdminHttpJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AdminHttpJson() {
    }

    static String jobs(List<ScheduledJobRecord> jobs) {
        StringBuilder json = new StringBuilder("{\"jobs\":[");
        for (int index = 0; index < jobs.size(); index++) {
            ScheduledJobRecord job = jobs.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escape(job.definition().id()))
                    .append("\",\"name\":\"").append(escape(job.definition().name()))
                    .append("\",\"groupId\":\"").append(escape(job.definition().groupId()))
                    .append("\",\"handlerName\":\"").append(escape(job.definition().handlerName()))
                    .append("\",\"executorName\":\"").append(escape(
                            job.definition().remote() ? job.definition().destination().executorName() : "embedded"
                    ))
                    .append("\",\"businessHandlerName\":\"").append(escape(job.definition().businessHandlerName()))
                    .append("\",\"schedule\":\"").append(escape(job.definition().schedule().toString()))
                    .append("\",\"zoneId\":\"").append(escape(job.definition().zoneId().getId()))
                    .append("\",\"enabled\":").append(job.definition().enabled())
                    .append(",\"misfirePolicy\":\"").append(job.definition().misfirePolicy().name())
                    .append("\",\"concurrencyPolicy\":\"").append(job.definition().concurrencyPolicy().name())
                    .append("\",\"dispatchMode\":\"").append(job.definition().dispatchMode().name())
                    .append("\",\"routingStrategy\":\"").append(job.definition().routingStrategy().name())
                    .append("\",\"completionPolicy\":\"").append(job.definition().completionPolicy().name())
                    .append("\",\"shardCount\":").append(job.definition().shardCount())
                    .append(",\"routingKey\":\"").append(escape(job.definition().routingKey()))
                    .append("\",\"retryMaxAttempts\":").append(job.definition().retryPolicy().maxAttempts())
                    .append(",\"retryInitialDelay\":\"").append(job.definition().retryPolicy().initialDelay())
                    .append("\",\"retryMultiplier\":").append(job.definition().retryPolicy().multiplier())
                    .append(",\"retryMaxDelay\":\"").append(job.definition().retryPolicy().maxDelay())
                    .append("\",\"retryOnFailure\":").append(job.definition().retryPolicy().retryOnFailure())
                    .append(",\"retryOnTimeout\":").append(job.definition().retryPolicy().retryOnTimeout())
                    .append(",\"nextFireTime\":\"").append(escape(job.nextFireTime().toString()))
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    static String overview(List<ScheduledJobRecord> jobs, List<FireflyNode> nodes, List<ExecutorInstance> instances) {
        long enabledJobs = jobs.stream().filter(job -> job.definition().enabled()).count();
        long disabledJobs = jobs.size() - enabledJobs;
        Instant nextFireTime = jobs.stream()
                .filter(job -> job.definition().enabled())
                .map(ScheduledJobRecord::nextFireTime)
                .min(Instant::compareTo)
                .orElse(null);
        return "{\"status\":\"UP\""
                + ",\"jobsTotal\":" + jobs.size()
                + ",\"jobsEnabled\":" + enabledJobs
                + ",\"jobsDisabled\":" + disabledJobs
                + ",\"nodesOnline\":" + nodes.size()
                + ",\"executorsOnline\":" + instances.size()
                + ",\"nextFireTime\":\"" + (nextFireTime == null ? "" : escape(nextFireTime.toString())) + "\"}";
    }

    static String nodes(List<FireflyNode> nodes) {
        StringBuilder json = new StringBuilder("{\"nodes\":[");
        for (int index = 0; index < nodes.size(); index++) {
            FireflyNode node = nodes.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"nodeId\":\"").append(escape(node.nodeId()))
                    .append("\",\"roles\":\"").append(escape(node.roles().toString()))
                    .append("\",\"mode\":\"").append(escape(node.metadata().getOrDefault("mode", "cluster")))
                    .append("\",\"registeredAt\":\"").append(escape(node.registeredAt().toString()))
                    .append("\",\"status\":\"").append(node.status().name())
                    .append("\",\"lastHeartbeatAt\":\"").append(escape(node.lastHeartbeatAt().toString()))
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    static String executions(List<ScheduledJobRecord> jobs, Instant now) {
        StringBuilder json = new StringBuilder("{\"executions\":[");
        List<ScheduledJobRecord> sorted = jobs.stream()
                .sorted((left, right) -> left.nextFireTime().compareTo(right.nextFireTime()))
                .limit(50)
                .toList();
        for (int index = 0; index < sorted.size(); index++) {
            ScheduledJobRecord job = sorted.get(index);
            if (index > 0) {
                json.append(',');
            }
            String executionId = job.definition().id() + "@" + job.nextFireTime();
            String status = job.definition().enabled() ? "SCHEDULED" : "DISABLED";
            json.append("{\"executionId\":\"").append(escape(executionId))
                    .append("\",\"jobId\":\"").append(escape(job.definition().id()))
                    .append("\",\"scheduledFireTime\":\"").append(escape(job.nextFireTime().toString()))
                    .append("\",\"dispatchTime\":\"").append(escape(job.nextFireTime().isBefore(now) ? now.toString() : ""))
                    .append("\",\"startTime\":\"\"")
                    .append(",\"endTime\":\"\"")
                    .append(",\"duration\":\"-\"")
                    .append(",\"executorInstance\":\"").append(escape(executorName(job.definition().handlerName(), job.definition().parameters())))
                    .append("\",\"status\":\"").append(status)
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    static String executionHistory(List<ExecutionRecord> executions) {
        StringBuilder json = new StringBuilder("{\"executions\":[");
        for (int index = 0; index < executions.size(); index++) {
            ExecutionRecord execution = executions.get(index);
            if (index > 0) json.append(',');
            json.append("{\"executionId\":\"").append(escape(execution.executionId()))
                    .append("\",\"rootExecutionId\":\"").append(escape(execution.rootExecutionId()))
                    .append("\",\"runAttempt\":").append(execution.runAttempt())
                    .append(",\"jobId\":\"").append(escape(execution.jobId()))
                    .append("\",\"scheduledFireTime\":\"").append(execution.scheduledFireTime())
                    .append("\",\"dispatchTime\":\"").append(execution.dispatchTime())
                    .append("\",\"dispatchMode\":\"").append(execution.dispatchMode().name())
                    .append("\",\"expectedTargets\":").append(execution.expectedTargets())
                    .append(",\"acceptedTargets\":").append(execution.acceptedTargets())
                    .append(",\"ownerNodeId\":\"").append(escape(execution.ownerNodeId()))
                    .append("\",\"fencingToken\":").append(execution.fencingToken())
                    .append(",\"status\":\"").append(execution.status().name())
                    .append("\",\"startTime\":\"\",\"endTime\":\"\",\"duration\":\"-\",\"executorInstance\":\"")
                    .append(execution.acceptedTargets()).append('/').append(execution.expectedTargets())
                    .append(" targets\"}");
        }
        return json.append("]}").toString();
    }

    static String executionDetail(ExecutionRecord execution, List<ExecutionTargetRecord> targets) {
        StringBuilder json = new StringBuilder("{\"execution\":");
        appendExecution(json, execution);
        json.append(",\"targets\":[");
        for (int index = 0; index < targets.size(); index++) {
            if (index > 0) json.append(',');
            appendExecutionTarget(json, targets.get(index));
        }
        json.append("],\"summary\":{")
                .append("\"expectedTargets\":").append(execution.expectedTargets())
                .append(",\"acceptedTargets\":").append(execution.acceptedTargets())
                .append(",\"targetRecords\":").append(targets.size())
                .append(",\"succeededTargets\":").append(targets.stream()
                        .filter(target -> target.status().name().equals("SUCCEEDED")).count())
                .append(",\"failedTargets\":").append(targets.stream()
                        .filter(target -> target.status().name().equals("FAILED")).count())
                .append(",\"timeoutTargets\":").append(targets.stream()
                        .filter(target -> target.status().name().equals("TIMEOUT")).count())
                .append(",\"carriedTargets\":").append(targets.stream()
                        .filter(AdminHttpJson::carriedTarget).count())
                .append("}}");
        return json.toString();
    }

    static String deadDispatches(List<DispatchOutboxRecord> records) {
        StringBuilder json = new StringBuilder("{\"deadDispatches\":[");
        for (int index = 0; index < records.size(); index++) {
            if (index > 0) json.append(',');
            DispatchOutboxRecord record = records.get(index);
            json.append("{\"outboxId\":\"").append(escape(record.outboxId()))
                    .append("\",\"executionId\":\"").append(escape(record.command().executionId()))
                    .append("\",\"rootExecutionId\":\"").append(escape(record.command().rootExecutionId()))
                    .append("\",\"runAttempt\":").append(record.command().runAttempt())
                    .append(",\"jobId\":\"").append(escape(record.command().definition().id()))
                    .append("\",\"dispatchType\":\"").append(record.dispatchType().name())
                    .append("\",\"status\":\"").append(record.status().name())
                    .append("\",\"attempt\":").append(record.attempt())
                    .append(",\"availableAt\":\"").append(record.availableAt())
                    .append("\",\"claimOwner\":\"").append(escape(record.claimOwner()))
                    .append("\",\"claimUntil\":\"").append(instant(record.claimUntil()))
                    .append("\",\"ackDeadline\":\"").append(instant(record.ackDeadline()))
                    .append("\",\"lastError\":\"").append(escape(record.lastError()))
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    private static void appendExecution(StringBuilder json, ExecutionRecord execution) {
        json.append("{\"executionId\":\"").append(escape(execution.executionId()))
                .append("\",\"rootExecutionId\":\"").append(escape(execution.rootExecutionId()))
                .append("\",\"runAttempt\":").append(execution.runAttempt())
                .append(",\"jobId\":\"").append(escape(execution.jobId()))
                .append("\",\"scheduledFireTime\":\"").append(execution.scheduledFireTime())
                .append("\",\"dispatchTime\":\"").append(execution.dispatchTime())
                .append("\",\"dispatchMode\":\"").append(execution.dispatchMode().name())
                .append("\",\"completionPolicy\":\"").append(execution.completionPolicy().name())
                .append("\",\"status\":\"").append(execution.status().name())
                .append("\",\"expectedTargets\":").append(execution.expectedTargets())
                .append(",\"acceptedTargets\":").append(execution.acceptedTargets())
                .append(",\"ownerNodeId\":\"").append(escape(execution.ownerNodeId()))
                .append("\",\"fencingToken\":").append(execution.fencingToken())
                .append(",\"timeoutAt\":\"").append(instant(execution.timeoutAt()))
                .append("\",\"createdAt\":\"").append(execution.createdAt())
                .append("\",\"updatedAt\":\"").append(execution.updatedAt())
                .append("\"}");
    }

    private static void appendExecutionTarget(StringBuilder json, ExecutionTargetRecord target) {
        json.append("{\"targetExecutionId\":\"").append(escape(target.targetExecutionId()))
                .append("\",\"executionId\":\"").append(escape(target.executionId()))
                .append("\",\"instanceId\":\"").append(escape(target.instanceId()))
                .append("\",\"gatewayNodeId\":\"").append(escape(target.gatewayNodeId()))
                .append("\",\"shardIndex\":");
        if (target.shardIndex() == null) json.append("null");
        else json.append(target.shardIndex());
        json.append(",\"status\":\"").append(target.status().name())
                .append("\",\"attempt\":").append(target.attempt())
                .append(",\"acknowledgedAt\":\"").append(instant(target.acknowledgedAt()))
                .append("\",\"completedAt\":\"").append(instant(target.completedAt()))
                .append("\",\"errorMessage\":\"").append(escape(target.errorMessage()))
                .append("\",\"createdAt\":\"").append(target.createdAt())
                .append("\",\"updatedAt\":\"").append(target.updatedAt())
                .append("\",\"carried\":").append(carriedTarget(target))
                .append('}');
    }

    private static boolean carriedTarget(ExecutionTargetRecord target) {
        return target.targetExecutionId().contains("@carry:");
    }

    static String executors(
            List<ExecutorDefinition> definitions,
            List<ExecutorInstance> instances,
            Instant now,
            Duration heartbeatTimeout
    ) {
        StringBuilder json = new StringBuilder("{\"definitions\":[");
        appendExecutorDefinitions(json, definitions);
        json.append("],\"instances\":[");
        appendExecutorInstances(json, instances, now, heartbeatTimeout);
        json.append("],\"executors\":[");
        appendExecutorInstances(json, instances, now, heartbeatTimeout);
        return json.append("]}").toString();
    }

    static String executorDefinitions(List<ExecutorDefinition> definitions) {
        StringBuilder json = new StringBuilder("{\"definitions\":[");
        appendExecutorDefinitions(json, definitions);
        return json.append("]}").toString();
    }

    static String executorDefinition(ExecutorDefinition definition) {
        StringBuilder json = new StringBuilder();
        appendExecutorDefinition(json, definition);
        return json.toString();
    }

    private static void appendExecutorDefinitions(StringBuilder json, List<ExecutorDefinition> definitions) {
        for (int index = 0; index < definitions.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            appendExecutorDefinition(json, definitions.get(index));
        }
    }

    private static void appendExecutorDefinition(StringBuilder json, ExecutorDefinition definition) {
        json.append("{\"name\":\"").append(escape(definition.name()))
                .append("\",\"description\":\"").append(escape(definition.description()))
                .append("\",\"protocols\":[");
        int protocolIndex = 0;
        for (var protocol : definition.protocols().stream().sorted().toList()) {
            if (protocolIndex++ > 0) {
                json.append(',');
            }
            json.append("\"").append(protocol.name()).append("\"");
        }
        json.append("],\"enabled\":").append(definition.enabled()).append('}');
    }

    private static void appendExecutorInstances(
            StringBuilder json,
            List<ExecutorInstance> instances,
            Instant now,
            Duration heartbeatTimeout
    ) {
        for (int index = 0; index < instances.size(); index++) {
            ExecutorInstance instance = instances.get(index);
            if (index > 0) {
                json.append(',');
            }
            boolean online = instance.status().name().equals("ONLINE")
                    && !instance.lastHeartbeatAt().isBefore(now.minus(heartbeatTimeout));
            json.append("{\"executorName\":\"").append(escape(instance.executorName()))
                    .append("\",\"instanceId\":\"").append(escape(instance.instanceId()))
                    .append("\",\"sessionId\":\"").append(escape(instance.sessionId()))
                    .append("\",\"gatewayNodeId\":\"").append(escape(instance.gatewayNodeId()))
                    .append("\",\"serviceName\":\"").append(escape(instance.serviceName()))
                    .append("\",\"host\":\"").append(escape(instance.host()))
                    .append("\",\"port\":").append(instance.port())
                    .append(",\"protocol\":\"").append(instance.protocol().name())
                    .append("\",\"status\":\"").append(online ? "ONLINE" : "OFFLINE")
                    .append("\",\"lastHeartbeatAt\":\"").append(escape(instance.lastHeartbeatAt().toString()))
                    .append("\",\"heartbeatAgeSeconds\":").append(Math.max(0, Duration.between(instance.lastHeartbeatAt(), now).toSeconds()))
                    .append('}');
        }
    }

    static Map<String, String> object(String json) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() { });
            Map<String, String> result = new LinkedHashMap<>();
            parsed.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalid JSON object", e);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String instant(Instant value) {
        return value == null ? "" : escape(value.toString());
    }

    static String executorName(String handlerName, Map<String, String> parameters) {
        String value = parameters.get("executorName");
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (handlerName.startsWith("remote:")) {
            String[] parts = handlerName.split(":", 3);
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return "embedded";
    }

    static String businessHandlerName(String handlerName, Map<String, String> parameters) {
        String value = parameters.get("handlerName");
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (handlerName.startsWith("remote:")) {
            String[] parts = handlerName.split(":", 3);
            if (parts.length == 3) {
                return parts[2];
            }
        }
        return handlerName;
    }

    private static String unquote(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }
}
