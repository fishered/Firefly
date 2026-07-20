package com.firefly.spring.job;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.spring.annotation.FireflyJob;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.HashMap;
import java.util.Map;

/** Registers annotated methods before starting the outbound Executor connection. */
public final class FireflyExecutorStartup implements SmartInitializingSingleton {
    private static final Log log = LogFactory.getLog(FireflyExecutorStartup.class);
    private final NettyExecutorClient executorClient;
    private final FireflyJobRegistrar jobRegistrar;
    private final FireflyJobAnnotationBeanPostProcessor annotationProcessor;
    private final boolean autoStart;

    public FireflyExecutorStartup(
            NettyExecutorClient executorClient,
            FireflyJobRegistrar jobRegistrar,
            FireflyJobAnnotationBeanPostProcessor annotationProcessor,
            boolean autoStart
    ) {
        this.executorClient = executorClient;
        this.jobRegistrar = jobRegistrar;
        this.annotationProcessor = annotationProcessor;
        this.autoStart = autoStart;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, FireflyJobAnnotationBeanPostProcessor.DiscoveredJobMethod> handlers = new HashMap<>();
        int jobs = 0;
        for (var method : annotationProcessor.discoveredMethods()) {
            for (FireflyJob declaration : method.declarations()) {
                String handlerName = method.handlerName(declaration);
                var previous = handlers.putIfAbsent(handlerName, method);
                if (previous != null
                        && (previous.bean() != method.bean() || !previous.method().equals(method.method()))) {
                    throw new IllegalStateException("duplicate @FireflyJob handlerName: " + handlerName);
                }
                if (previous == null) {
                    if (executorClient.handlerRegistry().find(handlerName).isPresent()) {
                        throw new IllegalStateException(
                                "@FireflyJob handlerName conflicts with an existing handler: " + handlerName
                        );
                    }
                    executorClient.registerHandler(handlerName, method.handler());
                }
                jobRegistrar.register(method.registration(declaration));
                jobs++;
            }
        }
        if (jobs > 0) {
            log.info("Discovered annotated Firefly jobs: jobs=" + jobs + ", handlers=" + handlers.size());
        }
        if (autoStart) {
            try {
                executorClient.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while starting Firefly Executor", e);
            }
        }
    }
}
