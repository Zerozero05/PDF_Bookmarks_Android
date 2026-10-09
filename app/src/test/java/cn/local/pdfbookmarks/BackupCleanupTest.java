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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 内存 Provider 故障回归，不执行任何真实文档删除，也不声称设备验证。 */
public final class BackupCleanupTest {
    private static final byte[] OLD = "old PDF".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "new PDF with bookmarks".getBytes(StandardCharsets.UTF_8);
    private static final File FIXTURES = fixtureDirectory();
    private StagedReplacement.Document output;
    private StagedReplacement.Document backup;
    private MemoryProvider provider;
    private File privateBackup;

    @Before public void before() throws Exception {
        output = new StagedReplacement.Document("content://test/new", "原书.pdf");
        backup = new StagedReplacement.Document("content://test/old", "原件备份.pdf");
        provider = new MemoryProvider(output, backup);
        privateBackup = File.createTempFile("private-backup-", ".pdf", FIXTURES);
        Files.write(privateBackup.toPath(), OLD);
    }

    @After public void after() {
        Thread.interrupted();
        // 本轮不清理验证产物；fixture 路径在测试日志中保留。
    }

    @Test public void successOnlyDeletesOldBackupAndRetainsNewAndPrivateCopies() throws Exception {
        assertTrue(remove());
        assertEquals(1, provider.deletes);
        assertEquals(backup.uri, provider.deletedUri);
        assertArrayEquals(NEW, provider.data.get(output.name));
        assertArrayEquals(OLD, Files.readAllBytes(privateBackup.toPath()));
        assertFalse(provider.documents.containsKey(backup.name));
    }

    @Test public void alreadyAbsentRetryCompletesWithoutAnotherDelete() throws Exception {
        provider.erase(backup.name);
        assertFalse(remove());
        assertEquals(0, provider.deletes);
        assertTrue(provider.outputReads >= 2);
        assertArrayEquals(NEW, provider.data.get(output.name));
    }

    @Test public void alreadyAbsentStillRejectsChangedNewPdf() throws Exception {
        provider.erase(backup.name);
        provider.data.put(output.name, OLD);
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void absentQueryChangingNewPdfCannotBeReportedComplete() throws Exception {
        provider.erase(backup.name);
        provider.changeOutputOnAbsentQuery = true;
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void backupDisappearingAtSecondQueryStillRequiresFreshNewPdf() throws Exception {
        provider.eraseOnSecondBackupQuery = true;
        provider.changeOutputOnAbsentQuery = true;
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void invalidPrivateBackupPreventsDeletingExistingOrAbsentOldBackup() throws Exception {
        Files.write(privateBackup.toPath(), NEW);
        failRemove();
        provider.erase(backup.name);
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void missingPrivateBackupPreventsDeletion() throws Exception {
        privateBackup = new File(FIXTURES, "does-not-exist.pdf");
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void duplicateNewOrBackupNamePreventsDeletion() throws Exception {
        provider.duplicate = output.name;
        failRemove();
        provider.duplicate = backup.name;
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void wrongOldOrNewHashPreventsDeletion() throws Exception {
        provider.data.put(backup.name, NEW);
        failRemove();
        provider.data.put(backup.name, OLD);
        provider.data.put(output.name, OLD);
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void changedUriWithSameContentIsConservativelyRejected() throws Exception {
        provider.documents.put(backup.name,
                new StagedReplacement.Document("content://test/recreated-old", backup.name));
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void aliasedNewAndOldIdentityOrNameCannotBeDeleted() throws Exception {
        backup = new StagedReplacement.Document(output.uri, backup.name);
        failRemove();
        backup = new StagedReplacement.Document("content://test/old", output.name);
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void privateFileAliasIsNeverDeletedAsExternalBackup() throws Exception {
        backup = new StagedReplacement.Document(privateBackup.toURI().toString(), backup.name);
        failRemove();
        assertEquals(0, provider.deletes);
        assertArrayEquals(OLD, Files.readAllBytes(privateBackup.toPath()));
    }

    @Test public void fileUriAliasesOfSameExternalFileAreRejected() throws Exception {
        File external = File.createTempFile("external-file-", ".pdf", FIXTURES);
        Files.write(external.toPath(), OLD);
        output = new StagedReplacement.Document(external.toURI().toString(), output.name);
        backup = new StagedReplacement.Document(
                new File(external.getParentFile(), "." + File.separator + external.getName())
                        .toURI().toString(), backup.name);
        failRemove();
        assertEquals(0, provider.deletes);
        assertArrayEquals(OLD, Files.readAllBytes(external.toPath()));
    }

    @Test public void permissionFailureDuringReadPreventsDelete() throws Exception {
        provider.denyRead = true;
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void permissionFailureDuringDeleteIsReportedAndKeepsFallback() throws Exception {
        provider.mode = DeleteMode.SECURITY;
        failRemove();
        assertEquals(1, provider.deletes);
        assertTrue(provider.documents.containsKey(backup.name));
        assertArrayEquals(OLD, Files.readAllBytes(privateBackup.toPath()));
    }

    @Test public void deleteFalseCannotBeReportedSuccessful() throws Exception {
        provider.mode = DeleteMode.FALSE;
        failRemove();
        assertTrue(provider.documents.containsKey(backup.name));
        assertArrayEquals(NEW, provider.data.get(output.name));
    }

    @Test public void trueButNoOpDeleteFailsPostVerification() throws Exception {
        provider.mode = DeleteMode.NO_OP;
        failRemove();
        assertTrue(provider.documents.containsKey(backup.name));
    }

    @Test public void falseOrThrowAfterActualRemovalRequiresSeparateRetry() throws Exception {
        for (DeleteMode mode : new DeleteMode[] {
                DeleteMode.FALSE_AFTER_REMOVE, DeleteMode.THROW_AFTER_REMOVE}) {
            provider = new MemoryProvider(output, backup);
            provider.mode = mode;
            failRemove();
            assertFalse(provider.documents.containsKey(backup.name));
            assertEquals(1, provider.deletes);
            assertFalse(remove());
            assertEquals(1, provider.deletes);
        }
    }

    @Test public void changedNewPdfImmediatelyBeforeDeleteIsRejected() throws Exception {
        provider.changeOutputOnSecondRead = true;
        failRemove();
        assertEquals(0, provider.deletes);
        assertTrue(provider.documents.containsKey(backup.name));
    }

    @Test public void changedOldBackupImmediatelyBeforeDeleteIsRejected() throws Exception {
        provider.changeBackupOnSecondRead = true;
        failRemove();
        assertEquals(0, provider.deletes);
    }

    @Test public void deletedButPostVerificationFailureNeverReportsSuccess() throws Exception {
        provider.mode = DeleteMode.CORRUPT_NEW_AFTER_REMOVE;
        failRemove();
        assertFalse(provider.documents.containsKey(backup.name));
        assertArrayEquals(OLD, Files.readAllBytes(privateBackup.toPath()));
        failRemove();
        assertEquals(1, provider.deletes);
    }

    @Test public void wrongFileDeletedByProviderCannotPassNewPdfGate() throws Exception {
        provider.mode = DeleteMode.REMOVE_BOTH;
        failRemove();
        assertFalse(provider.documents.containsKey(output.name));
        assertArrayEquals(OLD, Files.readAllBytes(privateBackup.toPath()));
    }

    @Test public void interruptedBeforeCallDoesNotDelete() throws Exception {
        Thread.currentThread().interrupt();
        failRemove();
        assertEquals(0, provider.deletes);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void interruptedAfterDeleteNeedsLaterAbsentRetry() throws Exception {
        provider.mode = DeleteMode.INTERRUPT_AFTER_REMOVE;
        failRemove();
        assertEquals(1, provider.deletes);
        assertTrue(Thread.interrupted());
        assertFalse(remove());
        assertEquals(1, provider.deletes);
    }

    @Test public void unifiedCleanupRemovesAllOldCopiesAndKeepsOutputs() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        BackupCleanup.removeAll(Arrays.asList(first, second), false, () -> { });
        for (BackupCleanup.Item item : Arrays.asList(first, second)) {
            MemoryProvider source = (MemoryProvider) item.provider;
            assertEquals(1, source.deletes);
            assertArrayEquals(NEW, source.data.get(item.output.name));
            assertFalse(item.privateBackup.exists());
            assertEquals(1, ((MemoryFile) item.privateBackup).deletes);
            // 模拟删除只改变内存状态；宿主验证样本原件仍保留。
            assertArrayEquals(OLD, Files.readAllBytes(new File(item.privateBackup.getPath()).toPath()));
        }
    }

    @Test public void wholePlanPrivateHashFailurePreventsAnyDeletion() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        Files.write(second.privateBackup.toPath(), NEW);
        failBatch(Arrays.asList(first, second), false, () -> { });
        assertEquals(0, provider.deletes);
        assertEquals(0, ((MemoryProvider) second.provider).deletes);
        assertTrue(first.privateBackup.exists());
    }

    @Test public void wholePlanChangedOutputPreventsAnyDeletion() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        ((MemoryProvider) second.provider).data.put(second.output.name, OLD);
        failBatch(Arrays.asList(first, second), false, () -> { });
        assertEquals(0, provider.deletes);
        assertTrue(first.privateBackup.exists());
    }

    @Test public void wholePlanDuplicateOrChangedOldIdentityPreventsAnyDeletion() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        MemoryProvider other = (MemoryProvider) second.provider;
        other.duplicate = second.backup.name;
        failBatch(Arrays.asList(first, second), false, () -> { });
        other.duplicate = null;
        other.documents.put(second.backup.name,
                new StagedReplacement.Document("content://test/recreated", second.backup.name));
        failBatch(Arrays.asList(first, second), false, () -> { });
        assertEquals(0, provider.deletes);
        assertEquals(0, other.deletes);
    }

    @Test public void preflightOnlyDoesNotDeleteAnyCopy() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.verifyAll(Arrays.asList(first, secondItem()), false);
        assertEquals(0, provider.deletes);
        assertTrue(first.privateBackup.exists());
        assertEquals(0, ((MemoryFile) first.privateBackup).deletes);
    }

    @Test public void externalPartialFailurePreservesEveryPrivateCopyAndCanResume() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        MemoryProvider other = (MemoryProvider) second.provider;
        other.mode = DeleteMode.FALSE;
        List<BackupCleanup.Item> plan = Arrays.asList(first, second);
        failBatch(plan, false, () -> { });
        assertFalse(provider.documents.containsKey(backup.name));
        assertTrue(other.documents.containsKey(second.backup.name));
        assertTrue(first.privateBackup.exists());
        assertTrue(second.privateBackup.exists());
        other.mode = DeleteMode.NORMAL;
        BackupCleanup.removeAll(plan, true, () -> { });
        assertEquals(1, provider.deletes);
        assertFalse(first.privateBackup.exists());
        assertFalse(second.privateBackup.exists());
    }

    @Test public void externalRemovalWithoutConfirmationDoesNotDeletePrivateCopy() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        provider.mode = DeleteMode.THROW_AFTER_REMOVE;
        failBatch(Arrays.asList(first), false, () -> { });
        assertTrue(first.privateBackup.exists());
        assertEquals(0, ((MemoryFile) first.privateBackup).deletes);
        BackupCleanup.removeAll(Arrays.asList(first), true, () -> { });
        assertFalse(first.privateBackup.exists());
        assertEquals(1, provider.deletes);
    }

    @Test public void allExternalCopiesAreAbsentBeforeFirstPrivateDelete() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        ((MemoryFile) first.privateBackup).beforeDelete = () -> {
            assertFalse(provider.documents.containsKey(backup.name));
            assertFalse(((MemoryProvider) second.provider).documents.containsKey(second.backup.name));
            assertTrue(second.privateBackup.exists());
        };
        BackupCleanup.removeAll(Arrays.asList(first, second), false, () -> { });
    }

    @Test public void interruptedPrivateDeletionCanResumeWithoutRepeatingExternalDeletes() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        MemoryFile original = (MemoryFile) first.privateBackup;
        original.mode = DeleteMode.INTERRUPT_AFTER_REMOVE;
        List<BackupCleanup.Item> plan = Arrays.asList(first, second);
        failBatch(plan, false, () -> { });
        assertTrue(Thread.interrupted());
        assertFalse(original.exists());
        assertTrue(second.privateBackup.exists());
        failBatch(plan, false, () -> { });
        BackupCleanup.removeAll(plan, true, () -> { });
        assertEquals(1, provider.deletes);
        assertEquals(1, ((MemoryProvider) second.provider).deletes);
        assertEquals(1, original.deletes);
        assertFalse(second.privateBackup.exists());
    }

    @Test public void unconfirmedPrivateDeletionIsNotReportedCompleteAndRetryChecksOutputs() throws Exception {
        for (DeleteMode mode : new DeleteMode[] {
                DeleteMode.FALSE_AFTER_REMOVE, DeleteMode.THROW_AFTER_REMOVE}) {
            BackupCleanup.Item item = secondItem();
            MemoryFile original = (MemoryFile) item.privateBackup;
            original.mode = mode;
            failBatch(Arrays.asList(item), false, () -> { });
            assertFalse(original.exists());
            ((MemoryProvider) item.provider).data.put(item.output.name, OLD);
            failBatch(Arrays.asList(item), true, () -> { });
            ((MemoryProvider) item.provider).data.put(item.output.name, NEW);
            BackupCleanup.removeAll(Arrays.asList(item), true, () -> { });
            assertEquals(1, original.deletes);
        }
    }

    @Test public void privateDeleteFalseOrNoOpIsFailureAndRecordCanRetry() throws Exception {
        for (DeleteMode mode : new DeleteMode[] {DeleteMode.FALSE, DeleteMode.NO_OP}) {
            BackupCleanup.Item item = secondItem();
            MemoryFile original = (MemoryFile) item.privateBackup;
            original.mode = mode;
            failBatch(Arrays.asList(item), false, () -> { });
            assertTrue(original.exists());
            original.mode = DeleteMode.NORMAL;
            BackupCleanup.removeAll(Arrays.asList(item), true, () -> { });
            assertFalse(original.exists());
        }
    }

    @Test public void resumedMissingPrivateStillRejectsExistingExternalCopy() throws Exception {
        BackupCleanup.Item item = batchItem(provider, output, backup, privateBackup);
        ((MemoryFile) item.privateBackup).present = false;
        failBatch(Arrays.asList(item), true, () -> { });
        assertEquals(0, provider.deletes);
        provider.erase(backup.name);
        BackupCleanup.removeAll(Arrays.asList(item), true, () -> { });
        assertEquals(0, ((MemoryFile) item.privateBackup).deletes);
    }

    @Test public void freshCleanupDoesNotAcceptMissingPrivateEvenIfExternalAbsent() throws Exception {
        BackupCleanup.Item item = batchItem(provider, output, backup, privateBackup);
        provider.erase(backup.name);
        ((MemoryFile) item.privateBackup).present = false;
        failBatch(Arrays.asList(item), false, () -> { });
        assertEquals(0, provider.deletes);
    }

    @Test public void completedResumeIsNoOpButStillChecksEveryCurrentOutput() throws Exception {
        BackupCleanup.Item item = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.removeAll(Arrays.asList(item), false, () -> { });
        BackupCleanup.removeAll(Arrays.asList(item), true, () -> { });
        assertEquals(1, provider.deletes);
        assertEquals(1, ((MemoryFile) item.privateBackup).deletes);
        provider.data.put(output.name, OLD);
        failBatch(Arrays.asList(item), true, () -> { });
    }

    @Test public void crossItemOutputCannotBeAnotherItemsDeletionTarget() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item other = secondItem();
        BackupCleanup.Item alias = batchItem((MemoryProvider) other.provider, other.output,
                new StagedReplacement.Document(output.uri, other.backup.name), other.privateBackup);
        failBatch(Arrays.asList(first, alias), false, () -> { });
        assertEquals(0, provider.deletes);
    }

    @Test public void crossItemPrivateAliasAndDuplicatePlanAreRejected() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        failBatch(Arrays.asList(first, first), false, () -> { });
        BackupCleanup.Item other = secondItem();
        BackupCleanup.Item alias = new BackupCleanup.Item(other.provider, other.output,
                other.backup, hash(NEW), hash(OLD), first.privateBackup);
        failBatch(Arrays.asList(first, alias), false, () -> { });
        assertEquals(0, provider.deletes);
    }

    @Test public void crossItemPrivateBackupCannotAliasAProtectedFileUri() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item other = secondItem();
        BackupCleanup.Item alias = new BackupCleanup.Item(other.provider,
                new StagedReplacement.Document(privateBackup.toURI().toString(), other.output.name),
                other.backup, hash(NEW), hash(OLD), other.privateBackup);
        failBatch(Arrays.asList(first, alias), false, () -> { });
        assertEquals(0, provider.deletes);
    }

    @Test public void crossItemHardLinkAliasIsRejectedBeforeDeletion() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        File linked = new File(FIXTURES, privateBackup.getName() + "-link");
        Files.createLink(linked.toPath(), privateBackup.toPath());
        BackupCleanup.Item other = secondItem();
        BackupCleanup.Item alias = new BackupCleanup.Item(other.provider,
                new StagedReplacement.Document(linked.toURI().toString(), other.output.name),
                other.backup, hash(NEW), hash(OLD), other.privateBackup);
        failBatch(Arrays.asList(first, alias), false, () -> { });
        assertEquals(0, provider.deletes);
        assertArrayEquals(OLD, Files.readAllBytes(linked.toPath()));
    }

    @Test public void changedSecondOutputAfterFirstExternalDeletePreservesAllPrivateCopies() throws Exception {
        BackupCleanup.Item first = batchItem(provider, output, backup, privateBackup);
        BackupCleanup.Item second = secondItem();
        failBatch(Arrays.asList(first, second), false, () -> {
            if (provider.deletes != 0) ((MemoryProvider) second.provider).data.put(second.output.name, OLD);
        });
        assertEquals(1, provider.deletes);
        assertEquals(0, ((MemoryProvider) second.provider).deletes);
        assertTrue(first.privateBackup.exists());
        assertTrue(second.privateBackup.exists());
    }

    @Test public void guardFailureBeforeAnyDeleteRetainsAllCopies() throws Exception {
        BackupCleanup.Item item = batchItem(provider, output, backup, privateBackup);
        failBatch(Arrays.asList(item), false, () -> { throw new IOException("成功记录已变化"); });
        assertEquals(0, provider.deletes);
        assertTrue(item.privateBackup.exists());
    }

    @Test public void recreatedExternalCopyPreventsPrivateDeletion() throws Exception {
        BackupCleanup.Item item = batchItem(provider, output, backup, privateBackup);
        failBatch(Arrays.asList(item), false, () -> {
            if (provider.deletes != 0) {
                provider.documents.put(backup.name, backup);
                provider.data.put(backup.name, OLD);
            }
        });
        assertEquals(1, provider.deletes);
        assertTrue(item.privateBackup.exists());
        assertEquals(0, ((MemoryFile) item.privateBackup).deletes);
    }

    @Test public void interruptedUnifiedCleanupBeforeCallPerformsNoDelete() throws Exception {
        BackupCleanup.Item item = batchItem(provider, output, backup, privateBackup);
        Thread.currentThread().interrupt();
        failBatch(Arrays.asList(item), false, () -> { });
        assertEquals(0, provider.deletes);
        assertTrue(item.privateBackup.exists());
    }

    private static BackupCleanup.Item batchItem(MemoryProvider source,
            StagedReplacement.Document current, StagedReplacement.Document old, File original) throws Exception {
        return new BackupCleanup.Item(source, current, old, hash(NEW), hash(OLD),
                original instanceof MemoryFile ? original : new MemoryFile(original));
    }

    private static BackupCleanup.Item secondItem() throws Exception {
        StagedReplacement.Document current = new StagedReplacement.Document("content://test/catalog", "catalog.json");
        StagedReplacement.Document old = new StagedReplacement.Document("content://test/old-catalog", "catalog-backup.json");
        File original = File.createTempFile("private-catalog-", ".json", FIXTURES);
        Files.write(original.toPath(), OLD);
        return batchItem(new MemoryProvider(current, old), current, old, original);
    }

    private static void failBatch(List<BackupCleanup.Item> plan, boolean resume,
                                  BackupCleanup.Guard guard) throws Exception {
        try { BackupCleanup.removeAll(plan, resume, guard); fail("必须保留统一清理记录并报告未确认"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("未确认")); }
    }

    private boolean remove() throws Exception {
        return BackupCleanup.remove(provider, output, backup, hash(NEW), hash(OLD), privateBackup);
    }

    private void failRemove() throws Exception {
        try { remove(); fail("必须报告未确认，不清除记录"); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未确认"));
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 255));
        }
        return result.toString();
    }

    private static File fixtureDirectory() {
        try {
            File directory = Files.createTempDirectory("pdf-backup-cleanup-tests-").toFile();
            System.out.println("BackupCleanupTest fixtures retained: " + directory.getAbsolutePath());
            return directory;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private enum DeleteMode {
        NORMAL, FALSE, SECURITY, NO_OP, FALSE_AFTER_REMOVE, THROW_AFTER_REMOVE,
        CORRUPT_NEW_AFTER_REMOVE, REMOVE_BOTH, INTERRUPT_AFTER_REMOVE
    }

    /** 删除故障只在内存模拟，保留宿主上全部原始测试样本。 */
    private static final class MemoryFile extends File {
        boolean present = true;
        int deletes;
        DeleteMode mode = DeleteMode.NORMAL;
        Runnable beforeDelete;

        MemoryFile(File original) { super(original.getPath()); }

        @Override public boolean exists() { return present && super.exists(); }
        @Override public boolean isFile() { return present && super.isFile(); }
        @Override public boolean delete() {
            deletes++;
            if (beforeDelete != null) beforeDelete.run();
            if (mode == DeleteMode.SECURITY) throw new SecurityException("应用内备份删除被拒绝");
            if (mode == DeleteMode.FALSE) return false;
            if (mode == DeleteMode.NO_OP) return true;
            present = false;
            if (mode == DeleteMode.FALSE_AFTER_REMOVE) return false;
            if (mode == DeleteMode.THROW_AFTER_REMOVE) throw new IllegalStateException("已删但调用抛异常");
            if (mode == DeleteMode.INTERRUPT_AFTER_REMOVE) Thread.currentThread().interrupt();
            return true;
        }
    }

    private static final class MemoryProvider implements BackupCleanup.Provider {
        final StagedReplacement.Document output, backup;
        final Map<String, StagedReplacement.Document> documents = new HashMap<>();
        final Map<String, byte[]> data = new HashMap<>();
        int deletes, outputReads, backupReads, backupQueries;
        String deletedUri, duplicate;
        boolean denyRead, changeOutputOnAbsentQuery, eraseOnSecondBackupQuery;
        boolean changeOutputOnSecondRead, changeBackupOnSecondRead;
        DeleteMode mode = DeleteMode.NORMAL;

        MemoryProvider(StagedReplacement.Document output, StagedReplacement.Document backup) {
            this.output = output;
            this.backup = backup;
            documents.put(output.name, output);
            documents.put(backup.name, backup);
            data.put(output.name, NEW);
            data.put(backup.name, OLD);
        }

        void erase(String name) { documents.remove(name); data.remove(name); }

        @Override public List<StagedReplacement.Document> find(String name) {
            if (name.equals(backup.name)) {
                backupQueries++;
                if (eraseOnSecondBackupQuery && backupQueries == 2) erase(name);
                if (!documents.containsKey(name) && changeOutputOnAbsentQuery) {
                    data.put(output.name, OLD);
                }
            }
            List<StagedReplacement.Document> result = new ArrayList<>();
            if (documents.containsKey(name)) result.add(documents.get(name));
            if (documents.containsKey(name) && name.equals(duplicate)) result.add(documents.get(name));
            return result;
        }

        @Override public InputStream openRead(StagedReplacement.Document document) {
            if (denyRead) throw new SecurityException("读取权限丢失");
            if (document.name.equals(output.name)) {
                outputReads++;
                if (changeOutputOnSecondRead && outputReads == 2) data.put(document.name, OLD);
            } else {
                backupReads++;
                if (changeBackupOnSecondRead && backupReads == 2) data.put(document.name, NEW);
            }
            return new ByteArrayInputStream(data.get(document.name));
        }

        @Override public boolean delete(StagedReplacement.Document document) throws IOException {
            deletes++;
            deletedUri = document.uri;
            assertEquals("只能删除记录对应的旧备份", backup.uri, document.uri);
            assertEquals(backup.name, document.name);
            if (mode == DeleteMode.SECURITY) throw new SecurityException("删除权限丢失");
            if (mode == DeleteMode.FALSE) return false;
            if (mode == DeleteMode.NO_OP) return true;
            erase(backup.name);
            if (mode == DeleteMode.FALSE_AFTER_REMOVE) return false;
            if (mode == DeleteMode.THROW_AFTER_REMOVE) throw new IOException("已删但调用抛异常");
            if (mode == DeleteMode.CORRUPT_NEW_AFTER_REMOVE) data.put(output.name, OLD);
            if (mode == DeleteMode.REMOVE_BOTH) erase(output.name);
            if (mode == DeleteMode.INTERRUPT_AFTER_REMOVE) Thread.currentThread().interrupt();
            return true;
        }
    }
}
