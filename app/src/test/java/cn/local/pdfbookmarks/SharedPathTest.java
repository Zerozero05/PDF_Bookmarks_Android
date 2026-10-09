package cn.local.pdfbookmarks;

import org.junit.Test;
import static org.junit.Assert.*;

/** 路径提示不是授权：这里验证归一化及可用于 SAF 授权判断的目录边界。 */
public class SharedPathTest {
    private static void rejected(String input) {
        try { SharedPath.documentId(input); fail("不应接受的路径：" + input); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void recognizesHuaweiDisplayedSharedPath() {
        assertEquals("primary:享做笔记/note/实分析与复分析.pdf",
                SharedPath.documentId("/享做笔记/note/实分析与复分析.pdf"));
        assertEquals("primary:Documents/目录.json", SharedPath.documentId("/Documents/目录.json"));
    }

    @Test public void sharedStorageAliasesResolveToTheSameDocument() {
        String expected = "primary:享做笔记/note/书.pdf";
        assertEquals(expected, SharedPath.documentId("/storage/emulated/0/享做笔记/note/书.pdf"));
        assertEquals(expected, SharedPath.documentId("/sdcard/享做笔记/note/书.pdf"));
        assertEquals(expected, SharedPath.documentId("享做笔记/note/书.pdf"));
        assertEquals(expected, SharedPath.documentId("  /sdcard/享做笔记//note/书.pdf  "));
    }

    @Test public void plainPathsKeepLiteralPercentPlusHashAndQuestionMark() {
        assertEquals("primary:Books/A+B%20C#D?.pdf",
                SharedPath.documentId("Books/A+B%20C#D?.pdf"));
        assertEquals("primary:Books/%2e%2e/书.pdf",
                SharedPath.documentId("Books/%2e%2e/书.pdf"));
    }

    @Test public void fileUriDecodesExactlyOnceAndKeepsPlus() {
        assertEquals("primary:Books/A+B C.pdf",
                SharedPath.documentId("file:///sdcard/Books/A+B%20C.pdf"));
        assertEquals("primary:Books/%2F.pdf",
                SharedPath.documentId("file:///storage/emulated/0/Books/%252F.pdf"));
        assertEquals("primary:享做笔记/中文.pdf",
                SharedPath.documentId("file:///sdcard/%E4%BA%AB%E5%81%9A%E7%AC%94%E8%AE%B0/%E4%B8%AD%E6%96%87.pdf"));
    }

    @Test public void rejectsTraversalBeforeAndAfterFileUriDecoding() {
        for (String input : new String[]{"../书.pdf", "Books/../书.pdf", "Books/./书.pdf",
                "/sdcard/Books/../../data/书.pdf", "file:///sdcard/Books/%2e%2e/书.pdf",
                "file:///sdcard/Books/%00.pdf", "Books/\u0000.pdf"}) rejected(input);
    }

    @Test public void rejectsPrivateAndUnknownAbsolutePathsAndWindowsPaths() {
        for (String input : new String[]{"/data/user/0/app/书.pdf", "/system/书.pdf",
                "/proc/书.pdf", "/unknown-system-root/书.pdf", "/storage/emulated/10/书.pdf",
                "/storage/emulated/0other/书.pdf", "/sdcard2/书.pdf", "/mnt/media_rw/书.pdf",
                "C:\\Books\\书.pdf", "D:/Books/书.pdf", "\\\\server\\Books\\书.pdf",
                "//server/Books/书.pdf"}) rejected(input);
    }

    @Test public void rejectsRestrictedAndroidDirectoriesWithoutMatchingOtherNames() {
        rejected("/sdcard/Android/data/app/书.pdf");
        rejected("Android/obb/app/书.pdf");
        rejected("file:///storage/emulated/0/Android/data/app/书.pdf");
        assertEquals("primary:Android/database/书.pdf", SharedPath.documentId("Android/database/书.pdf"));
        assertEquals("primary:Books/Android/data/书.pdf", SharedPath.documentId("Books/Android/data/书.pdf"));
    }

    @Test public void rejectsEmptyRemoteAndUnsupportedUris() {
        for (String input : new String[]{null, "", "   ", "/", "/sdcard", "/storage/emulated/0/",
                "file://server/sdcard/书.pdf", "file:///sdcard/书.pdf?query=1",
                "file:///sdcard/书.pdf#fragment", "file:///sdcard/%invalid.pdf",
                "https://example.test/书.pdf", "content://example/document/1"}) rejected(input);
    }

    @Test public void directoryGrantCoversOnlyItsOwnVolumeAndDescendants() {
        assertTrue(SharedPath.containsDocument("primary:Books", "primary:Books"));
        assertTrue(SharedPath.containsDocument("primary:Books", "primary:Books/章/书.pdf"));
        assertTrue(SharedPath.containsDocument("primary:", "primary:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:Books", "primary:Books2/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:Books/章", "primary:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:Books", "ABCD-1234:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("ABCD-1234:Books", "primary:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:Books", "primary:books/书.pdf"));
    }

    @Test public void malformedGrantIdsNeverExpandAccess() {
        assertFalse(SharedPath.containsDocument("primary:Books", "primary:Books/../private/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:Books/..", "primary:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("opaque-id", "primary:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:/Books", "primary:Books/书.pdf"));
        assertFalse(SharedPath.containsDocument("primary:Books", "primary:Books//书.pdf"));
    }

    @Test public void folderHintUsesTheParentWithoutChangingTheVolume() {
        assertEquals("primary:享做笔记/note",
                SharedPath.parentDocumentId(SharedPath.documentId("/享做笔记/note/书.pdf")));
        assertEquals("primary:", SharedPath.parentDocumentId(SharedPath.documentId("书.pdf")));
        assertEquals("primary:Books", SharedPath.parentDocumentId("primary:Books/%2F.pdf"));
    }
}
