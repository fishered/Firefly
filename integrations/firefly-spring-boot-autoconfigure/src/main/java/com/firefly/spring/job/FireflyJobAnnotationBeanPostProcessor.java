package com.firefly.spring.job;

import com.firefly.domain.ExecutionContext;
import com.firefly.handler.JobHandler;
import com.firefly.spring.annotation.FireflyJob;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Collects annotated job methods without forcing early creation of application beans. */
public final class FireflyJobAnnotationBeanPostProcessor implements BeanPostProcessor, Ordered {
    private static final int MAX_JOB_ID_LENGTH = 128;
    private static final int MAX_HANDLER_NAME_LENGTH = 256;
    private static final Pattern SCHEDULE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final List<DiscoveredJobMethod> discovered = new ArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        ReflectionUtils.doWithMethods(targetClass, method -> {
            FireflyJob[] jobs = method.getAnnotationsByType(FireflyJob.class);
            if (jobs.length == 0) {
                return;
            }
            validateMethod(method);
            validateScheduleKeys(method, jobs);
            validateZoneIds(method, jobs);
            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
            ReflectionUtils.makeAccessible(invocable);
            synchronized (discovered) {
                discovered.add(new DiscoveredJobMethod(
                        beanName,
                        bean,
                        invocable,
                        boundedIdentifier(targetClass.getName() + "#" + method.getName(), MAX_HANDLER_NAME_LENGTH),
                        List.of(jobs)
                ));
            }
        }, method -> !method.isBridge() && !method.isSynthetic());
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    public List<DiscoveredJobMethod> discoveredMethods() {
        synchronized (discovered) {
            return List.copyOf(discovered);
        }
    }

    private void validateMethod(Method method) {
        if (method.getReturnType() != Void.TYPE) {
            throw new IllegalStateException("@FireflyJob method must return void: " + method.toGenericString());
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length > 1
                || (parameterTypes.length == 1 && parameterTypes[0] != ExecutionContext.class)) {
            throw new IllegalStateException(
                    "@FireflyJob method must accept no arguments or one ExecutionContext: "
                            + method.toGenericString()
            );
        }
    }

    private void validateScheduleKeys(Method method, FireflyJob[] jobs) {
        Set<String> keys = new HashSet<>();
        for (FireflyJob job : jobs) {
            String key = job.key().trim();
            if (jobs.length > 1 && key.isEmpty()) {
                throw new IllegalStateException(
                        "repeated @FireflyJob declarations require non-blank keys: " + method.toGenericString()
                );
            }
            if (!key.isEmpty() && !SCHEDULE_KEY.matcher(key).matches()) {
                throw new IllegalStateException(
                        "@FireflyJob key must match " + SCHEDULE_KEY.pattern() + ": " + method.toGenericString()
                );
            }
            if (!key.isEmpty() && !keys.add(key)) {
                throw new IllegalStateException(
                        "duplicate @FireflyJob key '" + key + "': " + method.toGenericString()
                );
            }
        }
    }

    private void validateZoneIds(Method method, FireflyJob[] jobs) {
        for (FireflyJob job : jobs) {
            FireflyJobRegistration.requireValidZoneId(
                    job.zoneId(),
                    "@FireflyJob method " + method.toGenericString()
            );
        }
    }

    private static String boundedIdentifier(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            ).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        return value.substring(0, maxLength - digest.length() - 1) + "~" + digest;
    }

    public record DiscoveredJobMethod(
            String beanName,
            Object bean,
            Method method,
            String handlerName,
            List<FireflyJob> declarations
    ) {
        public JobHandler handler() {
            return context -> invoke(context);
        }

        public FireflyJobRegistration registration(FireflyJob declaration) {
            String scheduleKey = declaration.key().trim();
            String jobId = boundedIdentifier(
                    scheduleKey.isEmpty() ? handlerName : handlerName + ":" + scheduleKey,
                    MAX_JOB_ID_LENGTH
            );
            FireflyJobRegistration.Builder builder = FireflyJobRegistration.builder(
                            jobId, handlerName, declaration.cron()
                    )
                    .name(declaration.name().isBlank() ? jobId : declaration.name())
                    .groupId(declaration.groupId())
                    .zoneId(declaration.zoneId())
                    .enabled(declaration.enabled())
                    .dispatchMode(declaration.dispatchMode())
                    .routingStrategy(declaration.routingStrategy())
                    .completionPolicy(declaration.completionPolicy())
                    .shardCount(declaration.shardCount())
                    .routingKey(declaration.routingKey())
                    .retryScope(declaration.retryScope())
                    .retryMaxAttempts(declaration.retryMaxAttempts());
            parameters(declaration).forEach(builder::parameter);
            return builder.build();
        }

        private void invoke(ExecutionContext context) throws Exception {
            try {
                if (method.getParameterCount() == 0) {
                    method.invoke(bean);
                } else {
                    method.invoke(bean, context);
                }
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("annotated Firefly job invocation failed", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot invoke annotated Firefly job method", e);
            }
        }

        private Map<String, String> parameters(FireflyJob declaration) {
            Map<String, String> parameters = new LinkedHashMap<>();
            for (String entry : declaration.parameters()) {
                int separator = entry.indexOf('=');
                if (separator < 1) {
                    throw new IllegalStateException(
                            "@FireflyJob parameter must use key=value form on " + method.toGenericString()
                    );
                }
                String key = entry.substring(0, separator).trim();
                String value = entry.substring(separator + 1);
                if (key.isEmpty() || parameters.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException(
                            "invalid or duplicate @FireflyJob parameter " + key + " on " + method.toGenericString()
                    );
                }
            }
            return parameters;
        }
    }
}
