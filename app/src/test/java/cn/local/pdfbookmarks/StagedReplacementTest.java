package cn.local.pdfbookmarks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** 宿主故障模型；不能代替平板 DocumentsProvider 实测。 */
public final class StagedReplacementTest {
    private static final String ORIGINAL = "原书.pdf";
    private static final byte[] OLD = "original PDF bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "verified PDF with bookmarks".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD_CATALOG = "{\"kids\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_CATALOG = "{\"kids\":[{\"title\":\"第一章\",\"pageId\":\"note_page1\"}]}"
            .getBytes(StandardCharsets.UTF_8);
    private File output;
    private MemoryProvider provider;
    private MemorySink sink;

    @Before public void before() throws Exception {
        output = File.createTempFile("staged-pdf-test-", ".pdf");
        Files.write(output.toPath(), NEW);
        provider = new MemoryProvider();
        provider.put(ORIGINAL, OLD);
        sink = new MemorySink();
    }

    @After public void after() throws Exception {
        Thread.interrupted();
        Files.deleteIfExists(output.toPath());
    }

    @Test public void successPreservesOriginalBackupAndOnlyWritesNewStage() throws Exception {
        StagedReplacement.Result result = replace();
        assertArrayEquals(NEW, provider.bytes(ORIGINAL));
        assertArrayEquals(OLD, provider.bytes(result.backup.name));
        assertEquals(2, provider.files.size());
        assertEquals(1, provider.writeCount);
        assertTrue(provider.stagedBeforeOriginalRename);
        assertEquals(StagedReplacement.Phase.COMMITTED, sink.last().phase);
        assertEquals(ORIGINAL, result.document.name);
        assertTrue(result.journal.stagingName.matches("pdf-bookmarks-[0-9a-f-]+-staged\\.pdf"));
        assertTrue(result.backup.name.matches("pdf-bookmarks-[0-9a-f-]+-backup\\.pdf"));
    }

    @Test public void catalogSuccessKeepsJsonBackupAndNeverTouchesPdfOrOtherConfiguration() throws Exception {
        prepareCatalog();
        provider.changeUri = true;
        StagedReplacement.Result result = replaceCatalog();
        assertEquals("catalog.json", result.document.name);
        assertTrue(result.journal.stagingName.matches("pdf-bookmarks-catalog-[0-9a-f-]+-staged\\.json"));
        assertTrue(result.backup.name.matches("pdf-bookmarks-catalog-[0-9a-f-]+-backup\\.json"));
        assertArrayEquals(NEW_CATALOG, provider.bytes("catalog.json"));
        assertArrayEquals(OLD_CATALOG, provider.bytes(result.backup.name));
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json.temp"));
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(1, provider.writeCount);
        assertEquals(2, provider.renameCount);
        assertEquals(4, provider.files.size());
        assertEquals(StagedReplacement.Phase.COMMITTED, sink.last().phase);
        assertEquals(provider.files.get("catalog.json").uri, result.document.uri);
    }

    @Test public void catalogRejectsEveryOtherTargetBeforeAnyMutation() throws Exception {
        prepareCatalog();
        for (String name : new String[] {null, "catalog.json.temp", "pageids.json", "doc",
                "Catalog.json", "../catalog.json", ORIGINAL}) {
            StagedReplacement.ReplacementException failure = failCatalog(name);
            assertNull(failure.journal);
            assertFalse(failure.originalRestored);
        }
        assertEquals(0, provider.createCount);
        assertEquals(0, provider.writeCount);
        assertEquals(0, provider.renameCount);
        assertTrue(sink.saved.isEmpty());
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json"));
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
    }

    @Test public void changedCatalogIsRejectedBeforeCreatingStage() throws Exception {
        prepareCatalog();
        provider.put("catalog.json", NEW_CATALOG);
        StagedReplacement.ReplacementException failure = failCatalog("catalog.json");
        assertNull(failure.journal);
        assertEquals(0, provider.createCount);
        assertEquals(0, provider.renameCount);
        assertArrayEquals(NEW_CATALOG, provider.bytes("catalog.json"));
        assertFalse(failure.getMessage().contains("PDF"));
    }

    @Test public void catalogPromotionFailureRestoresOldCatalogAndRetainsVerifiedStage() throws Exception {
        prepareCatalog();
        provider.renameFaults.put(2, RenameFault.BEFORE_THROW);
        StagedReplacement.ReplacementException failure = failCatalog("catalog.json");
        assertTrue(failure.originalRestored);
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json"));
        assertArrayEquals(NEW_CATALOG, provider.bytes(failure.journal.stagingName));
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json.temp"));
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(StagedReplacement.Phase.RESTORED, sink.last().phase);
        assertEquals(4, provider.files.size());
    }

    @Test public void catalogCommitFailureRetainsBothVersionsAndCanRecover() throws Exception {
        prepareCatalog();
        sink.failPhase = StagedReplacement.Phase.COMMITTED;
        StagedReplacement.ReplacementException failure = failCatalog("catalog.json");
        assertFalse(failure.originalRestored);
        assertFalse(failure.getMessage().contains("PDF"));
        assertEquals(StagedReplacement.Phase.PROMOTE_INTENT, sink.last().phase);
        assertArrayEquals(NEW_CATALOG, provider.bytes("catalog.json"));
        assertArrayEquals(OLD_CATALOG, provider.bytes(failure.journal.backupName));
        sink.failPhase = null;
        StagedReplacement.Result restored = StagedReplacement.recover(provider, sink.last(), sink);
        assertEquals(StagedReplacement.Phase.RESTORED, restored.journal.phase);
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json"));
        assertArrayEquals(NEW_CATALOG, provider.bytes(restored.journal.stagingName));
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json.temp"));
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
    }

    @Test public void catalogRecoveryAfterProcessStopPreservesOtherNoteFiles() throws Exception {
        prepareCatalog();
        provider.files.remove("catalog.json");
        MemoryDocument backup = provider.put("pdf-bookmarks-catalog-interrupted-backup.json", OLD_CATALOG);
        MemoryDocument stage = provider.put("pdf-bookmarks-catalog-interrupted-staged.json", NEW_CATALOG);
        StagedReplacement.Journal journal = new StagedReplacement.Journal(
                StagedReplacement.Phase.BACKUP_INTENT, "catalog.json", "old-catalog-uri", digest(OLD_CATALOG),
                stage.name, "stale-stage-uri", digest(NEW_CATALOG), backup.name, "stale-backup-uri", null);
        StagedReplacement.Result restored = StagedReplacement.recover(provider, journal, sink);
        assertEquals(StagedReplacement.Phase.RESTORED, restored.journal.phase);
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json"));
        assertArrayEquals(NEW_CATALOG, provider.bytes(stage.name));
        assertArrayEquals(OLD_CATALOG, provider.bytes("catalog.json.temp"));
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(0, provider.writeCount);
        assertEquals(4, provider.files.size());
    }

    @Test public void changedOriginalIsRejectedBeforeCreate() throws Exception {
        provider.put(ORIGINAL, NEW);
        StagedReplacement.ReplacementException failure = failReplace();
        assertNull(failure.journal);
        assertEquals(0, provider.createCount);
        assertEquals(0, provider.renameCount);
        assertTrue(sink.saved.isEmpty());
    }

    @Test public void duplicateOriginalNamePreventsAllMutations() throws Exception {
        provider.duplicateNames.add(ORIGINAL);
        failReplace();
        assertEquals(0, provider.createCount);
        assertEquals(0, provider.renameCount);
    }

    @Test public void stagingPartialWriteFailureLeavesOriginalUntouched() throws Exception {
        provider.failWrite = true;
        StagedReplacement.ReplacementException failure = failReplace();
        assertTrue(failure.originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(0, provider.renameCount);
        assertEquals(2, provider.files.size());
        assertTrue(provider.bytes(failure.journal.stagingName).length > 0);
        assertEquals(StagedReplacement.Phase.CREATE_INTENT, failure.journal.phase);
    }

    @Test public void stagingCloseFailureLeavesOriginalUntouched() throws Exception {
        provider.failClose = true;
        assertTrue(failReplace().originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(0, provider.renameCount);
    }

    @Test public void stagingReadMismatchPreventsOriginalRename() throws Exception {
        provider.corruptStage = true;
        failReplace();
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(0, provider.renameCount);
    }

    @Test public void createAliasOfOriginalCannotBeWritten() throws Exception {
        provider.createAliasesOriginal = true;
        failReplace();
        assertEquals(0, provider.writeCount);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
    }

    @Test public void createNullOrThrowAfterCreationRetainsStageAndOriginal() throws Exception {
        for (boolean returnNull : new boolean[] {true, false}) {
            beforeAgain();
            provider.createNull = returnNull;
            provider.createThrow = !returnNull;
            StagedReplacement.ReplacementException failure = failReplace();
            assertTrue(failure.originalRestored);
            assertEquals(StagedReplacement.Phase.CREATE_INTENT, failure.journal.phase);
            assertEquals(2, provider.files.size());
            assertEquals(0, provider.writeCount);
            assertEquals(0, provider.renameCount);
        }
    }

    @Test public void initialJournalFailurePreventsCreatingAnything() throws Exception {
        sink.failPhase = StagedReplacement.Phase.CREATE_INTENT;
        failReplace();
        assertEquals(0, provider.createCount);
        assertEquals(0, provider.renameCount);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
    }

    @Test public void journalFailureBeforeWritePreventsOpeningWriteStream() throws Exception {
        sink.failSaveNumber = 2;
        failReplace();
        assertEquals(1, provider.createCount);
        assertEquals(0, provider.writeCount);
        assertEquals(0, provider.renameCount);
    }

    @Test public void stagedOrBackupIntentJournalFailurePreventsOriginalRename() throws Exception {
        for (StagedReplacement.Phase phase : new StagedReplacement.Phase[] {
                StagedReplacement.Phase.STAGED, StagedReplacement.Phase.BACKUP_INTENT}) {
            beforeAgain();
            sink.failPhase = phase;
            assertTrue(failReplace().originalRestored);
            assertEquals(0, provider.renameCount);
            assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        }
    }

    @Test public void promoteIntentJournalFailureRestoresOriginalName() throws Exception {
        sink.failPhase = StagedReplacement.Phase.PROMOTE_INTENT;
        StagedReplacement.ReplacementException failure = failReplace();
        assertTrue(failure.originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.bytes(failure.journal.stagingName));
        assertEquals(StagedReplacement.Phase.RESTORED, sink.last().phase);
    }

    @Test public void renameChangingUriUsesFreshDirectoryDocuments() throws Exception {
        String oldUri = provider.files.get(ORIGINAL).uri;
        provider.changeUri = true;
        StagedReplacement.Result result = replace();
        assertNotEquals(oldUri, result.document.uri);
        assertNotEquals(oldUri, result.backup.uri);
        assertEquals(provider.files.get(ORIGINAL).uri, result.document.uri);
        assertArrayEquals(OLD, provider.readBytes(oldUri));
    }

    @Test public void renameNullOrExceptionAfterMutationIsResolvedByNamesAndHashes() throws Exception {
        for (RenameFault fault : new RenameFault[] {RenameFault.AFTER_NULL, RenameFault.AFTER_THROW}) {
            beforeAgain();
            provider.changeUri = true;
            provider.renameFaults.put(1, fault);
            provider.renameFaults.put(2, fault);
            StagedReplacement.Result result = replace();
            assertArrayEquals(NEW, provider.bytes(ORIGINAL));
            assertArrayEquals(OLD, provider.bytes(result.backup.name));
            assertEquals(StagedReplacement.Phase.COMMITTED, result.journal.phase);
        }
    }

    @Test public void renameFailureBeforeBackupLeavesOriginalAndStage() throws Exception {
        provider.renameFaults.put(1, RenameFault.BEFORE_THROW);
        StagedReplacement.ReplacementException failure = failReplace();
        assertTrue(failure.originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.bytes(failure.journal.stagingName));
        assertEquals(2, provider.files.size());
    }

    @Test public void promotionFailureRestoresBackupWithoutDeletingStage() throws Exception {
        provider.renameFaults.put(2, RenameFault.BEFORE_THROW);
        StagedReplacement.ReplacementException failure = failReplace();
        assertTrue(failure.originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.bytes(failure.journal.stagingName));
        assertEquals(2, provider.files.size());
    }

    @Test public void missingOriginalCannotBeReportedSuccessfulViaReadableOldUri() throws Exception {
        provider.renameFaults.put(2, RenameFault.WRONG_NAME);
        StagedReplacement.ReplacementException failure = failReplace();
        assertTrue(failure.originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.readBytes(provider.lastPromotionUri));
        assertArrayEquals(NEW, provider.bytes("provider-lost-name.pdf"));
        for (StagedReplacement.Journal journal : sink.saved) {
            assertNotEquals(StagedReplacement.Phase.COMMITTED, journal.phase);
        }
    }

    @Test public void commitJournalFailureKeepsNewPdfBackupAndPendingRecovery() throws Exception {
        sink.failPhase = StagedReplacement.Phase.COMMITTED;
        StagedReplacement.ReplacementException failure = failReplace();
        assertFalse(failure.originalRestored);
        assertEquals(StagedReplacement.Phase.PROMOTE_INTENT, failure.journal.phase);
        assertEquals(StagedReplacement.Phase.PROMOTE_INTENT, sink.last().phase);
        assertArrayEquals(NEW, provider.bytes(ORIGINAL));
        assertArrayEquals(OLD, provider.bytes(failure.journal.backupName));
        assertEquals(2, provider.renameCount);
        sink.failPhase = null;
        StagedReplacement.Result restored = StagedReplacement.recover(provider, sink.last(), sink);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.bytes(restored.journal.stagingName));
        assertNull(restored.backup);
    }

    @Test public void recoverAfterProcessStopsBetweenBackupAndPromotion() throws Exception {
        StagedReplacement.Journal journal = interruptedJournal(StagedReplacement.Phase.BACKUP_INTENT);
        StagedReplacement.Result result = StagedReplacement.recover(provider, journal, sink);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.bytes(journal.stagingName));
        assertEquals(StagedReplacement.Phase.RESTORED, result.journal.phase);
        assertFalse(provider.files.containsKey(journal.backupName));
        assertEquals(0, provider.writeCount);
    }

    @Test public void recoverInterruptedStageDoesNotNeedToRewriteUntouchedOriginal() throws Exception {
        provider.put("stage.pdf", "partial stage".getBytes(StandardCharsets.UTF_8));
        StagedReplacement.Journal journal = new StagedReplacement.Journal(
                StagedReplacement.Phase.CREATE_INTENT, ORIGINAL, "stale-original-uri", digest(OLD),
                "stage.pdf", "stale-stage-uri", digest(NEW), "backup.pdf", null, null);
        StagedReplacement.recover(provider, journal, sink);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertEquals(0, provider.renameCount);
        assertEquals(0, provider.writeCount);
        assertEquals(2, provider.files.size());
    }

    @Test public void recoverRenameReturningNullOrThrowingAfterMutationIsVerified() throws Exception {
        for (RenameFault fault : new RenameFault[] {RenameFault.AFTER_NULL, RenameFault.AFTER_THROW}) {
            beforeAgain();
            StagedReplacement.Journal journal = interruptedJournal(StagedReplacement.Phase.PROMOTE_INTENT);
            provider.changeUri = true;
            provider.renameFaults.put(1, fault);
            StagedReplacement.Result result = StagedReplacement.recover(provider, journal, sink);
            assertArrayEquals(OLD, provider.bytes(ORIGINAL));
            assertEquals(StagedReplacement.Phase.RESTORED, result.journal.phase);
            assertEquals(provider.files.get(ORIGINAL).uri, result.document.uri);
        }
    }

    @Test public void recoverCompletedReplacementRetainsNewOutputUnderStageName() throws Exception {
        StagedReplacement.Result replaced = replace();
        StagedReplacement.Result restored = StagedReplacement.recover(provider, replaced.journal, sink);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        assertArrayEquals(NEW, provider.bytes(replaced.journal.stagingName));
        assertEquals(StagedReplacement.Phase.RESTORED, restored.journal.phase);
        assertEquals(1, provider.writeCount);
        assertEquals(2, provider.files.size());
    }

    @Test public void restoreNameCollisionPreservesEveryFileWithoutMutation() throws Exception {
        StagedReplacement.Result result = replace();
        provider.put(result.journal.stagingName, "unrelated".getBytes(StandardCharsets.UTF_8));
        int renames = provider.renameCount;
        failRecover(result.journal);
        assertEquals(renames, provider.renameCount);
        assertArrayEquals(NEW, provider.bytes(ORIGINAL));
        assertArrayEquals(OLD, provider.bytes(result.backup.name));
        assertEquals(3, provider.files.size());
    }

    @Test public void unrelatedOriginalNameOccupantIsNeverMovedOrOverwritten() throws Exception {
        StagedReplacement.Journal journal = interruptedJournal(StagedReplacement.Phase.PROMOTE_INTENT);
        byte[] unrelated = "another PDF".getBytes(StandardCharsets.UTF_8);
        provider.put(ORIGINAL, unrelated);
        failRecover(journal);
        assertEquals(0, provider.renameCount);
        assertArrayEquals(unrelated, provider.bytes(ORIGINAL));
        assertArrayEquals(OLD, provider.bytes(journal.backupName));
        assertEquals(3, provider.files.size());
    }

    @Test public void ambiguousBackupNamePreventsRecoveryMutation() throws Exception {
        StagedReplacement.Journal journal = interruptedJournal(StagedReplacement.Phase.PROMOTE_INTENT);
        provider.duplicateNames.add(journal.backupName);
        failRecover(journal);
        assertEquals(0, provider.renameCount);
        assertFalse(provider.files.containsKey(ORIGINAL));
        assertArrayEquals(OLD, provider.bytes(journal.backupName));
    }

    @Test public void restoreIntentJournalFailurePreventsAnyRecoveryRename() throws Exception {
        StagedReplacement.Result result = replace();
        sink.failPhase = StagedReplacement.Phase.RESTORE_INTENT;
        int renames = provider.renameCount;
        assertFalse(failRecover(result.journal).originalRestored);
        assertEquals(renames, provider.renameCount);
        assertArrayEquals(NEW, provider.bytes(ORIGINAL));
        assertArrayEquals(OLD, provider.bytes(result.backup.name));
    }

    @Test public void restoredJournalFailureStillRequiresRecoveryConfirmation() throws Exception {
        StagedReplacement.Journal journal = interruptedJournal(StagedReplacement.Phase.BACKUP_INTENT);
        sink.failPhase = StagedReplacement.Phase.RESTORED;
        StagedReplacement.ReplacementException failure = failRecover(journal);
        assertFalse(failure.originalRestored);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
        sink.failPhase = null;
        StagedReplacement.Result result = StagedReplacement.recover(provider, failure.journal, sink);
        assertEquals(StagedReplacement.Phase.RESTORED, result.journal.phase);
        assertEquals(1, provider.renameCount);
    }

    @Test public void recoveryIgnoresAndRestoresThreadInterruption() throws Exception {
        StagedReplacement.Journal journal = interruptedJournal(StagedReplacement.Phase.BACKUP_INTENT);
        provider.rejectInterruptedIo = true;
        Thread.currentThread().interrupt();
        StagedReplacement.recover(provider, journal, sink);
        assertTrue(Thread.currentThread().isInterrupted());
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
    }

    @Test public void malformedRecoveryNamesCannotRenameOriginal() throws Exception {
        StagedReplacement.Journal journal = new StagedReplacement.Journal(
                StagedReplacement.Phase.BACKUP_INTENT, ORIGINAL, "old", digest(OLD),
                ORIGINAL, "stage", digest(NEW), "backup.pdf", "backup", null);
        failRecover(journal);
        assertEquals(0, provider.renameCount);
        assertArrayEquals(OLD, provider.bytes(ORIGINAL));
    }

    private StagedReplacement.Result replace() throws Exception {
        return StagedReplacement.replace(provider, ORIGINAL, output, digest(OLD), sink);
    }

    private void prepareCatalog() throws Exception {
        provider.put("catalog.json", OLD_CATALOG);
        provider.put("catalog.json.temp", OLD_CATALOG);
        Files.write(output.toPath(), NEW_CATALOG);
    }

    private StagedReplacement.Result replaceCatalog() throws Exception {
        return StagedReplacement.replaceCatalog(provider, "catalog.json", output, digest(OLD_CATALOG), sink);
    }

    private StagedReplacement.ReplacementException failCatalog(String name) throws Exception {
        try {
            StagedReplacement.replaceCatalog(provider, name, output, digest(OLD_CATALOG), sink);
            fail("应保留目录及其他笔记文件");
        } catch (StagedReplacement.ReplacementException expected) { return expected; }
        throw new AssertionError();
    }

    private StagedReplacement.ReplacementException failReplace() throws Exception {
        try { replace(); fail("应拒绝或报告失败"); }
        catch (StagedReplacement.ReplacementException expected) { return expected; }
        throw new AssertionError();
    }

    private StagedReplacement.ReplacementException failRecover(StagedReplacement.Journal journal)
            throws Exception {
        try { StagedReplacement.recover(provider, journal, sink); fail("应保留恢复状态"); }
        catch (StagedReplacement.ReplacementException expected) { return expected; }
        throw new AssertionError();
    }

    private void beforeAgain() {
        provider = new MemoryProvider();
        provider.put(ORIGINAL, OLD);
        sink = new MemorySink();
    }

    private StagedReplacement.Journal interruptedJournal(StagedReplacement.Phase phase) throws Exception {
        provider.files.remove(ORIGINAL);
        MemoryDocument backup = provider.put("backup.pdf", OLD);
        MemoryDocument stage = provider.put("stage.pdf", NEW);
        return new StagedReplacement.Journal(phase, ORIGINAL, "old-uri", digest(OLD),
                stage.name, "stale-stage-uri", digest(NEW), backup.name, "stale-backup-uri", null);
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] values = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : values) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private enum RenameFault { BEFORE_THROW, AFTER_THROW, AFTER_NULL, WRONG_NAME }

    private static final class MemoryDocument {
        String uri;
        String name;
        byte[] bytes;
        MemoryDocument(String uri, String name, byte[] bytes) {
            this.uri = uri;
            this.name = name;
            this.bytes = bytes.clone();
        }
        StagedReplacement.Document snapshot() { return new StagedReplacement.Document(uri, name); }
    }

    private static final class MemoryProvider implements StagedReplacement.Provider {
        final Map<String, MemoryDocument> files = new LinkedHashMap<>();
        // 旧 URI 故意仍可读，以暴露只凭 URI 判断成功的错误。
        final Map<String, MemoryDocument> readableUris = new HashMap<>();
        final Set<MemoryDocument> created = new HashSet<>();
        final Set<String> duplicateNames = new HashSet<>();
        final Map<Integer, RenameFault> renameFaults = new HashMap<>();
        int nextId, createCount, writeCount, renameCount;
        boolean failWrite, failClose, corruptStage, createAliasesOriginal, createNull, createThrow;
        boolean changeUri, stagedBeforeOriginalRename, rejectInterruptedIo;
        String lastPromotionUri;

        MemoryDocument put(String name, byte[] bytes) {
            MemoryDocument document = new MemoryDocument("content://fake/" + (++nextId), name, bytes);
            files.put(name, document);
            readableUris.put(document.uri, document);
            return document;
        }
        byte[] bytes(String name) { return files.get(name).bytes; }
        byte[] readBytes(String uri) { return readableUris.get(uri).bytes; }
        void checkIo() throws IOException {
            if (rejectInterruptedIo && Thread.currentThread().isInterrupted()) {
                throw new IOException("provider refused interrupted I/O");
            }
        }
        @Override public List<StagedReplacement.Document> find(String name) throws IOException {
            checkIo();
            List<StagedReplacement.Document> result = new ArrayList<>();
            MemoryDocument document = files.get(name);
            if (document != null) result.add(document.snapshot());
            if (document != null && duplicateNames.contains(name)) result.add(document.snapshot());
            return result;
        }
        @Override public StagedReplacement.Document create(String name) throws IOException {
            checkIo();
            createCount++;
            MemoryDocument document = put(name, new byte[0]);
            created.add(document);
            if (createAliasesOriginal) {
                document.uri = files.get(ORIGINAL).uri;
                return document.snapshot();
            }
            if (createThrow) throw new IOException("create threw after mutation");
            return createNull ? null : document.snapshot();
        }
        @Override public InputStream openRead(StagedReplacement.Document document) throws IOException {
            checkIo();
            MemoryDocument memory = readableUris.get(document.uri);
            if (memory == null) throw new IOException("unknown URI");
            return new ByteArrayInputStream(memory.bytes);
        }
        @Override public OutputStream openWriteNew(StagedReplacement.Document document) throws IOException {
            checkIo();
            final MemoryDocument memory = readableUris.get(document.uri);
            assertTrue("禁止打开非本次新建文件写入", created.contains(memory));
            assertNotEquals(ORIGINAL, memory.name);
            writeCount++;
            memory.bytes = new byte[0];
            return new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int offset, int count) {
                    if (failWrite) {
                        super.write(bytes, offset, Math.min(3, count));
                        memory.bytes = toByteArray();
                        throw new IllegalStateException("partial stage write failure");
                    }
                    super.write(bytes, offset, count);
                }
                @Override public void close() throws IOException {
                    memory.bytes = corruptStage ? OLD.clone() : toByteArray();
                    if (failClose) throw new IOException("stage sync/close failure");
                    super.close();
                }
            };
        }
        @Override public StagedReplacement.Document rename(StagedReplacement.Document document, String name)
                throws IOException {
            checkIo();
            renameCount++;
            MemoryDocument memory = files.get(document.name);
            assertNotNull(memory);
            assertEquals(memory.uri, document.uri);
            RenameFault fault = renameFaults.get(renameCount);
            if (fault == RenameFault.BEFORE_THROW) throw new IOException("rename failed before mutation");
            if (renameCount == 1 && ORIGINAL.equals(document.name)) {
                for (MemoryDocument file : files.values()) {
                    if (created.contains(file) && Arrays.equals(NEW, file.bytes)) {
                        stagedBeforeOriginalRename = true;
                    }
                }
            }
            assertFalse("改名不得覆盖已有名字", files.containsKey(name));
            files.remove(memory.name);
            memory.name = fault == RenameFault.WRONG_NAME ? "provider-lost-name.pdf" : name;
            if (changeUri) {
                memory.uri = "content://fake/" + (++nextId);
                readableUris.put(memory.uri, memory);
            }
            files.put(memory.name, memory);
            if (ORIGINAL.equals(name) && renameCount == 2) lastPromotionUri = memory.uri;
            if (fault == RenameFault.AFTER_THROW) throw new IOException("rename threw after mutation");
            return fault == RenameFault.AFTER_NULL ? null : memory.snapshot();
        }
    }

    private static final class MemorySink implements StagedReplacement.Sink {
        final List<StagedReplacement.Journal> saved = new ArrayList<>();
        StagedReplacement.Phase failPhase;
        int saveCount, failSaveNumber;
        @Override public void save(StagedReplacement.Journal journal) throws IOException {
            saveCount++;
            if (journal.phase == failPhase || saveCount == failSaveNumber) {
                throw new IOException("durable journal save failure: " + journal.phase);
            }
            saved.add(journal);
        }
        StagedReplacement.Journal last() { return saved.get(saved.size() - 1); }
    }
}
