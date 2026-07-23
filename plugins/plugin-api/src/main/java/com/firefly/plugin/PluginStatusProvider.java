package com.firefly.plugin;

import java.util.List;

@FunctionalInterface
public interface PluginStatusProvider {
    List<FireflyPluginDescriptor> plugins();
}
