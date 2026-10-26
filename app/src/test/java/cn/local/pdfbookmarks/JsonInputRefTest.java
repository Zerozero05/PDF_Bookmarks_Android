package cn.local.pdfbookmarks;

import org.junit.Test;
import static org.junit.Assert.*;

public class JsonInputRefTest {
    private static final String ID = "12345678-1234-4234-9234-123456789abc";
    private static final String HASH = "a".repeat(64);

    @Test public void preservesOriginalProviderIdentityAndNormalizesOnlyHash() {
        JsonInputRef ref = new JsonInputRef(ID, "content://qq.provider/Android/data/opaque%20name.json?version=1",
                "目录.JSON", HASH.toUpperCase(java.util.Locale.ROOT));
        assertEquals("content://qq.provider/Android/data/opaque%20name.json?version=1", ref.sourceUri);
        assertEquals("目录.JSON", ref.displayName); assertEquals(HASH, ref.sha256);
        assertEquals(ref, new JsonInputRef(ID, ref.sourceUri, ref.displayName, HASH));
        assertEquals(ref.hashCode(), new JsonInputRef(ID, ref.sourceUri, ref.displayName, HASH).hashCode());
    }

    @Test public void rejectsPathsMalformedIdsControlsAndNonJsonNames() {
        for (String id : new String[]{null, "", "../input", ID + "/other", ID.toUpperCase(java.util.Locale.ROOT),
                "1-2-3-4-5", "/data/user/0/app/input.json"}) {
            assertThrows(IllegalArgumentException.class, () -> new JsonInputRef(id, "content://provider/input", "目录.json", HASH));
        }
        for (String uri : new String[]{null, "file:///sdcard/input.json", "https://host/input.json", "content:/input",
                "/storage/emulated/0/Download/input.json", "content://provider/input\n.json"}) {
            assertThrows(IllegalArgumentException.class, () -> new JsonInputRef(ID, uri, "目录.json", HASH));
        }
        for (String name : new String[]{null, "", " ", "input.pdf", "input.json\n", "../input.json", "folder\\input.json"}) {
            assertThrows(IllegalArgumentException.class, () -> new JsonInputRef(ID, "content://provider/input", name, HASH));
        }
        assertThrows(IllegalArgumentException.class, () -> new JsonInputRef(ID, "content://provider/input", "目录.json", "bad"));
    }

    @Test public void sourceOrHashDifferenceIsNotSameOwnedInput() {
        JsonInputRef ref = new JsonInputRef(ID, "content://provider/first", "same.json", HASH);
        assertNotEquals(ref, new JsonInputRef(ID, "content://provider/second", "same.json", HASH));
        assertNotEquals(ref, new JsonInputRef(ID, ref.sourceUri, "same.json", "b".repeat(64)));
    }
}
