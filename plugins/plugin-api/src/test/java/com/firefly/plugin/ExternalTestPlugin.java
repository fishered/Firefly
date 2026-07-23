package com.firefly.plugin;

public final class ExternalTestPlugin implements FireflyPlugin {
    @Override
    public String id() {
        return "external-test";
    }

    @Override
    public void start(FireflyPluginContext context) {
    }
}
