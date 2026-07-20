package com.firefly.spring.netty;

import com.firefly.executor.idempotency.jdbc.JdbcBusinessIdempotencyStore;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "firefly.executor.business-idempotency")
public class FireflyBusinessIdempotencyProperties {
    private boolean enabled;
    private Duration abandonedClaimTimeout = Duration.ofMinutes(30);
    private String tableName = JdbcBusinessIdempotencyStore.DEFAULT_TABLE;
}
