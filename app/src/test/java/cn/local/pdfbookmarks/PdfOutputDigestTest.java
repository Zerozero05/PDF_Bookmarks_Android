package cn.local.pdfbookmarks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Stream-level digest checks; the PDF structure itself is verified on Android. */
public final class PdfOutputDigestTest {
    private File output;

    @Before public void before() throws Exception { output = File.createTempFile("pdf-output-digest-", ".bin"); }
    @After public void after() throws Exception { Files.deleteIfExists(output.toPath()); }

    @Test public void verifiesPrefixAndCompleteDigestAcrossTheBufferBoundary() throws Exception {
        byte[] original = new byte[65539], complete = new byte[196729];
        for (int i = 0; i < complete.length; i++) complete[i] = (byte) (i * 31 + 17);
        System.arraycopy(complete, 0, original, 0, original.length);
        Files.write(output.toPath(), complete);
        PdfBookmarks.VerifiedOutput verified = verify(original);
        assertEquals(output.getCanonicalFile(), verified.file);
        assertEquals(complete.length, verified.length);
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(complete)), verified.sha256);
        assertArrayEquals(complete, Files.readAllBytes(output.toPath()));
    }

    @Test public void addedRevisionBytesContributeToTheCompleteDigest() throws Exception {
        byte[] original = {1, 2, 3, 4};
        byte[] first = {1, 2, 3, 4, 10, 11}, second = {1, 2, 3, 4, 10, 12};
        Files.write(output.toPath(), first); PdfBookmarks.VerifiedOutput before = verify(original);
        Files.write(output.toPath(), second); PdfBookmarks.VerifiedOutput after = verify(original);
        assertEquals(before.length, after.length);
        assertNotEquals(before.sha256, after.sha256);
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(first)), before.sha256);
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(second)), after.sha256);
    }

    @Test public void rejectsChangedOriginalPrefixEvenWhenTheOutputIsLonger() throws Exception {
        byte[] original = {1, 2, 3, 4};
        Files.write(output.toPath(), new byte[]{1, 2, 8, 4, 10});
        try { verify(original); fail("A longer output with a changed prefix must be rejected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("字节前缀")); }
    }

    @Test public void requiresAnAppendedRevisionBeyondTheWholeOriginal() throws Exception {
        byte[] original = {1, 2, 3, 4};
        for (int length : new int[]{0, 3, 4}) {
            Files.write(output.toPath(), Arrays.copyOf(original, length));
            try { verify(original); fail("Incomplete output must not produce a verified artifact"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("字节前缀")); }
        }
    }

    private PdfBookmarks.VerifiedOutput verify(byte[] original) throws Exception {
        Method method = PdfBookmarks.class.getDeclaredMethod("verifyOutput", File.class, long.class, byte[].class);
        method.setAccessible(true);
        try { return (PdfBookmarks.VerifiedOutput) method.invoke(null, output, (long) original.length,
                MessageDigest.getInstance("SHA-256").digest(original)); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
