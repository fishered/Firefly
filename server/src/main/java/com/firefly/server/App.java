package com.firefly.server;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws InterruptedException {
        ServerLogging.configure();
        FireflyBootstrap bootstrap = FireflyBootstrap.start(ServerOptions.parse(args));
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::close, "firefly-shutdown"));
        bootstrap.await();
    }
}
