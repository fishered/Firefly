package com.firefly.integration.remote;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes one method on an explicitly supplied object as a Firefly handler.
 * The stable handler name is derived from the fully qualified class and method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FireflyHandler {
}
