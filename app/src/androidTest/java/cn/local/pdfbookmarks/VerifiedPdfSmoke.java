package cn.local.pdfbookmarks;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Optional device check using newly generated documents only; it never opens user inputs. */
public final class VerifiedPdfSmoke {
    private VerifiedPdfSmoke() { }

    public static String verify(Context context) throws Exception {
        PDFBoxResourceLoader.init(context);
        File cache = context.getCacheDir().getCanonicalFile();
        require("cn.local.pdfbookmarks.testhost".equals(context.getPackageName()), "Synthetic PDFs require the isolated test host");
        File base = new File(cache, "perf142").getCanonicalFile();
        require(cache.equals(base.getParentFile()) && (base.isDirectory() || base.mkdir()), "Cannot establish test cache");
        File root = new File(base, "verified-pdf-" + UUID.randomUUID()).getCanonicalFile();
        require(base.equals(root.getParentFile()) && root.mkdir(), "Cannot establish synthetic PDF directory");
        File source = new File(root, "input.pdf"), output = new File(root, "output.pdf");
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < 5; i++) document.addPage(new PDPage());
            document.save(source);
        }
        String originalHash = hash(source);
        List<TocParser.Row> rows = Arrays.asList(new TocParser.Row(1, "Synthetic chapter", null, 1),
                new TocParser.Row(2, "Synthetic section", null, 3), new TocParser.Row(1, "Synthetic last", null, 5));
        PdfBookmarks.VerifiedOutput artifact = PdfBookmarks.writeVerified(source, output, rows, false);
        require(output.getCanonicalFile().equals(artifact.file) && output.length() == artifact.length,
                "Verified artifact does not identify the generated file");
        require(hash(output).equals(artifact.sha256), "Complete output digest differs from independent readback");
        PdfBookmarks.Info info = PdfBookmarks.inspect(output);
        require(info.pageCount == 5 && info.existingRows.size() == rows.size(), "Generated PDF lost pages or outline nodes");
        for (int i = 0; i < rows.size(); i++) {
            TocParser.Row actual = info.existingRows.get(i), expected = rows.get(i);
            require(actual.level == expected.level && actual.pdfPage == expected.pdfPage && actual.title.equals(expected.title),
                    "Generated PDF outline semantics changed");
        }
        require(originalHash.equals(hash(source)), "Generating a verified artifact modified its input");
        try { PdfBookmarks.writeVerified(source, output, rows, false); throw new AssertionError("Existing output was overwritten"); }
        catch (IOException expected) { require(hash(output).equals(artifact.sha256), "Rejected overwrite modified generated output"); }
        File rejected = new File(root, "rejected.pdf");
        try { PdfBookmarks.writeVerified(output, rejected, rows, false); throw new AssertionError("Existing bookmarks were replaced without confirmation"); }
        catch (IOException expected) { require(!rejected.exists(), "Rejected default-skip left an output file"); }
        File legacy = new File(root, "legacy.pdf");
        PdfBookmarks.write(source, legacy, rows, false);
        require(PdfBookmarks.inspect(legacy).existingCount == rows.size(), "Legacy write entry regressed");
        require(originalHash.equals(hash(source)), "Legacy entry modified its input");
        return "PASS: verified artifact digest/outline/pages/source preserved; overwrite/default-skip rejected; legacy write preserved; synthetic files=" + root;
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
