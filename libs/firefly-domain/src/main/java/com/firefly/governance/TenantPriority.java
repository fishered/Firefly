package com.firefly.governance;

/** Product-level queue priority bands. Intermediate numeric levels remain available to advanced clients. */
public enum TenantPriority {
    BACKGROUND(0),
    STANDARD(3),
    HIGH(6),
    CRITICAL(9);

    public static final int MIN_LEVEL = 0;
    public static final int MAX_LEVEL = 9;

    private final int level;

    TenantPriority(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}
