package cn.local.pdfbookmarks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** 已验证 PDF 输出的同 URI 覆盖；持久备份与恢复记录由界面负责保留。 */
public final class SafeReplacement {
    private SafeReplacement() { }

    public interface Target {
        InputStream openRead() throws IOException;
        /** 必须截断旧内容；关闭返回成功前，调用方应完成底层文件描述符同步。 */
        OutputStream openWrite() throws IOException;
    }

    /** 已尝试覆盖后的失败。恢复成功仍报告失败，界面不得将覆盖误报为成功。 */
    public static final class ReplacementException extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean originalRestored;

        private ReplacementException(String message, Throwable cause, boolean originalRestored) {
            super(message, cause);
            this.originalRestored = originalRestored;
        }
    }

    public static void replace(Target target, File verifiedOutput, File durableOriginalBackup,
                               String expectedOriginalHash) throws IOException {
        requireTarget(target);
        String expected = validHash(expectedOriginalHash);
        requireFile(verifiedOutput, "已验证输出");
        requireFile(durableOriginalBackup, "持久原件备份");
        if (verifiedOutput.getCanonicalFile().equals(durableOriginalBackup.getCanonicalFile())
                || Files.isSameFile(verifiedOutput.toPath(), durableOriginalBackup.toPath())) {
            throw new IOException("输出与原件备份指向同一个文件，禁止覆盖");
        }
        if (!expected.equals(sha256(durableOriginalBackup))) {
            throw new IOException("持久备份与预览时的原 PDF 校验值不一致，目标未写入");
        }
        String outputHash = sha256(verifiedOutput);
        if (!expected.equals(targetHash(target, null))) {
            throw new IOException("原 PDF 在预览后已变化，目标未写入；请重新预览");
        }
        checkInterrupted();

        boolean writeAttempted = false;
        try {
            // 先打开输出，避免输出不可读时才截断原件。
            try (InputStream source = new FileInputStream(verifiedOutput)) {
                checkInterrupted();
                // openWrite 也可能在截断之后抛异常，必须从调用前起视为已尝试写入。
                writeAttempted = true;
                try (OutputStream destination = writable(target)) {
                    copy(source, destination, null);
                }
            }
            if (!outputHash.equals(targetHash(target, null))) {
                throw new IOException("覆盖后的 PDF 重读校验失败");
            }
        } catch (Exception failure) {
            if (!writeAttempted) throw new IOException("覆盖尚未写入目标：" + describe(failure), failure);
            try {
                restore(target, durableOriginalBackup, expected);
            } catch (Exception recoveryFailure) {
                ReplacementException result = new ReplacementException(
                        "覆盖失败且原 PDF 恢复失败。备份已保留，需要恢复："
                                + durableOriginalBackup.getAbsolutePath() + "；原因：" + describe(recoveryFailure),
                        failure, false);
                result.addSuppressed(recoveryFailure);
                throw result;
            }
            throw new ReplacementException("覆盖失败，原 PDF 已从备份恢复并通过校验："
                    + describe(failure), failure, true);
        }
    }

    /** 重启后由用户明确选择恢复；不删除备份，不启动任何自动恢复。 */
    public static void recover(Target target, File backup) throws IOException {
        requireTarget(target);
        requireFile(backup, "持久原件备份");
        String hash = sha256(backup);
        try {
            restore(target, backup, hash);
        } catch (Exception failure) {
            throw new IOException("原 PDF 恢复失败，备份已保留，需要恢复："
                    + backup.getAbsolutePath() + "；原因：" + describe(failure), failure);
        }
    }

    public static String sha256(File file) throws IOException {
        if (file == null) throw new IOException("待校验文件不能为空");
        try (InputStream input = new FileInputStream(file)) {
            return hash(input, null);
        }
    }

    private static void restore(Target target, File backup, String expectedHash) throws IOException {
        InterruptShield shield = new InterruptShield();
        try {
            shield.clear();
            requireFile(backup, "持久原件备份");
            try (InputStream source = new FileInputStream(backup)) {
                if (!expectedHash.equals(hash(source, shield))) {
                    throw new IOException("持久备份已变化，拒绝用不一致的备份恢复");
                }
            }
            shield.clear();
            try (InputStream source = new FileInputStream(backup);
                 OutputStream destination = writable(target)) {
                copy(source, destination, shield);
                shield.clear();
            }
            if (!expectedHash.equals(targetHash(target, shield))) {
                throw new IOException("备份回写后重读校验失败");
            }
        } finally {
            shield.restore();
        }
    }

    private static void copy(InputStream source, OutputStream destination, InterruptShield shield)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            beforeIo(shield);
            int count = source.read(buffer);
            if (count == -1) break;
            if (count == 0) continue;
            beforeIo(shield);
            destination.write(buffer, 0, count);
        }
        beforeIo(shield);
        destination.flush();
    }

    private static String targetHash(Target target, InterruptShield shield) throws IOException {
        beforeIo(shield);
        try (InputStream input = target.openRead()) {
            if (input == null) throw new IOException("系统未返回目标 PDF 读取流");
            String result = hash(input, shield);
            beforeIo(shield);
            return result;
        }
    }

    private static String hash(InputStream input, InterruptShield shield) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("系统不支持 SHA-256", impossible); }
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            if (shield != null) shield.clear();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static OutputStream writable(Target target) throws IOException {
        OutputStream output = target.openWrite();
        if (output == null) throw new IOException("系统未返回目标 PDF 写入流");
        return output;
    }

    private static void beforeIo(InterruptShield shield) throws IOException {
        if (shield == null) checkInterrupted(); else shield.clear();
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("覆盖任务已中断");
    }

    private static void requireTarget(Target target) throws IOException {
        if (target == null) throw new IOException("目标 PDF 不能为空");
    }

    private static void requireFile(File file, String label) throws IOException {
        if (file == null || !file.isFile() || !file.canRead() || file.length() == 0) {
            throw new IOException(label + "不是可读取的非空文件，禁止写入目标");
        }
    }

    private static String validHash(String value) throws IOException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("缺少有效的原 PDF 预览校验值，禁止写入目标");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    /** 回滚不接受取消；暂时清除中断标志，每次 I/O 前重新清除，最后还原。 */
    private static final class InterruptShield {
        private boolean interrupted = Thread.interrupted();
        void clear() { interrupted |= Thread.interrupted(); }
        void restore() { clear(); if (interrupted) Thread.currentThread().interrupt(); }
    }
}
