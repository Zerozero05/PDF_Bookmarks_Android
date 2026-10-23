package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic review checks; no user file, grant, clipboard or appearance preference is changed. */
public final class ReviewWorkspaceSmoke {
    private static final String SOURCE = "{\"version\":1,\"bookmarks\":["
            + "{\"title\":\"甲章\",\"pdf_page\":10,\"children\":[{\"title\":\"重复\",\"pdf_page\":11,"
            + "\"children\":[{\"title\":\"深层\",\"pdf_page\":12}]}]},"
            + "{\"title\":\"乙章\",\"pdf_page\":20,\"children\":[{\"title\":\"重复\",\"pdf_page\":21}]},"
            + "{\"title\":\"丙章\",\"pdf_page\":30}]}";
    private static final int[] TARGETS = {10, 11, 12, 20, 21, 30};

    private ReviewWorkspaceSmoke() { }

    /** Runs on the instrumentation worker thread, independently of the existing workflow smoke. */
    public static String verify(Instrumentation instrumentation, Activity activity) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Use instrumentation worker thread");
        SharedPreferences preferences = activity.getSharedPreferences("review_ui", Context.MODE_PRIVATE);
        Map<String, ?> originalPreferences = new HashMap<>(preferences.getAll());
        Fixture fixture = new Fixture();
        float[] zoomBefore = new float[1];
        Throwable failure = null;
        try {
            onMain(instrumentation, () -> fixture.open(activity));
            awaitLayout(instrumentation, fixture);
            onMain(instrumentation, () -> {
                ReviewWorkspace review = fixture.review;
                review.restoreState(new ReviewWorkspace.State("丙章", new int[]{0, 1, 3}, 1,
                        11, "重复", 0, 0, TARGETS));
                require(review.revealSourceRow(2), "Valid hidden source was not revealed");
                ReviewWorkspace.State revealed = review.snapshotState();
                require(revealed.query.isEmpty() && revealed.selectedSourceIndex == 2 && revealed.previewPage == 12,
                        "Source reveal did not clear only the obstructing search/select/render its target");
                require(Arrays.equals(new int[]{3}, revealed.collapsedSourceIndices),
                        "Source reveal changed an unrelated collapsed branch");
                require(review.rows().size() == TARGETS.length && Arrays.equals(TARGETS, revealed.targetPages),
                        "Source reveal changed the full output tree");
                int requests = fixture.requests;
                require(!review.revealSourceRow(-1) && !review.revealSourceRow(TARGETS.length),
                        "An old/deleted or out-of-range source was accepted");
                review.setBusy(true);
                require(!review.revealSourceRow(4), "Busy review accepted navigation");
                review.setBusy(false);
                require(fixture.requests == requests && sameProgress(revealed, review.snapshotState()),
                        "Rejected navigation changed review progress");
                ((EditText) field(review, "search")).setText("重复");
                require(review.revealSourceRow(4), "Second duplicate-title source was not revealed");
                require("重复".equals(review.snapshotState().query)
                                && review.snapshotState().selectedSourceIndex == 4 && fixture.lastPage == 21,
                        "Source reveal resolved a duplicate title to the wrong row");
                fixture.showPage(21, "重复");
                require(((View) field(review, "pageContext")).getVisibility() == View.GONE,
                        "Matching preview displayed a mismatch hint");
                invoke(field(review, "pageControls"), "navigate", new Class<?>[]{int.class}, 22);
                TextView context = (TextView) field(review, "pageContext");
                String expected = activity.getString(R.string.review_browsing_other_page, 22, 21);
                require(context.getVisibility() == View.VISIBLE && expected.contentEquals(context.getText()),
                        "Manual navigation omitted the selected target distinction while loading");
                review.showPageError("synthetic page failure");
                require(context.getVisibility() == View.VISIBLE && expected.contentEquals(context.getText()),
                        "Preview failure discarded the browse/target distinction");
                fixture.showPage(22, activity.getString(R.string.review_manual_preview));
                invoke(review, "showFullscreen");
                TextView full = (TextView) field(review, "fullContext");
                require(full != null && full.getVisibility() == View.VISIBLE && expected.contentEquals(full.getText()),
                        "Fullscreen omitted the browse/target distinction");
                require(review.handleBack(), "Back did not close fullscreen");
                require(review.snapshotState().selectedSourceIndex == 4
                                && Arrays.equals(TARGETS, review.snapshotState().targetPages),
                        "Browsing changed selected source or directory targets");
            });
            awaitLayout(instrumentation, fixture);
            onMain(instrumentation, () -> {
                ReviewWorkspace review = fixture.review;
                ReviewWorkspace.State before = review.snapshotState();
                ZoomImageView image = (ZoomImageView) field(review, "image");
                image.zoomIn();
                zoomBefore[0] = image.getZoom();
                require(zoomBefore[0] > 1, "Synthetic preview did not zoom before resize");
                // Disable only this synthetic widget's ratio persistence before changing its axes.
                configured(review, false);
                review.onWindowChanged(360, 640);
                require(!((ResizablePreviewLayout) field(review, "panes")).isWide(),
                        "Constrained window retained side-by-side panes");
                configured(review, false);
                UiAppearance enlarged = UiAppearance.of(UiAppearance.DARK, UiAppearance.EXTRA_LARGE,
                        UiAppearance.COMPACT, false);
                review.applyAppearance(enlarged);
                int wideWidth = Math.round(900 * Math.max(1f,
                        activity.getResources().getConfiguration().fontScale * enlarged.textSp(1)));
                review.onWindowChanged(wideWidth, 600);
                ResizablePreviewLayout panes = (ResizablePreviewLayout) field(review, "panes");
                require(panes.isWide(), "A sufficiently wide window did not use side-by-side panes");
                int shortHeight = panes.getLayoutParams().height;
                panes.setSplitRatio(.63f);
                review.onWindowChanged(wideWidth, 1200);
                require(panes.getLayoutParams().height > shortHeight && Math.abs(panes.getSplitRatio() - .63f) < .001f,
                        "Review height did not follow viewport height or resize lost its drag ratio");
                require(sameProgress(before, review.snapshotState()), "Window/appearance change lost review progress");
                invoke(review, "showAdjustment");
                Object adjustment = field(review, "adjustmentDialog");
                ((RadioButton) field(adjustment, "segmentMode")).setChecked(true);
                for (int i = 1; i < 5; i++) invoke(adjustment, "addSegment");
                ScrollView segmentScroll = (ScrollView) field(adjustment, "segmentScroll");
                LinearLayout columns = (LinearLayout) field(adjustment, "segmentColumns");
                LinearLayout rows = (LinearLayout) field(adjustment, "segmentRows");
                require(columns.getParent() != segmentScroll && rows.getParent() == segmentScroll,
                        "Segment column labels scroll together with the editable ranges");
                require(columns.getChildCount() == 4 && rows.getChildCount() == 5
                                && segmentScroll.getLayoutParams().height > 0,
                        "Segment headers or bounded editable range list are missing");
                for (int i = 0; i < rows.getChildCount(); i++) {
                    LinearLayout row = (LinearLayout) rows.getChildAt(i);
                    require(row.getOrientation() == LinearLayout.HORIZONTAL && row.getChildCount() == 4,
                            "Segment range is not one compact row");
                    Button remove = (Button) row.getChildAt(3);
                    int touch = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                    require(remove.getLayoutParams().width >= touch && remove.getMinHeight() >= touch,
                            "Segment delete control is smaller than 48dp");
                }
            });
            awaitLayout(instrumentation, fixture);
            onMain(instrumentation, () -> {
                ReviewWorkspace review = fixture.review;
                require(Math.abs(((ZoomImageView) field(review, "image")).getZoom() - zoomBefore[0]) < .001f,
                        "Window/appearance layout discarded preview zoom");
                Object adjustment = field(review, "adjustmentDialog");
                ScrollView segmentScroll = (ScrollView) field(adjustment, "segmentScroll");
                LinearLayout columns = (LinearLayout) field(adjustment, "segmentColumns");
                LinearLayout rows = (LinearLayout) field(adjustment, "segmentRows");
                int[] beforePosition = new int[2], afterPosition = new int[2];
                columns.getLocationOnScreen(beforePosition);
                segmentScroll.scrollTo(0, rows.getHeight());
                columns.getLocationOnScreen(afterPosition);
                require(segmentScroll.getScrollY() > 0 && Arrays.equals(beforePosition, afterPosition),
                        "Scrolling editable ranges moved their fixed column labels");
                List<?> inputs = (List<?>) field(adjustment, "segments");
                Object first = inputs.get(0);
                ((EditText) field(first, "start")).setText("10");
                ((EditText) field(first, "end")).setText("12");
                ((EditText) field(first, "target")).setText("15");
                for (int i = rows.getChildCount() - 1; i > 0; i--) {
                    ((Button) ((LinearLayout) rows.getChildAt(i)).getChildAt(3)).performClick();
                }
                require(rows.getChildCount() == 1 && inputs.size() == 1,
                        "Deleting a segment removed the wrong fields");
                ((Button) field(adjustment, "trial")).performClick();
                require(((AlertDialog) field(adjustment, "dialog")).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),
                        "Compact segment fields cannot be trialed/applied");
                require(Arrays.equals(TARGETS, review.snapshotState().targetPages),
                        "Segment trial applied changes without explicit confirmation");
                ((AlertDialog) field(adjustment, "dialog")).dismiss();
            });
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                onMain(instrumentation, fixture::close);
                instrumentation.waitForIdleSync();
                require(originalPreferences.equals(preferences.getAll()), "Synthetic review changed review preferences");
            } catch (Throwable closing) {
                if (failure == null) failure = closing;
                else failure.addSuppressed(closing);
            }
        }
        rethrow(failure);
        return "PASS: source-index reveal, obstructing search/ancestor folds, duplicate titles, busy/invalid navigation, "
                + "browse/target distinction (loading/failure/fullscreen), window/appearance progress, drag ratio, "
                + "fixed segment columns, 48dp deletion and explicit-only mapping; synthetic data only";
    }

    private static final class Fixture {
        Dialog dialog;
        ReviewWorkspace review;
        Bitmap bitmap;
        int requests, lastPage;

        void open(Activity activity) throws Exception {
            UiAppearance appearance = UiAppearance.load(activity);
            review = new ReviewWorkspace(activity, appearance, () -> { }, (page, title) -> {
                requests++; lastPage = page; review.showLoading(page, title);
            });
            configured(review, false);
            review.setDocument(TocParser.parse(SOURCE, 40), 40);
            bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888);
            dialog = new Dialog(appearance.dialogContext(activity));
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            ScrollView shell = new ScrollView(dialog.getContext());
            shell.addView(review, new ScrollView.LayoutParams(-1, -2));
            dialog.setContentView(shell);
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        }

        void showPage(int page, String title) { review.showBitmap(bitmap, page, title); }

        void close() throws Exception {
            if (review != null) { configured(review, false); review.clearDocument(); }
            if (dialog != null) dialog.dismiss();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static boolean sameProgress(ReviewWorkspace.State before, ReviewWorkspace.State after) {
        return before.query.equals(after.query) && before.selectedSourceIndex == after.selectedSourceIndex
                && before.previewPage == after.previewPage && before.previewTitle.equals(after.previewTitle)
                && Arrays.equals(before.targetPages, after.targetPages)
                && Arrays.equals(before.collapsedSourceIndices, after.collapsedSourceIndices);
    }

    private static void awaitLayout(Instrumentation instrumentation, Fixture fixture) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        boolean[] ready = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                ListView list = (ListView) field(fixture.review, "list");
                ready[0] = fixture.dialog.isShowing() && list.isAttachedToWindow() && list.getHeight() > 0;
            });
            if (ready[0]) return;
            SystemClock.sleep(30);
        }
        throw new AssertionError("Synthetic review did not lay out in 3 seconds");
    }

    private static void configured(ReviewWorkspace review, boolean value) throws Exception {
        Field field = ReviewWorkspace.class.getDeclaredField("windowConfigured");
        field.setAccessible(true); field.setBoolean(review, value);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static void invoke(Object object, String name) throws Exception { invoke(object, name, new Class<?>[0]); }

    private static void invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { method.invoke(object, values); }
        catch (InvocationTargetException error) { rethrow(error.getCause()); }
    }

    @FunctionalInterface private interface MainAction { void run() throws Exception; }

    private static void onMain(Instrumentation instrumentation, MainAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        rethrow(failure.get());
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
    }
}
