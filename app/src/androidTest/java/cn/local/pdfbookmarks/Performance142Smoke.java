package cn.local.pdfbookmarks;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import com.google.gson.Gson;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Controlled backend measurement, explicitly opt-in; files belong to the isolated test host's cache. */
public final class Performance142Smoke {
    private static final long MIB = 1024L * 1024;
    private static final Gson JSON = new Gson();
    private static final List<TocParser.Row> ROWS = Arrays.asList(
            new TocParser.Row(1, "Synthetic chapter", null, 1),
            new TocParser.Row(2, "Synthetic section", null, 2),
            new TocParser.Row(1, "Synthetic last", null, 4));
    private Performance142Smoke() { }

    public static String verify(Context testContext) throws Exception { return verify(testContext, 50); }

    /** 200 MiB is available only when the caller explicitly selects it; no whole-file byte array is allocated. */
    public static String verify(Context testContext, int payloadMiB) throws Exception {
        require("cn.local.pdfbookmarks.testhost".equals(testContext.getPackageName()), "Performance files require the isolated test host context");
        require(payloadMiB == 50 || payloadMiB == 200, "Only explicit 50/200 MiB fixtures are supported");
        File cache = testContext.getCacheDir().getCanonicalFile(), base = new File(cache, "perf142");
        require(cache.equals(base.getCanonicalFile().getParentFile()), "Performance directory is outside test cache");
        require(base.isDirectory() || base.mkdir(), "Cannot create performance cache directory");
        require(base.getUsableSpace() >= payloadMiB * MIB * 22 + 128 * MIB, "Insufficient free space for retained synthetic trials");
        File suite = new File(base, UUID.randomUUID().toString());
        require(suite.mkdir(), "Cannot create a fresh performance suite");
        String previousTemp = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", suite.getAbsolutePath());
        try {
            PDFBoxResourceLoader.init(testContext);
            File template = new File(suite, "synthetic-template.pdf");
            createPdf(template, suite, payloadMiB * MIB);
            String sourceHash = hash(template);
            require(PdfBookmarks.inspect(template).pageCount == 4, "Synthetic PDF has the wrong page count");
            List<Map<String, Object>> runs = new ArrayList<>();
            for (int trial = 0; trial < 3; trial++) {
                // Alternate the order to reduce one-sided warm-cache/order bias; neither case evicts OS caches.
                boolean[] order = trial == 1 ? new boolean[]{true, false} : new boolean[]{false, true};
                for (boolean optimized : order) runs.add(run(suite, template, sourceHash, trial, optimized));
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", "PASS"); report.put("payloadMiB", payloadMiB); report.put("inputBytes", template.length());
            report.put("trialsPerMode", 3); report.put("directory", suite.getAbsolutePath());
            report.put("scope", "Controlled legacy/new scheduling on the SAME CURRENT PDF/transaction core and private filesystem provider. "
                    + "Legacy replays six source-PDF preflight scans and separate generated-output hash; optimized replays three scans and passes VerifiedOutput. "
                    + "Measures PDF generation plus same-folder staged replacement including durable journal. "
                    + "Independent postconditions are checked after the measured interval. "
                    + "Not an old signed APK comparison, real SAF/Enjoy write, cold-cache guarantee, or complete MainActivity/UI/catalog/cleanup measurement.");
            report.put("legacy", stats(runs, "controlledLegacy")); report.put("optimized", stats(runs, "verifiedOutput"));
            report.put("runs", runs); report.put("outputs", "Each output independently matches its own generated digest; page/outline semantics match, original prefix and original backup are preserved. PDF revision IDs may differ across saves.");
            report.put("retention", "Synthetic files and backups remain under the listed test cache directory; this method does not delete them.");
            return JSON.toJson(report);
        } finally {
            if (previousTemp == null) System.clearProperty("java.io.tmpdir");
            else System.setProperty("java.io.tmpdir", previousTemp);
        }
    }

    private static Map<String, Object> run(File suite, File template, String sourceHash,
                                           int trial, boolean optimized) throws Exception {
        String mode = optimized ? "verifiedOutput" : "controlledLegacy";
        File root = new File(suite, mode + "-" + trial), remote = new File(root, "provider");
        require(root.mkdir() && remote.mkdir(), "Cannot create a fresh synthetic trial");
        File source = new File(remote, "original.pdf"), ready = new File(root, "ready.pdf");
        Files.copy(template.toPath(), source.toPath());
        require(sourceHash.equals(hash(source)), "Fresh trial input differs from the common synthetic PDF");
        LocalProvider provider = new LocalProvider(remote);
        AtomicFile journal = new AtomicFile(new File(root, "journal.json"));
        StagedReplacement.Sink sink = value -> {
            FileOutputStream stream = journal.startWrite();
            try { stream.write(JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); journal.finishWrite(stream); }
            catch (IOException | RuntimeException failure) { journal.failWrite(stream); throw failure; }
        };
        WorkMetrics metrics = new WorkMetrics(mode); WorkMetrics.attach(metrics);
        long started = System.nanoTime(), generated, finished; String expected;
        StagedReplacement.Result result;
        try {
            int scans = optimized ? 3 : 6;
            for (int i = 0; i < scans; i++) {
                try (InputStream input = provider.openRead(provider.document(source))) {
                    require(sourceHash.equals(hash(input)), "Synthetic preflight source changed");
                }
            }
            metrics.mark("preflightScans");
            if (optimized) {
                PdfBookmarks.VerifiedOutput artifact = PdfBookmarks.writeVerified(source, ready, ROWS, false);
                expected = artifact.sha256; generated = System.nanoTime(); metrics.mark("generate");
                result = StagedReplacement.replaceVerified(provider, "original.pdf", artifact.file,
                        sourceHash, artifact.sha256, artifact.length, sink);
            } else {
                PdfBookmarks.write(source, ready, ROWS, false);
                expected = hash(ready); generated = System.nanoTime(); metrics.mark("generateAndExtraHash");
                result = StagedReplacement.replace(provider, "original.pdf", ready, sourceHash, sink);
            }
            finished = System.nanoTime(); metrics.mark("stagedReplacement");
        } finally { WorkMetrics.detach(); }
        File actual = provider.file(result.document), backup = provider.file(result.backup);
        require("original.pdf".equals(actual.getName()), "Final output did not preserve the exact original filename");
        require(expected.equals(hash(actual)) && expected.equals(hash(ready)), "Generated and final output SHA-256 differ");
        require(sourceHash.equals(hash(backup)) && sourceHash.equals(hashPrefix(actual, template.length())),
                "Original bytes were not retained in both backup and PDF prefix");
        PdfBookmarks.Info info = PdfBookmarks.inspect(actual);
        require(info.pageCount == 4 && info.existingRows.size() == ROWS.size(), "Output page/outline counts differ");
        for (int i = 0; i < ROWS.size(); i++) {
            TocParser.Row row = info.existingRows.get(i), expectedRow = ROWS.get(i);
            require(row.level == expectedRow.level && row.pdfPage == expectedRow.pdfPage && row.title.equals(expectedRow.title),
                    "Output outline semantics differ");
        }
        require(result.journal.phase == StagedReplacement.Phase.COMMITTED, "Replacement did not commit");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("mode", mode); record.put("trial", trial); record.put("totalMs", (finished - started) / 1_000_000);
        record.put("generationEndMs", (generated - started) / 1_000_000);
        record.put("replacementMs", (finished - generated) / 1_000_000);
        record.put("providerReads", metrics.providerReads); record.put("providerBytes", metrics.providerBytes);
        record.put("directoryQueries", metrics.directoryQueries); record.put("phasesMs", metrics.phases);
        record.put("sourceScans", optimized ? 3 : 6); record.put("generatedSha256", expected); record.put("finalSha256", hash(actual));
        record.put("inputBytes", template.length()); record.put("outputBytes", actual.length()); record.put("checks", "PASS");
        Log.i("Perf142", mode + " trial=" + trial + " totalMs=" + record.get("totalMs") + " providerBytes=" + metrics.providerBytes);
        return record;
    }

    private static Map<String, Long> stats(List<Map<String, Object>> runs, String mode) {
        long[] times = runs.stream().filter(value -> mode.equals(value.get("mode")))
                .mapToLong(value -> ((Number) value.get("totalMs")).longValue()).sorted().toArray();
        require(times.length == 3, "Incomplete controlled trial group");
        Map<String, Long> stats = new LinkedHashMap<>(); stats.put("medianMs", times[1]); stats.put("worstMs", times[2]);
        return stats;
    }

    private static void createPdf(File file, File scratch, long payloadBytes) throws Exception {
        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(8 * MIB).setTempDir(scratch);
        try (PDDocument document = new PDDocument(memory)) {
            for (int i = 0; i < 4; i++) document.addPage(new PDPage());
            PDStream payload = new PDStream(document);
            try (OutputStream output = payload.createOutputStream()) {
                Random random = new Random(142); byte[] bytes = new byte[65536];
                for (long remaining = payloadBytes; remaining > 0;) {
                    checkInterrupted(); random.nextBytes(bytes); int count = (int) Math.min(bytes.length, remaining);
                    output.write(bytes, 0, count); remaining -= count;
                }
            }
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Perf142Payload"), payload.getCOSObject());
            document.save(file);
        }
        require(file.length() >= payloadBytes, "Synthetic PDF payload was not retained");
    }

    private static String hash(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return hash(input); }
    }
    private static String hash(InputStream input) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536];
        for (int count; (count = input.read(bytes)) != -1;) { checkInterrupted(); sha.update(bytes, 0, count); }
        return hex(sha.digest());
    }
    private static String hashPrefix(File file, long length) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536];
        try (InputStream input = new FileInputStream(file)) {
            while (length > 0) {
                checkInterrupted(); int count = input.read(bytes, 0, (int) Math.min(bytes.length, length));
                if (count < 0) throw new IOException("Output PDF prefix is incomplete"); sha.update(bytes, 0, count); length -= count;
            }
        }
        return hex(sha.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Controlled performance check was interrupted");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** File-backed test provider; directory listings are fresh and only freshly created stages can be written. */
    private static final class LocalProvider implements StagedReplacement.Provider {
        final File root; final Set<File> writable = new HashSet<>();
        LocalProvider(File root) throws IOException { this.root = root.getCanonicalFile(); }
        StagedReplacement.Document document(File file) { return new StagedReplacement.Document(file.toURI().toString(), file.getName()); }
        File file(StagedReplacement.Document document) throws IOException {
            File file;
            try { file = new File(URI.create(document.uri)).getCanonicalFile(); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid synthetic document URI", invalid); }
            if (!root.equals(file.getParentFile()) || !document.name.equals(file.getName()) || !file.isFile())
                throw new IOException("Synthetic document left its exact trial directory");
            return file;
        }
        @Override public List<StagedReplacement.Document> find(String exactName) throws IOException {
            WorkMetrics.directoryQuery(); File[] files = root.listFiles();
            if (files == null) throw new IOException("Cannot list synthetic provider");
            List<StagedReplacement.Document> result = new ArrayList<>();
            for (File file : files) if (exactName.equals(file.getName())) result.add(document(file)); return result;
        }
        @Override public StagedReplacement.Document create(String uniqueName) throws IOException {
            File file = new File(root, uniqueName).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.createNewFile()) throw new IOException("Synthetic stage is not new");
            writable.add(file); return document(file);
        }
        @Override public InputStream openRead(StagedReplacement.Document document) throws IOException {
            File file = file(document); List<StagedReplacement.Document> current = find(document.name);
            if (current.size() != 1 || !document.uri.equals(current.get(0).uri)) throw new IOException("Synthetic read identity changed");
            return WorkMetrics.providerRead(new FileInputStream(file));
        }
        @Override public OutputStream openWriteNew(StagedReplacement.Document document) throws IOException {
            File file = file(document); if (!writable.remove(file)) throw new IOException("Only a fresh synthetic stage can be written");
            return new FileOutputStream(file) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (closed) return; closed = true; try { flush(); getFD().sync(); } finally { super.close(); }
                }
            };
        }
        @Override public StagedReplacement.Document rename(StagedReplacement.Document document, String newName) throws IOException {
            File source = file(document), destination = new File(root, newName).getCanonicalFile();
            if (!root.equals(destination.getParentFile()) || destination.exists() || !source.renameTo(destination))
                throw new IOException("Synthetic rename failed without overwrite");
            writable.remove(source); return document(destination);
        }
    }
}
