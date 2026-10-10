package cn.local.pdfbookmarks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Real Gson 2.11/2.14 readers and writers on the unchanged production record classes. */
public final class GsonUpgradeCompatibilityTest {
    private static final Gson CURRENT = new Gson();
    private static final byte[] ORIGINAL = "original PDF".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OUTPUT = "PDF with bookmarks".getBytes(StandardCharsets.UTF_8);
    private static URLClassLoader legacyLoader;
    private static Object legacy;
    private static Method legacyRead, legacyWrite;

    @BeforeClass public static void loadVerifiedLegacyGson() throws Exception {
        String value = System.getProperty("gson.legacyJar");
        assertNotNull("Gradle must provide the isolated Gson 2.11 artifact", value);
        Path jar = Path.of(value);
        assertEquals("57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b",
                hash(Files.readAllBytes(jar)));
        legacyLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        Class<?> gson = legacyLoader.loadClass("com.google.gson.Gson");
        assertNotSame(Gson.class, gson);
        legacy = gson.getConstructor().newInstance();
        legacyRead = gson.getMethod("fromJson", String.class, Class.class);
        legacyWrite = gson.getMethod("toJson", Object.class);
    }

    @AfterClass public static void closeLegacyLoader() throws Exception {
        if (legacyLoader != null) legacyLoader.close();
    }

    @Test public void allReplacementPhasesAndNullUrisAreBidirectionallyCompatible() throws Exception {
        for (StagedReplacement.Phase phase : StagedReplacement.Phase.values()) {
            assertBidirectional(CURRENT.toJson(journal(phase)), StagedReplacement.Journal.class);
        }
        StagedReplacement.Journal intent = new StagedReplacement.Journal(
                StagedReplacement.Phase.CREATE_INTENT, "原书📚.pdf", "content://fixture/old",
                hash(ORIGINAL), "暂存.pdf", null, hash(OUTPUT), "备份.pdf", null, null);
        StagedReplacement.Journal restored = (StagedReplacement.Journal) assertBidirectional(
                CURRENT.toJson(intent), StagedReplacement.Journal.class);
        assertNull(restored.stagingUri);
        assertNull(restored.backupUri);
        assertNull(restored.documentUri);
    }

    @Test public void successfulBackupCatalogAndCleanupRecordsKeepTheirExactLinks() throws Exception {
        JsonObject backup = backupRecord();
        assertBidirectional(backup.toString(), productionType("MainActivity$BackupRecord"));
        JsonObject catalog = catalogRecord();
        catalog.addProperty("discardAfterReplacement", true);
        EnjoyDirectorySync.Record restored = (EnjoyDirectorySync.Record) assertBidirectional(
                catalog.toString(), EnjoyDirectorySync.Record.class);
        assertTrue(restored.discardAfterReplacement);
        assertNull(restored.snapshot);
        JsonObject cleanup = new JsonObject();
        cleanup.addProperty("pdfUri", "content://fixture/新书📚.pdf");
        cleanup.addProperty("pdfRecord", backup.toString());
        cleanup.addProperty("catalogRecord", catalog.toString());
        cleanup.addProperty("retainedNotice", "保留旧件 <核对> & 不扫描其他记录");
        assertBidirectional(cleanup.toString(), productionType("MainActivity$CleanupPlan"));
    }

    @Test public void pendingCatalogSyncKeepsSnapshotsAndTheReplacementJournal() throws Exception {
        JsonObject pending = new JsonObject();
        pending.addProperty("folder", "content://fixture/tree/primary%3A享做笔记");
        pending.addProperty("pdfName", "原书📚.pdf");
        pending.addProperty("pdfHash", hash(OUTPUT));
        pending.addProperty("privateBackup", "/data/user/0/cn.local.pdfbookmarks/files/catalog-backups/旧目录.json");
        pending.add("snapshots", JsonParser.parseString("[{\"name\":\"catalog.json\","
                + "\"uri\":\"content://fixture/catalog\",\"hash\":\"" + hash(ORIGINAL) + "\"}]"));
        pending.add("journal", CURRENT.toJsonTree(journal(StagedReplacement.Phase.PROMOTE_INTENT)));
        assertBidirectional(pending.toString(), productionType("EnjoyDirectorySync$Pending"));
    }

    @Test public void absentLegacyDeletionIntentAndCleanupLinksRemainConservative() throws Exception {
        JsonObject oldCatalog = catalogRecord();
        String oldStored = writeLegacy(readLegacy(oldCatalog.toString(), EnjoyDirectorySync.Record.class));
        EnjoyDirectorySync.Record restored = CURRENT.fromJson(oldStored, EnjoyDirectorySync.Record.class);
        assertFalse(restored.discardAfterReplacement);
        assertNull(restored.snapshot);
        Class<?> type = productionType("MainActivity$CleanupPlan");
        Object oldPlan = readLegacy("{\"pdfUri\":\"content://fixture/current\"}", type);
        Object plan = CURRENT.fromJson(writeLegacy(oldPlan), type);
        JsonObject json = CURRENT.toJsonTree(plan).getAsJsonObject();
        assertFalse(json.has("pdfRecord"));
        assertFalse(json.has("catalogRecord"));
    }

    @Test public void journalWrittenByLegacyGsonCanRecoverOriginalWithoutOpeningAWriteStream()
            throws Exception {
        String persisted = writeLegacy(journal(StagedReplacement.Phase.PROMOTE_INTENT));
        StagedReplacement.Journal restored = CURRENT.fromJson(persisted, StagedReplacement.Journal.class);
        MemoryProvider provider = new MemoryProvider();
        provider.files.put(restored.backupName, ORIGINAL);
        provider.files.put(restored.stagingName, OUTPUT);
        List<StagedReplacement.Journal> snapshots = new ArrayList<>();
        StagedReplacement.Result result = StagedReplacement.recover(provider, restored, snapshots::add);
        assertEquals(restored.originalName, result.document.name);
        assertArrayEquals(ORIGINAL, provider.files.get(restored.originalName));
        assertArrayEquals(OUTPUT, provider.files.get(restored.stagingName));
        assertEquals(1, provider.mutations);
        assertEquals(0, provider.writeOpens);
        assertEquals(StagedReplacement.Phase.RESTORED, snapshots.get(snapshots.size() - 1).phase);
    }

    @Test public void incompleteLegacyRecordsCannotTouchTheProvider() throws Exception {
        for (String seed : List.of("{}", "{\"phase\":\"FUTURE_UNKNOWN_PHASE\"}",
                "{\"phase\":\"BACKUP_INTENT\",\"originalName\":\"旧书.pdf\"}")) {
            String stored = writeLegacy(readLegacy(seed, StagedReplacement.Journal.class));
            StagedReplacement.Journal restored = CURRENT.fromJson(stored, StagedReplacement.Journal.class);
            MemoryProvider provider = new MemoryProvider();
            List<StagedReplacement.Journal> snapshots = new ArrayList<>();
            try {
                StagedReplacement.recover(provider, restored, snapshots::add);
                fail("Incomplete legacy records must stop recovery");
            } catch (StagedReplacement.ReplacementException expected) {
                assertFalse(expected.originalRestored);
            }
            assertEquals(0, provider.calls);
            assertEquals(0, provider.mutations);
            assertEquals(0, provider.writeOpens);
            assertTrue(snapshots.isEmpty());
        }
    }

    private static Object assertBidirectional(String seed, Class<?> type) throws Exception {
        Object oldValue = readLegacy(seed, type);
        String oldStored = writeLegacy(oldValue);
        assertEquals("Legacy writer lost fixture fields", JsonParser.parseString(seed),
                JsonParser.parseString(oldStored));
        Object restored = CURRENT.fromJson(oldStored, type);
        String newStored = CURRENT.toJson(restored);
        assertEquals("New reader/writer changed persisted fields", JsonParser.parseString(oldStored),
                JsonParser.parseString(newStored));
        assertEquals("Legacy reader cannot read the new writer", JsonParser.parseString(newStored),
                JsonParser.parseString(writeLegacy(readLegacy(newStored, type))));
        return restored;
    }

    private static Object readLegacy(String json, Class<?> type) throws Exception {
        return legacyRead.invoke(legacy, json, type);
    }

    private static String writeLegacy(Object value) throws Exception {
        return (String) legacyWrite.invoke(legacy, value);
    }

    private static Class<?> productionType(String nestedName) throws Exception {
        return Class.forName("cn.local.pdfbookmarks." + nestedName);
    }

    private static StagedReplacement.Journal journal(StagedReplacement.Phase phase) throws Exception {
        return new StagedReplacement.Journal(phase, "原书📚.pdf", "content://fixture/stale-original",
                hash(ORIGINAL), "暂存.pdf", "content://fixture/stale-stage", hash(OUTPUT),
                "备份.pdf", "content://fixture/stale-backup", "content://fixture/stale-final");
    }

    private static JsonObject backupRecord() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("folder", "content://fixture/tree/primary%3A享做笔记");
        result.addProperty("outputUri", "content://fixture/新书📚.pdf");
        result.addProperty("outputName", "原书📚.pdf");
        result.addProperty("outputHash", hash(OUTPUT));
        result.addProperty("backupUri", "content://fixture/旧件.pdf");
        result.addProperty("backupName", "原件备份.pdf");
        result.addProperty("originalHash", hash(ORIGINAL));
        result.addProperty("privateBackup", "/data/user/0/cn.local.pdfbookmarks/files/pdf-backups/旧件.pdf");
        return result;
    }

    private static JsonObject catalogRecord() throws Exception {
        JsonObject result = backupRecord();
        result.addProperty("outputName", "catalog.json");
        result.addProperty("backupName", "旧目录.json");
        result.addProperty("privateBackup", "/data/user/0/cn.local.pdfbookmarks/files/catalog-backups/旧目录.json");
        result.addProperty("pdfName", "原书📚.pdf");
        result.addProperty("pdfHash", hash(OUTPUT));
        return result;
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            value.append(String.format("%02x", b & 255));
        }
        return value.toString();
    }

    private static final class MemoryProvider implements StagedReplacement.Provider {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        int calls, mutations, writeOpens;
        @Override public List<StagedReplacement.Document> find(String name) {
            calls++;
            return files.containsKey(name) ? List.of(document(name)) : List.of();
        }
        @Override public InputStream openRead(StagedReplacement.Document document) throws IOException {
            calls++;
            byte[] bytes = files.get(document.name);
            if (bytes == null) throw new IOException("Missing exact document");
            return new ByteArrayInputStream(bytes);
        }
        @Override public StagedReplacement.Document create(String name) {
            calls++;
            throw new AssertionError("Recovery must not create a file");
        }
        @Override public OutputStream openWriteNew(StagedReplacement.Document document) {
            calls++; writeOpens++;
            throw new AssertionError("Recovery must not open a write stream");
        }
        @Override public StagedReplacement.Document rename(StagedReplacement.Document source, String name)
                throws IOException {
            calls++;
            if (files.containsKey(name) || !files.containsKey(source.name)) {
                throw new IOException("Missing source or conflicting destination");
            }
            files.put(name, files.remove(source.name));
            mutations++;
            return document(name);
        }
        private static StagedReplacement.Document document(String name) {
            return new StagedReplacement.Document("content://fixture/live/" + name, name);
        }
    }
}
