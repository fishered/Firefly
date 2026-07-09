package io.github.nishi.firefly.plugin.admin;

import io.github.nishi.firefly.cluster.FireflyNode;
import io.github.nishi.firefly.store.ScheduledJobRecord;

import java.util.List;

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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
