package com.firefly.benchmark.process;

public final class SloViolationException extends AssertionError {
    public SloViolationException(String message) {
        super(message);
    }
}
