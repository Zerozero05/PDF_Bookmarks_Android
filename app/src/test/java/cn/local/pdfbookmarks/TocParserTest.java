package cn.local.pdfbookmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 纯 Java 回归；真实用户目录只通过显式系统属性以只读方式加载。 */
public class TocParserTest {
    private static String desktop(String bookmark) {
        return "{\"version\":1,\"bookmarks\":[" + bookmark + "]}";
    }
    private static String jasminum(String outline) {
        return "{\"info\":{\"schema\":2},\"outline\":[" + outline + "]}";
    }
    private static void rejected(String text) {
        try { TocParser.parse(text, 500); fail("非法目录被接受：" + text); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void desktopDefaultsAndPreservesTitle() {
        TocParser.Parsed parsed = TocParser.parse("\uFEFF" + desktop("{\"title\":\" 第一章 \" ,\"page\":3}"), 3);
        assertEquals("desktop", parsed.sourceKind);
        assertEquals(1, parsed.rows.size());
        TocParser.Row row = parsed.rows.get(0);
        assertEquals(" 第一章 ", row.title);
        assertEquals(Integer.valueOf(3), row.printedPage);
        assertEquals(3, row.pdfPage);
        assertEquals(1, row.level);
    }

    @Test public void desktopKeepsCompleteHierarchyAndInputOrder() {
        String nodes = "{\"title\":\"甲\",\"page\":1,\"children\":[{\"title\":\"甲一\",\"page\":2,\"children\":[{\"title\":\"甲一一\",\"pdf_page\":4}]}]},"
                + "{\"title\":\"乙\",\"pdf_page\":3,\"children\":[]}";
        TocParser.Parsed parsed = TocParser.parse("{\"version\":1,\"mapping\":{\"offset\":2},\"bookmarks\":[" + nodes + "]}", 10);
        assertEquals(4, parsed.rows.size());
        int[] levels = {1, 2, 3, 1}, pages = {3, 4, 4, 3};
        for (int i = 0; i < levels.length; i++) {
            assertEquals(levels[i], parsed.rows.get(i).level);
            assertEquals(pages[i], parsed.rows.get(i).pdfPage);
        }
        assertNull(parsed.rows.get(2).printedPage);
    }

    @Test public void supportsNegativeOffsetAndDirectPageBypassesMapping() {
        TocParser.Parsed parsed = TocParser.parse("{\"version\":1,\"mapping\":{\"offset\":-3},\"bookmarks\":[{\"title\":\"甲\",\"page\":4},{\"title\":\"乙\",\"pdf_page\":2}]}", 2);
        assertEquals(1, parsed.rows.get(0).pdfPage);
        assertEquals(2, parsed.rows.get(1).pdfPage);
        assertNull(parsed.rows.get(1).printedPage);
    }

    @Test public void segmentsSortAndIncludeBothBoundaries() {
        String mapping = "{\"segments\":[{\"printed_start\":10,\"printed_end\":12,\"pdf_start\":4},{\"printed_start\":1,\"printed_end\":3,\"pdf_start\":1}]}";
        TocParser.Parsed parsed = TocParser.parse("{\"version\":1,\"mapping\":" + mapping + ",\"bookmarks\":[{\"title\":\"甲\",\"page\":3},{\"title\":\"乙\",\"page\":10},{\"title\":\"丙\",\"page\":12}]}", 6);
        assertEquals(3, parsed.rows.get(0).pdfPage);
        assertEquals(4, parsed.rows.get(1).pdfPage);
        assertEquals(6, parsed.rows.get(2).pdfPage);
    }

    @Test public void jasminumPageIsAlreadyPhysicalAndCoordinatesAreIgnored() {
        String node = "{\"level\":1,\"title\":\"章\",\"page\":9,\"x\":72,\"y\":809.788,\"collapsed\":true,\"children\":[{\"level\":2,\"title\":\"节\",\"page\":12,\"children\":[],\"collapsed\":false}]}";
        TocParser.Parsed parsed = TocParser.parse(jasminum(node), 12);
        assertEquals("jasminum", parsed.sourceKind);
        assertEquals(2, parsed.rows.size());
        assertEquals(9, parsed.rows.get(0).pdfPage);
        assertEquals(12, parsed.rows.get(1).pdfPage);
        assertEquals(2, parsed.rows.get(1).level);
        assertNull(parsed.rows.get(0).printedPage);
    }

    @Test public void acceptsMoreThanFiveEntriesAndRepeatedReferenceTitles() {
        StringBuilder nodes = new StringBuilder();
        for (int i = 1; i <= 70; i++) {
            if (i > 1) nodes.append(',');
            nodes.append("{\"title\":\"参考文献\",\"pdf_page\":").append(i).append('}');
        }
        assertEquals(70, TocParser.parse(desktop(nodes.toString()), 70).rows.size());
    }

    @Test public void rejectsUnknownFieldsAtEveryDesktopLevel() {
        rejected("{\"version\":1,\"bookmarks\":[{\"title\":\"甲\",\"page\":1}],\"docname\":\"猜测\"}");
        rejected("{\"version\":1,\"mapping\":{\"offset\":0,\"extra\":1},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
        rejected(desktop("{\"title\":\"甲\",\"page\":1,\"level\":1}"));
        rejected("{\"version\":1,\"mapping\":{\"segments\":[{\"printed_start\":1,\"printed_end\":2,\"pdf_start\":1,\"extra\":0}]},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
    }

    @Test public void rejectsDuplicateKeysIncludingEscapedKeysAndMetadata() {
        rejected("{\"version\":1,\"version\":1,\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
        rejected(desktop("{\"title\":\"甲\",\"title\":\"乙\",\"page\":1}"));
        rejected(desktop("{\"title\":\"甲\",\"page\":1,\"p\\u0061ge\":2}"));
        rejected("{\"info\":{\"schema\":2,\"schema\":2},\"outline\":[{\"level\":1,\"title\":\"甲\",\"page\":1}]}");
    }

    @Test public void rejectsStringBooleanDecimalAndExponentIntegers() {
        for (String value : new String[]{"\"1\"", "true", "false", "1.0", "1e0", "null", "2147483648"}) {
            rejected(desktop("{\"title\":\"甲\",\"page\":" + value + "}"));
            rejected(jasminum("{\"title\":\"甲\",\"level\":1,\"page\":" + value + "}"));
        }
        rejected("{\"version\":1.0,\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
        rejected("{\"version\":1,\"mapping\":{\"offset\":true},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
    }

    @Test public void rejectsEmptyAndNulTitlesButPreservesUnicode() {
        for (String value : new String[]{"null", "true", "1", "\"\"", "\" \\t \\n\"", "\"\\u3000\\u00a0\\u0085\"", "\"甲\\u0000乙\""}) {
            rejected(desktop("{\"title\":" + value + ",\"page\":1}"));
        }
        assertEquals("S²、P₂ 和 T²", TocParser.parse(desktop("{\"title\":\"S²、P₂ 和 T²\",\"page\":1}"), 1).rows.get(0).title);
    }

    @Test public void rejectsMissingOrAmbiguousPagesAndInvalidChildArrays() {
        rejected(desktop("{\"title\":\"甲\"}"));
        rejected(desktop("{\"title\":\"甲\",\"page\":1,\"pdf_page\":1}"));
        rejected(desktop("{\"title\":\"甲\",\"pdf_page\":0}"));
        rejected(desktop("{\"title\":\"甲\",\"page\":1,\"children\":null}"));
        rejected(desktop("{\"title\":\"甲\",\"page\":1,\"children\":{}}"));
        rejected("{\"version\":1,\"bookmarks\":[]}");
    }

    @Test public void rejectsBothMappingsAndNullOrEmptyMappings() {
        for (String mapping : new String[]{"null", "{}", "{\"offset\":0,\"segments\":[]}", "{\"segments\":[]}", "{\"segments\":null}"}) {
            rejected("{\"version\":1,\"mapping\":" + mapping + ",\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
        }
    }

    @Test public void rejectsSegmentOverlapReversalMissingAndUncoveredPage() {
        String[] segments = {
                "{\"printed_start\":1,\"printed_end\":2,\"pdf_start\":1},{\"printed_start\":2,\"printed_end\":3,\"pdf_start\":3}",
                "{\"printed_start\":3,\"printed_end\":2,\"pdf_start\":1}",
                "{\"printed_start\":1,\"printed_end\":2}",
                "{\"printed_start\":2,\"printed_end\":3,\"pdf_start\":1}",
                "{\"printed_start\":0,\"printed_end\":2,\"pdf_start\":1}"
        };
        for (String value : segments) rejected("{\"version\":1,\"mapping\":{\"segments\":[" + value + "]},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
    }

    @Test public void validatesEntireSegmentRangeEvenWhenNotUsed() {
        try {
            TocParser.parse("{\"version\":1,\"mapping\":{\"segments\":[{\"printed_start\":1,\"printed_end\":10,\"pdf_start\":5}]},\"bookmarks\":[{\"title\":\"甲\",\"pdf_page\":1}]}", 10);
            fail("未使用但越界的分段不应被接受");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("完整映射范围")); }
    }

    @Test public void rejectsPageRangeAndAvoidsIntegerOverflow() {
        for (String offset : new String[]{"-1", "2147483647"}) {
            rejected("{\"version\":1,\"mapping\":{\"offset\":" + offset + "},\"bookmarks\":[{\"title\":\"甲\",\"page\":1}]}");
        }
        try { TocParser.parse(jasminum("{\"level\":1,\"title\":\"甲\",\"page\":10}"), 9); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("超出")); }
        try { TocParser.parse(desktop("{\"title\":\"甲\",\"page\":1}"), 0); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("实际总页数")); }
    }

    @Test public void rejectsJasminumLevelJumpsAndSchemaMismatch() {
        rejected(jasminum("{\"level\":2,\"title\":\"甲\",\"page\":1}"));
        rejected(jasminum("{\"level\":1,\"title\":\"甲\",\"page\":1,\"children\":[{\"level\":3,\"title\":\"乙\",\"page\":2}]}"));
        rejected("{\"info\":{\"schema\":1},\"outline\":[{\"level\":1,\"title\":\"甲\",\"page\":1}]}");
        rejected("{\"info\":{\"schema\":2},\"outline\":[]}");
    }

    @Test public void rejectsUnknownJasminumFieldsAndWrongMetadataTypes() {
        rejected(jasminum("{\"level\":1,\"title\":\"甲\",\"page\":1,\"pdf_page\":1}"));
        rejected(jasminum("{\"level\":1,\"title\":\"甲\",\"page\":1,\"x\":\"72\"}"));
        rejected(jasminum("{\"level\":1,\"title\":\"甲\",\"page\":1,\"collapsed\":0}"));
        rejected("{\"info\":{\"schema\":2,\"count\":500},\"outline\":[{\"level\":1,\"title\":\"甲\",\"page\":1}]}");
    }

    @Test public void strictJsonRejectsCommentsTrailingDocumentsAndUnquotedSyntax() {
        for (String raw : new String[]{
                desktop("{\"title\":\"甲\",\"page\":1}") + " {}",
                "/* comment */" + desktop("{\"title\":\"甲\",\"page\":1}"),
                "{version:1,bookmarks:[]}", "{'version':1,'bookmarks':[]}",
                "{\"version\":1,\"bookmarks\":[{\"title\":\"甲\",\"page\":1},]}",
                desktop("{\"title\":\"甲\",\"page\":NaN}"), "[]", "null", "", " "}) rejected(raw);
    }

    @Test public void resultRowsCannotBeMutated() {
        TocParser.Parsed parsed = TocParser.parse(desktop("{\"title\":\"甲\",\"page\":1}"), 1);
        try { parsed.rows.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void realUserJasminumKeepsAllSeventyRowsWithoutPageOffset() throws Exception {
        String fixture = System.getProperty("jasminum.fixture");
        String pages = System.getProperty("jasminum.actualPageCount");
        Assume.assumeTrue("须显式提供只读用户JSON路径和从PDF实读的总页数", fixture != null && pages != null);
        String raw = new String(Files.readAllBytes(Paths.get(fixture)), StandardCharsets.UTF_8);
        TocParser.Parsed parsed = TocParser.parse(raw, Integer.parseInt(pages));
        assertEquals("jasminum", parsed.sourceKind);
        assertEquals(70, parsed.rows.size());
        List<JsonObject> expected = new ArrayList<>();
        flatten(JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("outline"), expected);
        assertEquals(70, expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonObject source = expected.get(i);
            TocParser.Row row = parsed.rows.get(i);
            assertEquals(source.get("title").getAsString(), row.title);
            assertEquals(source.get("level").getAsInt(), row.level);
            assertEquals(source.get("page").getAsInt(), row.pdfPage);
            assertNull(row.printedPage);
        }
        assertEquals(9, parsed.rows.get(0).pdfPage);
        assertEquals(429, parsed.rows.get(69).pdfPage);
    }

    private static void flatten(JsonArray nodes, List<JsonObject> result) {
        for (JsonElement element : nodes) {
            JsonObject node = element.getAsJsonObject();
            result.add(node);
            if (node.has("children")) flatten(node.getAsJsonArray("children"), result);
        }
    }
}
