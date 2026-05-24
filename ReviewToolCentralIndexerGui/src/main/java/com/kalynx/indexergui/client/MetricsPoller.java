package com.kalynx.indexergui.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls {@code GET /metrics} every second and populates local time-series buffers.
 * The GUI reads these buffers exactly as it would read MetricsCollector in the
 * embedded version — the interface is intentionally identical.
 */
public final class MetricsPoller {

    private final IndexerClient client;

    private final LocalTimeSeriesBuffer cpuSamples        = new LocalTimeSeriesBuffer();
    private final LocalTimeSeriesBuffer memorySamples     = new LocalTimeSeriesBuffer();
    private final LocalTimeSeriesBuffer connectionSamples = new LocalTimeSeriesBuffer();
    private final LocalTimeSeriesBuffer apiCallSamples    = new LocalTimeSeriesBuffer();

    private volatile List<LocalTimeSeriesBuffer> perCoreSamples = List.of();
    private volatile double  memoryMaxMb = 0;
    private volatile boolean connected   = false;

    private ScheduledExecutorService scheduler;

    public MetricsPoller(IndexerClient client) {
        this.client = client;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void poll() {
        try {
            IndexerClient.MetricsSnapshot snap = client.getMetrics();
            IndexerClient.SystemMetrics   sys  = snap.system();

            cpuSamples.record(sys.cpu_percent());
            memorySamples.record(sys.memory_mb());
            connectionSamples.record(sys.active_connections());
            apiCallSamples.record(sys.api_calls_last_second());

            if (memoryMaxMb == 0 && sys.memory_max_mb() > 0) {
                memoryMaxMb = sys.memory_max_mb();
            }

            double[] cores = sys.per_core_cpu_percent();
            if (perCoreSamples.size() != cores.length) {
                List<LocalTimeSeriesBuffer> bufs = new ArrayList<>(cores.length);
                for (int i = 0; i < cores.length; i++) bufs.add(new LocalTimeSeriesBuffer());
                perCoreSamples = Collections.unmodifiableList(bufs);
            }
            for (int i = 0; i < cores.length; i++) {
                perCoreSamples.get(i).record(cores[i]);
            }

            connected = true;
        } catch (Exception e) {
            connected = false;
        }
    }

    // --- accessors ---------------------------------------------------------------

    public LocalTimeSeriesBuffer getCpuSamples()        { return cpuSamples; }
    public LocalTimeSeriesBuffer getMemorySamples()     { return memorySamples; }
    public LocalTimeSeriesBuffer getConnectionSamples() { return connectionSamples; }
    public LocalTimeSeriesBuffer getApiCallSamples()    { return apiCallSamples; }
    public List<LocalTimeSeriesBuffer> getPerCoreSamples() { return perCoreSamples; }
    public double  getMemoryMaxMb() { return memoryMaxMb; }
    public boolean isConnected()    { return connected; }
}
