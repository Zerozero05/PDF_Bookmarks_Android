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
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

/** 流目标模拟器会真实截断旧内容；宿主验证不能代替 Android 文档提供方实测。 */
public class SafeReplacementTest {
    private static final byte[] ORIGINAL = bytes("ORIGINAL PDF CONTENT LONGER THAN OUTPUT");
    private static final byte[] OUTPUT = bytes("VERIFIED PDF");
    private Path directory;
    private File backup, output;
    private String expectedHash;

    @Before public void prepare() throws Exception {
        directory = Files.createTempDirectory("pdf-safe-replacement-test-");
        backup = directory.resolve("original-backup.pdf").toFile();
        output = directory.resolve("verified-output.pdf").toFile();
        Files.write(backup.toPath(), ORIGINAL);
        Files.write(output.toPath(), OUTPUT);
        expectedHash = SafeReplacement.sha256(backup);
    }

    @After public void cleanup() throws Exception {
        Thread.interrupted();
        // 仅删除本测试逐个创建的精确路径，不递归清空任何共享目录。
        if (output != null) Files.deleteIfExists(output.toPath());
        if (backup != null) Files.deleteIfExists(backup.toPath());
        if (directory != null) Files.deleteIfExists(directory);
    }

    @Test public void successTruncatesClosesAndRereadsTarget() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        SafeReplacement.replace(target, output, backup, expectedHash);
        assertArrayEquals(OUTPUT, target.data);
        assertEquals(1, target.writes);
        assertEquals(2, target.reads);
        assertEquals(1, target.closedWrites);
        assertTrue(backup.isFile());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(backup.toPath()));
    }

    @Test public void changedOriginalIsRejectedBeforeAnyTruncate() throws Exception {
        FakeTarget target = new FakeTarget(bytes("EXTERNALLY CHANGED PDF"));
        IOException error = fails(() -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertFalse(error instanceof SafeReplacement.ReplacementException);
        assertTrue(error.getMessage().contains("已变化"));
        assertEquals(0, target.writes);
        assertArrayEquals(bytes("EXTERNALLY CHANGED PDF"), target.data);
    }

    @Test public void inconsistentBackupAndMissingOutputAreRejectedBeforeWriting() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        Files.write(backup.toPath(), bytes("WRONG BACKUP"));
        fails(() -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertEquals(0, target.writes);
        Files.write(backup.toPath(), ORIGINAL);
        Files.delete(output.toPath());
        fails(() -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertEquals(0, target.writes);
        assertArrayEquals(ORIGINAL, target.data);
    }

    @Test public void sameFileOutputBackupIsRejected() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        IOException error = fails(() -> SafeReplacement.replace(target, backup, backup, expectedHash));
        assertTrue(error.getMessage().contains("同一个文件"));
        assertEquals(0, target.writes);
    }

    @Test public void hardLinkAliasIsRejected() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        Files.delete(output.toPath());
        Files.createLink(output.toPath(), backup.toPath());
        IOException error = fails(() -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.getMessage().contains("同一个文件"));
        assertEquals(0, target.writes);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(backup.toPath()));
    }

    @Test public void partialWriteFailureRestoresBackupAndStillReportsFailure() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.partialFailureOn = 1;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.originalRestored);
        assertTrue(error.getMessage().contains("已从备份恢复"));
        assertArrayEquals(ORIGINAL, target.data);
        assertEquals(2, target.writes);
        assertTrue(backup.isFile());
    }

    @Test public void runtimeProviderFailureAfterWritingAlsoRestores() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.runtimeFailureOn = 1;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.originalRestored);
        assertTrue(error.getCause() instanceof SecurityException);
        assertArrayEquals(ORIGINAL, target.data);
        assertEquals(2, target.writes);
    }

    @Test public void exceptionAfterOpenWriteTruncationAlsoRestores() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.throwOnOpenAfterTruncate = 1;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.originalRestored);
        assertArrayEquals(ORIGINAL, target.data);
        assertEquals(2, target.writes);
    }

    @Test public void closeSyncFailureRestores() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.closeFailureOn = 1;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.originalRestored);
        assertArrayEquals(ORIGINAL, target.data);
    }

    @Test public void incorrectRereadRestoresOriginal() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.corruptReadOn = 2;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.originalRestored);
        assertArrayEquals(ORIGINAL, target.data);
        assertEquals(3, target.reads);
        assertEquals(2, target.writes);
    }

    @Test public void recoveryFailureKeepsBackupAndCanBeRecoveredExplicitly() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.partialFailureOn = 1;
        target.recoveryAlwaysFails = true;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertFalse(error.originalRestored);
        assertTrue(error.getMessage().contains("需要恢复"));
        assertTrue(error.getMessage().contains(backup.getAbsolutePath()));
        assertTrue(backup.isFile());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(backup.toPath()));
        target.recoveryAlwaysFails = false;
        SafeReplacement.recover(target, backup);
        assertArrayEquals(ORIGINAL, target.data);
        assertTrue(backup.isFile());
    }

    @Test public void interruptedFailedWriteStillCompletesRollbackAndRestoresInterrupt() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        target.interruptFailureOn = 1;
        SafeReplacement.ReplacementException error = replacementFails(
                () -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertTrue(error.originalRestored);
        assertArrayEquals(ORIGINAL, target.data);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void explicitRecoveryIgnoresExistingInterrupt() throws Exception {
        FakeTarget target = new FakeTarget(bytes("PARTIAL PDF"));
        Thread.currentThread().interrupt();
        SafeReplacement.recover(target, backup);
        assertArrayEquals(ORIGINAL, target.data);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test public void cancellationBeforeWriteLeavesTargetUntouched() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        Thread.currentThread().interrupt();
        IOException error = fails(() -> SafeReplacement.replace(target, output, backup, expectedHash));
        assertFalse(error instanceof SafeReplacement.ReplacementException);
        assertEquals(0, target.writes);
        assertArrayEquals(ORIGINAL, target.data);
    }

    @Test public void invalidPreviewHashLeavesTargetUntouched() throws Exception {
        FakeTarget target = new FakeTarget(ORIGINAL);
        fails(() -> SafeReplacement.replace(target, output, backup, "not-a-hash"));
        assertEquals(0, target.writes);
        assertArrayEquals(ORIGINAL, target.data);
    }

    private interface Action { void run() throws IOException; }
    private static IOException fails(Action action) {
        try { action.run(); fail("预期安全门禁或覆盖失败"); }
        catch (IOException expected) { return expected; }
        throw new AssertionError();
    }
    private static SafeReplacement.ReplacementException replacementFails(Action action) {
        IOException error = fails(action);
        assertTrue(error instanceof SafeReplacement.ReplacementException);
        return (SafeReplacement.ReplacementException) error;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class FakeTarget implements SafeReplacement.Target {
        byte[] data;
        int reads, writes, closedWrites;
        int partialFailureOn, runtimeFailureOn, throwOnOpenAfterTruncate, closeFailureOn, corruptReadOn, interruptFailureOn;
        boolean recoveryAlwaysFails;
        FakeTarget(byte[] data) { this.data = Arrays.copyOf(data, data.length); }

        public InputStream openRead() throws IOException {
            reads++;
            if (Thread.currentThread().isInterrupted()) throw new IOException("模拟中断拒绝读取");
            return new ByteArrayInputStream(reads == corruptReadOn ? bytes("INCORRECT REREAD") : data);
        }

        public OutputStream openWrite() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("模拟中断拒绝写入");
            int operation = ++writes;
            data = new byte[0]; // openWrite 合同要求真实截断，不能保留旧文件尾部。
            if (operation == throwOnOpenAfterTruncate || (recoveryAlwaysFails && operation >= 2)) {
                throw new IOException("模拟截断后写入流打开失败");
            }
            return new OutputStream() {
                final ByteArrayOutputStream stream = new ByteArrayOutputStream();
                public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    if (operation == partialFailureOn || operation == interruptFailureOn || operation == runtimeFailureOn) {
                        stream.write(buffer, offset, Math.min(3, length));
                        data = stream.toByteArray();
                        if (operation == interruptFailureOn) Thread.currentThread().interrupt();
                        if (operation == runtimeFailureOn) throw new SecurityException("模拟提供方写入后撤销权限");
                        throw new IOException("模拟写入部分内容后失败");
                    }
                    stream.write(buffer, offset, length);
                    data = stream.toByteArray();
                }
                public void close() throws IOException {
                    closedWrites++;
                    if (operation == closeFailureOn) throw new IOException("模拟关闭时文件同步失败");
                }
            };
        }
    }
}
