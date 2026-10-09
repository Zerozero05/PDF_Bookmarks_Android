package cn.local.pdfbookmarks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 持久化顺序用内存状态建模；不声称验证 Android 存储，也不删除宿主样本。 */
public final class CatalogReplacementCleanupTest {
    private static final byte[] OLD = bytes("old manual catalog");
    private static final byte[] NEW = bytes("new catalog");
    private static final byte[] PDF = bytes("current PDF");
    private static final byte[] PDF_BACKUP = bytes("original PDF");
    private static final File FIXTURES = fixtureDirectory();
    private static final String PDF_NAME = "原书.pdf";
    private static final String PDF_BACKUP_NAME = "原书备份.pdf";
    private final List<String> events = new ArrayList<>();
    private StagedReplacement.Document output, backup;
    private MemoryProvider provider;
    private MemoryFile privateBackup;
    private File physicalBackup, unrelatedPrivatePdf;
    private BackupCleanup.Item item;
    private boolean progressSaved, planShown, finished;

    @Before public void before() throws Exception {
        output = new StagedReplacement.Document("content://test/catalog", "catalog.json");
        backup = new StagedReplacement.Document("content://test/old-catalog", "old-catalog.json");
        provider = new MemoryProvider();
        provider.add(output, NEW);
        provider.add(backup, OLD);
        provider.add(new StagedReplacement.Document("content://test/pdf", PDF_NAME), PDF);
        provider.add(new StagedReplacement.Document("content://test/old-pdf", PDF_BACKUP_NAME), PDF_BACKUP);
        physicalBackup = File.createTempFile("original-catalog-", ".json", FIXTURES);
        Files.write(physicalBackup.toPath(), OLD);
        privateBackup = new MemoryFile(physicalBackup);
        unrelatedPrivatePdf = File.createTempFile("original-pdf-", ".pdf", FIXTURES);
        Files.write(unrelatedPrivatePdf.toPath(), PDF_BACKUP);
        item = new BackupCleanup.Item(provider, output, backup, hash(NEW), hash(OLD), privateBackup);
    }

    @After public void after() { Thread.interrupted(); }

    @Test public void successPersistsAndShowsBeforeDeletingOnlyCatalogOldCopies() throws Exception {
        discard(this::saveProgress, this::showPlan, this::guard, this::finish);
        assertEquals(Arrays.asList("save", "show", "external", "private", "finish"), events);
        assertFalse(provider.documents.containsKey(backup.name));
        assertFalse(privateBackup.exists());
        assertTrue(finished);
        assertFalse(progressSaved);
        assertProtectedFiles();
        assertArrayEquals("内存删除后宿主样本仍保留", OLD, Files.readAllBytes(physicalBackup.toPath()));
    }

    @Test public void changedCurrentCatalogFailsBeforeSavingProgressOrDisplaying() throws Exception {
        provider.data.put(output.name, OLD);
        failDiscard(this::saveProgress, this::showPlan, this::guard, this::finish);
        assertTrue(events.isEmpty());
        assertFalse(progressSaved);
        assertOldCopiesPresent();
    }

    @Test public void persistenceFailureNeverDisplaysOrDeletesOldCopies() throws Exception {
        failDiscard(() -> { events.add("save failed"); throw new IOException("持久化失败"); },
                this::showPlan, this::guard, this::finish);
        assertEquals(Collections.singletonList("save failed"), events);
        assertFalse(progressSaved);
        assertFalse(planShown);
        assertOldCopiesPresent();
    }

    @Test public void displayFailureKeepsDurableProgressAndBothOldCopies() throws Exception {
        failDiscard(this::saveProgress,
                () -> { events.add("show failed"); throw new IOException("清单未显示"); },
                this::guard, this::finish);
        assertEquals(Arrays.asList("save", "show failed"), events);
        assertTrue(progressSaved);
        assertFalse(planShown);
        assertOldCopiesPresent();
    }

    @Test public void guardRefusalKeepsProgressAndOldCopies() throws Exception {
        failDiscard(this::saveProgress, this::showPlan,
                () -> { throw new IOException("关联记录已经变化"); }, this::finish);
        assertEquals(Arrays.asList("save", "show"), events);
        assertTrue(progressSaved);
        assertOldCopiesPresent();
    }

    @Test public void currentChangedWhileShowingPlanIsRecheckedBeforeDeletion() throws Exception {
        failDiscard(this::saveProgress, () -> {
            showPlan();
            provider.data.put(output.name, OLD);
        }, this::guard, this::finish);
        assertTrue(progressSaved);
        assertOldCopiesPresent();
    }

    @Test public void providerDeleteFalseKeepsBothOldCopiesAndResumeCanComplete() throws Exception {
        provider.deleteMode = DeleteMode.FALSE;
        failDiscard(this::saveProgress, this::showPlan, this::guard, this::finish);
        assertTrue(progressSaved);
        assertFalse(finished);
        assertTrue(provider.documents.containsKey(backup.name));
        assertTrue(privateBackup.exists());
        provider.deleteMode = DeleteMode.NORMAL;
        BackupCleanup.removeAll(Collections.singletonList(item), true, this::guard);
        finish();
        assertTrue(finished);
        assertFalse(progressSaved);
        assertProtectedFiles();
    }

    @Test public void providerRemovedThenThrewRetainsPrivateUntilExistingResumeChecksAgain() throws Exception {
        provider.deleteMode = DeleteMode.THROW_AFTER_REMOVE;
        failDiscard(this::saveProgress, this::showPlan, this::guard, this::finish);
        assertTrue(progressSaved);
        assertFalse(finished);
        assertFalse(provider.documents.containsKey(backup.name));
        assertTrue(privateBackup.exists());
        BackupCleanup.removeAll(Collections.singletonList(item), true, this::guard);
        finish();
        assertEquals("已消失的外部旧件不得再次删除", 1, provider.deletes);
        assertTrue(finished);
        assertProtectedFiles();
    }

    @Test public void privateDeleteFailureKeepsProgressAndResumeDoesNotDeletePdfCopies() throws Exception {
        privateBackup.allowDelete = false;
        failDiscard(this::saveProgress, this::showPlan, this::guard, this::finish);
        assertTrue(progressSaved);
        assertFalse(finished);
        assertFalse(provider.documents.containsKey(backup.name));
        assertTrue(privateBackup.exists());
        privateBackup.allowDelete = true;
        BackupCleanup.removeAll(Collections.singletonList(item), true, this::guard);
        finish();
        assertEquals(1, provider.deletes);
        assertTrue(finished);
        assertProtectedFiles();
    }

    @Test public void finishFailureLeavesProgressForExistingResumeAfterBothOldCopiesGone() throws Exception {
        failDiscard(this::saveProgress, this::showPlan, this::guard,
                () -> { events.add("finish failed"); throw new IOException("完成记录写入失败"); });
        assertTrue(progressSaved);
        assertFalse(finished);
        assertFalse(provider.documents.containsKey(backup.name));
        assertFalse(privateBackup.exists());
        BackupCleanup.removeAll(Collections.singletonList(item), true, this::guard);
        finish();
        assertEquals(1, provider.deletes);
        assertEquals(1, privateBackup.deletes);
        assertTrue(finished);
        assertProtectedFiles();
    }

    @Test public void missingRequiredStepIsRejectedBeforeAnyProgressOrDeletion() throws Exception {
        failDiscard(this::saveProgress, null, this::guard, this::finish);
        assertTrue(events.isEmpty());
        assertFalse(progressSaved);
        assertOldCopiesPresent();
        try {
            CatalogReplacementCleanup.discard(null, this::saveProgress, this::showPlan,
                    this::guard, this::finish);
            fail("没有关联目录项时必须拒绝");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("缺少")); }
        assertOldCopiesPresent();
    }

    private void discard(CatalogReplacementCleanup.Action save,
                         CatalogReplacementCleanup.Action show,
                         BackupCleanup.Guard check,
                         CatalogReplacementCleanup.Action complete) throws IOException {
        CatalogReplacementCleanup.discard(item, save, show, check, complete);
    }

    private void failDiscard(CatalogReplacementCleanup.Action save,
                             CatalogReplacementCleanup.Action show,
                             BackupCleanup.Guard check,
                             CatalogReplacementCleanup.Action complete) throws Exception {
        try { discard(save, show, check, complete); fail("失败后不得报告目录旧件删除完成"); }
        catch (IOException expected) { assertFalse(finished); }
    }

    private void saveProgress() {
        assertEquals(0, provider.deletes);
        assertEquals(0, privateBackup.deletes);
        progressSaved = true;
        events.add("save");
    }

    private void showPlan() {
        assertTrue("清单显示之前必须已有可继续的持久记录", progressSaved);
        assertEquals(0, provider.deletes);
        assertEquals(0, privateBackup.deletes);
        planShown = true;
        events.add("show");
    }

    private void guard() { assertTrue(progressSaved); assertTrue(planShown); }

    private void finish() {
        assertTrue(progressSaved);
        assertFalse(provider.documents.containsKey(backup.name));
        assertFalse(privateBackup.exists());
        assertArrayEquals(NEW, provider.data.get(output.name));
        finished = true;
        progressSaved = false;
        events.add("finish");
    }

    private void assertOldCopiesPresent() {
        assertTrue(provider.documents.containsKey(backup.name));
        assertTrue(privateBackup.exists());
        assertEquals(0, provider.deletes);
        assertEquals(0, privateBackup.deletes);
        assertFalse(finished);
    }

    private void assertProtectedFiles() throws Exception {
        assertArrayEquals(NEW, provider.data.get(output.name));
        assertArrayEquals(PDF, provider.data.get(PDF_NAME));
        assertArrayEquals(PDF_BACKUP, provider.data.get(PDF_BACKUP_NAME));
        assertArrayEquals(PDF_BACKUP, Files.readAllBytes(unrelatedPrivatePdf.toPath()));
    }

    private enum DeleteMode { NORMAL, FALSE, THROW_AFTER_REMOVE }

    private final class MemoryFile extends File {
        boolean present = true, allowDelete = true;
        int deletes;

        MemoryFile(File file) { super(file.getPath()); }
        @Override public boolean exists() { return present && super.exists(); }
        @Override public boolean isFile() { return present && super.isFile(); }
        @Override public boolean delete() {
            assertTrue(progressSaved);
            assertTrue(planShown);
            assertFalse("外部旧目录未消失时不得删除内部目录备份", provider.documents.containsKey(backup.name));
            assertArrayEquals(NEW, provider.data.get(output.name));
            deletes++;
            if (!allowDelete) return false;
            events.add("private");
            present = false;
            return true;
        }
    }

    private final class MemoryProvider implements BackupCleanup.Provider {
        final Map<String, StagedReplacement.Document> documents = new HashMap<>();
        final Map<String, byte[]> data = new HashMap<>();
        int deletes;
        DeleteMode deleteMode = DeleteMode.NORMAL;

        void add(StagedReplacement.Document document, byte[] bytes) {
            documents.put(document.name, document);
            data.put(document.name, bytes);
        }

        @Override public List<StagedReplacement.Document> find(String exactName) {
            StagedReplacement.Document document = documents.get(exactName);
            return document == null ? Collections.emptyList() : Collections.singletonList(document);
        }

        @Override public InputStream openRead(StagedReplacement.Document document) {
            return new ByteArrayInputStream(data.get(document.name));
        }

        @Override public boolean delete(StagedReplacement.Document document) throws IOException {
            assertTrue(progressSaved);
            assertTrue(planShown);
            assertEquals("只能删本次旧目录关联项", backup.uri, document.uri);
            assertEquals(backup.name, document.name);
            assertTrue(privateBackup.exists());
            assertArrayEquals(NEW, data.get(output.name));
            deletes++;
            if (deleteMode == DeleteMode.FALSE) return false;
            events.add("external");
            documents.remove(document.name);
            data.remove(document.name);
            if (deleteMode == DeleteMode.THROW_AFTER_REMOVE) throw new IOException("目录旧件已删但提供者抛异常");
            return true;
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            value.append(String.format("%02x", part & 255));
        }
        return value.toString();
    }

    private static File fixtureDirectory() {
        try {
            File folder = Files.createTempDirectory("catalog-replacement-cleanup-tests-").toFile();
            System.out.println("CatalogReplacementCleanupTest fixtures retained: " + folder.getAbsolutePath());
            return folder;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
