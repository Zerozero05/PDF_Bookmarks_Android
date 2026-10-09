package cn.local.pdfbookmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 合成资料验证安全边界；真实笔记只通过显式系统属性以只读方式核对。 */
public class EnjoyCatalogTest {
    private static final String PDF = "/storage/emulated/0/享做笔记/note/test__res__/book.pdf";

    private static final class Fixture {
        JsonObject catalog = node("", "");
        JsonObject pageids = new JsonObject(), doc = new JsonObject();
        JsonArray infos = new JsonArray(), pages = new JsonArray();

        Fixture() {
            catalog.addProperty("expand", true);
            catalog.addProperty("usn", 125);
            pageids.addProperty("id", "test");
            pageids.add("infos", infos);
            doc.add("pages", pages);
            for (int i = 0; i < 10; i++) {
                JsonObject info = new JsonObject(), page = new JsonObject();
                info.addProperty("id", "pg" + i);
                info.addProperty("document", i == 0 ? "" : PDF);
                info.addProperty("origin", i == 0);
                info.addProperty("docIndex", i);
                // 这个字段与真正的 PDF 页索引不同，合并器不得使用它。
                info.addProperty("index", 99);
                info.addProperty("usn", 8);
                infos.add(info);
                page.addProperty("id", "pg" + i);
                page.addProperty("index", i);
                page.addProperty("width", 1482.0);
                pages.add(page);
            }
        }

        EnjoyCatalog.Plan merge(TocParser.Row... rows) throws IOException {
            return EnjoyCatalog.merge(bytes(catalog), bytes(pageids), bytes(doc), PDF, 10, Arrays.asList(rows));
        }

        EnjoyCatalog.Plan replace(TocParser.Row... rows) throws IOException {
            return EnjoyCatalog.merge(bytes(catalog), bytes(pageids), bytes(doc), PDF, 10, Arrays.asList(rows), true);
        }
    }

    private static JsonObject node(String title, String id) {
        JsonObject node = new JsonObject();
        node.addProperty("dirty", false);
        node.addProperty("expand", false);
        node.add("kids", new JsonArray());
        node.addProperty("page", -1);
        node.addProperty("pageId", id);
        node.addProperty("title", title);
        node.addProperty("usn", 0);
        return node;
    }

    private static TocParser.Row row(int level, String title, int physical) {
        return new TocParser.Row(level, title, null, physical);
    }

    private static byte[] bytes(JsonElement value) { return bytes(value.toString()); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JsonObject output(EnjoyCatalog.Plan plan) {
        return JsonParser.parseString(new String(plan.json, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void rejected(Fixture fixture, TocParser.Row... rows) {
        try { fixture.merge(rows); fail("无法安全核实的目录被接受"); }
        catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    private static void rejectedBytes(byte[] catalog, byte[] pageids, byte[] doc) {
        try { EnjoyCatalog.merge(catalog, pageids, doc, PDF, 10, Arrays.asList(row(1, "章", 2))); fail(); }
        catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void preservesManualTreesUnknownFieldsAndNativeRootValues() throws Exception {
        Fixture fixture = new Fixture();
        JsonObject manual = node("我的笔记", "pg3");
        manual.addProperty("dirty", true);
        manual.addProperty("expand", true);
        manual.addProperty("usn", 7);
        manual.add("userData", JsonParser.parseString("{\"colors\":[1,null,3],\"big\":123456789012345678901234567890}"));
        manual.getAsJsonArray("kids").add(node("手动子目录", "pg6"));
        fixture.catalog.getAsJsonArray("kids").add(manual);
        fixture.catalog.add("extra", JsonParser.parseString("{\"zoom\":1.00000,\"nullable\":null}"));
        byte[] originalCatalog = bytes(fixture.catalog), originalIds = bytes(fixture.pageids), originalDoc = bytes(fixture.doc);
        EnjoyCatalog.Plan plan = fixture.merge(row(1, "章", 2), row(2, "节", 3), row(1, "后章", 6));
        JsonObject result = output(plan);
        assertEquals(2, plan.originalCount);
        assertEquals(3, plan.addedCount);
        assertEquals(5, plan.totalCount);
        assertFalse(plan.replacesExisting);
        assertEquals(manual, result.getAsJsonArray("kids").get(0));
        for (String key : Arrays.asList("dirty", "expand", "page", "pageId", "title", "usn", "extra")) {
            assertEquals(fixture.catalog.get(key), result.get(key));
        }
        assertTrue(new String(plan.json, StandardCharsets.UTF_8).contains("123456789012345678901234567890"));
        assertTrue(new String(plan.json, StandardCharsets.UTF_8).contains("1.00000"));
        assertArrayEquals(originalCatalog, bytes(fixture.catalog));
        assertArrayEquals(originalIds, bytes(fixture.pageids));
        assertArrayEquals(originalDoc, bytes(fixture.doc));
    }

    @Test public void usesDocIndexAndCorroboratedIdDespiteMisleadingIndexAndOrder() throws Exception {
        Fixture fixture = new Fixture();
        JsonArray reversed = new JsonArray();
        for (int i = fixture.infos.size() - 1; i >= 0; i--) reversed.add(fixture.infos.get(i));
        fixture.pageids.add("infos", reversed);
        JsonObject added = output(fixture.merge(row(1, "目标", 6))).getAsJsonArray("kids").get(0).getAsJsonObject();
        assertEquals("pg5", added.get("pageId").getAsString());
        assertEquals(-1, added.get("page").getAsInt());
        assertEquals(0, added.get("usn").getAsInt());
        assertFalse(added.get("dirty").getAsBoolean());
    }

    @Test public void mergesChildrenUnderMatchingParentWithoutReplacingManualFields() throws Exception {
        Fixture fixture = new Fixture();
        JsonObject chapter = node("章", "pg1");
        chapter.addProperty("usn", 99);
        chapter.addProperty("dirty", true);
        chapter.addProperty("expand", true);
        JsonObject manual = node("手动节", "pg8");
        chapter.getAsJsonArray("kids").add(manual);
        fixture.catalog.getAsJsonArray("kids").add(chapter);
        EnjoyCatalog.Plan plan = fixture.merge(row(1, "章", 2), row(2, "新节", 3), row(1, "后章", 6));
        JsonObject resultChapter = output(plan).getAsJsonArray("kids").get(0).getAsJsonObject();
        assertEquals(2, plan.originalCount);
        assertEquals(2, plan.addedCount);
        assertEquals(4, plan.totalCount);
        assertEquals(99, resultChapter.get("usn").getAsInt());
        assertTrue(resultChapter.get("dirty").getAsBoolean());
        assertTrue(resultChapter.get("expand").getAsBoolean());
        assertEquals(manual, resultChapter.getAsJsonArray("kids").get(0));
        assertEquals("pg2", resultChapter.getAsJsonArray("kids").get(1).getAsJsonObject().get("pageId").getAsString());
    }

    @Test public void sameTitleDifferentPagesRemainDistinct() throws Exception {
        Fixture fixture = new Fixture();
        fixture.catalog.getAsJsonArray("kids").add(node("新建目录", "pg3"));
        EnjoyCatalog.Plan plan = fixture.merge(row(1, "新建目录", 2), row(1, "新建目录", 3));
        assertEquals(1, plan.originalCount);
        assertEquals(2, plan.addedCount);
        assertEquals(3, output(plan).getAsJsonArray("kids").size());
    }

    @Test public void identicalChildKeysInSeparateBranchesRemainSeparate() throws Exception {
        Fixture fixture = new Fixture();
        EnjoyCatalog.Plan plan = fixture.merge(row(1, "甲", 2), row(2, "同名节", 5),
                row(1, "乙", 3), row(2, "同名节", 5));
        assertEquals(4, plan.addedCount);
        JsonArray result = output(plan).getAsJsonArray("kids");
        assertEquals(1, result.get(0).getAsJsonObject().getAsJsonArray("kids").size());
        assertEquals(1, result.get(1).getAsJsonObject().getAsJsonArray("kids").size());
    }

    @Test public void repeatedImportReturnsExactOriginalBytesAndAddsNothing() throws Exception {
        Fixture fixture = new Fixture();
        TocParser.Row[] rows = {row(1, "甲", 2), row(2, "甲一", 3), row(1, "乙", 4)};
        EnjoyCatalog.Plan first = fixture.merge(rows);
        byte[] formatted = bytes(" \n" + new String(first.json, StandardCharsets.UTF_8) + "\n ");
        EnjoyCatalog.Plan second = EnjoyCatalog.merge(formatted, bytes(fixture.pageids), bytes(fixture.doc), PDF, 10, Arrays.asList(rows));
        assertEquals(3, second.originalCount);
        assertEquals(0, second.addedCount);
        assertEquals(3, second.totalCount);
        assertArrayEquals(formatted, second.json);
        assertNotSame(formatted, second.json);
    }

    @Test public void mixedNativePagesKeepOriginalValuesAndMergeByExplicitId() throws Exception {
        Fixture fixture = new Fixture();
        JsonObject chapter = node("章", "pg1");
        chapter.addProperty("page", 1);
        JsonObject manual = node("手动节", "pg8");
        manual.addProperty("page", 8);
        chapter.getAsJsonArray("kids").add(manual);
        fixture.catalog.getAsJsonArray("kids").add(chapter);
        JsonObject firstPage = node("旧封面", "pg0");
        firstPage.addProperty("page", 0);
        fixture.catalog.getAsJsonArray("kids").add(firstPage);
        byte[] before = bytes(fixture.catalog);
        TocParser.Row[] rows = {row(1, "章", 2), row(2, "新节", 3)};
        EnjoyCatalog.Plan plan = fixture.merge(rows);
        assertEquals(3, plan.originalCount);
        assertEquals(1, plan.addedCount);
        JsonArray kids = output(plan).getAsJsonArray("kids");
        JsonObject merged = kids.get(0).getAsJsonObject();
        assertEquals(1, merged.get("page").getAsInt());
        assertEquals(manual, merged.getAsJsonArray("kids").get(0));
        assertEquals(-1, merged.getAsJsonArray("kids").get(1).getAsJsonObject().get("page").getAsInt());
        assertEquals(firstPage, kids.get(1));
        assertArrayEquals(before, bytes(fixture.catalog));
        EnjoyCatalog.Plan repeat = EnjoyCatalog.merge(plan.json, bytes(fixture.pageids), bytes(fixture.doc), PDF, 10, Arrays.asList(rows));
        assertEquals(0, repeat.addedCount);
        assertArrayEquals(plan.json, repeat.json);
    }

    @Test public void nativeNumericPageNeverReplacesMissingOrWrongPdfMapping() {
        Fixture fixture = new Fixture();
        JsonObject manual = node("旧封面", "pg0");
        manual.addProperty("page", 0);
        fixture.catalog.getAsJsonArray("kids").add(manual);
        rejected(fixture, row(1, "封面", 1));
        fixture.infos.get(1).getAsJsonObject().addProperty("document", PDF.replace("book.pdf", "other.pdf"));
        manual.addProperty("page", 1);
        rejected(fixture, row(1, "旧封面", 2));
        fixture = new Fixture();
        JsonObject legacy = node("未适配页标识", "");
        legacy.addProperty("page", 1);
        fixture.catalog.getAsJsonArray("kids").add(legacy);
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsInvalidNativePageTypesAndUnknownNegativeSentinels() {
        for (String value : new String[]{"\"1\"", "1.0", "1e0", "true", "null", "-2", "2147483648"}) {
            Fixture fixture = new Fixture();
            JsonObject manual = node("手动", "pg3");
            manual.add("page", JsonParser.parseString(value));
            fixture.catalog.getAsJsonArray("kids").add(manual);
            byte[] before = bytes(fixture.catalog);
            rejected(fixture, row(1, "章", 2));
            assertArrayEquals(before, bytes(fixture.catalog));
        }
    }

    @Test public void actualMixedNativeFixturePreservesAllOriginalNodes() throws Exception {
        String directory = System.getProperty("enjoy.mixedFixtureFolder");
        Assume.assumeTrue(directory != null);
        Path folder = Paths.get(directory);
        byte[] catalog = Files.readAllBytes(folder.resolve("catalog.json"));
        byte[] ids = Files.readAllBytes(folder.resolve("pageids.json"));
        byte[] doc = Files.readAllBytes(folder.resolve("doc"));
        String pdf = null;
        for (JsonElement value : JsonParser.parseString(new String(ids, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("infos")) {
            String path = value.getAsJsonObject().get("document").getAsString();
            if (!path.isEmpty()) { pdf = path; break; }
        }
        assertNotNull(pdf);
        // 实际只读PDF检查确认344页；此样本已有10条目录，混合page=-1和19/21/22/26/28。
        TocParser.Row[] rows = {row(1, "译者序", 4), row(1, "第1章 抽象积分", 13), row(2, "测度的初等性质", 20)};
        EnjoyCatalog.Plan plan = EnjoyCatalog.merge(catalog, ids, doc, pdf, 344, Arrays.asList(rows));
        assertEquals(10, plan.originalCount);
        assertEquals(1, plan.addedCount);
        JsonObject original = JsonParser.parseString(new String(catalog, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject merged = output(plan);
        for (String key : Arrays.asList("dirty", "expand", "page", "pageId", "title", "usn")) assertEquals(original.get(key), merged.get(key));
        JsonArray oldKids = original.getAsJsonArray("kids"), newKids = merged.getAsJsonArray("kids");
        for (int i = 0; i < oldKids.size(); i++) assertEquals(oldKids.get(i), newKids.get(i));
        assertEquals(19, newKids.get(1).getAsJsonObject().getAsJsonArray("kids").get(3).getAsJsonObject().get("page").getAsInt());
        EnjoyCatalog.Plan repeat = EnjoyCatalog.merge(plan.json, ids, doc, pdf, 344, Arrays.asList(rows));
        assertEquals(0, repeat.addedCount);
        assertArrayEquals(plan.json, repeat.json);
        EnjoyCatalog.Plan replacement = EnjoyCatalog.merge(catalog, ids, doc, pdf, 344, Arrays.asList(rows), true);
        assertTrue(replacement.replacesExisting);
        assertEquals(10, replacement.originalCount);
        assertEquals(3, replacement.totalCount);
        JsonObject replaced = output(replacement);
        assertEquals(2, replaced.getAsJsonArray("kids").size());
        assertEquals(1, replaced.getAsJsonArray("kids").get(1).getAsJsonObject().getAsJsonArray("kids").size());
        for (String key : Arrays.asList("dirty", "expand", "page", "pageId", "title", "usn")) assertEquals(original.get(key), replaced.get(key));
        assertEquals(-1, replaced.getAsJsonArray("kids").get(1).getAsJsonObject().getAsJsonArray("kids").get(0).getAsJsonObject().get("page").getAsInt());
    }

    @Test public void replacementUsesOnlyImportedTreeWhilePreservingRootAndSourceBytes() throws Exception {
        Fixture fixture = new Fixture();
        JsonObject manual = node("旧手动章", "pg8");
        manual.addProperty("page", 8);
        manual.getAsJsonArray("kids").add(node("旧手动节", "pg9"));
        fixture.catalog.getAsJsonArray("kids").add(manual);
        fixture.catalog.add("userState", JsonParser.parseString("{\"zoom\":1.00000,\"note\":null}"));
        byte[] catalog = bytes(fixture.catalog), ids = bytes(fixture.pageids), doc = bytes(fixture.doc);
        EnjoyCatalog.Plan plan = fixture.replace(row(1, "新章", 2), row(2, "新节", 3));
        assertTrue(plan.replacesExisting);
        assertEquals(2, plan.originalCount);
        assertEquals(2, plan.addedCount);
        assertEquals(2, plan.totalCount);
        JsonObject result = output(plan);
        for (String key : Arrays.asList("dirty", "expand", "page", "pageId", "title", "usn", "userState")) assertEquals(fixture.catalog.get(key), result.get(key));
        JsonArray kids = result.getAsJsonArray("kids");
        assertEquals(1, kids.size());
        assertEquals("新章", kids.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("pg2", kids.get(0).getAsJsonObject().getAsJsonArray("kids").get(0).getAsJsonObject().get("pageId").getAsString());
        assertArrayEquals(catalog, bytes(fixture.catalog));
        assertArrayEquals(ids, bytes(fixture.pageids));
        assertArrayEquals(doc, bytes(fixture.doc));
    }

    @Test public void selectedModeControlsWhetherOldManualAndDifferentTargetsRemain() throws Exception {
        Fixture fixture = new Fixture();
        fixture.catalog.getAsJsonArray("kids").add(node("同名章", "pg8"));
        fixture.catalog.getAsJsonArray("kids").add(node("手动备注", "pg9"));
        EnjoyCatalog.Plan preserved = fixture.merge(row(1, "同名章", 2));
        EnjoyCatalog.Plan replaced = fixture.replace(row(1, "同名章", 2));
        assertFalse(preserved.replacesExisting);
        assertEquals(3, preserved.totalCount);
        assertTrue(replaced.replacesExisting);
        assertEquals(1, replaced.totalCount);
        assertEquals(1, output(replaced).getAsJsonArray("kids").size());
        assertEquals("pg1", output(replaced).getAsJsonArray("kids").get(0).getAsJsonObject().get("pageId").getAsString());
    }

    @Test public void repeatingIdenticalReplacementDoesNotRewriteOrCreateNewChanges() throws Exception {
        Fixture fixture = new Fixture();
        fixture.catalog.getAsJsonArray("kids").add(node("旧目录", "pg8"));
        TocParser.Row[] rows = {row(1, "新章", 2), row(2, "新节", 3)};
        EnjoyCatalog.Plan first = fixture.replace(rows);
        byte[] formatted = bytes("\n " + new String(first.json, StandardCharsets.UTF_8) + "\n");
        EnjoyCatalog.Plan repeat = EnjoyCatalog.merge(formatted, bytes(fixture.pageids), bytes(fixture.doc), PDF, 10, Arrays.asList(rows), true);
        assertTrue(repeat.replacesExisting);
        assertEquals(2, repeat.originalCount);
        assertEquals(0, repeat.addedCount);
        assertEquals(2, repeat.totalCount);
        assertArrayEquals(formatted, repeat.json);
    }

    @Test public void replacementDoesNotBypassInvalidMappingOrAlterOriginalOnFailure() {
        Fixture fixture = new Fixture();
        fixture.catalog.getAsJsonArray("kids").add(node("我的目录", "pg8"));
        fixture.infos.get(1).getAsJsonObject().addProperty("document", PDF.replace("book.pdf", "other.pdf"));
        byte[] before = bytes(fixture.catalog);
        try { fixture.replace(row(1, "新目录", 2)); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("映射")); }
        assertArrayEquals(before, bytes(fixture.catalog));
    }

    @Test public void replacementStillRejectsAmbiguousIncomingTree() {
        Fixture fixture = new Fixture();
        fixture.catalog.getAsJsonArray("kids").add(node("我的目录", "pg8"));
        byte[] before = bytes(fixture.catalog);
        try { fixture.replace(row(1, "重复章", 2), row(1, "重复章", 2)); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("重复")); }
        assertArrayEquals(before, bytes(fixture.catalog));
    }

    @Test public void sharedStorageAliasesNormalizeButCaseIsNotGuessed() throws Exception {
        Fixture fixture = new Fixture();
        EnjoyCatalog.Plan plan = EnjoyCatalog.merge(bytes(fixture.catalog), bytes(fixture.pageids), bytes(fixture.doc),
                PDF.replace("/storage/emulated/0", "/sdcard"), 10, Arrays.asList(row(1, "章", 2)));
        assertEquals(1, plan.addedCount);
        try {
            EnjoyCatalog.merge(bytes(fixture.catalog), bytes(fixture.pageids), bytes(fixture.doc),
                    PDF.replace("book.pdf", "BOOK.pdf"), 10, Arrays.asList(row(1, "章", 2)));
            fail("不应猜测大小写不同的文件相同");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("映射")); }
    }

    @Test public void doesNotBorrowPageMappingsFromAnotherPdf() {
        Fixture fixture = new Fixture();
        fixture.infos.get(1).getAsJsonObject().addProperty("document", PDF.replace("book.pdf", "other.pdf"));
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsUnmappedFirstPdfPageInsteadOfGuessingOriginPage() {
        rejected(new Fixture(), row(1, "封面", 1));
    }

    @Test public void acceptsLinkedOriginPageWithExplicitCorroboratedMapping() throws Exception {
        Fixture fixture = new Fixture();
        fixture.infos.get(1).getAsJsonObject().addProperty("origin", true);
        EnjoyCatalog.Plan plan = fixture.merge(row(1, "有笔迹的页", 2));
        assertEquals("pg1", output(plan).getAsJsonArray("kids").get(0).getAsJsonObject().get("pageId").getAsString());
    }

    @Test public void rejectsDuplicatePdfIndexEvenWhenIdsDiffer() {
        Fixture fixture = new Fixture();
        fixture.infos.get(2).getAsJsonObject().addProperty("docIndex", 1);
        fixture.pages.get(2).getAsJsonObject().addProperty("index", 1);
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsDuplicateIdsInEitherPageSource() {
        Fixture fixture = new Fixture();
        fixture.infos.add(fixture.infos.get(1).deepCopy());
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.pages.add(fixture.pages.get(1).deepCopy());
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsMissingAndMismatchedDocCorroboration() {
        Fixture fixture = new Fixture();
        fixture.pages.get(1).getAsJsonObject().addProperty("index", 5);
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.pages.remove(1);
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsOutOfBoundsSelectedMappingEvenIfRequestedRowLooksValid() {
        Fixture fixture = new Fixture();
        fixture.infos.get(9).getAsJsonObject().addProperty("docIndex", 10);
        fixture.pages.get(9).getAsJsonObject().addProperty("index", 10);
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsUnknownNativeFieldsAndWrongPrimitiveTypes() {
        for (String value : new String[]{"\"1\"", "1.0", "1e0", "true", "null", "-1", "2147483648"}) {
            Fixture fixture = new Fixture();
            fixture.infos.get(1).getAsJsonObject().add("docIndex", JsonParser.parseString(value));
            rejected(fixture, row(1, "章", 2));
        }
        Fixture fixture = new Fixture();
        fixture.catalog.remove("kids");
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.catalog.addProperty("page", 0);
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.catalog.addProperty("dirty", "false");
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.infos.get(1).getAsJsonObject().addProperty("origin", "true");
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.infos.get(1).getAsJsonObject().remove("document");
        rejected(fixture, row(1, "章", 2));
        fixture = new Fixture();
        fixture.pageids.remove("id");
        rejected(fixture, row(1, "章", 2));
    }

    @Test public void rejectsAmbiguousExistingSiblingsWithoutChangingInputs() {
        Fixture fixture = new Fixture();
        fixture.catalog.getAsJsonArray("kids").add(node("章", "pg1"));
        fixture.catalog.getAsJsonArray("kids").add(node("章", "pg1"));
        byte[] before = bytes(fixture.catalog);
        rejected(fixture, row(1, "章", 2));
        assertArrayEquals(before, bytes(fixture.catalog));
    }

    @Test public void rejectsAmbiguousInputAndInvalidLevelsTitlesOrPages() {
        rejected(new Fixture(), row(1, "章", 2), row(1, "章", 2));
        rejected(new Fixture(), row(2, "孤儿节", 2));
        rejected(new Fixture(), row(1, "章", 2), row(3, "跳级", 3));
        rejected(new Fixture(), row(0, "章", 2));
        rejected(new Fixture(), row(1, "章", 0));
        rejected(new Fixture(), row(1, "章", 11));
        rejected(new Fixture(), row(1, "", 2));
        rejected(new Fixture(), row(1, " \t", 2));
        rejected(new Fixture(), row(1, "含\u0000字符", 2));
        rejected(new Fixture(), (TocParser.Row) null);
    }

    @Test public void rejectsMalformedDuplicateKeysInAllThreeFiles() {
        Fixture fixture = new Fixture();
        for (String text : new String[]{"{} {}", "{\"dirty\":false,}", "{/*注释*/}",
                "{\"kids\":[],\"k\\u0069ds\":[]}", "{\"extra\":{\"a\":1,\"a\":2}}"}) {
            rejectedBytes(bytes(text), bytes(fixture.pageids), bytes(fixture.doc));
        }
        rejectedBytes(bytes(fixture.catalog), bytes("{\"id\":\"test\",\"infos\":[],\"infos\":[]}"), bytes(fixture.doc));
        rejectedBytes(bytes(fixture.catalog), bytes(fixture.pageids), bytes("{\"pages\":[],\"pages\":[]}"));
        rejectedBytes(bytes(fixture.catalog), bytes(fixture.pageids), bytes("{\"pages\":[{\"id\":\"pg1\",\"index\":1,\"index\":1}]}"));
    }

    @Test public void rejectsInvalidUtf8AndExcessiveJsonNesting() {
        Fixture fixture = new Fixture();
        rejectedBytes(new byte[]{(byte) 0xc3, (byte) 0x28}, bytes(fixture.pageids), bytes(fixture.doc));
        String text = "{\"extra\":" + "[".repeat(257) + "0" + "]".repeat(257) + "}";
        rejectedBytes(bytes(text), bytes(fixture.pageids), bytes(fixture.doc));
    }

    @Test public void exactFixtureMatchesVerifiedMergeAndIsIdempotent() throws Exception {
        String directory = System.getProperty("enjoy.fixtureFolder"), toc = System.getProperty("jasminum.fixture");
        Assume.assumeTrue(directory != null && toc != null);
        Path folder = Paths.get(directory);
        byte[] catalog = Files.readAllBytes(folder.resolve("catalog.json"));
        byte[] pageids = Files.readAllBytes(folder.resolve("pageids.json"));
        byte[] doc = Files.readAllBytes(folder.resolve("doc"));
        JsonArray infos = JsonParser.parseString(new String(pageids, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("infos");
        String pdfPath = null;
        for (JsonElement value : infos) {
            JsonObject info = value.getAsJsonObject();
            if (!info.get("origin").getAsBoolean() && !info.get("document").getAsString().isEmpty()) {
                pdfPath = info.get("document").getAsString();
                break;
            }
        }
        assertNotNull(pdfPath);
        int pageCount = Integer.parseInt(System.getProperty("jasminum.actualPageCount", "434"));
        List<TocParser.Row> rows = TocParser.parse(new String(Files.readAllBytes(Paths.get(toc)), StandardCharsets.UTF_8), pageCount).rows;
        EnjoyCatalog.Plan first = EnjoyCatalog.merge(catalog, pageids, doc, pdfPath, pageCount, rows);
        assertEquals(2, first.originalCount);
        assertEquals(70, first.addedCount);
        assertEquals(72, first.totalCount);
        // 与已经回读并获用户实机确认的候选逐字段比较，不能只证明数量相等。
        Path planFile = folder.getParent().resolve("catalog-update-plan.json");
        JsonObject recordedPlan = JsonParser.parseString(new String(Files.readAllBytes(planFile), StandardCharsets.UTF_8)).getAsJsonObject();
        String stagedName = recordedPlan.get("stagingName").getAsString();
        assertTrue(stagedName.matches("pdf-bookmarks-catalog-[A-Za-z0-9-]+-staged\\.json"));
        JsonObject verifiedCandidate = JsonParser.parseString(new String(Files.readAllBytes(folder.getParent().resolve(stagedName)), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(verifiedCandidate, output(first));
        JsonArray oldKids = JsonParser.parseString(new String(catalog, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("kids");
        JsonArray mergedKids = output(first).getAsJsonArray("kids");
        for (int i = 0; i < oldKids.size(); i++) assertEquals(oldKids.get(i), mergedKids.get(i));
        EnjoyCatalog.Plan second = EnjoyCatalog.merge(first.json, pageids, doc, pdfPath, pageCount, rows);
        assertEquals(0, second.addedCount);
        assertArrayEquals(first.json, second.json);
    }
}
