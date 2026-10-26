package cn.local.pdfbookmarks;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A failed SharedPreferences commit may already have changed its in-process input values. */
public final class InputSelectionCommit {
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "pdf", "pdfName", "json", "jsonName", "selectedJsonId", "selectedJsonHash"));

    public enum Result {
        COMMITTED,
        /** Previous input keys were committed and read back; an unused new copy may be discarded. */
        RESTORED,
        /** Durable ownership is uncertain; retain the new copy and report the failure. */
        UNCERTAIN
    }

    private InputSelectionCommit() { }

    /** Caller serializes this operation with other input/cleanup mutations using replacementLock. */
    public static Result commit(SharedPreferences preferences, Map<String, String> changes) {
        if (preferences == null || changes == null || changes.isEmpty() || !KEYS.containsAll(changes.keySet())) {
            throw new IllegalArgumentException("只能提交本次文件选择的输入字段。");
        }
        Map<String, String> requested = new LinkedHashMap<>(changes), previous = new LinkedHashMap<>();
        try {
            Map<String, ?> values = preferences.getAll();
            for (String key : requested.keySet()) {
                Object old = values.get(key);
                if (old != null && !(old instanceof String)) return Result.UNCERTAIN;
                previous.put(key, (String) old);
            }
        } catch (RuntimeException unreadable) { return Result.UNCERTAIN; }

        try {
            if (write(preferences, requested) && matches(preferences, requested)) return Result.COMMITTED;
        } catch (RuntimeException failed) { /* Memory may already contain the requested values. */ }

        // Do not overwrite a newer input if the caller's serialization boundary was violated.
        if (!matches(preferences, requested) && !matches(preferences, previous)) return Result.UNCERTAIN;
        try {
            if (write(preferences, previous) && matches(preferences, previous)) return Result.RESTORED;
        } catch (RuntimeException failed) { /* Keep the candidate until ownership can be recovered. */ }
        return Result.UNCERTAIN;
    }

    private static boolean write(SharedPreferences preferences, Map<String, String> values) {
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) editor.remove(entry.getKey());
            else editor.putString(entry.getKey(), entry.getValue());
        }
        return editor.commit();
    }

    private static boolean matches(SharedPreferences preferences, Map<String, String> expected) {
        try {
            Map<String, ?> actual = preferences.getAll();
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                if (entry.getValue() == null) {
                    if (actual.containsKey(entry.getKey())) return false;
                } else if (!entry.getValue().equals(actual.get(entry.getKey()))) return false;
            }
            return true;
        } catch (RuntimeException unreadable) { return false; }
    }
}
