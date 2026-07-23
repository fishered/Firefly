package com.firefly.spring.netty;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spring Boot properties for the business-side Netty executor client.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "firefly.executor")
public class FireflyNettyExecutorProperties {
    private boolean autoStart = true;
    private String schedulerHost = "127.0.0.1";
    private int schedulerPort = 9700;
    private List<String> gatewayAddresses = new ArrayList<>();
    private String name = "";
    private String instanceId = UUID.randomUUID().toString();
    private String serviceName = "";
    private Duration heartbeatInterval = Duration.ofSeconds(10);
    private Duration reconnectInitialDelay = Duration.ofSeconds(1);
    private Duration reconnectMaxDelay = Duration.ofSeconds(30);
    private String integrationKey = "";
    private String idempotencyDirectory = "";
    private Duration idempotencyRetention = Duration.ofHours(24);
    private boolean tlsEnabled;
    private String tlsCertificateChain = "";
    private String tlsPrivateKey = "";
    private String tlsPrivateKeyPassword = "";
    private String tlsTrustCertificates = "";
    private boolean tlsVerifyHostname = true;

}
