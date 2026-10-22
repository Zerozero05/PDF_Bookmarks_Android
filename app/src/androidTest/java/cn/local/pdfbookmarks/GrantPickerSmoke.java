package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real persisted roots and production controls, always cancelling removal confirmation. */
public final class GrantPickerSmoke {
    private static final long TIMEOUT_MS = 8000;

    private GrantPickerSmoke() { }

    /** Run on the instrumentation worker. No positive confirmation or clipboard control is clicked. */
    public static String verify(Instrumentation instrumentation, Activity activity) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("GrantPickerSmoke requires worker thread");
        Map<String, Integer> originalGrants = grants(activity);
        Object[] originalInputs = new Object[2];
        FilePickerDialog[] owned = new FilePickerDialog[1];
        int[] selected = new int[1], cancelled = new int[1], grantChanges = new int[1];
        int[] roots = new int[1];
        File visualEvidence = null;
        StringBuilder report = new StringBuilder();
        Throwable failure = null;
        try {
            onMain(instrumentation, () -> {
                require(!activity.isFinishing() && !activity.isDestroyed(), "Main activity is not available");
                originalInputs[0] = field(activity, "pdfUri"); originalInputs[1] = field(activity, "jsonUri");
                owned[0] = new FilePickerDialog(activity, true, uri -> {
                    selected[0]++;
                    throw new AssertionError("Grant management test must not select a document");
                }, () -> cancelled[0]++, () -> { throw new AssertionError("Test must not open system picker"); },
                        () -> { throw new AssertionError("Test must not request a new authorization"); },
                        () -> true, () -> grantChanges[0]++);
                owned[0].show();
            });
            FilePickerDialog picker = owned[0];
            await(instrumentation, () -> loaded(picker), "Existing authorization roots did not finish loading");
            onMain(instrumentation, () -> {
                require(state(picker).location() == null, "Picker did not start at existing authorization roots");
                roots[0] = state(picker).entries().size();
                require(selection(picker).currentSnapshot().size() == roots[0], "Visible grant rows and selection identities disagree");
            });
            report.append("real persisted roots ").append(roots[0]).append("; ");
            if (roots[0] == 0) {
                require(!hasExactTreeGrant(originalGrants),
                        "Existing exact persisted tree grants produced zero root rows; loading failure cannot be reported as SKIP");
                report.append("SKIP root swipe/selection/details/removal-confirm/selection-Back: no real visible persisted root; ");
            } else {
                visualEvidence = visualDirectory(activity);
                await(instrumentation, () -> firstRow(picker) != null, "Existing root row did not attach to the dialog");
                onMain(instrumentation, () -> gesture(picker, firstRow(picker), false));
                instrumentation.waitForIdleSync();
                onMain(instrumentation, () -> {
                    require(state(picker).location() == null && selected[0] == 0,
                            "Vertical scrolling entered a root or selected a document");
                    require(selection(picker).selectedSnapshot().isEmpty(), "Vertical scrolling selected a grant");
                    for (SwipeGrantRow row : visibleRows(picker)) require(!row.isOpen(), "Vertical scrolling opened horizontal actions");
                });
                assertUnchanged(activity, originalGrants, originalInputs, selected, grantChanges);
                report.append("PASS actual vertical MotionEvent avoids swipe/selection/navigation; ");

                SwipeGrantRow[] swiped = new SwipeGrantRow[1];
                onMain(instrumentation, () -> {
                    swiped[0] = firstRow(picker);
                    require(swiped[0] != null, "No visible root remains for horizontal swipe");
                    gesture(picker, swiped[0], true);
                });
                await(instrumentation, () -> swiped[0].isOpen(), "Actual left swipe did not reveal grant actions");
                await(instrumentation, () -> fullyOpen(swiped[0]), "Actual left swipe did not settle at its reveal width");
                File swipeImage = capture(instrumentation, picker, visualEvidence, "left-swipe");
                report.append("leftSwipeImage=").append(swipeImage).append("; ");
                onMain(instrumentation, () -> {
                    require(state(picker).location() == null && selected[0] == 0, "Horizontal swipe navigated or selected a document");
                    back(picker);
                    require(picker.isShowing(), "Back closed picker instead of collapsing its open row");
                });
                await(instrumentation, () -> !swiped[0].isOpen(), "Back did not collapse the swiped row");
                report.append("PASS actual horizontal MotionEvent reveal and Back collapse; ");

                String[] rootUri = new String[1];
                onMain(instrumentation, () -> {
                    swiped[0] = firstRow(picker);
                    require(swiped[0] != null, "No existing root is visible for details");
                    Object entry = swiped[0].getTag();
                    Object root = ((Map<?, ?>) field(picker, "rootItems")).get(entry);
                    rootUri[0] = ((GrantSelection.Grant) field(root, "grant")).uri;
                    Method copy = picker.getClass().getDeclaredMethod("copyLocation", root.getClass());
                    copy.setAccessible(true);
                    String copied = (String) copy.invoke(picker, root);
                    Uri authorized = Uri.parse(rootUri[0]);
                    String expected = rootUri[0];
                    if ("com.android.externalstorage.documents".equals(authorized.getAuthority())) {
                        String id = DocumentsContract.isDocumentUri(activity, authorized)
                                ? DocumentsContract.getDocumentId(authorized) : DocumentsContract.getTreeDocumentId(authorized);
                        if (id.startsWith("primary:")) expected = "/storage/emulated/0/" + id.substring(8);
                    }
                    require(expected.equals(copied), "Copy location used a display-only path or changed the exact provider URI");
                    gesture(picker, swiped[0], true);
                });
                await(instrumentation, () -> swiped[0].isOpen(), "Details row did not reveal");
                await(instrumentation, () -> fullyOpen(swiped[0]), "Details row did not settle at its reveal width");
                onMain(instrumentation, () -> click(button(swiped[0], text(activity, "grant_details")), "Actual Details action"));
                await(instrumentation, () -> showing(child(picker)), "Actual grant details did not open");
                onMain(instrumentation, () -> {
                    AlertDialog details = child(picker);
                    TextView message = details.findViewById(android.R.id.message);
                    require(message != null && message.getText().toString().contains(rootUri[0]), "Details omitted the exact persisted URI");
                    // Details' positive action copies a path; deliberately never click it.
                    click(details.getButton(AlertDialog.BUTTON_NEUTRAL), "Details removal action (confirmation only)");
                });
                await(instrumentation, () -> isRemovalConfirmation(activity, child(picker)), "Single-grant removal confirmation did not open");
                onMain(instrumentation, () -> {
                    TextView message = child(picker).findViewById(android.R.id.message);
                    require(message != null && message.getText().toString().contains(rootUri[0]), "Removal preview omitted its exact URI");
                    click(child(picker).getButton(AlertDialog.BUTTON_NEGATIVE), "Cancel single-grant confirmation");
                });
                await(instrumentation, () -> !showing(child(picker)), "Single-grant Cancel did not close confirmation");
                assertUnchanged(activity, originalGrants, originalInputs, selected, grantChanges);
                report.append("PASS real details/exact URI, copy-location path/URI mapping without clipboard and single-grant confirmation Cancel; ");

                onMain(instrumentation, () -> {
                    SwipeGrantRow row = firstRow(picker);
                    View content = (View) field(row, "content");
                    require(content.performLongClick(), "Existing root long press did not enter selection");
                });
                await(instrumentation, () -> selection(picker).isSelectionMode()
                        && selection(picker).selectedSnapshot().size() == 1 && selectedCheckbox(picker) != null,
                        "Long press did not render one selected real grant");
                String[] toggledUri = new String[1];
                onMain(instrumentation, () -> {
                    CheckBox check = selectedCheckbox(picker);
                    require(check != null && check.isChecked(), "Selected root checkbox did not reflect selection");
                    toggledUri[0] = selection(picker).selectedSnapshot().get(0).uri;
                    toggleCheckbox(check, false);
                    require(selection(picker).selectedSnapshot().isEmpty(), "Checkbox did not toggle the selected root off");
                });
                await(instrumentation, () -> checkboxState(picker, toggledUri[0], false), "Unselected real-root checkbox did not render");
                onMain(instrumentation, () -> {
                    toggleCheckbox(checkboxForUri(picker, toggledUri[0]), true);
                    List<GrantSelection.Grant> restored = selection(picker).selectedSnapshot();
                    require(restored.size() == 1 && restored.get(0).uri.equals(toggledUri[0]),
                            "Checkbox did not restore exactly the same selected grant");
                });
                await(instrumentation, () -> checkboxState(picker, toggledUri[0], true), "Restored selected real-root checkbox did not render");
                onMain(instrumentation, () -> {
                    toggleCheckbox(checkboxForUri(picker, toggledUri[0]), false);
                    require(selection(picker).selectedSnapshot().isEmpty(), "Repeated checkbox toggle left a selected grant");
                });
                await(instrumentation, () -> checkboxState(picker, toggledUri[0], false), "Repeated unchecked real-root checkbox did not render");
                onMain(instrumentation, () -> {
                    click((Button) field(picker, "all"), "Actual Select all");
                    require(selection(picker).selectedSnapshot().size() == roots[0], "Select all did not include every visible real root");
                    click((Button) field(picker, "all"), "Actual Clear selection");
                    require(selection(picker).selectedSnapshot().isEmpty(), "Clear selection left selected grants");
                    click((Button) field(picker, "all"), "Actual Select all for preview");
                });
                await(instrumentation, () -> allSelectedVisible(picker, roots[0]), "Select-all checkboxes did not finish laying out");
                File selectedImage = capture(instrumentation, picker, visualEvidence, "all-selected");
                report.append("allSelectedImage=").append(selectedImage).append("; ");
                onMain(instrumentation, () -> click((Button) field(picker, "remove"), "Actual selected-grant removal preview"));
                await(instrumentation, () -> isRemovalConfirmation(activity, child(picker)), "Selected-grant confirmation did not open");
                onMain(instrumentation, () -> {
                    TextView message = child(picker).findViewById(android.R.id.message);
                    require(message != null, "Batch confirmation has no preview");
                    for (GrantSelection.Grant grant : selection(picker).selectedSnapshot()) {
                        require(message.getText().toString().contains(grant.uri), "Batch preview omitted a selected exact grant URI");
                    }
                    // Positive confirmation is intentionally never invoked, even if only one root exists.
                    click(child(picker).getButton(AlertDialog.BUTTON_NEGATIVE), "Cancel selected-grant confirmation");
                });
                await(instrumentation, () -> !showing(child(picker)), "Batch confirmation Cancel did not return");
                onMain(instrumentation, () -> {
                    require(selection(picker).isSelectionMode(), "Cancelling confirmation discarded selection mode");
                    back(picker);
                    require(picker.isShowing() && !selection(picker).isSelectionMode()
                                    && selection(picker).selectedSnapshot().isEmpty(),
                            "Back did not leave selection mode while retaining the picker");
                    click((Button) field(picker, "choose"), "Actual selection-mode button");
                    require(selection(picker).isSelectionMode(), "Selection button did not enter selection mode");
                    click((Button) field(picker, "choose"), "Actual selection-mode Done button");
                    require(!selection(picker).isSelectionMode() && selection(picker).selectedSnapshot().isEmpty(),
                            "Done did not leave selection mode");
                });
                assertUnchanged(activity, originalGrants, originalInputs, selected, grantChanges);
                report.append("PASS real long-press/checkbox/select-all/clear-all/batch Cancel/selection Back; ");
            }

            onMain(instrumentation, () -> click(button(picker.getWindow().getDecorView(), text(activity, "picker_cancel")),
                    "Actual fixed picker Cancel"));
            await(instrumentation, () -> !picker.isShowing(), "Fixed Cancel did not return without a file choice");
            require(cancelled[0] == 1, "Fixed Cancel did not call cancellation exactly once");
            report.append("PASS fixed Cancel without choosing a file; ");
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                onMain(instrumentation, () -> {
                    if (owned[0] == null) return;
                    AlertDialog child = child(owned[0]);
                    if (showing(child)) child.dismiss();
                    if (owned[0].isShowing()) owned[0].cancel();
                });
                instrumentation.waitForIdleSync();
                assertUnchanged(activity, originalGrants, originalInputs, selected, grantChanges);
            } catch (Throwable closing) {
                if (failure == null) failure = closing;
                else failure.addSuppressed(closing);
            }
        }
        if (failure != null && visualEvidence != null) {
            failure.addSuppressed(new IOException("Own-picker visual evidence retained at " + visualEvidence));
        }
        rethrow(failure);
        return report + "PASS exact persisted URI/read-write flags unchanged; main inputs unchanged; "
                + "no positive removal, clipboard, document selection or document operation";
    }

    private static File visualDirectory(Activity activity) throws IOException {
        File cache = activity.getCacheDir().getCanonicalFile();
        File directory = new File(cache, "grant-picker-visual-" + UUID.randomUUID()).getCanonicalFile();
        require(cache.equals(directory.getParentFile()), "Picker visual evidence escaped the app cache");
        if (!directory.mkdir()) throw new IOException("Cannot create unique picker visual evidence: " + directory);
        return directory;
    }

    private static boolean fullyOpen(SwipeGrantRow row) throws Exception {
        View content = (View) field(row, "content");
        int revealWidth = (Integer) field(row, "revealWidth");
        return row.isOpen() && content.isLaidOut() && Math.abs(content.getTranslationX() + revealWidth) <= 1f;
    }

    private static boolean allSelectedVisible(FilePickerDialog picker, int count) throws Exception {
        if (!selection(picker).isSelectionMode() || selection(picker).selectedSnapshot().size() != count) return false;
        if (!((View) field(picker, "selectionBar")).isShown()) return false;
        List<SwipeGrantRow> rows = visibleRows(picker);
        if (rows.isEmpty()) return false;
        for (SwipeGrantRow row : rows) {
            CheckBox check = checkbox(row);
            if (check == null || !check.isChecked()) return false;
        }
        return !picker.getWindow().getDecorView().isLayoutRequested();
    }

    /** Draws this test's own actual dialog only; encoding runs on the instrumentation worker. */
    private static File capture(Instrumentation instrumentation, FilePickerDialog picker, File directory, String label)
            throws Exception {
        AtomicReference<Bitmap> frame = new AtomicReference<>();
        try {
            onMain(instrumentation, () -> {
                View decor = picker.getWindow().getDecorView();
                require(picker.isShowing() && decor.isAttachedToWindow() && decor.isLaidOut()
                                && decor.getWidth() > 0 && decor.getHeight() > 0 && !decor.isLayoutRequested(),
                        "Own picker decor is not ready for visual capture");
                Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                frame.set(bitmap);
                decor.draw(new Canvas(bitmap));
            });
            File image = new File(directory, label + "-" + UUID.randomUUID() + ".png");
            if (!image.createNewFile()) throw new IOException("Refusing to overwrite picker evidence: " + image);
            try (FileOutputStream output = new FileOutputStream(image)) {
                require(frame.get().compress(Bitmap.CompressFormat.PNG, 100, output), "Picker PNG encoding failed");
            }
            return image;
        } finally {
            Bitmap bitmap = frame.get();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static boolean loaded(FilePickerDialog picker) throws Exception {
        if (!picker.isShowing()) return false;
        TextView notice = (TextView) field(picker, "notice");
        String current = notice.getText().toString();
        return !current.equals(text(picker.getOwnerActivity(), "picker_loading"))
                && !current.equals(text(picker.getOwnerActivity(), "picker_loading_locations"));
    }

    private static ListView list(FilePickerDialog picker) {
        return findList(picker.getWindow().getDecorView());
    }

    private static ListView findList(View view) {
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                ListView found = findList(parent.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<SwipeGrantRow> visibleRows(FilePickerDialog picker) {
        List<SwipeGrantRow> rows = new ArrayList<>();
        ListView list = list(picker);
        if (list != null) for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof SwipeGrantRow && child.isShown() && child.getWidth() > 0 && child.getHeight() > 0) {
                rows.add((SwipeGrantRow) child);
            }
        }
        return rows;
    }

    private static SwipeGrantRow firstRow(FilePickerDialog picker) {
        List<SwipeGrantRow> rows = visibleRows(picker);
        SwipeGrantRow largest = null;
        int largestHeight = 0;
        Rect visible = new Rect();
        for (SwipeGrantRow row : rows) {
            if (!row.getLocalVisibleRect(visible)) continue;
            if (visible.height() >= row.getHeight()) return row;
            if (visible.height() > largestHeight) { largest = row; largestHeight = visible.height(); }
        }
        return largest;
    }

    private static CheckBox selectedCheckbox(FilePickerDialog picker) {
        for (SwipeGrantRow row : visibleRows(picker)) {
            CheckBox check = checkbox(row);
            if (check != null && check.isChecked()) return check;
        }
        return null;
    }

    private static CheckBox checkboxForUri(FilePickerDialog picker, String uri) throws Exception {
        Map<?, ?> items = (Map<?, ?>) field(picker, "rootItems");
        for (SwipeGrantRow row : visibleRows(picker)) {
            Object root = items.get(row.getTag());
            if (root != null && ((GrantSelection.Grant) field(root, "grant")).uri.equals(uri)) return checkbox(row);
        }
        return null;
    }

    private static boolean checkboxState(FilePickerDialog picker, String uri, boolean expected) throws Exception {
        CheckBox check = checkboxForUri(picker, uri);
        return check != null && check.isShown() && check.isEnabled() && check.isChecked() == expected;
    }

    private static void toggleCheckbox(CheckBox check, boolean expected) {
        require(check != null && check.isShown() && check.isEnabled() && check.isChecked() != expected,
                "Actual grant checkbox is not visible/enabled in the expected initial state");
        // CompoundButton toggles before returning super.performClick's OnClick-listener result.
        // This checkbox uses OnCheckedChangeListener, so validate the actual state instead of that boolean.
        check.performClick();
        require(check.isChecked() == expected, "Actual grant checkbox did not toggle its checked state");
    }

    private static CheckBox checkbox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                CheckBox found = checkbox(parent.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Dispatch through the actual dialog and ListView hierarchy, using only the attached row's bounds. */
    private static void gesture(FilePickerDialog picker, SwipeGrantRow row, boolean horizontal) {
        require(row != null && row.isShown() && row.getWidth() > 0 && row.getHeight() > 0, "Swipe target is not a visible real root");
        View decor = picker.getWindow().getDecorView();
        int[] rowPosition = new int[2], decorPosition = new int[2];
        row.getLocationInWindow(rowPosition); decor.getLocationInWindow(decorPosition);
        float left = rowPosition[0] - decorPosition[0], top = rowPosition[1] - decorPosition[1];
        Rect visible = new Rect();
        require(row.getLocalVisibleRect(visible), "Gesture target is clipped outside the real picker");
        float startX = left + visible.left + visible.width() * (horizontal ? .75f : .5f);
        float endX = left + visible.left + visible.width() * (horizontal ? .08f : .5f);
        float startY = top + visible.top + visible.height() * (horizontal ? .5f : .8f);
        float endY = top + visible.top + visible.height() * (horizontal ? .5f : .1f);
        long down = SystemClock.uptimeMillis();
        event(decor, down, down, MotionEvent.ACTION_DOWN, startX, startY);
        for (int step = 1; step <= 5; step++) {
            float fraction = step / 5f;
            event(decor, down, down + step * 16L, MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * fraction, startY + (endY - startY) * fraction);
        }
        event(decor, down, down + 96, MotionEvent.ACTION_UP, endX, endY);
    }

    private static void event(View target, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try { target.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private static void back(Dialog dialog) {
        long time = SystemClock.uptimeMillis();
        dialog.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
        require(dialog.dispatchKeyEvent(new KeyEvent(time, time + 16, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)),
                "Actual picker did not handle Back key");
    }

    private static AlertDialog child(FilePickerDialog picker) throws Exception {
        return (AlertDialog) field(picker, "childDialog");
    }

    private static FilePickerState state(FilePickerDialog picker) throws Exception {
        return (FilePickerState) field(picker, "state");
    }

    private static GrantSelection selection(FilePickerDialog picker) throws Exception {
        return (GrantSelection) field(picker, "selection");
    }

    private static boolean isRemovalConfirmation(Activity activity, AlertDialog child) {
        Button positive = child == null ? null : child.getButton(AlertDialog.BUTTON_POSITIVE);
        return showing(child) && positive != null && text(activity, "grant_confirm_button").contentEquals(positive.getText());
    }

    private static boolean showing(Dialog dialog) { return dialog != null && dialog.isShowing(); }

    private static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                Button found = button(parent.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void click(View control, String description) {
        require(control != null && control.isEnabled() && control.performClick(), description + " did not execute");
    }

    private static String text(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "string", activity.getPackageName());
        require(id != 0, "Missing production control label " + name);
        return activity.getString(id);
    }

    private static Map<String, Integer> grants(Activity activity) {
        Map<String, Integer> values = new TreeMap<>();
        for (UriPermission grant : activity.getContentResolver().getPersistedUriPermissions()) {
            int flags = (grant.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                    | (grant.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            Integer duplicate = values.put(grant.getUri().toString(), flags);
            require(duplicate == null, "Duplicate exact persisted grant URI");
        }
        return values;
    }

    private static boolean hasExactTreeGrant(Map<String, Integer> persisted) {
        for (Map.Entry<String, Integer> grant : persisted.entrySet()) {
            Uri uri = Uri.parse(grant.getKey());
            if (grant.getValue() != 0 && DocumentsContract.isTreeUri(uri) && uri.getPathSegments().size() == 2) return true;
        }
        return false;
    }

    private static void assertUnchanged(Activity activity, Map<String, Integer> expected, Object[] inputs,
                                        int[] selected, int[] changed) throws Exception {
        require(expected.equals(grants(activity)), "Grant test changed exact persisted URI/read-write flags");
        require(Objects.equals(inputs[0], field(activity, "pdfUri")) && Objects.equals(inputs[1], field(activity, "jsonUri")),
                "Grant management changed the main PDF/JSON inputs");
        require(selected[0] == 0 && changed[0] == 0, "Read-only grant test selected a document or changed a grant");
    }

    private static Object field(Object object, String name) throws Exception {
        require(object != null, "Expected production state is missing: " + name);
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    @FunctionalInterface private interface MainAction { void run() throws Exception; }
    @FunctionalInterface private interface Condition { boolean ready() throws Exception; }

    private static void await(Instrumentation instrumentation, Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        boolean[] ready = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> ready[0] = condition.ready());
            if (ready[0]) return;
            SystemClock.sleep(40);
        }
        throw new AssertionError(message);
    }

    private static void onMain(Instrumentation instrumentation, MainAction action) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable failure) { thrown.set(failure); }
        });
        rethrow(thrown.get());
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
    }
}
