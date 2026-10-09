package cn.local.pdfbookmarks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 同目录暂存、改名替换；从不打开原件写入，也没有删除文件的接口。 */
public final class StagedReplacement {
    private StagedReplacement() { }

    public static final class Document {
        public final String uri;
        public final String name;

        public Document(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    public interface Provider {
        /** 必须重新列举目录；只返回当前显示名完全相等的文件，不能返回旧 URI 缓存。 */
        List<Document> find(String exactName) throws IOException;
        Document create(String uniqueName) throws IOException;
        InputStream openRead(Document document) throws IOException;
        /** 只允许写入本次刚创建的暂存文件；关闭成功前须同步底层数据。 */
        OutputStream openWriteNew(Document document) throws IOException;
        /** 改名可以改变 URI；返回 null 或抛异常后，也可能已经完成了改名。 */
        Document rename(Document document, String newName) throws IOException;
    }

    public interface Sink {
        /** 返回成功才表示记录已经持久化；实现不能仅异步排队保存。 */
        void save(Journal journal) throws IOException;
    }

    public enum Phase {
        CREATE_INTENT, STAGED, BACKUP_INTENT, PROMOTE_INTENT,
        COMMITTED, RESTORE_INTENT, RESTORED
    }

    /** 重启恢复所需的完整快照；URI 只用于记录，恢复以目录中的精确名字及校验值为准。 */
    public static final class Journal {
        public final Phase phase;
        public final String originalName;
        public final String originalUri;
        public final String originalHash;
        public final String stagingName;
        public final String stagingUri;
        public final String outputHash;
        public final String backupName;
        public final String backupUri;
        public final String documentUri;

        public Journal(Phase phase, String originalName, String originalUri, String originalHash,
                       String stagingName, String stagingUri, String outputHash,
                       String backupName, String backupUri, String documentUri) {
            this.phase = phase;
            this.originalName = originalName;
            this.originalUri = originalUri;
            this.originalHash = originalHash;
            this.stagingName = stagingName;
            this.stagingUri = stagingUri;
            this.outputHash = outputHash;
            this.backupName = backupName;
            this.backupUri = backupUri;
            this.documentUri = documentUri;
        }
    }

    public static final class Result {
        public final Document document;
        /** 替换成功时为保留的原件备份；恢复成功时为 null。 */
        public final Document backup;
        public final Journal journal;

        private Result(Document document, Document backup, Journal journal) {
            this.document = document;
            this.backup = backup;
            this.journal = journal;
        }
    }

    public static final class ReplacementException extends IOException {
        private static final long serialVersionUID = 1L;
        /** 准备阶段失败时可以为 null；非空记录必须保留供恢复。 */
        public final Journal journal;
        /** true 仅表示原文件名下的原始内容已核验，且需要的恢复记录已持久化。 */
        public final boolean originalRestored;

        private ReplacementException(String message, Throwable cause, Journal journal,
                                     boolean originalRestored) {
            super(message, cause);
            this.journal = journal;
            this.originalRestored = originalRestored;
        }
    }

    public static Result replace(Provider provider, String originalName, File verifiedOutput,
                                 String expectedOriginalHash, Sink sink)
            throws ReplacementException {
        return replace(provider, originalName, verifiedOutput, expectedOriginalHash, sink, false);
    }

    /** 只更新已验证的享做主目录文件；不接触临时目录或其他配置。 */
    public static Result replaceCatalog(Provider provider, String originalName, File verifiedOutput,
                                        String expectedOriginalHash, Sink sink)
            throws ReplacementException {
        if (!"catalog.json".equals(originalName)) {
            throw new ReplacementException("仅允许更新 catalog.json，其他目录或配置文件不会修改",
                    null, null, false);
        }
        return replace(provider, originalName, verifiedOutput, expectedOriginalHash, sink, true);
    }

    private static Result replace(Provider provider, String originalName, File verifiedOutput,
                                  String expectedOriginalHash, Sink sink, boolean catalog)
            throws ReplacementException {
        String kind = catalog ? "目录文件" : "PDF";
        Journal journal = null;
        boolean originalMutationAttempted = false;
        boolean commitAttempted = false;
        try {
            require(provider, sink);
            validName(originalName);
            String originalHash = validHash(expectedOriginalHash);
            if (verifiedOutput == null || !verifiedOutput.isFile()
                    || !verifiedOutput.canRead() || verifiedOutput.length() == 0) {
                throw new IOException("已验证输出不是可读取的非空文件");
            }
            String outputHash;
            try (InputStream input = new FileInputStream(verifiedOutput)) {
                outputHash = hash(input, null);
            }
            Document original = checked(provider, originalName, originalHash, null);
            String token = UUID.randomUUID().toString();
            String prefix = catalog ? "pdf-bookmarks-catalog-" : "pdf-bookmarks-";
            String extension = catalog ? ".json" : ".pdf";
            String stagingName = prefix + token + "-staged" + extension;
            String backupName = prefix + token + "-backup" + extension;
            absent(provider, stagingName, null);
            absent(provider, backupName, null);
            journal = new Journal(Phase.CREATE_INTENT, originalName, original.uri, originalHash,
                    stagingName, null, outputHash, backupName, null, null);
            save(sink, journal, null);

            beforeIo(null);
            Document created = provider.create(stagingName);
            if (created == null) throw new IOException("系统未返回新建暂存 " + kind + "，原件未修改");
            Document staging = unique(provider, stagingName, false, null);
            if (!staging.uri.equals(created.uri) || staging.uri.equals(original.uri)) {
                throw new IOException("新建暂存文件身份不一致，禁止写入");
            }
            journal = snapshot(journal, Phase.CREATE_INTENT, staging.uri, null, null);
            save(sink, journal, null);
            // 原件直到完整暂存文件关闭、重读校验通过之前，只有读取操作。
            try (InputStream source = new FileInputStream(verifiedOutput)) {
                beforeIo(null);
                try (OutputStream destination = provider.openWriteNew(staging)) {
                    if (destination == null) throw new IOException("系统未返回暂存 " + kind + " 写入流");
                    copy(source, destination);
                }
            }
            staging = checked(provider, stagingName, outputHash, null);
            journal = snapshot(journal, Phase.STAGED, staging.uri, null, null);
            save(sink, journal, null);
            original = checked(provider, originalName, originalHash, null);
            journal = snapshot(journal, Phase.BACKUP_INTENT, staging.uri, null, null);
            save(sink, journal, null);
            originalMutationAttempted = true;
            Document backup = renameChecked(provider, original, backupName, originalHash, null);

            journal = snapshot(journal, Phase.PROMOTE_INTENT, staging.uri, backup.uri, null);
            save(sink, journal, null);
            Document result = renameChecked(provider, staging, originalName, outputHash, null);
            // 最终成功必须核对原文件名确实存在；旧 URI 可读不能证明原路径仍在。
            result = checked(provider, originalName, outputHash, null);
            backup = checked(provider, backupName, originalHash, null);
            if (result.uri.equals(backup.uri)) throw new IOException("新 " + kind + " 与原件备份身份重复");
            journal = snapshot(journal, Phase.PROMOTE_INTENT, staging.uri, backup.uri, result.uri);
            Journal committed = snapshot(journal, Phase.COMMITTED, staging.uri, backup.uri, result.uri);
            commitAttempted = true;
            save(sink, committed, null);
            return new Result(result, backup, committed);
        } catch (Exception failure) {
            if (commitAttempted) {
                throw new ReplacementException("新 " + kind + " 已核验，但提交记录保存失败；保留备份及待恢复记录，不能报告成功："
                        + describe(failure), failure, journal, false);
            }
            if (originalMutationAttempted) {
                Result restored;
                try {
                    restored = recover(provider, journal, sink);
                } catch (ReplacementException recoveryFailure) {
                    ReplacementException result = new ReplacementException(
                            "替换失败且恢复尚未完成；所有备份及暂存文件已保留，需要恢复："
                                    + describe(recoveryFailure),
                            failure, recoveryFailure.journal, false);
                    result.addSuppressed(recoveryFailure);
                    throw result;
                }
                throw new ReplacementException("替换失败，原文件名及原始内容已恢复并核验："
                        + describe(failure), failure, restored.journal, true);
            }
            boolean intact = false;
            if (journal != null) {
                try {
                    checked(provider, journal.originalName, journal.originalHash, null);
                    intact = true;
                } catch (Exception ignored) {
                    // 无法重读核验时，不能声称原件安全；仍保留记录。
                }
            }
            throw new ReplacementException("暂存或准备失败，未尝试改名原 " + kind + "："
                    + describe(failure), failure, journal, intact);
        }
    }

    /** 仅由明确恢复操作调用；不覆盖、不截断、不删除任何已有文件。 */
    public static Result recover(Provider provider, Journal journal, Sink sink)
            throws ReplacementException {
        String kind = journal != null && "catalog.json".equals(journal.originalName) ? "目录文件" : "PDF";
        Journal current = journal;
        InterruptShield shield = new InterruptShield();
        try {
            require(provider, sink);
            validJournal(current);
            current = new Journal(current.phase, current.originalName, current.originalUri,
                    validHash(current.originalHash), current.stagingName, current.stagingUri,
                    validHash(current.outputHash), current.backupName, current.backupUri, current.documentUri);
            Document original = unique(provider, current.originalName, true, shield);
            if (original != null && current.originalHash.equals(documentHash(provider, original, shield))) {
                current = snapshot(current, Phase.RESTORED, current.stagingUri,
                        current.backupUri, original.uri);
                save(sink, current, shield);
                return new Result(original, null, current);
            }
            Document backup = checked(provider, current.backupName, current.originalHash, shield);
            if (original != null) {
                if (!current.outputHash.equals(documentHash(provider, original, shield))) {
                    throw new IOException("原文件名已被其他内容占用，禁止移动或覆盖，请人工核对");
                }
                absent(provider, current.stagingName, shield);
            }
            current = snapshot(current, Phase.RESTORE_INTENT, current.stagingUri, backup.uri,
                    original == null ? null : original.uri);
            save(sink, current, shield);
            if (original != null) {
                Document retainedOutput = renameChecked(provider, original, current.stagingName,
                        current.outputHash, shield);
                current = snapshot(current, Phase.RESTORE_INTENT, retainedOutput.uri, backup.uri, null);
                save(sink, current, shield);
            }
            backup = checked(provider, current.backupName, current.originalHash, shield);
            Document restored = renameChecked(provider, backup, current.originalName,
                    current.originalHash, shield);
            restored = checked(provider, current.originalName, current.originalHash, shield);
            current = snapshot(current, Phase.RESTORED, current.stagingUri, backup.uri, restored.uri);
            save(sink, current, shield);
            return new Result(restored, null, current);
        } catch (Exception failure) {
            throw new ReplacementException("原 " + kind + " 恢复未完成，禁止清除恢复记录；备份及暂存文件继续保留："
                    + describe(failure), failure, current, false);
        } finally {
            shield.restore();
        }
    }

    private static Document renameChecked(Provider provider, Document source, String newName,
                                          String expectedHash, InterruptShield shield)
            throws IOException {
        Document freshSource = checked(provider, source.name, expectedHash, shield);
        absent(provider, newName, shield);
        Exception renameFailure = null;
        try {
            beforeIo(shield);
            provider.rename(freshSource, newName);
        } catch (IOException | RuntimeException failure) {
            renameFailure = failure;
        }
        try {
            Document renamed = checked(provider, newName, expectedHash, shield);
            absent(provider, source.name, shield);
            return renamed;
        } catch (IOException | RuntimeException verificationFailure) {
            if (renameFailure != null) verificationFailure.addSuppressed(renameFailure);
            throw verificationFailure;
        }
    }

    private static Document checked(Provider provider, String name, String expectedHash,
                                    InterruptShield shield) throws IOException {
        Document document = unique(provider, name, false, shield);
        if (!expectedHash.equals(documentHash(provider, document, shield))) {
            throw new IOException("文件内容校验值不一致：" + name);
        }
        return document;
    }

    private static void absent(Provider provider, String name, InterruptShield shield)
            throws IOException {
        if (unique(provider, name, true, shield) != null) {
            throw new IOException("文件名已被占用，禁止覆盖：" + name);
        }
    }

    private static Document unique(Provider provider, String name, boolean allowAbsent,
                                   InterruptShield shield) throws IOException {
        beforeIo(shield);
        List<Document> documents = provider.find(name);
        if (documents == null) throw new IOException("无法列举原文件夹：" + name);
        if (documents.isEmpty()) {
            if (allowAbsent) return null;
            throw new IOException("原文件夹中找不到文件：" + name);
        }
        if (documents.size() != 1) throw new IOException("同名文件不唯一，禁止操作：" + name);
        Document document = documents.get(0);
        if (document == null || document.uri == null || document.uri.isEmpty()
                || !name.equals(document.name)) {
            throw new IOException("文件夹返回的文件名或身份无效：" + name);
        }
        return document;
    }

    private static String documentHash(Provider provider, Document document, InterruptShield shield)
            throws IOException {
        beforeIo(shield);
        try (InputStream input = provider.openRead(document)) {
            if (input == null) throw new IOException("系统未返回文件读取流：" + document.name);
            return hash(input, shield);
        }
    }

    private static String hash(InputStream input, InterruptShield shield) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("系统不支持 SHA-256", impossible); }
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            beforeIo(shield);
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            beforeIo(null);
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > 0) {
                beforeIo(null);
                output.write(buffer, 0, count);
            }
        }
        beforeIo(null);
        output.flush();
    }

    private static Journal snapshot(Journal journal, Phase phase, String stagingUri,
                                    String backupUri, String documentUri) {
        return new Journal(phase, journal.originalName, journal.originalUri, journal.originalHash,
                journal.stagingName, stagingUri, journal.outputHash,
                journal.backupName, backupUri, documentUri);
    }

    private static void save(Sink sink, Journal journal, InterruptShield shield) throws IOException {
        beforeIo(shield);
        sink.save(journal);
    }

    private static void require(Provider provider, Sink sink) throws IOException {
        if (provider == null || sink == null) throw new IOException("缺少文件夹权限适配器或持久恢复记录");
    }

    private static void validJournal(Journal journal) throws IOException {
        if (journal == null || journal.phase == null) throw new IOException("缺少有效恢复记录");
        validName(journal.originalName);
        validName(journal.stagingName);
        validName(journal.backupName);
        validHash(journal.originalHash);
        validHash(journal.outputHash);
        if (journal.originalName.equals(journal.stagingName)
                || journal.originalName.equals(journal.backupName)
                || journal.stagingName.equals(journal.backupName)) {
            throw new IOException("恢复记录中的文件名相同，禁止操作");
        }
    }

    private static void validName(String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("文件名无效，禁止操作");
        }
    }

    private static String validHash(String hash) throws IOException {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("缺少有效的预览校验值");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static void beforeIo(InterruptShield shield) throws IOException {
        if (shield != null) shield.clear();
        else if (Thread.currentThread().isInterrupted()) throw new IOException("替换任务已中断");
    }

    /** 恢复不接受取消；完成后还原中断标志，避免把原文件名停留在空缺状态。 */
    private static final class InterruptShield {
        private boolean interrupted = Thread.interrupted();
        void clear() { interrupted |= Thread.interrupted(); }
        void restore() { clear(); if (interrupted) Thread.currentThread().interrupt(); }
    }
}
