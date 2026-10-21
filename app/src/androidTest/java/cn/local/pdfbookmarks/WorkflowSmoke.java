package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only review regression using three in-memory rows; no user document or clipboard access. */
public final class WorkflowSmoke {
    private static final String SYNTHETIC_JSON = "{\"version\":1,\"bookmarks\":["
            + "{\"title\":\"章甲\",\"pdf_page\":10,\"children\":[{\"title\":\"节甲\",\"pdf_page\":11}]},"
            + "{\"title\":\"章乙\",\"pdf_page\":20}]}";
    private static final int[] ORIGINAL = {10, 11, 20};
    private static final int[] ADJUSTED = {12, 13, 22};

    private WorkflowSmoke() { }

    /** Runs on the instrumentation worker thread; the temporary dialog is always closed. */
    public static String verify(Instrumentation instrumentation, Activity activity) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("WorkflowSmoke must run on the instrumentation worker thread");
        }
        SharedPreferences preferences = activity.getSharedPreferences("review_ui", Context.MODE_PRIVATE);
        Map<String, ?> beforePreferences = new HashMap<>(preferences.getAll());
        Fixture fixture = new Fixture();
        Throwable failure = null;
        String draftIo = "";
        try {
            draftIo = verifyDraftIo(activity);
            onMain(instrumentation, () -> fixture.open(activity));
            awaitRows(instrumentation, fixture, 3, true);
            onMain(instrumentation, () -> {
                ReviewWorkspace.State initial = fixture.review.snapshotState();
                require(initial.previewPage == 0, "Initial review invented a preview page");
                require(initial.firstListPosition == 0 && initial.listTopOffset == 0,
                        "Initial list position is not canonical");
                require(initial.selectedSourceIndex == -1, "Initial review selected a row");
                require(Arrays.equals(ORIGINAL, initial.targetPages), "Initial targets changed");
            });

            final int[] notificationsBeforeRestore = new int[1];
            onMain(instrumentation, () -> {
                notificationsBeforeRestore[0] = fixture.notifications;
                fixture.review.restoreState(state("没有匹配的词", ADJUSTED));
            });
            awaitRows(instrumentation, fixture, 0, false);
            onMain(instrumentation, () -> {
                ReviewWorkspace.State restored = fixture.review.snapshotState();
                require(restored.previewPage == 13 && "节甲".equals(restored.previewTitle),
                        "Preview state did not restore");
                require("没有匹配的词".equals(restored.query), "Search did not restore");
                require(restored.firstListPosition == 0 && restored.listTopOffset == 0,
                        "Empty search saved a negative or stale list position");
                require(restored.selectedSourceIndex == 1 && Arrays.equals(new int[]{0}, restored.collapsedSourceIndices),
                        "Selection or collapsed source identity did not restore");
                require(Arrays.equals(ADJUSTED, restored.targetPages), "Adjusted targets did not restore");
                require(fixture.rowsApplied == 1 && fixture.callbackPreviewPage == 13,
                        "Restored targets were not invalidated once with consistent preview state");
                require(fixture.notifications == notificationsBeforeRestore[0], "Restore emitted draft notification loops");
                fixture.review.restoreState(state("没有匹配的词", ADJUSTED));
            });
            awaitRows(instrumentation, fixture, 0, false);
            onMain(instrumentation, () -> {
                require(fixture.rowsApplied == 1, "Applying the same draft accumulated a second adjustment");
                require(fixture.notifications == notificationsBeforeRestore[0], "Repeated restore emitted notifications");
                fixture.review.restoreState(state("", ADJUSTED));
            });
            awaitRows(instrumentation, fixture, 2, true);
            onMain(instrumentation, () -> {
                require(fixture.review.snapshotState().selectedSourceIndex == 1,
                        "A collapsed ancestor discarded the selected source row");
                for (int i = 0; i < fixture.list.getAdapter().getCount(); i++) {
                    DirectoryViewModel.VisibleRow visible = (DirectoryViewModel.VisibleRow) fixture.list.getAdapter().getItem(i);
                    require(visible.sourceIndex != 1, "Restore implicitly unfolded the hidden selected row");
                }
                ReviewWorkspace.State valid = fixture.review.snapshotState();
                reject(() -> fixture.review.restoreState(state("changed", new int[]{12, 13})),
                        "Incomplete restored targets were accepted");
                require(same(valid, fixture.review.snapshotState()), "Rejected length changed valid review state");
                reject(() -> fixture.review.restoreState(state("changed", new int[]{12, 41, 22})),
                        "Out-of-range restored targets were accepted");
                require(same(valid, fixture.review.snapshotState()), "Rejected page changed valid review state");
                require(fixture.rowsApplied == 1, "Rejected restore invalidated valid targets");
            });

            onMain(instrumentation, () -> {
                ReviewWorkspace.State beforeLongPress = fixture.review.snapshotState();
                int requested = fixture.pageRequests;
                View first = fixture.list.getChildAt(0);
                require(first != null && first.performLongClick(), "Directory row did not handle long press");
                AlertDialog details = (AlertDialog) field(fixture.review, "detailDialog");
                require(details != null && details.isShowing(), "Long press did not open row details");
                require(containsText(details.getWindow().getDecorView(), "章甲"), "Details omitted the full title");
                require(details.getButton(AlertDialog.BUTTON_NEUTRAL) != null
                                && details.getButton(AlertDialog.BUTTON_POSITIVE) != null,
                        "Details omitted copy controls");
                // Do not click copy controls: the user's current clipboard must remain untouched.
                require(fixture.pageRequests == requested && same(beforeLongPress, fixture.review.snapshotState()),
                        "Long press selected a row or changed its preview/targets");
                require(fixture.review.handleBack(), "Back did not consume row details");
                require(!details.isShowing() && !fixture.review.hasModalUi(), "Back left row details open");
                require(!fixture.review.handleBack(), "Review consumed Back after transient UI closed");
                require(fixture.rowsApplied == 1, "Details changed applied targets");
            });
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                onMain(instrumentation, fixture::close);
                instrumentation.waitForIdleSync();
                require(beforePreferences.equals(preferences.getAll()), "Synthetic review changed review_ui preferences");
            } catch (Throwable closing) {
                if (failure == null) failure = closing;
                else failure.addSuppressed(closing);
            }
        }
        rethrow(failure);
        return "PASS: synthetic review initial state, draft restore/idempotence, empty search, hidden selection, "
                + "invalid-target rollback, long-press details and Back; no user document/clipboard operation; review preferences unchanged; "
                + draftIo;
    }

    /** Real AtomicFile calls, confined to a fresh UUID cache child; the evidence is deliberately retained. */
    private static String verifyDraftIo(Activity activity) throws Exception {
        File cache = activity.getCacheDir().getCanonicalFile();
        File evidence = new File(cache, "workflow-smoke-" + UUID.randomUUID()).getCanonicalFile();
        require(cache.equals(evidence.getParentFile()), "Synthetic draft evidence escaped the app cache");
        if (!evidence.mkdir()) throw new IOException("Cannot create unique synthetic draft evidence: " + evidence);
        StringBuilder log = new StringBuilder("Synthetic DraftStore/AtomicFile device checks\nEvidence: ")
                .append(evidence).append('\n');
        int checks = 0;
        Throwable failure = null;
        try {
            TocParser.Parsed source = TocParser.parse(SYNTHETIC_JSON, 40);
            String pdf = "content://workflow-smoke/synthetic.pdf", json = "content://workflow-smoke/synthetic.json";
            String pdfSha = String.join("", Collections.nCopies(64, "a"));
            String jsonSha = String.join("", Collections.nCopies(64, "b"));
            ReviewDraft initial = ReviewDraft.capture(pdf, json, pdfSha, jsonSha, source.rows, source.rows, 40,
                    "", Collections.emptyList(), -1, 0, "", 0, 0);
            PageAdjustments mapping = new PageAdjustments(source.rows, 40);
            ReviewDraft adjusted = ReviewDraft.capture(pdf, json, pdfSha, jsonSha, source.rows, mapping.previewOffset(2),
                    40, "章", Collections.singletonList(0), 1, 13, "节甲", 0, -12);
            write(new File(evidence, "initial-expected.json"), initial.toJson());
            write(new File(evidence, "adjusted-expected.json"), adjusted.toJson());

            DraftStore first = new DraftStore(child(evidence, "initial"));
            DraftStore.LoadResult absent = first.load();
            require(absent.draft == null && absent.error == null, "Missing synthetic draft reported corruption");
            checks++; log.append("PASS 1: missing draft is distinct from corruption\n");
            first.save(initial);
            ReviewDraft restoredInitial = loaded(first, initial);
            restoredInitial.validateAgainst(source.rows, 40);
            require(restoredInitial.previewPage == 0 && restoredInitial.selectedSourceIndex == -1,
                    "Real draft I/O invented a preview or selected row");
            checks++; log.append("PASS 2: initial draft save/load preserves preview 0\n");

            DraftStore changed = new DraftStore(child(evidence, "adjusted"));
            changed.save(adjusted);
            ReviewDraft restoredAdjusted = loaded(changed, adjusted);
            restoredAdjusted.validateAgainst(source.rows, 40);
            require(Arrays.equals(ADJUSTED, restoredAdjusted.targetPages())
                            && restoredAdjusted.collapsedSourceIndices().equals(Collections.singletonList(0))
                            && restoredAdjusted.previewPage == 13 && restoredAdjusted.selectedSourceIndex == 1,
                    "Real draft I/O changed adjusted targets or UI identity");
            checks++; log.append("PASS 3: adjusted draft save/load/validation preserves complete state\n");
            require(restoredAdjusted.matches(pdf, json, pdfSha, jsonSha), "Saved draft lost matching file identity");
            checks++; log.append("PASS 4: unchanged synthetic identities match\n");
            require(!restoredAdjusted.matches(pdf, json, jsonSha, jsonSha)
                            && !restoredAdjusted.matches(pdf + ".changed", json, pdfSha, jsonSha),
                    "Draft accepted changed content or file identity");
            checks++; log.append("PASS 5: changed synthetic content and path are rejected\n");

            File corruptDirectory = child(evidence, "corrupt");
            write(new File(corruptDirectory, "review-draft-v1.json"), "{\"version\":1,broken");
            DraftStore.LoadResult corrupt = new DraftStore(corruptDirectory).load();
            require(corrupt.draft == null && corrupt.error != null && !corrupt.error.isEmpty(),
                    "Corrupt draft did not return a non-crashing error result");
            log.append("Corrupt draft diagnostic: ").append(corrupt.error).append('\n');
            checks++; log.append("PASS 6: malformed JSON is reported without a crash\n");

            File rollbackDirectory = child(evidence, "rollback");
            DraftStore rollback = new DraftStore(rollbackDirectory);
            rollback.save(adjusted);
            AtomicFile transaction = new AtomicFile(new File(rollbackDirectory, "review-draft-v1.json"));
            FileOutputStream interrupted = transaction.startWrite();
            try { interrupted.write("{\"unfinished\":".getBytes(StandardCharsets.UTF_8)); }
            finally { transaction.failWrite(interrupted); }
            loaded(rollback, adjusted).validateAgainst(source.rows, 40);
            write(new File(rollbackDirectory, "interrupted-payload.txt"), "{\"unfinished\":");
            checks++; log.append("PASS 7: real AtomicFile startWrite/failWrite retains the previous valid draft\n");

            File backupDirectory = child(evidence, "legacy-backup");
            File base = new File(backupDirectory, "review-draft-v1.json");
            write(base, "{broken-current");
            write(new File(base.getPath() + ".bak"), adjusted.toJson());
            // openRead performs Android's normal backup recovery; no test cleanup/delete is invoked.
            loaded(new DraftStore(backupDirectory), adjusted).validateAgainst(source.rows, 40);
            checks++; log.append("PASS 8: real AtomicFile openRead recovers a valid existing .bak\n");
        } catch (Throwable error) {
            failure = error;
            log.append("FAIL: ").append(error).append('\n');
        } finally {
            log.append("Completed checks: ").append(checks).append(" / 8\n")
                    .append("Evidence retained; no cleanup API called.\n");
            try { write(new File(evidence, "checks.txt"), log.toString()); }
            catch (IOException reporting) {
                if (failure == null) failure = reporting;
                else failure.addSuppressed(reporting);
            }
        }
        rethrow(failure);
        return "DraftStore/AtomicFile " + checks + " checks PASS; retained synthetic cache evidence: " + evidence;
    }

    private static File child(File parent, String name) throws IOException {
        File directory = new File(parent, name);
        if (!directory.mkdir()) throw new IOException("Cannot create synthetic draft test folder: " + directory);
        return directory;
    }

    private static ReviewDraft loaded(DraftStore store, ReviewDraft expected) {
        DraftStore.LoadResult result = store.load();
        require(result.error == null && result.draft != null && expected.toJson().equals(result.draft.toJson()),
                "DraftStore round-trip or AtomicFile recovery changed the draft: " + result.error);
        return result.draft;
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ReviewWorkspace.State state(String query, int[] pages) {
        return new ReviewWorkspace.State(query, new int[]{0}, 1, 13, "节甲", 0, -12, pages);
    }

    private static boolean same(ReviewWorkspace.State before, ReviewWorkspace.State after) {
        return before.query.equals(after.query) && Arrays.equals(before.collapsedSourceIndices, after.collapsedSourceIndices)
                && before.selectedSourceIndex == after.selectedSourceIndex && before.previewPage == after.previewPage
                && before.previewTitle.equals(after.previewTitle) && before.firstListPosition == after.firstListPosition
                && before.listTopOffset == after.listTopOffset && Arrays.equals(before.targetPages, after.targetPages);
    }

    private static final class Fixture {
        Dialog dialog;
        ReviewWorkspace review;
        ListView list;
        int rowsApplied, notifications, pageRequests, callbackPreviewPage;

        void open(Activity activity) throws Exception {
            UiAppearance appearance = UiAppearance.load(activity);
            review = new ReviewWorkspace(activity, appearance, () -> {
                rowsApplied++;
                callbackPreviewPage = review.snapshotState().previewPage;
            }, (page, title) -> {
                pageRequests++;
                review.showLoading(page, title);
            });
            review.setOnStateChangedListener(() -> notifications++);
            review.setDocument(TocParser.parse(SYNTHETIC_JSON, 40), 40);
            review.setFocusableInTouchMode(true);
            review.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            review.requestFocus();
            list = (ListView) field(review, "list");
            dialog = new Dialog(appearance.dialogContext(activity));
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.setContentView(review);
            dialog.show();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        void close() throws Exception {
            if (review != null) {
                // This fixture never changes a pane ratio. Suppress only its detach-time preference write.
                Field configured = ReviewWorkspace.class.getDeclaredField("windowConfigured");
                configured.setAccessible(true);
                configured.setBoolean(review, false);
                review.clearDocument();
            }
            if (dialog != null) dialog.dismiss();
        }
    }

    private static void awaitRows(Instrumentation instrumentation, Fixture fixture, int expected, boolean requireChild)
            throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        boolean[] ready = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> ready[0] = fixture.dialog.isShowing() && fixture.list.isAttachedToWindow()
                    && fixture.list.getWidth() > 0 && fixture.list.getHeight() > 0
                    && fixture.list.getAdapter().getCount() == expected && (!requireChild || fixture.list.getChildCount() > 0));
            if (ready[0]) return;
            SystemClock.sleep(30);
        }
        throw new AssertionError("Synthetic review did not lay out " + expected + " rows within 3 seconds");
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) if (containsText(parent.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(CheckedAction action, String message) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static void onMain(Instrumentation instrumentation, CheckedAction action) throws Exception {
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
