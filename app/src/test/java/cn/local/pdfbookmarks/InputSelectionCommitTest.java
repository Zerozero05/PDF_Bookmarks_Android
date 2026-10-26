package cn.local.pdfbookmarks;

import android.content.SharedPreferences;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class InputSelectionCommitTest {
    @Test public void commitsTheCompletePairOnceAndPreservesOtherPreferences() {
        MemoryPreferences preferences = original();
        Map<String, String> changes = json(); changes.put("pdf", "new-pdf"); changes.put("pdfName", "new.pdf");
        assertEquals(InputSelectionCommit.Result.COMMITTED, InputSelectionCommit.commit(preferences, changes));
        assertEquals(1, preferences.commits);
        for (Map.Entry<String, String> entry : changes.entrySet()) assertEquals(entry.getValue(), preferences.disk.get(entry.getKey()));
        assertEquals("keep", preferences.disk.get("lastBackup"));
    }

    @Test public void falseCommitWithChangedMemoryRestoresPreviousInputsBeforeCopyCanBeReleased() {
        MemoryPreferences preferences = original(); Map<String, Object> before = new HashMap<>(preferences.memory);
        preferences.modes.add(Mode.FAIL);
        assertEquals(InputSelectionCommit.Result.RESTORED, InputSelectionCommit.commit(preferences, json()));
        assertEquals(2, preferences.commits); assertEquals(before, preferences.memory); assertEquals(before, preferences.disk);
    }

    @Test public void failedRestoreRemainsUncertainEvenWhenItsMemoryLooksRestored() {
        MemoryPreferences preferences = original(); Map<String, Object> before = new HashMap<>(preferences.memory);
        preferences.modes.add(Mode.FAIL); preferences.modes.add(Mode.FAIL);
        assertEquals(InputSelectionCommit.Result.UNCERTAIN, InputSelectionCommit.commit(preferences, json()));
        assertEquals(2, preferences.commits); assertEquals(before, preferences.memory);
    }

    @Test public void absentOldCopyKeysAreRemovedRatherThanBoundToTheDiscardedCopy() {
        MemoryPreferences preferences = original(); preferences.modes.add(Mode.FAIL);
        assertFalse(preferences.contains("selectedJsonId")); assertFalse(preferences.contains("selectedJsonHash"));
        assertEquals(InputSelectionCommit.Result.RESTORED, InputSelectionCommit.commit(preferences, json()));
        assertFalse(preferences.contains("selectedJsonId")); assertFalse(preferences.contains("selectedJsonHash"));
        assertFalse(preferences.disk.containsKey("selectedJsonId"));
    }

    @Test public void restoresOnlyModifiedKeysAndKeepsConcurrentUnrelatedProgress() {
        MemoryPreferences preferences = original(); preferences.modes.add(Mode.FAIL);
        preferences.afterFirst = () -> preferences.memory.put("jsonCleanup", "new-cleanup-progress");
        assertEquals(InputSelectionCommit.Result.RESTORED, InputSelectionCommit.commit(preferences, json()));
        assertEquals("old-pdf", preferences.memory.get("pdf"));
        assertEquals("new-cleanup-progress", preferences.memory.get("jsonCleanup"));
        assertEquals("new-cleanup-progress", preferences.disk.get("jsonCleanup"));
    }

    @Test public void aNewerDifferentInputIsNotOverwrittenByRollback() {
        MemoryPreferences preferences = original(); preferences.modes.add(Mode.FAIL);
        preferences.afterFirst = () -> preferences.memory.put("json", "newer-input-from-another-owner");
        assertEquals(InputSelectionCommit.Result.UNCERTAIN, InputSelectionCommit.commit(preferences, json()));
        assertEquals(1, preferences.commits);
        assertEquals("newer-input-from-another-owner", preferences.memory.get("json"));
    }

    @Test public void exceptionAfterMemoryMutationStillAttemptsConfirmedRestore() {
        MemoryPreferences preferences = original(); Map<String, Object> before = new HashMap<>(preferences.memory);
        preferences.modes.add(Mode.THROW);
        assertEquals(InputSelectionCommit.Result.RESTORED, InputSelectionCommit.commit(preferences, json()));
        assertEquals(before, preferences.memory); assertEquals(before, preferences.disk);
    }

    @Test public void unsupportedKeysCannotModifyBackupOrRecoveryState() {
        MemoryPreferences preferences = original(); Map<String, Object> before = new HashMap<>(preferences.memory);
        assertThrows(IllegalArgumentException.class, () -> InputSelectionCommit.commit(preferences, Collections.singletonMap("lastBackup", "bad")));
        assertEquals(0, preferences.commits); assertEquals(before, preferences.memory);
    }

    @Test public void wrongOldInputTypeIsPreservedAndMarkedUncertain() {
        MemoryPreferences preferences = original(); preferences.memory.put("json", 42);
        assertEquals(InputSelectionCommit.Result.UNCERTAIN, InputSelectionCommit.commit(preferences, json()));
        assertEquals(0, preferences.commits); assertEquals(42, preferences.memory.get("json"));
    }

    private static Map<String, String> json() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("json", "new-json"); changes.put("jsonName", "new.json");
        changes.put("selectedJsonId", "new-owned-copy"); changes.put("selectedJsonHash", "new-hash");
        return changes;
    }

    private static MemoryPreferences original() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.memory.put("json", "old-json"); preferences.memory.put("jsonName", "old.json");
        preferences.memory.put("pdf", "old-pdf"); preferences.memory.put("pdfName", "old.pdf");
        preferences.memory.put("lastBackup", "keep"); preferences.disk.putAll(preferences.memory);
        return preferences;
    }

    private enum Mode { SUCCESS, FAIL, THROW }

    /** Like Android, commit changes memory before reporting whether the durable write succeeded. */
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> memory = new HashMap<>(), disk = new HashMap<>();
        final Deque<Mode> modes = new ArrayDeque<>();
        Runnable afterFirst;
        int commits;
        @Override public Map<String, ?> getAll() { return new HashMap<>(memory); }
        @Override public String getString(String key, String fallback) { return memory.containsKey(key) ? (String) memory.get(key) : fallback; }
        @Override public boolean contains(String key) { return memory.containsKey(key); }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { throw new UnsupportedOperationException(); }
        @Override public int getInt(String key, int fallback) { throw new UnsupportedOperationException(); }
        @Override public long getLong(String key, long fallback) { throw new UnsupportedOperationException(); }
        @Override public float getFloat(String key, float fallback) { throw new UnsupportedOperationException(); }
        @Override public boolean getBoolean(String key, boolean fallback) { throw new UnsupportedOperationException(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, String> changes = new LinkedHashMap<>();
                @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
                @Override public Editor remove(String key) { changes.put(key, null); return this; }
                @Override public boolean commit() {
                    commits++;
                    for (Map.Entry<String, String> entry : changes.entrySet()) {
                        if (entry.getValue() == null) memory.remove(entry.getKey()); else memory.put(entry.getKey(), entry.getValue());
                    }
                    if (commits == 1 && afterFirst != null) afterFirst.run();
                    Mode mode = modes.isEmpty() ? Mode.SUCCESS : modes.removeFirst();
                    if (mode == Mode.THROW) throw new IllegalStateException("synthetic failure after memory write");
                    if (mode == Mode.FAIL) return false;
                    disk.clear(); disk.putAll(memory); return true;
                }
                @Override public void apply() { throw new UnsupportedOperationException(); }
                @Override public Editor clear() { throw new UnsupportedOperationException(); }
                @Override public Editor putStringSet(String key, Set<String> values) { throw new UnsupportedOperationException(); }
                @Override public Editor putInt(String key, int value) { throw new UnsupportedOperationException(); }
                @Override public Editor putLong(String key, long value) { throw new UnsupportedOperationException(); }
                @Override public Editor putFloat(String key, float value) { throw new UnsupportedOperationException(); }
                @Override public Editor putBoolean(String key, boolean value) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
