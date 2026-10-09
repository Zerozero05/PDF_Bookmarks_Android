package cn.local.pdfbookmarks;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 仅同步入口必须拒绝损坏或不同的现有PDF书签，不能让界面崩溃或跳过生成。 */
public class CatalogOnlyMatchTest {
    private boolean matches(TocParser.Row existing, TocParser.Row requested) throws Exception {
        Method check = MainActivity.class.getDeclaredMethod("sameRows", List.class, List.class);
        check.setAccessible(true);
        return (Boolean) check.invoke(null, Arrays.asList(existing), Arrays.asList(requested));
    }

    @Test public void missingExistingTitleIsRejectedWithoutCrashing() throws Exception {
        assertFalse(matches(new TocParser.Row(1, null, null, 12), new TocParser.Row(1, "第一章", null, 12)));
    }

    @Test public void differentPageOrLevelCannotBypassPdfGeneration() throws Exception {
        TocParser.Row expected = new TocParser.Row(1, "第一章", null, 12);
        assertFalse(matches(new TocParser.Row(1, "第一章", null, 11), expected));
        assertFalse(matches(new TocParser.Row(2, "第一章", null, 12), expected));
        assertTrue(matches(new TocParser.Row(1, "第一章", 1, 12), expected));
    }
}
