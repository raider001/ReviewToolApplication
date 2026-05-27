package com.kalynx.indexergui.client;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Lock-free rolling buffer that retains timestamped samples for up to 24 hours.
 * Mirror of MetricsCollector.TimeSeriesBuffer, kept here so the GUI module has no
 * compile-time dependency on the indexer module.
 */
public final class LocalTimeSeriesBuffer {

    public record Sample(long timestampMs, double value) {}

    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private final ConcurrentLinkedDeque<Sample> samples = new ConcurrentLinkedDeque<>();

    public void record(double value) {
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(now, value));
        Sample head;
        while ((head = samples.peekFirst()) != null && now - head.timestampMs() > MAX_AGE_MS) {
            samples.pollFirst();
        }
    }

    public double average(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        return samples.stream()
                .filter(s -> s.timestampMs() >= cutoff)
                .mapToDouble(Sample::value)
                .average()
                .orElse(0.0);
    }

    public long count(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        return samples.stream()
                .filter(s -> s.timestampMs() >= cutoff)
                .count();
    }

    public double sum(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        return samples.stream()
                .filter(s -> s.timestampMs() >= cutoff)
                .mapToDouble(Sample::value)
                .sum();
    }

    public List<Sample> getWindow(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        return samples.stream()
                .filter(s -> s.timestampMs() >= cutoff)
                .toList();
    }
}
