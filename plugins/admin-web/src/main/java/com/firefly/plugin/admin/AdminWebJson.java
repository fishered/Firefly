package com.firefly.plugin.admin;

import com.firefly.cluster.FireflyNode;
import com.firefly.store.ScheduledJobRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdminWebJson {
    private AdminWebJson() {
    }

    static String jobs(List<ScheduledJobRecord> jobs) {
        StringBuilder json = new StringBuilder("{\"jobs\":[");
        for (int index = 0; index < jobs.size(); index++) {
            ScheduledJobRecord job = jobs.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escape(job.definition().id()))
                    .append("\",\"groupId\":\"").append(escape(job.definition().groupId()))
                    .append("\",\"handlerName\":\"").append(escape(job.definition().handlerName()))
                    .append("\",\"zoneId\":\"").append(escape(job.definition().zoneId().getId()))
                    .append("\",\"nextFireTime\":\"").append(escape(job.nextFireTime().toString()))
                    .append("\"}");
        }
        return json.append("]}").toString();
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
                    .append("\",\"status\":\"").append(node.status().name())
                    .append("\",\"lastHeartbeatAt\":\"").append(escape(node.lastHeartbeatAt().toString()))
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    static Map<String, String> object(String json) {
        String value = json.trim();
        if (!value.startsWith("{") || !value.endsWith("}")) {
            throw new IllegalArgumentException("json object expected");
        }
        value = value.substring(1, value.length() - 1).trim();
        Map<String, String> map = new LinkedHashMap<>();
        if (value.isEmpty()) {
            return map;
        }
        for (String part : value.split(",")) {
            String[] pair = part.split(":", 2);
            if (pair.length != 2) {
                throw new IllegalArgumentException("invalid json field: " + part);
            }
            map.put(unquote(pair[0].trim()), unquote(pair[1].trim()));
        }
        return map;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
