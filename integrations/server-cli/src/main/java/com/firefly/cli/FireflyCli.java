package com.firefly.cli;

/**
 * Placeholder entry point for a future standalone Firefly server command.
 */
public final class FireflyCli {
    private FireflyCli() {
    }

    public static void main(String[] args) {
        System.out.println(description());
    }

    public static String description() {
        return "Firefly CLI placeholder. Embedded and Spring integrations are available first.";
    }
}
