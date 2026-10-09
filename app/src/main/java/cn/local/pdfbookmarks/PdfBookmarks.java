package cn.local.pdfbookmarks;

import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 只读原件，以增量保存生成新副本；调用前须初始化 PDFBoxResourceLoader。 */
public final class PdfBookmarks {
    private PdfBookmarks() {}

    public static final class Info {
        public final int pageCount, existingCount;
        public final List<TocParser.Row> existingRows;

        private Info(int pageCount, List<TocParser.Row> rows) {
            this.pageCount = pageCount;
            this.existingRows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.existingCount = rows.size();
        }
    }

    /** 已有书签的目的地无法解析时 pdfPage 为 0，仅用于预览和默认跳过判断。 */
    public static Info inspect(File input) throws IOException {
        requireInput(input);
        try (PDDocument document = load(input)) {
            checkDocument(document);
            return new Info(document.getNumberOfPages(), readRows(document));
        }
    }

    /** replaceExisting=false 时，检测到任何已有书签即拒绝，不创建输出。 */
    public static void write(File input, File output, List<TocParser.Row> rows,
                             boolean replaceExisting) throws IOException {
        requireInput(input);
        if (output == null || input.getCanonicalFile().equals(output.getCanonicalFile())) {
            throw new IOException("输出必须是独立的新 PDF 文件，不能覆盖原件。");
        }
        if (Files.exists(output.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("输出文件已存在，请使用新的文件名。");
        }
        if (rows == null) throw new IOException("目录不能为空。");
        List<TocParser.Row> expected = new ArrayList<>(rows);
        long originalSize = input.length();
        byte[] originalHash = hash(input, originalSize);
        boolean created = false;
        try {
            int pageCount;
            try (PDDocument document = load(input)) {
                checkDocument(document);
                pageCount = document.getNumberOfPages();
                validateRows(expected, pageCount);
                if (!replaceExisting && !readRows(document).isEmpty()) {
                    throw new IOException("PDF 已有书签，默认跳过；替换须明确确认。");
                }
                PDDocumentOutline outline = buildOutline(document, expected);
                PDDocumentCatalog catalog = document.getDocumentCatalog();
                catalog.setDocumentOutline(outline);
                // 增量保存需从 catalog 到新书签树的更新路径；页面对象不做修改。
                catalog.getCOSObject().setNeedToBeUpdated(true);
                outline.getCOSObject().setNeedToBeUpdated(true);
                try (OutputStream stream = Files.newOutputStream(output.toPath(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    created = true;
                    document.saveIncremental(stream);
                }
            }
            try (PDDocument verified = load(output)) {
                checkDocument(verified);
                if (verified.getNumberOfPages() != pageCount
                        || !sameRows(expected, readRows(verified))) {
                    throw new IOException("输出未通过页数、标题、层级和目标页码核验。");
                }
            }
            if (output.length() <= originalSize
                    || !Arrays.equals(originalHash, hash(output, originalSize))) {
                throw new IOException("增量保存未保留原 PDF 的完整字节前缀。");
            }
            if (input.length() != originalSize
                    || !Arrays.equals(originalHash, hash(input, originalSize))) {
                throw new IOException("处理期间原 PDF 已变化，请重新选择和预览。");
            }
        } catch (IOException | RuntimeException failure) {
            if (created) {
                try { Files.deleteIfExists(output.toPath()); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    private static void requireInput(File input) throws IOException {
        if (input == null || !input.isFile() || !input.canRead()) {
            throw new IOException("请选择可读取的 PDF 文件。");
        }
    }

    private static PDDocument load(File input) throws IOException {
        // 随机访问原文件，不将整本扫描 PDF 读进 byte[]；必要时使用临时文件。
        try {
            return PDDocument.load(input, MemoryUsageSetting.setupMixed(32L * 1024 * 1024));
        } catch (InvalidPasswordException encrypted) {
            throw new IOException("PDF 已加密或需要密码，暂不处理。", encrypted);
        }
    }

    private static void checkDocument(PDDocument document) throws IOException {
        if (document.isEncrypted()) throw new IOException("暂不处理加密 PDF。");
        if (document.getNumberOfPages() < 1) throw new IOException("PDF 没有可用页面。");
        // 包括尚未签署的空签名字段，防止破坏签名模板或认证约束。
        PDAcroForm form = document.getDocumentCatalog().getAcroForm(null);
        if ((form != null && form.getCOSObject().getInt(COSName.getPDFName("SigFlags"), 0) != 0)
                || !document.getSignatureFields().isEmpty()) {
            throw new IOException("暂不处理包含签名或空签名字段的 PDF。");
        }
        Object permissions = document.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.PERMS);
        if (permissions instanceof COSDictionary
                && ((COSDictionary) permissions).getDictionaryObject(COSName.DOCMDP) != null) {
            throw new IOException("暂不处理带认证签名的 PDF。");
        }
    }

    private static void validateRows(List<TocParser.Row> rows, int pageCount) throws IOException {
        if (rows.isEmpty()) throw new IOException("目录不能为空。");
        int previous = 0;
        for (TocParser.Row row : rows) {
            if (row == null || row.title == null || row.title.trim().isEmpty()) {
                throw new IOException("目录标题不能为空。");
            }
            if (row.level < 1 || row.level > previous + 1) {
                throw new IOException("目录须从一级开始，子级不能跳级。");
            }
            if (row.pdfPage < 1 || row.pdfPage > pageCount) {
                throw new IOException("目录目标页超出 PDF 的实际页数。");
            }
            previous = row.level;
        }
    }

    private static PDDocumentOutline buildOutline(PDDocument document, List<TocParser.Row> rows) {
        PDDocumentOutline root = new PDDocumentOutline();
        List<PDOutlineNode> parents = new ArrayList<>();
        parents.add(root);
        for (TocParser.Row row : rows) {
            while (parents.size() > row.level) parents.remove(parents.size() - 1);
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(row.title);
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(document.getPage(row.pdfPage - 1));
            item.setDestination(destination);
            item.getCOSObject().setNeedToBeUpdated(true);
            parents.get(row.level - 1).addLast(item);
            parents.add(item);
        }
        return root;
    }

    private static final class Pending {
        final PDOutlineItem item;
        final int level;
        Pending(PDOutlineItem item, int level) { this.item = item; this.level = level; }
    }

    private static List<TocParser.Row> readRows(PDDocument document) throws IOException {
        List<TocParser.Row> rows = new ArrayList<>();
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null || outline.getFirstChild() == null) return rows;
        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(outline.getFirstChild(), 1));
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            PDOutlineItem item = current.item;
            if (!seen.add(item.getCOSObject())) throw new IOException("PDF 书签树含循环引用。");
            int pageNumber = 0;
            try {
                PDPage page = item.findDestinationPage(document);
                if (page != null) pageNumber = document.getPages().indexOf(page) + 1;
            } catch (IOException | RuntimeException unresolvedDestination) {
                // 原书签可包含外链或失效目的地：仍计入已有书签，预览页码显示未知。
            }
            rows.add(new TocParser.Row(current.level, item.getTitle(), null, pageNumber));
            PDOutlineItem sibling = item.getNextSibling();
            if (sibling != null) pending.push(new Pending(sibling, current.level));
            PDOutlineItem child = item.getFirstChild();
            if (child != null) pending.push(new Pending(child, current.level + 1));
        }
        return rows;
    }

    private static boolean sameRows(List<TocParser.Row> expected, List<TocParser.Row> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            TocParser.Row a = expected.get(i), b = actual.get(i);
            if (a.level != b.level || a.pdfPage != b.pdfPage || !a.title.equals(b.title)) return false;
        }
        return true;
    }

    private static byte[] hash(File file, long bytes) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = bytes;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("PDF 文件长度在读取时发生变化。");
                digest.update(buffer, 0, count);
                remaining -= count;
            }
        }
        return digest.digest();
    }
}
