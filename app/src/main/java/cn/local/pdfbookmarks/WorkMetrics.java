package cn.local.pdfbookmarks;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-operation numeric diagnostics; never records names, URIs or document contents. */
final class WorkMetrics {
    private static final ThreadLocal<WorkMetrics> CURRENT = new ThreadLocal<>();
    final String operation;
    final Map<String,Long> phases = new LinkedHashMap<>();
    long providerBytes, providerReads, directoryQueries;
    private final long started = System.nanoTime();
    private long previous = started;

    WorkMetrics(String operation) { this.operation = operation; }
    static void attach(WorkMetrics metrics) { if (metrics == null) CURRENT.remove(); else CURRENT.set(metrics); }
    static void detach() { CURRENT.remove(); }
    void mark(String phase) { long now = System.nanoTime(); phases.put(phase, (now - previous) / 1_000_000); previous = now; }
    static void elapsed(String phase,long start) { WorkMetrics metrics=CURRENT.get(); if(metrics!=null)metrics.phases.put(phase,(System.nanoTime()-start)/1_000_000); }
    static void directoryQuery() { WorkMetrics metrics = CURRENT.get(); if (metrics != null) metrics.directoryQueries++; }
    static InputStream providerRead(InputStream input) {
        WorkMetrics metrics = CURRENT.get();
        if (input == null || metrics == null) return input;
        metrics.providerReads++;
        return new InputStream() {
            @Override public int read() throws IOException { int value = input.read(); if (value >= 0) metrics.providerBytes++; return value; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = input.read(bytes, offset, length); if (count > 0) metrics.providerBytes += count; return count;
            }
            @Override public void close() throws IOException { input.close(); }
        };
    }
    String summary() {
        return operation + " totalMs=" + (System.nanoTime() - started) / 1_000_000
                + " providerReads=" + providerReads + " providerBytes=" + providerBytes
                + " directoryQueries=" + directoryQueries + " phasesMs=" + phases;
    }
}
