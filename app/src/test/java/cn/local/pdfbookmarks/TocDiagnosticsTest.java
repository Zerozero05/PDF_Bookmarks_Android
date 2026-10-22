package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class TocDiagnosticsTest {
    private static String desktop(String nodes) { return "{\"version\":1,\"bookmarks\":[" + nodes + "]}"; }
    private static String node(String title, int page) { return "{\"title\":\"" + title + "\",\"pdf_page\":" + page + "}"; }

    @Test public void collectsIndependentNodeProblemsWithLocationsInsteadOfFirstFailure() {
        String text = desktop("{\"title\":\"\",\"page\":1},"
                + "{\"title\":\"越界\",\"pdf_page\":11},"
                + "{\"title\":\"坏页码\",\"page\":\"2\"},"
                + "{\"pdf_page\":2},"
                + "{\"title\":\"漏页\"}");
        TocDiagnostics.Report report = TocDiagnostics.inspect(text, 10);
        assertTrue(report.hasErrors());
        assertNull(report.parsed);
        assertEquals(5, report.nodeCount);
        assertEquals(5, report.issues.size());
        assertEquals("bookmarks[1].title", report.issues.get(0).path);
        assertEquals("越界", report.issues.get(1).title);
        assertEquals("bookmarks[3].page", report.issues.get(2).path);
        assertEquals("bookmarks[4].title", report.issues.get(3).path);
        assertTrue(report.describe().contains("1–10"));
    }

    @Test public void duplicateCompletePathAndTargetIsWarningAndNeverRemoved() {
        TocDiagnostics.Report report = TocDiagnostics.inspect(desktop(node("参考", 1) + "," + node("参考", 1)
                + "," + node("参考", 2)), 2);
        assertFalse(report.hasErrors());
        assertNotNull(report.parsed);
        assertEquals(3, report.parsed.rows.size());
        assertEquals(1, report.issues.size());
        assertEquals(TocDiagnostics.Severity.WARNING, report.issues.get(0).severity);
        assertEquals("bookmarks[2]", report.issues.get(0).path);
        assertTrue(report.issues.get(0).reason.contains("bookmarks[1]"));
    }

    @Test public void differentParentsAndSeparatorTitlesDoNotCreateFalseDuplicates() {
        String nodes = "{\"title\":\"甲\",\"page\":1,\"children\":[" + node("参考", 2) + "]},"
                + "{\"title\":\"乙\",\"page\":1,\"children\":[" + node("参考", 2) + "]},"
                + node("甲 › 参考", 2);
        TocDiagnostics.Report report = TocDiagnostics.inspect(desktop(nodes), 2);
        assertFalse(report.hasErrors());
        assertTrue(report.issues.isEmpty());
        assertEquals(5, report.parsed.rows.size());
    }

    @Test public void collectsJasminumLevelMetadataAndChildErrors() {
        String text = "{\"info\":{\"schema\":2,\"itemID\":true},\"outline\":["
                + "{\"level\":2,\"title\":\"章\",\"page\":1,\"x\":\"72\",\"collapsed\":0,"
                + "\"children\":[{\"level\":2,\"title\":\"节\",\"page\":20}]},"
                + "{\"level\":1,\"title\":\"丙\",\"page\":2,\"children\":null}]}";
        TocDiagnostics.Report report = TocDiagnostics.inspect(text, 10);
        List<String> paths = report.issues.stream().map(issue -> issue.path).collect(Collectors.toList());
        assertEquals(6, report.issues.size());
        assertTrue(paths.contains("info.itemID"));
        assertTrue(paths.contains("outline[1].level"));
        assertTrue(paths.contains("outline[1].x"));
        assertTrue(paths.contains("outline[1].collapsed"));
        assertTrue(paths.contains("outline[1].children[1].page"));
        assertTrue(paths.contains("outline[2].children"));
    }

    @Test public void validFormatsUseExactlyTheOriginalParsersRowsAndPhysicalPages() {
        String[] texts = {
                "\uFEFF" + desktop(node(" 标题 ", 3)),
                "{\"version\":1,\"mapping\":{\"offset\":2},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}",
                "{\"version\":1,\"mapping\":{\"segments\":[{\"printed_start\":1,\"printed_end\":2,\"pdf_start\":2}]},\"bookmarks\":[{\"title\":\"甲\",\"page\":2}]}",
                "{\"info\":{\"schema\":2,\"baseFontSize\":12.5},\"outline\":[{\"level\":1,\"title\":\"甲\",\"page\":3,\"x\":1e2,\"y\":0,\"collapsed\":false}]}"
        };
        for (String text : texts) {
            TocDiagnostics.Report report = TocDiagnostics.inspect(text, 3);
            assertFalse(report.describe(), report.hasErrors());
            TocParser.Row expected = TocParser.parse(text, 3).rows.get(0), actual = report.parsed.rows.get(0);
            assertEquals(expected.title, actual.title);
            assertEquals(expected.pdfPage, actual.pdfPage);
            assertEquals(expected.printedPage, actual.printedPage);
        }
    }

    @Test public void strictInvalidInputsNeverProduceAcceptedRows() {
        String[] texts = {
                "/* comment */" + desktop(node("甲", 1)),
                desktop(node("甲", 1)) + " {}", "[]", "null", " ",
                "{\"version\":1,\"version\":1,\"bookmarks\":[]}",
                desktop("{\"title\":\"甲\",\"page\":1,\"p\\u0061ge\":2}"),
                desktop("{\"title\":\"甲\",\"page\":1.0}"),
                desktop("{\"title\":\"甲\",\"page\":1e0}"),
                desktop("{\"title\":\"甲\",\"page\":2147483648}"),
                desktop("{\"title\":\"甲\",\"page\":1,\"children\":{}}"),
                desktop("{\"title\":\"\\u3000\\u0085\",\"page\":1}"),
                desktop("{\"title\":\"甲\\u0000乙\",\"page\":1}"),
                desktop("{\"title\":\"甲\",\"page\":1,\"extra\":true}"),
                "{\"version\":1,\"mapping\":{\"offset\":0,\"segments\":[]},\"bookmarks\":[" + node("甲", 1) + "]}",
                "{\"version\":1,\"mapping\":{\"segments\":[{\"printed_start\":1,\"printed_end\":3,\"pdf_start\":1},{\"printed_start\":3,\"printed_end\":4,\"pdf_start\":4}]},\"bookmarks\":[" + node("甲", 1) + "]}"
        };
        for (String text : texts) {
            TocDiagnostics.Report report = TocDiagnostics.inspect(text, 10);
            assertTrue(text, report.hasErrors());
            assertNull(text, report.parsed);
            try { TocParser.parse(text, 10); fail("原 parser 不应接受非法样本"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }

    @Test public void malformedJsonHasOneAccurateSyntaxIssueAndNoPretendPartialNodes() {
        TocDiagnostics.Report report = TocDiagnostics.inspect("{\"version\":1,\"bookmarks\":[{\"title\":\"甲\",\"page\":]}", 10);
        assertEquals(1, report.issues.size());
        assertTrue(report.issues.get(0).reason.contains("JSON 格式错误"));
        assertTrue(report.issues.get(0).reason.contains("line"));
        assertEquals(0, report.nodeCount);
        assertNull(report.parsed);
    }

    @Test public void mapsEveryUsedPageAndValidatesUnusedSegmentBoundsWithoutOverflow() {
        String text = "{\"version\":1,\"mapping\":{\"offset\":2147483647},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}";
        TocDiagnostics.Report report = TocDiagnostics.inspect(text, 10);
        assertTrue(report.describe().contains("2147483648"));
        text = "{\"version\":1,\"mapping\":{\"segments\":[{\"printed_start\":1,\"printed_end\":20,\"pdf_start\":1}]},\"bookmarks\":[" + node("甲", 1) + "]}";
        assertTrue(TocDiagnostics.inspect(text, 10).describe().contains("完整映射范围"));
        text = "{\"version\":1,\"mapping\":{\"segments\":[{\"printed_start\":2,\"printed_end\":3,\"pdf_start\":1}]},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}";
        assertTrue(TocDiagnostics.inspect(text, 10).describe().contains("不在任何分段"));
    }

    @Test public void doesNotInventMvpEntryLimitAndKeepsDepthGuard() {
        StringBuilder nodes = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            if (i > 1) nodes.append(',');
            nodes.append(node("目录" + i, 1));
        }
        assertEquals(300, TocDiagnostics.inspect(desktop(nodes.toString()), 1).parsed.rows.size());
        String nested = node("末级", 1);
        for (int i = 0; i < 130; i++) nested = "{\"title\":\"层级\",\"pdf_page\":1,\"children\":[" + nested + "]}";
        assertTrue(TocDiagnostics.inspect(desktop(nested), 1).describe().contains("256"));
        assertTrue(TocDiagnostics.inspect(desktop(node("甲", 1)), 0).hasErrors());
    }

    @Test public void reportsAreImmutableAndInputIsNeverRewritten() {
        String text = desktop(node("甲", 1) + "," + node("甲", 1));
        TocDiagnostics.Report report = TocDiagnostics.inspect(text, 1);
        try { report.issues.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        assertEquals(2, TocParser.parse(text, 1).rows.size());
    }
}
