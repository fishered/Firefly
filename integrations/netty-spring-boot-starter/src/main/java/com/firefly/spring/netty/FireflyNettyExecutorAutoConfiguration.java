package com.firefly.spring.netty;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.executor.netty.NettyJobHandlerRegistration;
import com.firefly.executor.netty.ExecutorResultStore;
import com.firefly.executor.netty.FileExecutorResultStore;
import com.firefly.executor.netty.InMemoryExecutorResultStore;
import com.firefly.executor.netty.NettyTlsOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-starts a business-side Netty executor client when explicitly enabled.
 */
@AutoConfiguration
@ConditionalOnClass(NettyExecutorClient.class)
@ConditionalOnProperty(prefix = "firefly.executor.netty", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FireflyNettyExecutorProperties.class)
public class FireflyNettyExecutorAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NettyExecutorClient fireflyNettyExecutorClient(
            FireflyNettyExecutorProperties properties,
            ObjectProvider<NettyJobHandlerRegistration> registrations,
            ObjectProvider<ExecutorResultStore> resultStores
    ) throws InterruptedException {
        ExecutorResultStore resultStore = resultStores.getIfAvailable(() ->
                properties.getIdempotencyDirectory() == null || properties.getIdempotencyDirectory().isBlank()
                        ? new InMemoryExecutorResultStore()
                        : new FileExecutorResultStore(
                                java.nio.file.Path.of(properties.getIdempotencyDirectory()),
                                properties.getIdempotencyRetention()
                        ));
        NettyExecutorClient client = NettyExecutorClient.builder()
                .schedulerHost(properties.getSchedulerHost())
                .schedulerPort(properties.getSchedulerPort())
                .gatewayAddresses(properties.getGatewayAddresses())
                .executorName(properties.getExecutorName())
                .instanceId(properties.getInstanceId())
                .serviceName(properties.getServiceName())
                .heartbeatInterval(properties.getHeartbeatInterval())
                .reconnectInitialDelay(properties.getReconnectInitialDelay())
                .reconnectMaxDelay(properties.getReconnectMaxDelay())
                .authToken(properties.getAuthToken())
                .tlsOptions(new NettyTlsOptions(
                        properties.isTlsEnabled(),
                        path(properties.getTlsCertificateChain()),
                        path(properties.getTlsPrivateKey()),
                        properties.getTlsPrivateKeyPassword(),
                        path(properties.getTlsTrustCertificates()),
                        false,
                        properties.isTlsVerifyHostname()
                ))
                .resultStore(resultStore)
                .build();
        registrations.orderedStream()
                .forEach(registration -> client.registerHandler(registration.handlerName(), registration.handler()));
        if (properties.isAutoStart()) {
            client.start();
        }
        return client;
    }

    private java.nio.file.Path path(String value) {
        return value == null || value.isBlank() ? null : java.nio.file.Path.of(value);
    }
}
