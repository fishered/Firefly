package com.firefly.spring.annotation;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRetryScope;
import com.firefly.domain.ExecutorRoutingStrategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a Spring bean method as a Firefly handler and declares its scheduled job.
 *
 * <p>The annotated method must return {@code void} and accept either no arguments
 * or one {@code ExecutionContext} argument.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(FireflyJobs.class)
public @interface FireflyJob {
    /**
     * Distinguishes multiple schedules declared on the same method.
     * Leave blank when the method has only one schedule.
     */
    String key() default "";

    String cron();

    String name() default "";

    String groupId() default "default";

    /** IANA time-zone ID validated by the Starter while the Spring bean is initialized. */
    String zoneId() default "UTC";

    boolean enabled() default true;

    ExecutorDispatchMode dispatchMode() default ExecutorDispatchMode.UNICAST;

    ExecutorRoutingStrategy routingStrategy() default ExecutorRoutingStrategy.ROUND_ROBIN;

    ExecutorCompletionPolicy completionPolicy() default ExecutorCompletionPolicy.ALL_SUCCESS;

    int shardCount() default 1;

    String routingKey() default "";

    ExecutorRetryScope retryScope() default ExecutorRetryScope.FAILED_TARGETS_ONLY;

    int retryMaxAttempts() default 1;

    /** Job parameters in {@code key=value} form. */
    String[] parameters() default {};
}
