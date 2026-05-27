package com.kalynx.indexergui.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile long    diskFreeMb  = 0;
    private volatile long    diskTotalMb = 0;

    private final Map<String, LocalTimeSeriesBuffer> sseEventBuffers  = new ConcurrentHashMap<>();
    private final Map<String, LocalTimeSeriesBuffer> webhookBuffers   = new ConcurrentHashMap<>();
    private final Map<String, LocalTimeSeriesBuffer> restCallBuffers  = new ConcurrentHashMap<>();
    private volatile Map<String, Integer>            connectedClientIps = Map.of();

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
            diskFreeMb  = sys.disk_free_mb();
            diskTotalMb = sys.disk_total_mb();

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

            if (snap.sse() != null && snap.sse().events_by_type_last_second() != null) {
                snap.sse().events_by_type_last_second().forEach((type, count) ->
                    sseEventBuffers.computeIfAbsent(type, k -> new LocalTimeSeriesBuffer()).record(count));
            }
            if (snap.webhooks() != null && snap.webhooks().calls_by_type_last_second() != null) {
                snap.webhooks().calls_by_type_last_second().forEach((type, count) ->
                    webhookBuffers.computeIfAbsent(type, k -> new LocalTimeSeriesBuffer()).record(count));
            }
            if (snap.rest() != null && snap.rest().calls_by_type_last_second() != null) {
                snap.rest().calls_by_type_last_second().forEach((type, count) ->
                    restCallBuffers.computeIfAbsent(type, k -> new LocalTimeSeriesBuffer()).record(count));
            }
            if (snap.connections() != null && snap.connections().by_client() != null) {
                connectedClientIps = Map.copyOf(snap.connections().by_client());
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
    public double  getMemoryMaxMb()  { return memoryMaxMb; }
    public boolean isConnected()     { return connected; }
    public long    getDiskFreeMb()   { return diskFreeMb; }
    public long    getDiskTotalMb()  { return diskTotalMb; }

    public Map<String, LocalTimeSeriesBuffer> getSseEventBuffers()  { return Collections.unmodifiableMap(sseEventBuffers); }
    public Map<String, LocalTimeSeriesBuffer> getWebhookBuffers()   { return Collections.unmodifiableMap(webhookBuffers); }
    public Map<String, LocalTimeSeriesBuffer> getRestCallBuffers()  { return Collections.unmodifiableMap(restCallBuffers); }
    public Map<String, Integer> getConnectedClientIps()             { return connectedClientIps; }
}
