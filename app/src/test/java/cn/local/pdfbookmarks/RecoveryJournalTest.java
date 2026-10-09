package cn.local.pdfbookmarks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 与 MainActivity 相同的 Gson 日志持久化路径；宿主测试不证明设备文件授权。 */
public final class RecoveryJournalTest {
    private static final Gson GSON = new Gson();
    private static final byte[] ORIGINAL = "original PDF".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OUTPUT = "PDF with bookmarks".getBytes(StandardCharsets.UTF_8);

    @Test public void everyPhaseAndAllFieldsSurviveGsonRoundTrip() throws Exception {
        for (StagedReplacement.Phase phase : StagedReplacement.Phase.values()) {
            StagedReplacement.Journal journal = journal(phase);
            StagedReplacement.Journal restored = GSON.fromJson(
                    GSON.toJson(journal), StagedReplacement.Journal.class);
            for (Field field : StagedReplacement.Journal.class.getFields()) {
                assertEquals(field.getName(), field.get(journal), field.get(restored));
            }
        }
    }

    @Test public void createIntentNullUrisSurviveGsonRoundTrip() throws Exception {
        StagedReplacement.Journal journal = new StagedReplacement.Journal(
                StagedReplacement.Phase.CREATE_INTENT, "原书.pdf", "content://fixture/old",
                hash(ORIGINAL), "暂存.pdf", null, hash(OUTPUT), "备份.pdf", null, null);
        StagedReplacement.Journal restored = GSON.fromJson(
                GSON.toJson(journal), StagedReplacement.Journal.class);
        assertEquals(StagedReplacement.Phase.CREATE_INTENT, restored.phase);
        assertNull(restored.stagingUri);
        assertNull(restored.backupUri);
        assertNull(restored.documentUri);
        assertEquals(journal.originalHash, restored.originalHash);
        assertEquals(journal.outputHash, restored.outputHash);
    }

    @Test public void processRestartUsesExactNamesAndFreshUrisFromDeserializedJournal() throws Exception {
        StagedReplacement.Journal persisted = journal(StagedReplacement.Phase.PROMOTE_INTENT);
        StagedReplacement.Journal restarted = GSON.fromJson(
                GSON.toJson(persisted), StagedReplacement.Journal.class);
        Folder provider = new Folder();
        provider.put(restarted.backupName, ORIGINAL);
        provider.put(restarted.stagingName, OUTPUT);
        List<StagedReplacement.Journal> snapshots = new ArrayList<>();
        StagedReplacement.Result result = StagedReplacement.recover(provider, restarted, snapshots::add);
        assertEquals(restarted.originalName, result.document.name);
        assertNotEquals(restarted.originalUri, result.document.uri);
        assertArrayEquals(ORIGINAL, provider.files.get(restarted.originalName).bytes);
        assertArrayEquals(OUTPUT, provider.files.get(restarted.stagingName).bytes);
        assertEquals(StagedReplacement.Phase.RESTORED, snapshots.get(snapshots.size() - 1).phase);
        assertEquals(1, provider.mutations);
        assertEquals(0, provider.writeOpens);
    }

    @Test public void incompleteOrInvalidDeserializedJournalCannotTouchProvider() throws Exception {
        List<String> invalid = new ArrayList<>();
        invalid.add("null");
        invalid.add("{}");
        invalid.add("{\"phase\":\"FUTURE_UNKNOWN_PHASE\"}");
        JsonObject valid = GSON.toJsonTree(journal(StagedReplacement.Phase.BACKUP_INTENT)).getAsJsonObject();
        JsonObject badHash = valid.deepCopy();
        badHash.addProperty("originalHash", "corrupt");
        invalid.add(badHash.toString());
        JsonObject missingName = valid.deepCopy();
        missingName.remove("backupName");
        invalid.add(missingName.toString());
        JsonObject collidingNames = valid.deepCopy();
        collidingNames.addProperty("stagingName", valid.get("originalName").getAsString());
        invalid.add(collidingNames.toString());
        for (String raw : invalid) {
            Folder provider = new Folder();
            List<StagedReplacement.Journal> snapshots = new ArrayList<>();
            StagedReplacement.Journal restored = GSON.fromJson(raw, StagedReplacement.Journal.class);
            try {
                StagedReplacement.recover(provider, restored, snapshots::add);
                fail("损坏日志应暂停恢复");
            } catch (StagedReplacement.ReplacementException expected) {
                assertFalse(expected.originalRestored);
            }
            assertEquals("无效日志不得列举或读取 Provider", 0, provider.calls);
            assertEquals(0, provider.mutations);
            assertEquals(0, provider.writeOpens);
            assertTrue(snapshots.isEmpty());
        }
    }

    private static StagedReplacement.Journal journal(StagedReplacement.Phase phase) throws Exception {
        return new StagedReplacement.Journal(phase, "张芷芬_原书.pdf",
                "content://fixture/tree/primary%3A书/document/old%3APDF", hash(ORIGINAL),
                "目录暂存.pdf", "content://fixture/stale-stage", hash(OUTPUT),
                "原件备份.pdf", "content://fixture/stale-backup", "content://fixture/stale-final");
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 255));
        }
        return result.toString();
    }

    private static final class FileData {
        final String uri;
        final byte[] bytes;
        FileData(String uri, byte[] bytes) { this.uri = uri; this.bytes = bytes; }
    }

    private static final class Folder implements StagedReplacement.Provider {
        final Map<String, FileData> files = new LinkedHashMap<>();
        int calls, mutations, writeOpens, nextId;
        void put(String name, byte[] bytes) {
            files.put(name, new FileData("content://fixture/current-" + (++nextId), bytes));
        }
        @Override public List<StagedReplacement.Document> find(String name) {
            calls++;
            List<StagedReplacement.Document> result = new ArrayList<>();
            FileData file = files.get(name);
            if (file != null) result.add(new StagedReplacement.Document(file.uri, name));
            return result;
        }
        @Override public InputStream openRead(StagedReplacement.Document document) throws IOException {
            calls++;
            FileData file = files.get(document.name);
            if (file == null || !file.uri.equals(document.uri)) throw new IOException("旧 URI 不可读");
            return new ByteArrayInputStream(file.bytes);
        }
        @Override public StagedReplacement.Document create(String name) {
            calls++; mutations++;
            throw new AssertionError("恢复不能创建文件");
        }
        @Override public OutputStream openWriteNew(StagedReplacement.Document document) {
            calls++; writeOpens++;
            throw new AssertionError("恢复不能打开写入流");
        }
        @Override public StagedReplacement.Document rename(StagedReplacement.Document document, String name)
                throws IOException {
            calls++;
            if (files.containsKey(name)) throw new IOException("名字冲突");
            FileData source = files.remove(document.name);
            if (source == null || !source.uri.equals(document.uri)) throw new IOException("旧 URI 不可改名");
            mutations++;
            put(name, source.bytes);
            return new StagedReplacement.Document(files.get(name).uri, name);
        }
    }
}
