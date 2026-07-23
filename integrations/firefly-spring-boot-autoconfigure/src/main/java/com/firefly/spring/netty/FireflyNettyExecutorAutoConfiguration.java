package com.firefly.spring.netty;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.executor.netty.AuthTokenProvider;
import com.firefly.executor.netty.NettyJobHandlerRegistration;
import com.firefly.executor.netty.ExecutorResultStore;
import com.firefly.executor.netty.FileExecutorResultStore;
import com.firefly.executor.netty.InMemoryExecutorResultStore;
import com.firefly.executor.netty.NettyTlsOptions;
import com.firefly.handler.NamedJobHandler;
import com.firefly.handler.FireflyJobHandlerRegistration;
import com.firefly.idempotency.BusinessIdempotencyStore;
import com.firefly.executor.idempotency.jdbc.JdbcBusinessIdempotencyStore;
import com.firefly.spring.job.FireflyJobRegistrar;
import com.firefly.spring.job.FireflyJobRegistration;
import com.firefly.spring.job.FireflyJobRegistrationProperties;
import com.firefly.spring.job.FireflyExecutorStartup;
import com.firefly.spring.job.FireflyJobAnnotationBeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-starts a business-side executor client when firefly.executor.name is configured.
 */
@AutoConfiguration
@ConditionalOnClass(NettyExecutorClient.class)
@ConditionalOnProperty(prefix = "firefly.executor", name = "name")
@EnableConfigurationProperties({
        FireflyNettyExecutorProperties.class,
        FireflyBusinessIdempotencyProperties.class,
        FireflyJobRegistrationProperties.class
})
public class FireflyNettyExecutorAutoConfiguration {
    private static final Log log = LogFactory.getLog(FireflyNettyExecutorAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AuthTokenProvider fireflyIntegrationKeyProvider(FireflyNettyExecutorProperties properties) {
        return AuthTokenProvider.fixed(properties.getIntegrationKey());
    }

    @Bean
    @ConditionalOnBean(javax.sql.DataSource.class)
    @ConditionalOnMissingBean(BusinessIdempotencyStore.class)
    @ConditionalOnProperty(
            prefix = "firefly.executor.business-idempotency",
            name = "enabled", havingValue = "true"
    )
    public BusinessIdempotencyStore fireflyBusinessIdempotencyStore(
            javax.sql.DataSource dataSource,
            FireflyBusinessIdempotencyProperties properties
    ) {
        return new JdbcBusinessIdempotencyStore(
                dataSource, properties.getAbandonedClaimTimeout(), properties.getTableName()
        );
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NettyExecutorClient fireflyExecutorClient(
            FireflyNettyExecutorProperties properties,
            ObjectProvider<FireflyJobHandlerRegistration> fireflyRegistrations,
            ObjectProvider<NettyJobHandlerRegistration> registrations,
            ObjectProvider<NamedJobHandler> namedHandlers,
            ObjectProvider<ExecutorResultStore> resultStores,
            AuthTokenProvider integrationKeyProvider,
            Environment environment
    ) {
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
                .executorName(properties.getName())
                .instanceId(properties.getInstanceId())
                .serviceName(serviceName(properties, environment))
                .heartbeatInterval(properties.getHeartbeatInterval())
                .reconnectInitialDelay(properties.getReconnectInitialDelay())
                .reconnectMaxDelay(properties.getReconnectMaxDelay())
                .authToken(properties.getIntegrationKey())
                .authTokenProvider(integrationKeyProvider)
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
        fireflyRegistrations.orderedStream()
                .forEach(registration -> client.registerHandler(registration.handlerName(), registration.handler()));
        registrations.orderedStream()
                .forEach(registration -> client.registerHandler(registration.handlerName(), registration.handler()));
        namedHandlers.orderedStream()
                .forEach(handler -> client.registerHandler(handler.handlerName(), handler));
        log.info("Firefly Executor auto-configuration activated: executor=" + properties.getName()
                + ", service=" + serviceName(properties, environment)
                + ", gateways=" + gatewayAddresses(properties)
                + ", autoStart=" + properties.isAutoStart()
                + ", authentication=integration-key"
                + ", tls=" + properties.isTlsEnabled());
        return client;
    }

    @Bean
    public static FireflyJobAnnotationBeanPostProcessor fireflyJobAnnotationBeanPostProcessor() {
        return new FireflyJobAnnotationBeanPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public FireflyJobRegistrar fireflyJobRegistrar(
            FireflyNettyExecutorProperties executorProperties,
            FireflyJobRegistrationProperties registrationProperties,
            ObjectProvider<FireflyJobRegistration> registrations,
            NettyExecutorClient executorClient,
            AuthTokenProvider integrationKeyProvider
    ) {
        return new FireflyJobRegistrar(
                executorProperties.getName(),
                registrationProperties,
                registrations.orderedStream().toList(),
                executorClient,
                integrationKeyProvider
        );
    }

    @Bean
    public FireflyExecutorStartup fireflyExecutorStartup(
            FireflyNettyExecutorProperties properties,
            NettyExecutorClient executorClient,
            FireflyJobRegistrar jobRegistrar,
            FireflyJobAnnotationBeanPostProcessor annotationProcessor
    ) {
        return new FireflyExecutorStartup(
                executorClient, jobRegistrar, annotationProcessor, properties.isAutoStart()
        );
    }

    private String serviceName(FireflyNettyExecutorProperties properties, Environment environment) {
        if (properties.getServiceName() != null && !properties.getServiceName().isBlank()) {
            return properties.getServiceName();
        }
        return environment.getProperty("spring.application.name", properties.getName());
    }

    private String gatewayAddresses(FireflyNettyExecutorProperties properties) {
        if (properties.getGatewayAddresses() != null && !properties.getGatewayAddresses().isEmpty()) {
            return String.join(",", properties.getGatewayAddresses());
        }
        return properties.getSchedulerHost() + ":" + properties.getSchedulerPort();
    }

    private java.nio.file.Path path(String value) {
        return value == null || value.isBlank() ? null : java.nio.file.Path.of(value);
    }
}
