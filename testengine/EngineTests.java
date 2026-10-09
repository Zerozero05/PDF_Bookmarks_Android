import android.content.Context;
import cn.local.pdfbookmarks.PdfBookmarks;
import cn.local.pdfbookmarks.TocParser;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 真正执行生产 PdfBookmarks 与官方 Android AAR；平台适配仅用于日志和资源。 */
public final class EngineTests {
    private interface Checked { void run() throws Exception; }
    private static int passed;
    private static File fixtures, output;
    private static final JSONArray written = new JSONArray();

    public static void main(String[] args) throws Exception {
        PDFBoxResourceLoader.init(new Context(new File(args[0])));
        fixtures = new File(args[1]);
        output = new File(args[2]);
        output.mkdirs();
        List<TocParser.Row> rows = Arrays.asList(
            row(1, "第一章（α）📚", 1), row(2, "1.1 节—稳定性", 2),
            row(3, "1.1.1 Example β", 3), row(2, "1.2 页边界", 6), row(1, "附录 A", 6));

        test("读取真实页数和空目录", () -> {
            PdfBookmarks.Info info = PdfBookmarks.inspect(input("plain"));
            check(info.pageCount == 6 && info.existingCount == 0, "plain info");
        });
        test("读取已有书签的文字层级页码", () -> {
            PdfBookmarks.Info info = PdfBookmarks.inspect(input("existing"));
            check(info.existingCount == 2 && info.existingRows.get(1).level == 2
                && info.existingRows.get(0).pdfPage == 2 && info.existingRows.get(1).pdfPage == 3,
                "existing info");
        });
        test("中文希腊字母Emoji三级树及末页", () -> write("nested", input("plain"), rows, false));
        test("默认skip拒绝已有书签且不创建输出", () ->
            rejectWrite(input("existing"), new File(output, "skipped.pdf"), rows, false));
        test("明确replace替换完整已有树", () -> write("replace", input("existing"), rows, true));
        test("旧外链与失效目标仍计数且显示未知页0", () -> {
            PdfBookmarks.Info info = PdfBookmarks.inspect(input("external-outline"));
            check(info.existingCount == 2 && info.existingRows.get(0).pdfPage == 0
                && info.existingRows.get(1).pdfPage == 0, "external outline inspection");
        });
        test("旧外链仍触发默认skip", () ->
            rejectWrite(input("external-outline"), new File(output, "external-skip.pdf"), rows, false));
        test("旧外链与失效目标允许明确替换", () -> write("external-replace", input("external-outline"), rows, true));
        test("拒绝覆盖输入自身", () -> {
            byte[] before = Files.readAllBytes(input("plain").toPath());
            reject(() -> PdfBookmarks.write(input("plain"), input("plain"), rows, true));
            check(Arrays.equals(before, Files.readAllBytes(input("plain").toPath())), "source overwritten");
        });
        test("拒绝覆盖已存在输出", () -> {
            File protectedFile = new File(output, "existing-output.pdf");
            byte[] sentinel = "existing output must remain".getBytes(StandardCharsets.UTF_8);
            Files.write(protectedFile.toPath(), sentinel);
            reject(() -> PdfBookmarks.write(input("plain"), protectedFile, rows, true));
            check(Arrays.equals(sentinel, Files.readAllBytes(protectedFile.toPath())), "existing output overwritten");
        });
        test("无效页码0拒绝且零输出", () -> invalidRows(List.of(row(1, "bad", 0))));
        test("超过实际末页拒绝且零输出", () -> invalidRows(List.of(row(1, "bad", 7))));
        test("起始层级2拒绝且零输出", () -> invalidRows(List.of(row(2, "bad", 1))));
        test("跳级1到3拒绝且零输出", () -> invalidRows(List.of(row(1, "a", 1), row(3, "b", 2))));
        test("空标题拒绝且零输出", () -> invalidRows(List.of(row(1, "  ", 1))));
        test("空目录拒绝且零输出", () -> invalidRows(List.of()));
        for (String name : List.of("encrypted-blank", "encrypted-password", "empty-signature",
                "signed", "signature-flags", "no-pages", "invalid")) {
            test("拒绝 " + name + " 且零输出", () -> {
                reject(() -> PdfBookmarks.inspect(input(name)));
                rejectWrite(input(name), new File(output, name + "-output.pdf"), rows, true);
            });
        }
        test("循环书签检测终止", () -> reject(() -> PdfBookmarks.inspect(input("cyclic-outline"))));
        if (args.length == 5) {
            File realPdf = new File(args[3]);
            String json = Files.readString(new File(args[4]).toPath(), StandardCharsets.UTF_8);
            PdfBookmarks.Info actual = PdfBookmarks.inspect(realPdf);
            List<TocParser.Row> realRows = TocParser.parse(json, actual.pageCount).rows;
            test("真实书籍页数434及70条完整导入", () ->
                check(actual.pageCount == 434 && realRows.size() == 70, "actual input counts"));
            test("真实已有目录默认skip不创建输出", () ->
                rejectWrite(realPdf, new File(output, "real-skip.pdf"), realRows, false));
            test("真实153MB原件生成70条多级增量副本", () -> write("real", realPdf, realRows, true));
        }
        JSONObject report = new JSONObject().put("hostTests", passed).put("outputs", written)
            .put("runtime", "Official PdfBox-Android 2.0.27.0 AAR; test-only Android Log/resource adapters")
            .put("notDeviceTest", true);
        Files.writeString(new File(output, "engine-manifest.json").toPath(), report.toString(2), StandardCharsets.UTF_8);
        System.out.println("PASS: " + passed + " PDF 引擎宿主场景；未代替 Android 文件选择或平板实测。");
    }

    private static TocParser.Row row(int level, String title, int page) {
        return new TocParser.Row(level, title, null, page);
    }
    private static File input(String name) { return new File(fixtures, name + ".pdf"); }
    private static void invalidRows(List<TocParser.Row> rows) throws Exception {
        rejectWrite(input("plain"), new File(output, "invalid-rows-" + passed + ".pdf"), rows, true);
    }
    private static void rejectWrite(File source, File target, List<TocParser.Row> rows, boolean replace)
            throws Exception {
        check(!target.exists(), "negative target unexpectedly exists");
        reject(() -> PdfBookmarks.write(source, target, rows, replace));
        check(!target.exists(), "rejected write left partial output");
    }
    private static void reject(Checked operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("operation unexpectedly succeeded");
    }
    private static void write(String name, File source, List<TocParser.Row> rows, boolean replace)
            throws Exception {
        File target = new File(output, name + "-bookmarks.pdf");
        PdfBookmarks.write(source, target, rows, replace);
        PdfBookmarks.Info result = PdfBookmarks.inspect(target);
        check(result.existingCount == rows.size(), "saved outline count");
        JSONArray expected = new JSONArray();
        for (TocParser.Row row : rows) {
            expected.put(new JSONObject().put("level", row.level).put("title", row.title).put("pdfPage", row.pdfPage));
        }
        written.put(new JSONObject().put("source", source.getCanonicalPath())
            .put("output", target.getCanonicalPath()).put("expected", expected));
    }
    private static void test(String name, Checked operation) throws Exception {
        operation.run(); ++passed; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
