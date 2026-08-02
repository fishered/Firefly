package com.firefly.server;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Typed definition for a server option and its validation contract. */
public record OptionSpec<T>(
        String name,
        String environmentName,
        Function<String, T> parser,
        T defaultValue,
        Predicate<T> validator
) {
    public OptionSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(environmentName, "environmentName");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(validator, "validator");
    }

    public T parse(String value) {
        T parsed;
        try {
            parsed = parser.apply(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(name + " " + failure.getMessage(), failure);
        }
        if (!validator.test(parsed)) throw new IllegalArgumentException(name + " failed validation");
        return parsed;
    }

    public static OptionSpec<Boolean> strictBoolean(String name, String environmentName, boolean defaultValue) {
        return new OptionSpec<>(name, environmentName, value -> {
            if ("true".equalsIgnoreCase(value)) return true;
            if ("false".equalsIgnoreCase(value)) return false;
            throw new IllegalArgumentException("must be true or false");
        }, defaultValue, value -> true);
    }
}
