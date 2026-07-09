package com.firefly.example.embedded;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class ExampleLogging {
    private ExampleLogging() {
    }

    static void configure() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new ExampleFormatter());
        root.addHandler(handler);
        root.setLevel(Level.INFO);
    }

    private static final class ExampleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder builder = new StringBuilder()
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(" ")
                    .append(record.getLevel().getName())
                    .append(" [")
                    .append(record.getLoggerName())
                    .append("] ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter writer = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(writer));
                builder.append(writer);
            }
            return builder.toString();
        }
    }
}
