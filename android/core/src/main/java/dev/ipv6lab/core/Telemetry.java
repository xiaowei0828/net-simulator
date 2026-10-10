package dev.ipv6lab.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class Telemetry {
    public final AtomicLong dnsQueries = new AtomicLong();
    public final AtomicLong synthesized = new AtomicLong();
    public final AtomicLong translated = new AtomicLong();
    public final AtomicLong nativeIpv6 = new AtomicLong();
    public final AtomicLong rejected = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    public final AtomicLong sent = new AtomicLong();
    public final AtomicLong received = new AtomicLong();
    private final Consumer<String> logger;
    private final Consumer<String> errorLogger;
    public Telemetry(Consumer<String> logger) { this(logger, logger); }
    public Telemetry(Consumer<String> logger, Consumer<String> errorLogger) {
        this.logger = logger; this.errorLogger = errorLogger;
    }
    public void log(String message) { logger.accept(message); }
    public void error(String message) { errors.incrementAndGet(); errorLogger.accept(message); }
}
