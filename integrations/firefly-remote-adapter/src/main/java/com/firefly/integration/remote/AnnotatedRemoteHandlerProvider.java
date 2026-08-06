package com.firefly.integration.remote;

import com.firefly.domain.ExecutionContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class AnnotatedRemoteHandlerProvider implements RemoteHandlerProvider {
    private final List<Object> targets;

    AnnotatedRemoteHandlerProvider(Object... targets) {
        Objects.requireNonNull(targets, "targets");
        this.targets = Arrays.stream(targets)
                .map(target -> Objects.requireNonNull(target, "annotated handler object"))
                .toList();
    }

    @Override
    public void register(RemoteHandlerRegistry registry) {
        for (Object target : targets) {
            for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    FireflyHandler annotation = method.getAnnotation(FireflyHandler.class);
                    if (annotation == null || method.isBridge() || method.isSynthetic()) continue;
                    validate(method);
                    makeAccessible(method, target);
                    registry.bind(annotation.handlerName(), context -> invoke(method, target, context));
                }
            }
        }
    }

    private static void validate(Method method) {
        if (method.getReturnType() != Void.TYPE) {
            throw invalid(method, "must return void");
        }
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length > 1
                || (parameters.length == 1 && parameters[0] != ExecutionContext.class)) {
            throw invalid(method, "must accept no arguments or one ExecutionContext");
        }
    }

    private static void makeAccessible(Method method, Object target) {
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : target;
        if (!method.canAccess(receiver) && !method.trySetAccessible()) {
            throw invalid(method, "is not accessible; open its package to firefly-remote-adapter");
        }
    }

    private static void invoke(Method method, Object target, ExecutionContext context) throws Exception {
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : target;
        try {
            if (method.getParameterCount() == 0) {
                method.invoke(receiver);
            } else {
                method.invoke(receiver, context);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("annotated handler failed", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("annotated handler is no longer accessible: " + method.toGenericString(), e);
        }
    }

    private static IllegalArgumentException invalid(Method method, String reason) {
        return new IllegalArgumentException("@FireflyHandler method " + reason + ": " + method.toGenericString());
    }
}
