package com.firefly.benchmark.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class ManagedJavaProcess implements AutoCloseable {
    private final String name;
    private final Process process;
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final Thread reader;

    private ManagedJavaProcess(String name, Process process) {
        this.name = name;
        this.process = process;
        this.reader = Thread.ofVirtual()
                .name("firefly-benchmark-" + name + "-output")
                .start(this::readOutput);
    }

    public static ManagedJavaProcess start(String name, String mainClass, List<String> args, Map<String, String> env)
            throws IOException {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(env);
        return new ManagedJavaProcess(name, builder.start());
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public void destroyForciblyAndWait(Duration timeout) throws InterruptedException {
        process.destroyForcibly();
        process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<String> output() {
        return List.copyOf(output);
    }

    @Override
    public void close() throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        reader.join(Duration.ofSeconds(2));
    }

    private void readOutput() {
        try (BufferedReader buffered = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = buffered.readLine()) != null) {
                output.add(line);
            }
        } catch (IOException e) {
            output.add("failed to read process output for " + name + ": " + e.getMessage());
        }
    }

    private static String javaBin() {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "java.exe"
                : "java";
        return System.getProperty("java.home")
                + java.io.File.separator + "bin" + java.io.File.separator + executable;
    }
}
