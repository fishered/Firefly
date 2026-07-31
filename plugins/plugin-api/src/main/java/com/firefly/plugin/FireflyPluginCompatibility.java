package com.firefly.plugin;

/** Inclusive range of host plugin API levels supported by a plugin. */
public record FireflyPluginCompatibility(int minimumApiLevel, int maximumApiLevel) {
    public static final int CURRENT_API_LEVEL = 1;

    public FireflyPluginCompatibility {
        if (minimumApiLevel < 1) {
            throw new IllegalArgumentException("minimumApiLevel must be positive");
        }
        if (maximumApiLevel < minimumApiLevel) {
            throw new IllegalArgumentException("maximumApiLevel must not be less than minimumApiLevel");
        }
    }

    public static FireflyPluginCompatibility current() {
        return new FireflyPluginCompatibility(CURRENT_API_LEVEL, CURRENT_API_LEVEL);
    }

    public boolean supports(int apiLevel) {
        return apiLevel >= minimumApiLevel && apiLevel <= maximumApiLevel;
    }
}
