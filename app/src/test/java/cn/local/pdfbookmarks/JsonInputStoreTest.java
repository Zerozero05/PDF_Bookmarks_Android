package cn.local.pdfbookmarks;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.Assert.*;

/** All content is newly generated synthetic JSON; no external provider or user file is opened. */
public class JsonInputStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(new File("build"));
    private static final String SOURCE = "content://qq.provider/Android/data/opaque/outline.json";
    private static final byte[] INPUT = bytes("{\"outline\":[{\"title\":\"合成目录\",\"page\":3}]}");

    @Test public void importSurvivesStoreRecreationWithOriginalSourceAndIndependentBytes() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        byte[] incoming = INPUT.clone(); JsonInputRef ref = store.importShared(SOURCE, "目录.json", incoming);
        incoming[0] = 'X';
        assertEquals(SOURCE, ref.sourceUri); assertEquals(sha(INPUT), ref.sha256);
        assertArrayEquals(INPUT, store.read(ref));
        assertArrayEquals(INPUT, Files.readAllBytes(data(files, ref)));
        JsonInputStore restarted = new JsonInputStore(files);
        assertEquals(ref, restarted.findById(ref.id));
        assertEquals(ref, restarted.find(SOURCE, ref.sha256));
        assertArrayEquals(INPUT, restarted.read(ref));
        try (java.util.stream.Stream<Path> paths = Files.list(data(files, ref).getParent())) {
            assertEquals(2, paths.count());
        }
    }

    @Test public void sameNamesNeverOverwriteOrMatchDifferentSourcesOrContent() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef first = store.importShared(SOURCE, "same.json", INPUT);
        byte[] changed = bytes("{\"outline\":[]}");
        JsonInputRef second = store.importShared("content://qq.provider/different", "same.json", changed);
        assertNotEquals(first.id, second.id);
        assertArrayEquals(INPUT, store.read(first)); assertArrayEquals(changed, store.read(second));
        assertNull(store.find(SOURCE, second.sha256));
        assertNull(store.find("content://qq.provider/different", first.sha256));
        assertEquals(first, store.find(SOURCE, first.sha256));
        assertEquals(second, store.find(second.sourceUri, second.sha256));
    }

    @Test public void readAndDeleteRejectChangedContentAndPreserveMetadataAndOtherInput() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef ref = store.importShared(SOURCE, "目录.json", INPUT);
        JsonInputRef other = store.importShared("content://qq.provider/other", "other.json", INPUT);
        byte[] changed = bytes("changed by synthetic test"); Files.write(data(files, ref), changed);
        assertThrows(IOException.class, () -> store.read(ref));
        assertThrows(IOException.class, () -> store.deleteOwned(ref));
        assertArrayEquals(changed, Files.readAllBytes(data(files, ref)));
        assertTrue(Files.isRegularFile(meta(files, ref)));
        assertArrayEquals(INPUT, store.read(other));
    }

    @Test public void deleteOnlyRemovesExactOwnedPairAndCanBeRepeated() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef ref = store.importShared(SOURCE, "目录.json", INPUT);
        JsonInputRef other = store.importShared("content://qq.provider/other", "other.json", INPUT);
        Path unrelated = data(files, ref).getParent().resolve("unrelated.json"); Files.write(unrelated, INPUT);
        Path source = temporary.newFile("source.json").toPath(); Files.write(source, INPUT);
        store.deleteOwned(ref); store.deleteOwned(ref);
        assertFalse(Files.exists(data(files, ref))); assertFalse(Files.exists(meta(files, ref)));
        assertNull(store.findById(ref.id)); assertNull(store.find(SOURCE, ref.sha256));
        assertArrayEquals(INPUT, store.read(other)); assertArrayEquals(INPUT, Files.readAllBytes(unrelated));
        assertArrayEquals(INPUT, Files.readAllBytes(source));
    }

    @Test public void cleanupCanResumeAfterBytesWereAlreadyRemoved() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef ref = store.importShared(SOURCE, "目录.json", INPUT);
        Files.delete(data(files, ref));
        assertEquals(ref, new JsonInputStore(files).findById(ref.id));
        assertThrows(IOException.class, () -> store.read(ref));
        store.deleteOwned(ref);
        assertFalse(Files.exists(meta(files, ref)));
    }

    @Test public void forgedReferenceAndMissingOwnershipRecordCannotDeleteBytes() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef ref = store.importShared(SOURCE, "目录.json", INPUT);
        JsonInputRef forged = new JsonInputRef(ref.id, "content://qq.provider/other", ref.displayName, ref.sha256);
        assertThrows(IOException.class, () -> store.read(forged));
        assertThrows(IOException.class, () -> store.deleteOwned(forged));
        assertArrayEquals(INPUT, store.read(ref));
        Files.delete(meta(files, ref));
        assertThrows(IOException.class, () -> store.deleteOwned(ref));
        assertArrayEquals(INPUT, Files.readAllBytes(data(files, ref)));
    }

    @Test public void invalidInputDoesNotCreateWorkingDirectory() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        assertThrows(IOException.class, () -> store.importShared(SOURCE, "目录.json", null));
        assertThrows(IOException.class, () -> store.importShared(SOURCE, "目录.json", new byte[0]));
        assertThrows(IOException.class, () -> store.importShared(SOURCE, "目录.json", new byte[JsonInputStore.MAX_BYTES + 1]));
        assertThrows(IOException.class, () -> store.importShared(SOURCE, "目录.json", new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> store.importShared("file:///sdcard/input.json", "目录.json", INPUT));
        assertThrows(IllegalArgumentException.class, () -> store.importShared(SOURCE, "../目录.json", INPUT));
        assertFalse(new File(files, "imports").exists());
        assertNull(store.find(SOURCE, sha(INPUT)));
        assertFalse(new File(files, "imports").exists());
    }

    @Test public void exactSizeBoundaryIsAcceptedAndCorruptOrOversizedWorkFileIsRejected() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        byte[] maximum = new byte[JsonInputStore.MAX_BYTES]; java.util.Arrays.fill(maximum, (byte) ' ');
        JsonInputRef ref = store.importShared(SOURCE, "目录.json", maximum);
        assertEquals(JsonInputStore.MAX_BYTES, store.read(ref).length);
        Files.write(data(files, ref), new byte[JsonInputStore.MAX_BYTES + 1]);
        assertThrows(IOException.class, () -> store.read(ref));
        assertThrows(IOException.class, () -> store.deleteOwned(ref));
        assertTrue(Files.exists(meta(files, ref)));
    }

    @Test public void strictMetadataRejectsDuplicateKeysUnknownFieldsAndWrongTypes() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef ref = store.importShared(SOURCE, "目录.json", INPUT);
        String original = new String(Files.readAllBytes(meta(files, ref)), StandardCharsets.UTF_8);
        for (String invalid : new String[]{original.substring(0, original.length() - 1) + ",\"id\":\"" + ref.id + "\"}",
                original.substring(0, original.length() - 1) + ",\"unknown\":\"value\"}",
                original.replace("\"id\":\"" + ref.id + "\"", "\"id\":1"), original + "true"}) {
            Files.write(meta(files, ref), bytes(invalid));
            assertThrows(IOException.class, () -> store.findById(ref.id));
            assertThrows(IOException.class, () -> store.deleteOwned(ref));
            assertArrayEquals(INPUT, Files.readAllBytes(data(files, ref)));
        }
    }

    @Test public void unrelatedBrokenMetadataDoesNotHideAnotherMatchingImport() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        JsonInputRef broken = store.importShared("content://provider/broken", "same.json", INPUT);
        JsonInputRef valid = store.importShared(SOURCE, "same.json", INPUT);
        Files.write(meta(files, broken), bytes("not json"));
        assertEquals(valid, store.find(SOURCE, valid.sha256));
        assertTrue(Files.exists(meta(files, broken)));
    }

    @Test public void malformedIdsCannotReachOutsideManagedDirectory() throws Exception {
        File files = temporary.newFolder("files"); JsonInputStore store = new JsonInputStore(files);
        Path note = new File(files, "note.json").toPath(); Files.write(note, INPUT);
        for (String id : new String[]{"../note", "../../note.json", "C:\\note", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> store.findById(id));
        }
        assertArrayEquals(INPUT, Files.readAllBytes(note));
    }

    @Test public void regularFileCannotBeUsedAsManagedDirectoryOrWorkFile() throws Exception {
        File files = temporary.newFolder("files");
        Files.write(new File(files, "imports").toPath(), INPUT);
        assertThrows(IOException.class, () -> new JsonInputStore(files).importShared(SOURCE, "目录.json", INPUT));
        Files.delete(new File(files, "imports").toPath());
        JsonInputStore store = new JsonInputStore(files); JsonInputRef ref = store.importShared(SOURCE, "目录.json", INPUT);
        Files.delete(data(files, ref)); Files.createDirectory(data(files, ref));
        assertThrows(IOException.class, () -> store.read(ref)); assertThrows(IOException.class, () -> store.deleteOwned(ref));
        assertTrue(Files.exists(meta(files, ref)));
    }

    private static Path data(File files, JsonInputRef ref) { return new File(files, "imports/json/" + ref.id + ".json").toPath(); }
    private static Path meta(File files, JsonInputRef ref) { return new File(files, "imports/json/" + ref.id + ".meta.json").toPath(); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hash.toString();
    }
}
