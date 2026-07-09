package io.github.nishi.firefly.spring.netty;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

/**
 * Spring Boot properties for the business-side Netty executor client.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "firefly.executor.netty")
public class FireflyNettyExecutorProperties {
    private boolean enabled;
    private boolean autoStart = true;
    private String schedulerHost = "127.0.0.1";
    private int schedulerPort = 9700;
    private String executorName = "default";
    private String instanceId = UUID.randomUUID().toString();
    private String serviceName = "firefly-executor";
    private Duration heartbeatInterval = Duration.ofSeconds(10);
}
