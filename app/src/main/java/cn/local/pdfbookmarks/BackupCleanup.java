package cn.local.pdfbookmarks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按成功关联记录清理旧备份，始终核对并保留当前输出。 */
public final class BackupCleanup {
    private BackupCleanup() { }

    public interface Provider {
        /** 重新列举当前目录，不能使用旧 URI 或显示名缓存。 */
        List<StagedReplacement.Document> find(String exactName) throws IOException;
        InputStream openRead(StagedReplacement.Document document) throws IOException;
        /** 仅删除给定旧备份；返回 false 或抛异常均表示这次操作未确认成功。 */
        boolean delete(StagedReplacement.Document document) throws IOException;
    }

    public interface Guard {
        /** 重查持久记录、恢复状态及不在删除清单中的当前 PDF / 目录。 */
        void check() throws IOException;
    }

    public static final class Item {
        public final Provider provider;
        public final StagedReplacement.Document output, backup;
        public final String outputHash, oldHash;
        public final File privateBackup;

        public Item(Provider provider, StagedReplacement.Document output,
                    StagedReplacement.Document backup, String outputHash,
                    String oldHash, File privateBackup) {
            this.provider = provider;
            this.output = output;
            this.backup = backup;
            this.outputHash = outputHash;
            this.oldHash = oldHash;
            this.privateBackup = privateBackup;
        }
    }

    /** 全清单只读预检；resume 只供已持久化的同一份清理确认记录继续执行。 */
    public static void verifyAll(List<Item> items, boolean resume) throws IOException {
        try { verifyItems(snapshot(items), resume); }
        catch (IOException | RuntimeException failure) { throw cleanupFailure(failure); }
    }

    /**
     * 必须先展示全部路径并确认，且先持久化本次清理授权，才能调用。
     * 全部外部旧件删完并核验后，才删除应用内原件备份；任何失败均保留调用方记录。
     */
    public static void removeAll(List<Item> items, boolean resume, Guard guard) throws IOException {
        try {
            List<Item> plan = snapshot(items);
            if (guard == null) throw new IOException("缺少清理记录核验");
            guard.check();
            verifyItems(plan, resume);
            for (Item item : plan) {
                guard.check();
                verifyItems(plan, resume);
                StagedReplacement.Document old =
                        verified(item.provider, item.backup, validHash(item.oldHash), true);
                if (old == null) continue;
                requirePrivateBackup(item.privateBackup, validHash(item.oldHash), item.output, item.backup);
                guard.check();
                verifyOutputs(plan);
                beforeIo();
                if (!item.provider.delete(old)) throw new IOException("文件来源未确认删除旧备份");
                if (unique(item.provider, item.backup, true) != null) {
                    throw new IOException("删除后旧备份仍存在或出现同名文件");
                }
                guard.check();
                verifyItems(plan, resume);
            }
            for (Item item : plan) {
                guard.check();
                // 已删除的内部备份只在全部外部旧件均不存在时允许继续。
                verifyItems(plan, true);
                requireExternalAbsent(plan);
                if (!item.privateBackup.exists()) continue;
                requirePrivateBackup(item.privateBackup, validHash(item.oldHash), item.output, item.backup);
                guard.check();
                verifyOutputs(plan);
                requireExternalAbsent(plan);
                beforeIo();
                if (!item.privateBackup.delete()) throw new IOException("应用内原件备份删除未确认");
                beforeIo();
                if (item.privateBackup.exists()) throw new IOException("应用内原件备份删除后仍存在");
                guard.check();
                verifyItems(plan, true);
                requireExternalAbsent(plan);
            }
            guard.check();
            verifyItems(plan, true);
            requireExternalAbsent(plan);
        } catch (IOException | RuntimeException failure) { throw cleanupFailure(failure); }
    }

    private static List<Item> snapshot(List<Item> items) throws IOException {
        if (items == null || items.isEmpty()) throw new IOException("没有可核验的旧备份记录");
        return new ArrayList<>(items);
    }

    private static void verifyItems(List<Item> items, boolean resume) throws IOException {
        beforeIo();
        for (Item item : items) {
            if (item == null || item.provider == null || item.privateBackup == null) {
                throw new IOException("缺少原文件夹授权或应用内备份记录");
            }
            validDocument(item.output);
            validDocument(item.backup);
            validHash(item.outputHash);
            validHash(item.oldHash);
            if (item.output.name.equals(item.backup.name) || aliases(item.output, item.backup)) {
                throw new IOException("当前输出与旧备份指向相同文件，禁止删除");
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Item first = items.get(i);
            for (int j = 0; j < items.size(); j++) {
                Item second = items.get(j);
                if (aliases(first.output, second.backup)) {
                    throw new IOException("待删旧备份也是清单中的当前输出，禁止删除");
                }
                rejectFileAlias(first.privateBackup, second.output.uri);
                rejectFileAlias(first.privateBackup, second.backup.uri);
                if (i < j && (aliases(first.output, second.output)
                        || aliases(first.backup, second.backup)
                        || sameFile(first.privateBackup, second.privateBackup))) {
                    throw new IOException("清理清单含重复文件，禁止删除");
                }
            }
        }
        for (Item item : items) {
            verified(item.provider, item.output, validHash(item.outputHash), false);
            StagedReplacement.Document old =
                    verified(item.provider, item.backup, validHash(item.oldHash), true);
            if (item.privateBackup.exists()) {
                requirePrivateBackup(item.privateBackup, validHash(item.oldHash), item.output, item.backup);
            } else if (!resume || old != null) {
                throw new IOException("应用内原件备份缺失，禁止清理；只能续接已确认且外部旧件不存在的记录");
            }
            // 旧件列举可能触发提供者刷新，完成预检前再次核对当前输出。
            verified(item.provider, item.output, validHash(item.outputHash), false);
        }
    }

    private static boolean aliases(StagedReplacement.Document first,
                                   StagedReplacement.Document second) throws IOException {
        if (first.uri.equals(second.uri)) return true;
        File firstFile = fileDocument(first.uri), secondFile = fileDocument(second.uri);
        return firstFile != null && secondFile != null && sameFile(firstFile, secondFile);
    }

    private static void requireExternalAbsent(List<Item> items) throws IOException {
        for (Item item : items) {
            if (unique(item.provider, item.backup, true) != null) {
                throw new IOException("外部旧备份仍存在，保留全部应用内备份");
            }
        }
    }

    private static void verifyOutputs(List<Item> items) throws IOException {
        for (Item item : items) verified(item.provider, item.output, validHash(item.outputHash), false);
    }

    private static IOException cleanupFailure(Exception failure) {
        String reason = failure.getMessage();
        return new IOException("统一清理未确认，请保留记录并重新核对后继续："
                + (reason == null ? failure.getClass().getSimpleName() : reason), failure);
    }

    /**
     * 仅由用户明确确认调用，界面必须先拒绝未处理的恢复记录及已经变化的替换记录。
     * true：本次删除且重查通过；false：旧备份本就不在目录中，新 PDF 和内部备份已核验。
     * 抛异常时必须保留清理记录，即使提供者可能已完成删除，也只能下次重新核对。
     */
    public static boolean remove(Provider provider, StagedReplacement.Document outputDoc,
                                 StagedReplacement.Document backupDoc, String outputHash,
                                 String oldHash, File privateBackup) throws IOException {
        try {
            if (provider == null) throw new IOException("缺少原文件夹授权");
            validDocument(outputDoc);
            validDocument(backupDoc);
            if (outputDoc.name.equals(backupDoc.name) || outputDoc.uri.equals(backupDoc.uri)) {
                throw new IOException("新 PDF 与旧备份名称或标识相同，禁止删除");
            }
            File outputFile = fileDocument(outputDoc.uri);
            File backupFile = fileDocument(backupDoc.uri);
            if (outputFile != null && backupFile != null && sameFile(outputFile, backupFile)) {
                throw new IOException("新 PDF 与旧备份实际指向同一文件，禁止删除");
            }
            String expectedOutput = validHash(outputHash);
            String expectedOld = validHash(oldHash);
            requirePrivateBackup(privateBackup, expectedOld, outputDoc, backupDoc);
            verified(provider, outputDoc, expectedOutput, false);
            if (verified(provider, backupDoc, expectedOld, true) == null) {
                requirePrivateBackup(privateBackup, expectedOld, outputDoc, backupDoc);
                verified(provider, outputDoc, expectedOutput, false);
                return false;
            }

            // 用户确认和提供者操作之间可能发生变化：删除前重新查询身份及内容。
            verified(provider, outputDoc, expectedOutput, false);
            StagedReplacement.Document currentBackup =
                    verified(provider, backupDoc, expectedOld, true);
            requirePrivateBackup(privateBackup, expectedOld, outputDoc, backupDoc);
            if (currentBackup == null) {
                verified(provider, outputDoc, expectedOutput, false);
                return false;
            }
            beforeIo();
            if (!provider.delete(currentBackup)) {
                throw new IOException("文件来源未确认删除；保留记录与应用内备份，重试时重新核对");
            }
            if (unique(provider, backupDoc, true) != null) {
                throw new IOException("删除后旧备份仍存在或出现同名文件，未确认完成");
            }
            verified(provider, outputDoc, expectedOutput, false);
            requirePrivateBackup(privateBackup, expectedOld, outputDoc, backupDoc);
            return true;
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage();
            throw new IOException("旧备份清理未确认，请保留清理记录及应用内备份："
                    + (reason == null ? failure.getClass().getSimpleName() : reason), failure);
        }
    }

    private static StagedReplacement.Document verified(Provider provider,
            StagedReplacement.Document expected, String expectedHash, boolean allowAbsent)
            throws IOException {
        StagedReplacement.Document current = unique(provider, expected, allowAbsent);
        if (current == null) return null;
        beforeIo();
        try (InputStream input = provider.openRead(current)) {
            if (input == null) throw new IOException("文件来源未返回读取流：" + expected.name);
            if (!expectedHash.equals(hash(input))) {
                throw new IOException("文件内容在确认后变化，禁止删除：" + expected.name);
            }
        }
        return current;
    }

    private static StagedReplacement.Document unique(Provider provider,
            StagedReplacement.Document expected, boolean allowAbsent) throws IOException {
        beforeIo();
        List<StagedReplacement.Document> found = provider.find(expected.name);
        if (found == null) throw new IOException("无法重新列举原文件夹");
        if (found.isEmpty() && allowAbsent) return null;
        if (found.size() != 1) throw new IOException("文件不存在或同名文件不唯一：" + expected.name);
        StagedReplacement.Document current = found.get(0);
        if (current == null || !expected.name.equals(current.name)
                || !expected.uri.equals(current.uri)) {
            throw new IOException("文件名称或标识已变化，禁止删除：" + expected.name);
        }
        return current;
    }

    private static void requirePrivateBackup(File file, String expectedHash,
            StagedReplacement.Document output, StagedReplacement.Document backup) throws IOException {
        beforeIo();
        if (file == null || !file.isFile() || !file.canRead() || file.length() == 0) {
            throw new IOException("应用内原件备份不可读取，禁止删除外部旧备份");
        }
        rejectFileAlias(file, output.uri);
        rejectFileAlias(file, backup.uri);
        try (InputStream input = new FileInputStream(file)) {
            if (!expectedHash.equals(hash(input))) {
                throw new IOException("应用内备份与替换前原件校验值不一致，禁止删除");
            }
        }
    }

    /** 宿主 file URI 也不能把保留的内部备份冒充可删除的旧备份。 */
    private static void rejectFileAlias(File privateBackup, String documentUri) throws IOException {
        File document = fileDocument(documentUri);
        if (document != null && sameFile(privateBackup, document)) {
            throw new IOException("应用内备份与新 PDF 或待删旧备份指向同一文件，禁止删除");
        }
    }

    private static File fileDocument(String documentUri) throws IOException {
        final URI uri;
        try { uri = URI.create(documentUri); }
        catch (IllegalArgumentException invalid) { throw new IOException("保存的文件标识无效", invalid); }
        if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
        try { return new File(uri); }
        catch (IllegalArgumentException invalid) { throw new IOException("保存的文件标识无效", invalid); }
    }

    private static boolean sameFile(File first, File second) throws IOException {
        return first.getCanonicalFile().equals(second.getCanonicalFile())
                || (first.isFile() && second.isFile() && Files.isSameFile(first.toPath(), second.toPath()));
    }

    private static void validDocument(StagedReplacement.Document document) throws IOException {
        if (document == null || document.uri == null || document.uri.isEmpty()
                || document.name == null || document.name.trim().isEmpty()
                || document.name.equals(".") || document.name.equals("..")
                || document.name.indexOf('/') >= 0 || document.name.indexOf('\\') >= 0
                || document.name.indexOf('\0') >= 0) {
            throw new IOException("缺少有效的成功替换文件记录");
        }
    }

    private static String validHash(String hash) throws IOException {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("缺少有效的成功替换校验值");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private static String hash(InputStream input) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("系统不支持 SHA-256", impossible); }
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            beforeIo();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static void beforeIo() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("旧备份清理已中断");
    }
}
