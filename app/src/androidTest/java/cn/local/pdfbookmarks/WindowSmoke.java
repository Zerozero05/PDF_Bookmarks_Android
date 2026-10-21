package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Actual MainActivity controls/configurations; it never chooses or opens a document or uses the clipboard. */
public final class WindowSmoke {
    private static final long ORIENTATION_TIMEOUT_MS = 6000;

    private WindowSmoke() { }

    /** Invoke from the instrumentation worker after the main window is ready and idle. */
    public static String verify(Instrumentation instrumentation, Activity activity) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("WindowSmoke requires worker thread");
        SharedPreferences appearance = activity.getSharedPreferences("ui_appearance", Context.MODE_PRIVATE);
        SharedPreferences review = activity.getSharedPreferences("review_ui", Context.MODE_PRIVATE);
        Map<String, ?> originalAppearance = copy(appearance), originalReview = copy(review);
        Snapshot[] baseline = new Snapshot[1];
        UiSnapshot[] originalUi = new UiSnapshot[1];
        int[] requested = new int[1], originalDirection = new int[1];
        AlertDialog[] ownedAppearance = new AlertDialog[1];
        Dialog[] ownedPicker = new Dialog[1];
        boolean[] pathOpened = new boolean[1];
        StringBuilder report = new StringBuilder();
        Throwable failure = null;
        boolean captured = false;
        try {
            onMain(instrumentation, () -> {
                requireReady(activity);
                require(!(Boolean) field(activity, "busy") && !(Boolean) field(activity, "shareLoading")
                                && field(activity, "readJob") == null && (Integer) field(activity, "systemPickerRequest") == 0,
                        "Window test requires an idle main workflow");
                for (String modal : new String[]{"appearanceDialog", "workflowDialog", "filePicker"}) {
                    Object value = field(activity, modal);
                    require(!(value instanceof Dialog) || !((Dialog) value).isShowing(),
                            "An existing " + modal + " must be left undisturbed");
                }
                require(((View) field(activity, "pathEditor")).getVisibility() != View.VISIBLE
                                && (Integer) field(activity, "pendingPathRequest") == 0,
                        "Existing path input must be left undisturbed");
                baseline[0] = new Snapshot(activity);
                originalUi[0] = new UiSnapshot(activity);
                requested[0] = activity.getRequestedOrientation();
                originalDirection[0] = activity.getResources().getConfiguration().orientation;
            });
            captured = true;

            int[][] combinations = {{UiAppearance.SYSTEM, UiAppearance.STANDARD, UiAppearance.COMFORTABLE},
                    {UiAppearance.LIGHT, UiAppearance.LARGE, UiAppearance.COMPACT},
                    {UiAppearance.DARK, UiAppearance.EXTRA_LARGE, UiAppearance.COMFORTABLE}};
            for (int[] selection : combinations) {
                onMain(instrumentation, () -> {
                    invoke(activity, "showAppearance");
                    AlertDialog dialog = (AlertDialog) field(activity, "appearanceDialog");
                    ownedAppearance[0] = dialog;
                    require(dialog != null && dialog.isShowing(), "Actual appearance dialog did not open");
                    List<Spinner> spinners = new ArrayList<>();
                    collectSpinners(dialog.getWindow().getDecorView(), spinners);
                    require(spinners.size() == 3, "Actual appearance dialog does not contain three settings");
                    for (int i = 0; i < selection.length; i++) spinners.get(i).setSelection(selection[i]);
                    Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    require(apply != null && apply.isEnabled() && apply.performClick(), "Actual appearance Apply did not run");
                });
                instrumentation.waitForIdleSync();
                onMain(instrumentation, () -> {
                    UiAppearance applied = UiAppearance.load(activity);
                    require(applied.mode == selection[0] && applied.textSize == selection[1] && applied.density == selection[2],
                            "Actual theme/text/density controls did not apply their selected values");
                    require(field(activity, "appearanceDialog") == null, "Appearance dialog remained open after Apply");
                    requireReady(activity);
                    baseline[0].assertSame(activity);
                });
                report.append("PASS theme/text/density ").append(Arrays.toString(selection)).append("; ");
            }

            int orientationChecks = 0;
            for (int direction : new int[]{Configuration.ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_PORTRAIT}) {
                onMain(instrumentation, () -> activity.setRequestedOrientation(requestFor(direction)));
                if (awaitOrientation(instrumentation, activity, direction)) {
                    onMain(instrumentation, () -> { requireReady(activity); baseline[0].assertSame(activity); });
                    orientationChecks++;
                    report.append("PASS actual ").append(directionName(direction)).append("; ");
                } else {
                    report.append("UNSUPPORTED/TIMEOUT actual ").append(directionName(direction)).append("; ");
                }
            }
            report.append("orientation supported checks ").append(orientationChecks).append("/2; ");

            for (int request : new int[]{1, 2}) {
                onMain(instrumentation, () -> {
                    invoke(activity, "pick", new Class<?>[]{int.class}, request);
                    Dialog picker = (Dialog) field(activity, "filePicker");
                    ownedPicker[0] = picker;
                    require(picker != null && picker.isShowing(), "Actual app file picker did not open");
                    String cancelText = string(activity, "picker_cancel");
                    Button cancel = button(picker.getWindow().getDecorView(), cancelText);
                    require(cancel != null && cancel.isEnabled() && cancel.performClick(), "Actual file picker Cancel did not run");
                    require(!picker.isShowing(), "Actual file picker Cancel did not return");
                    baseline[0].assertSame(activity);
                    requireReady(activity);
                });
                instrumentation.waitForIdleSync();
                report.append("PASS ").append(request == 1 ? "PDF" : "JSON").append(" picker cancel; ");
                onMain(instrumentation, () -> {
                    invoke(activity, "inputPath", new Class<?>[]{int.class}, request);
                    pathOpened[0] = true;
                    View editor = (View) field(activity, "pathEditor");
                    require(editor.getVisibility() == View.VISIBLE, "Actual path editor did not open");
                    Button cancel = button(editor, "取消路径输入 / 返回");
                    require(cancel != null && cancel.isEnabled() && cancel.performClick(), "Actual path Cancel did not run");
                    pathOpened[0] = false;
                    require(editor.getVisibility() != View.VISIBLE, "Path Cancel did not return to the workflow");
                    baseline[0].assertSame(activity);
                    requireReady(activity);
                });
                instrumentation.waitForIdleSync();
                report.append("PASS ").append(request == 1 ? "PDF" : "JSON").append(" empty path cancel; ");
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (captured) {
                AtomicReference<Throwable> restorationFailure = new AtomicReference<>();
                attemptRestore(restorationFailure, () -> {
                    onMain(instrumentation, () -> {
                        if (ownedAppearance[0] != null && ownedAppearance[0].isShowing()) ownedAppearance[0].dismiss();
                        if (ownedPicker[0] != null && ownedPicker[0].isShowing()) ownedPicker[0].cancel();
                        if (pathOpened[0]) invoke(activity, "closePathEditor");
                    });
                });
                attemptRestore(restorationFailure, () -> restore(appearance, originalAppearance));
                attemptRestore(restorationFailure, () -> onMain(instrumentation, () -> invoke(activity, "applyCurrentAppearance")));
                // Independent restoration steps keep a failed dialog or orientation from preventing preference recovery.
                attemptRestore(restorationFailure, () -> {
                    if (originalDirection[0] == Configuration.ORIENTATION_LANDSCAPE
                            || originalDirection[0] == Configuration.ORIENTATION_PORTRAIT) {
                        onMain(instrumentation, () -> activity.setRequestedOrientation(requestFor(originalDirection[0])));
                        if (!awaitOrientation(instrumentation, activity, originalDirection[0])) {
                            report.append("UNSUPPORTED restoring actual original direction; ");
                        }
                    }
                });
                attemptRestore(restorationFailure, () -> {
                    onMain(instrumentation, () -> activity.setRequestedOrientation(requested[0]));
                    boolean restoredDirection = awaitOrientation(instrumentation, activity, originalDirection[0]);
                    report.append(restoredDirection ? "PASS original actual direction restored; "
                            : "UNSUPPORTED original actual direction after restoring request; ");
                    instrumentation.waitForIdleSync();
                });
                // Rotation can persist a pane ratio; restore its map after all configuration requests finish.
                attemptRestore(restorationFailure, () -> restore(review, originalReview));
                attemptRestore(restorationFailure, () -> {
                    onMain(instrumentation, () -> {
                        originalUi[0].restore(activity);
                        require(activity.getRequestedOrientation() == requested[0], "Original requested orientation was not restored");
                        requireReady(activity);
                        baseline[0].assertSame(activity);
                    });
                    require(originalAppearance.equals(copy(appearance)), "ui_appearance was not restored exactly");
                    require(originalReview.equals(copy(review)), "review_ui was not restored exactly");
                    report.append("PASS exact appearance/review preferences restored; ");
                });
                Throwable restoring = restorationFailure.get();
                if (restoring != null) {
                    if (failure == null) failure = restoring;
                    else failure.addSuppressed(restoring);
                }
            }
        }
        rethrow(failure);
        return report + "no document selected/read, no clipboard operation; unsupported orientations are not counted as passes";
    }

    private static final class Snapshot {
        final Object pdfUri, jsonUri, parsed, info;
        final String pdfName, jsonName, pdfHash, jsonHash;
        final boolean confirmed, replace, overwrite, syncEnjoy, deleteJson;
        final int catalogMode;
        final ReviewWorkspace.State review;

        Snapshot(Activity activity) throws Exception {
            pdfUri = field(activity, "pdfUri"); jsonUri = field(activity, "jsonUri");
            parsed = field(activity, "parsed"); info = field(activity, "info");
            pdfName = (String) field(activity, "pdfName"); jsonName = (String) field(activity, "jsonName");
            pdfHash = (String) field(activity, "pdfHash"); jsonHash = (String) field(activity, "jsonHash");
            confirmed = checked(activity, "confirmed"); replace = checked(activity, "replace");
            overwrite = checked(activity, "overwrite"); syncEnjoy = checked(activity, "syncEnjoy");
            deleteJson = checked(activity, "deleteJson");
            catalogMode = ((RadioGroup) field(activity, "catalogMode")).getCheckedRadioButtonId();
            review = ((ReviewWorkspace) field(activity, "review")).snapshotState();
        }

        void assertSame(Activity activity) throws Exception {
            Snapshot actual = new Snapshot(activity);
            require(Objects.equals(pdfUri, actual.pdfUri) && Objects.equals(jsonUri, actual.jsonUri)
                            && parsed == actual.parsed && info == actual.info
                            && Objects.equals(pdfName, actual.pdfName) && Objects.equals(jsonName, actual.jsonName)
                            && Objects.equals(pdfHash, actual.pdfHash) && Objects.equals(jsonHash, actual.jsonHash),
                    "Appearance, rotation or cancel changed file or parsed document identity");
            require(confirmed == actual.confirmed && replace == actual.replace && overwrite == actual.overwrite
                            && syncEnjoy == actual.syncEnjoy && deleteJson == actual.deleteJson && catalogMode == actual.catalogMode,
                    "Appearance, rotation or cancel changed verification/catalog/deletion options");
            require(review.query.equals(actual.review.query)
                            && review.selectedSourceIndex == actual.review.selectedSourceIndex
                            && review.previewPage == actual.review.previewPage && review.previewTitle.equals(actual.review.previewTitle)
                            && Arrays.equals(review.collapsedSourceIndices, actual.review.collapsedSourceIndices)
                            && Arrays.equals(review.targetPages, actual.review.targetPages),
                    "Appearance, rotation or cancel changed review progress");
        }
    }

    private static final class UiSnapshot {
        final CharSequence status, fileToggle, pathText;
        final int inputsVisibility, pathRequest;
        final View focused;

        UiSnapshot(Activity activity) throws Exception {
            status = ((TextView) field(activity, "status")).getText();
            fileToggle = ((TextView) field(activity, "fileToggle")).getText();
            pathText = ((EditText) field(activity, "pathInput")).getText().toString();
            inputsVisibility = ((View) field(activity, "inputBody")).getVisibility();
            pathRequest = (Integer) field(activity, "pathRequest");
            focused = activity.getCurrentFocus();
        }

        void restore(Activity activity) throws Exception {
            ((TextView) field(activity, "status")).setText(status);
            ((TextView) field(activity, "fileToggle")).setText(fileToggle);
            ((EditText) field(activity, "pathInput")).setText(pathText);
            ((View) field(activity, "inputBody")).setVisibility(inputsVisibility);
            Field request = activity.getClass().getDeclaredField("pathRequest");
            request.setAccessible(true); request.setInt(activity, pathRequest);
            if (focused != null && focused.isAttachedToWindow()) focused.requestFocus();
        }
    }

    private static int requestFor(int direction) {
        return direction == Configuration.ORIENTATION_LANDSCAPE ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    private static String directionName(int direction) {
        return direction == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait";
    }

    private static boolean awaitOrientation(Instrumentation instrumentation, Activity activity, int direction) throws Exception {
        long deadline = SystemClock.uptimeMillis() + ORIENTATION_TIMEOUT_MS;
        boolean[] ready = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                require(!activity.isDestroyed() && !activity.isFinishing(), "Configuration unexpectedly destroyed MainActivity");
                ready[0] = activity.getResources().getConfiguration().orientation == direction;
            });
            if (ready[0]) return true;
            SystemClock.sleep(60);
        }
        return false;
    }

    private static void requireReady(Activity activity) {
        require(!activity.isDestroyed() && !activity.isFinishing(), "MainActivity is not active");
        require(visibleTitle(activity.findViewById(android.R.id.content)), "Main PDF tool content is not visible");
    }

    private static boolean visibleTitle(View view) {
        if (view instanceof TextView && "PDF目录工具".contentEquals(((TextView) view).getText()) && view.isShown()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) if (visibleTitle(parent.getChildAt(i))) return true;
        }
        return false;
    }

    private static void collectSpinners(View view, List<Spinner> spinners) {
        if (view instanceof Spinner) spinners.add((Spinner) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectSpinners(group.getChildAt(i), spinners);
        }
    }

    private static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String string(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "string", activity.getPackageName());
        require(id != 0, "Missing actual control label " + name);
        return activity.getString(id);
    }

    private static boolean checked(Activity activity, String name) throws Exception {
        return ((CheckBox) field(activity, name)).isChecked();
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static void invoke(Object object, String name) throws Exception { invoke(object, name, new Class<?>[0]); }

    private static void invoke(Object object, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { method.invoke(object, arguments); }
        catch (InvocationTargetException failure) { rethrow(failure.getCause()); }
    }

    private static Map<String, ?> copy(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return copy;
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> original) throws Exception {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Set) {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) {
                    require(item instanceof String, "Unsupported preference set value"); strings.add((String) item);
                }
                editor.putStringSet(key, strings);
            } else throw new IllegalStateException("Unsupported preference value: " + key);
        }
        require(editor.commit(), "Could not restore original preferences");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void attemptRestore(AtomicReference<Throwable> failure, MainAction action) {
        try { action.run(); }
        catch (Throwable error) {
            if (failure.get() == null) failure.set(error);
            else failure.get().addSuppressed(error);
        }
    }

    @FunctionalInterface private interface MainAction { void run() throws Exception; }

    private static void onMain(Instrumentation instrumentation, MainAction action) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable failure) { thrown.set(failure); }
        });
        rethrow(thrown.get());
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
    }
}
