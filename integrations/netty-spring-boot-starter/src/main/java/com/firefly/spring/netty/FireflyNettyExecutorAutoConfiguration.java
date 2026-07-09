package com.firefly.spring.netty;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.executor.netty.NettyJobHandlerRegistration;
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
            ObjectProvider<NettyJobHandlerRegistration> registrations
    ) throws InterruptedException {
        NettyExecutorClient client = NettyExecutorClient.builder()
                .schedulerHost(properties.getSchedulerHost())
                .schedulerPort(properties.getSchedulerPort())
                .executorName(properties.getExecutorName())
                .instanceId(properties.getInstanceId())
                .serviceName(properties.getServiceName())
                .heartbeatInterval(properties.getHeartbeatInterval())
                .build();
        registrations.orderedStream()
                .forEach(registration -> client.registerHandler(registration.handlerName(), registration.handler()));
        if (properties.isAutoStart()) {
            client.start();
        }
        return client;
    }
}
