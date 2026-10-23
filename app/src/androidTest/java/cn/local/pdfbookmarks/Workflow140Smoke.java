package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Actual workflow controls with synthetic state only; no document stream, grant or preference write. */
public final class Workflow140Smoke {
    private static final int SAVE_PDF = 3;
    private static final String SOURCE = "{\"version\":1,\"bookmarks\":[{\"title\":\"合成章节\",\"pdf_page\":2}]}";

    private Workflow140Smoke() { }

    public static String verify(Instrumentation instrumentation, Activity activity) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Use instrumentation worker thread");
        Fixture fixture = new Fixture(activity);
        Throwable failure = null;
        try {
            onMain(instrumentation, fixture::open);
            verifyMissingInputs(instrumentation, fixture);
            onMain(instrumentation, () -> verifyImportDiagnostics(fixture));
            onMain(instrumentation, () -> {
                fixture.document();
                fixture.outcome.begin("合成取消另存");
                fixture.outcome.set(TaskOutcome.Part.PDF, TaskOutcome.Status.SUCCESS, "合成PDF已生成并核验");
                fixture.outcome.set(TaskOutcome.Part.SAVE, TaskOutcome.Status.WAITING, "等待保存选择");
                fixture.outcome.set(TaskOutcome.Part.JSON, TaskOutcome.Status.RETAINED, "JSON保留");
                set(activity, "readyVerified", true); set(activity, "systemPickerRequest", SAVE_PDF);
                invoke(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class},
                        SAVE_PDF, Activity.RESULT_CANCELED, null);
                assertCancelled(fixture);
                fixture.outcome.set(TaskOutcome.Part.SAVE, TaskOutcome.Status.WAITING, "等待保存选择");
                set(activity, "systemPickerRequest", SAVE_PDF);
                invoke(activity, "cancelCurrentInput");
                assertCancelled(fixture);
            });
            onMain(instrumentation, () -> verifyOptions(fixture));
            onMain(instrumentation, () -> verifyPending(fixture));
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                require(((View) field(activity, "recoverCatalog")).isShown()
                                && ((View) field(activity, "recoverCatalog")).isEnabled(),
                        "Pending primary action did not expose the directory recovery control");
                require(text(activity, "status").contains("已标出"), "Pending primary action omitted its location guidance");
                require(fixture.memory.values.equals(Collections.singletonMap("pending", "synthetic-journal-do-not-execute")),
                        "Locating a pending task changed its synthetic journal");
                fixture.memory.values.clear();
                verifySavedResult(fixture);
                verifyFailedActions(fixture);
                verifyReplacedPdfDraft(fixture);
            });
            verifyMenu(instrumentation, fixture, "外观");
            onMain(instrumentation, () -> {
                AlertDialog dialog = (AlertDialog) field(activity, "appearanceDialog");
                require(dialog != null && dialog.isShowing(), "More > Appearance did not open the actual settings dialog");
                require(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(), "Appearance Cancel did not run");
            });
            verifyMenu(instrumentation, fixture, "版本与更新");
            onMain(instrumentation, () -> {
                View panel = (View) field(activity, "utilityPanel");
                require(panel.getVisibility() == View.VISIBLE && button(panel, "检查更新") != null,
                        "More > Version and updates did not expose the actual update controls");
                Button close = button(panel, "收起版本与更新");
                require(close != null && close.performClick(), "Version and updates could not be collapsed");
                require(panel.getVisibility() != View.VISIBLE, "Version and updates remained expanded");
            });
            verifyMenu(instrumentation, fixture, "使用帮助");
            onMain(instrumentation, () -> {
                AlertDialog menu = (AlertDialog) field(activity, "workflowDialog");
                require(menu != null && menu.isShowing() && menu.getListView().getAdapter().getCount() == 4,
                        "More > Help did not expose all four help topics");
                require(menu.getListView().performItemClick(menu.getListView().getChildAt(0), 0, 0),
                        "File and authorization help topic did not open");
                AlertDialog detail = (AlertDialog) field(activity, "workflowDialog");
                require(detail != null && detail != menu && detail.isShowing()
                                && contains(detail.getWindow().getDecorView(), "移除授权只撤销本工具的访问权"),
                        "Context help omitted the authorization boundary");
                require(detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick(), "Help detail did not close");
            });
        } catch (Throwable error) {
            failure = error;
        } finally {
            AtomicReference<Throwable> restoration = new AtomicReference<>();
            if (fixture.menuOpen) attemptRestore(restoration, () -> dismissOwnedMenu(instrumentation, activity));
            if (fixture.errorOpen) attemptRestore(restoration, () -> tap(instrumentation, activity, "知道了"));
            attemptRestore(restoration, () -> onMain(instrumentation, fixture::close));
            attemptRestore(restoration, () -> {
                instrumentation.waitForIdleSync();
                fixture.assertPreferencesUnchanged();
            });
            if (restoration.get() != null) {
                if (failure == null) failure = restoration.get(); else failure.addSuppressed(restoration.get());
            }
        }
        rethrow(failure);
        return "PASS: precise missing-input guidance and clickable diagnostics-only reports; both save-cancel routes stay awaiting user; context options and "
                + "catalog mode retention; pending primary/result actions locate without recovery; saved name/URI and one open action; "
                + "persistent failure reasons, save-options continuation, catalog-only retry confirmation and explicit keep-JSON completion; "
                + "replacement draft capture uses the final PDF identity and preserves adjusted targets without draft I/O; "
                + "More > Appearance/updates/help reachable; no update check/download/install requested; "
                + "only synthetic state/URIs, all original workflow/review/appearance preferences unchanged";
    }

    private static void verifyMissingInputs(Instrumentation instrumentation, Fixture fixture) throws Exception {
        for (boolean pdfOnly : new boolean[]{true, false}) {
            String expected = pdfOnly ? "还需选择目录JSON。" : "还需选择PDF原文件。";
            onMain(instrumentation, () -> {
                set(fixture.activity, "pdfUri", pdfOnly ? fixture.pdf : null);
                set(fixture.activity, "jsonUri", pdfOnly ? null : fixture.json);
                invoke(fixture.activity, "updateButtons");
                require(!((Button) field(fixture.activity, "generate")).isEnabled(), "An incomplete input pair enabled reading");
                fixture.errorOpen = true;
                invoke(fixture.activity, "loadPreview");
                require(expected.equals(text(fixture.activity, "status")), "Missing input guidance did not name the missing file");
                require(field(fixture.activity, "readJob") == null, "Missing input started document reading");
            });
            awaitText(instrumentation, fixture.activity, expected);
            tap(instrumentation, fixture.activity, "知道了"); fixture.errorOpen = false;
        }
    }

    private static void verifyImportDiagnostics(Fixture fixture) throws Exception {
        Activity activity = fixture.activity;
        TocDiagnostics.Report first = TocDiagnostics.inspect(
                "{\"version\":1,\"bookmarks\":[{\"title\":\"合成越界页\",\"pdf_page\":9}]}", 5);
        TocDiagnostics.Report current = TocDiagnostics.inspect(
                "{\"version\":1,\"bookmarks\":[{\"title\":\"合成零页码\",\"pdf_page\":0}]}", 5);
        require(first.hasErrors() && first.parsed == null && current.hasErrors() && current.parsed == null,
                "Synthetic invalid imports did not produce diagnostics-only reports");
        set(activity, "pdfUri", fixture.pdf); set(activity, "jsonUri", fixture.json);
        set(activity, "parsed", null); set(activity, "info", null); set(activity, "diagnostics", first);
        invoke(activity, "updateButtons");
        TextView summary = (TextView) field(activity, "changeSummary");
        require(summary.isShown() && summary.isClickable() && summary.performClick(),
                "Diagnostics without a parsed document did not offer a direct detail action");
        AlertDialog detail = (AlertDialog) field(activity, "workflowDialog");
        require(detail != null && detail.isShowing() && contains(detail.getWindow().getDecorView(), first.describe()),
                "Diagnostics-only action omitted its actual import errors");
        require(detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick(), "Import diagnostics detail did not close");
        set(activity, "diagnostics", current); invoke(activity, "updateButtons");
        require(summary.isClickable() && summary.performClick(), "A replacement import report could not be opened");
        detail = (AlertDialog) field(activity, "workflowDialog");
        require(detail != null && detail.isShowing() && contains(detail.getWindow().getDecorView(), current.describe())
                        && !contains(detail.getWindow().getDecorView(), "合成越界页"),
                "Diagnostics action reused an earlier import report");
        detail.cancel();
        require(!detail.isShowing() && field(activity, "diagnostics") == current
                        && field(activity, "parsed") == null && field(activity, "info") == null
                        && field(activity, "readJob") == null && !(Boolean) field(activity, "busy"),
                "Cancelling import details changed the report or began document processing");
        set(activity, "diagnostics", null); invoke(activity, "updateButtons");
        require(!summary.isClickable() && !summary.hasOnClickListeners() && !summary.performClick()
                        && field(activity, "workflowDialog") == null,
                "Empty import state retained an obsolete detail action");
    }

    private static void assertCancelled(Fixture fixture) throws Exception {
        require(fixture.outcome.overall() == TaskOutcome.Overall.WAITING_ACTION, "Cancelled save was completed or left running");
        require(fixture.outcome.entries().get(0).status == TaskOutcome.Status.SUCCESS
                        && fixture.outcome.entries().get(1).status == TaskOutcome.Status.AWAITING_USER,
                "Cancelled save discarded successful generation or its awaiting-user state");
        require((Boolean) field(fixture.activity, "readyVerified")
                        && (Integer) field(fixture.activity, "systemPickerRequest") == 0,
                "Cancelled save discarded the verified output or left the picker locked");
        require(text(fixture.activity, "resultHeading").contains("等待")
                        && !text(fixture.activity, "resultHeading").contains("已完成"),
                "Actual result heading incorrectly announced completed save");
        require(action(fixture.activity, "继续保存已生成的PDF").isEnabled(), "Cancelled save has no enabled continuation");
    }

    private static void verifyOptions(Fixture fixture) throws Exception {
        Activity activity = fixture.activity;
        CheckBox overwrite = (CheckBox) field(activity, "overwrite"), sync = (CheckBox) field(activity, "syncEnjoy");
        RadioGroup mode = (RadioGroup) field(activity, "catalogMode");
        int replaceMode = ((View) field(activity, "replaceCatalog")).getId();
        set(activity, "readyVerified", false); set(activity, "pdfUri", fixture.pdf);
        overwrite.setChecked(true); sync.setChecked(true); invoke(activity, "updateButtons");
        require(((View) field(activity, "enjoyOptions")).getVisibility() == View.GONE,
                "An ordinary PDF displayed Enjoy directory options");
        String note = "note_workflow140_" + UUID.randomUUID().toString().replace("-", "");
        set(activity, "pdfUri", DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                "primary:享做笔记/note/" + note + "__res__/" + note + ".pdf"));
        mode.check(replaceMode); invoke(activity, "updateButtons");
        require(((View) field(activity, "enjoyOptions")).getVisibility() == View.VISIBLE,
                "A recognized synthetic Enjoy path did not expose its context options");
        require(((View) field(activity, "catalogChoices")).getVisibility() == View.VISIBLE, "Catalog mode was hidden for applicable sync");
        for (boolean value : new boolean[]{false, true}) {
            overwrite.setChecked(value); sync.setChecked(value); invoke(activity, "updateButtons");
            require(mode.getCheckedRadioButtonId() == replaceMode, "Changing save/sync switches reset the catalog mode");
            require(((View) field(activity, "catalogChoices")).getVisibility() == View.VISIBLE,
                    "Matching existing bookmarks lost the independent sync mode");
        }
        ((CheckBox) field(activity, "confirmed")).setChecked(true); invoke(activity, "updateButtons");
        require("仅同步享做目录".equals(text(activity, "generate")), "Same-bookmark context lost the direct sync primary action");
    }

    private static void verifyPending(Fixture fixture) throws Exception {
        Activity activity = fixture.activity;
        set(activity, "pdfUri", null); set(activity, "jsonUri", null); set(activity, "parsed", null); set(activity, "info", null);
        ((CheckBox) field(activity, "confirmed")).setChecked(false);
        fixture.memory.values.put("pending", "synthetic-journal-do-not-execute"); fixture.outcome.begin("合成待恢复");
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        Button primary = (Button) field(activity, "generate");
        require(primary.isEnabled() && "处理未完成任务".contentEquals(primary.getText()),
                "Pending work could not be reached without a complete input pair");
        require(primary.performClick(), "Pending primary action did not respond");
        require(action(activity, "处理未完成任务").performClick(), "Pending result continuation did not respond");
        require(!(Boolean) field(activity, "busy") && field(activity, "readJob") == null,
                "Pending navigation started recovery or reading automatically");
    }

    private static void verifySavedResult(Fixture fixture) throws Exception {
        Activity activity = fixture.activity; fixture.document();
        fixture.outcome.begin("合成已保存");
        fixture.outcome.set(TaskOutcome.Part.PDF, TaskOutcome.Status.SUCCESS, "已生成");
        fixture.outcome.set(TaskOutcome.Part.SAVE, TaskOutcome.Status.SUCCESS, "已保存并核验");
        set(activity, "exportedUri", fixture.saved); set(activity, "readyVerified", false);
        ((CheckBox) field(activity, "confirmed")).setChecked(false);
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        TextView file = (TextView) field(activity, "resultFile");
        require(file.getVisibility() == View.VISIBLE && file.getText().toString().startsWith("已保存：workflow-140-saved.pdf")
                        && file.getText().toString().contains(fixture.saved.toString()),
                "Saved result omitted its actual basename or provider URI");
        String suppliedName = "workflow-140-provider-name.pdf";
        set(activity, "exportedName", suppliedName); invoke(activity, "updateButtons");
        String cached = file.getText().toString();
        require(cached.startsWith("已保存：" + suppliedName) && cached.contains(fixture.saved.toString()),
                "Saved result ignored the cached provider name in favor of its URI basename");
        invoke(activity, "updateButtons");
        require(cached.contentEquals(file.getText()) && suppliedName.equals(field(activity, "exportedName")),
                "Refreshing controls replaced the cached saved-file identity");
        require(((View) field(activity, "open")).getVisibility() == View.GONE, "Duplicate saved-PDF open button remained visible");
        String label = activity.getString(R.string.ui_primary_open);
        require(label.equals(text(activity, "generate")) && visibleButtons(activity.findViewById(android.R.id.content), label) == 1,
                "Saved PDF does not have exactly one visible primary open action");
    }

    private static void verifyFailedActions(Fixture fixture) throws Exception {
        Activity activity = fixture.activity; set(activity, "exportedUri", null);
        fixture.outcome.set(TaskOutcome.Part.SAVE, TaskOutcome.Status.FAILED, "合成保存权限错误");
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        require(contains((View) field(activity, "resultRows"), "合成保存权限错误"), "Failure reason is not persistent beside its action");
        require(action(activity, "检查文件与保存选项").performClick(), "Save failure did not open its next step");
        require(((View) field(activity, "optionsPanel")).getVisibility() == View.VISIBLE
                        && fixture.outcome.entries().get(0).status == TaskOutcome.Status.SUCCESS,
                "Save continuation did not expose options or discarded generation success");
        set(activity, "busy", true); invoke(activity, "updateButtons");
        require(!action(activity, "检查文件与保存选项").isEnabled(), "Failure continuation bypassed the busy lock");
        set(activity, "busy", false); invoke(activity, "updateButtons");
        fixture.outcome.set(TaskOutcome.Part.SAVE, TaskOutcome.Status.SUCCESS, "已保存");
        fixture.outcome.set(TaskOutcome.Part.CATALOG, TaskOutcome.Status.FAILED, "合成目录失败");
        Constructor<?> retry = Class.forName(MainActivity.class.getName() + "$CatalogRetry").getDeclaredConstructor(
                Uri.class, String.class, Uri.class, String.class, int.class, List.class, boolean.class);
        retry.setAccessible(true);
        set(activity, "catalogRetry", retry.newInstance(fixture.saved, String.join("", Collections.nCopies(64, "a")),
                fixture.json, String.join("", Collections.nCopies(64, "b")), 5, fixture.parsed.rows, true));
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        require(action(activity, "仅重试未完成的享做目录同步").performClick(), "Catalog failure did not offer its single-stage continuation");
        AlertDialog confirmation = (AlertDialog) field(activity, "workflowDialog");
        require(confirmation != null && confirmation.isShowing()
                        && contains(confirmation.getWindow().getDecorView(), "不重新生成或替换PDF"),
                "Catalog retry omitted its limited scope confirmation");
        require(!(Boolean) field(activity, "busy") && fixture.outcome.entries().get(1).status == TaskOutcome.Status.SUCCESS,
                "Opening retry confirmation changed a successful save or started work");
        require(confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(), "Catalog retry Cancel did not run");
        require(fixture.outcome.entries().get(2).status == TaskOutcome.Status.FAILED, "Cancelling retry erased the unresolved catalog failure");
        set(activity, "catalogRetry", null);
        fixture.outcome.set(TaskOutcome.Part.CATALOG, TaskOutcome.Status.SUCCESS, "目录已同步");
        fixture.outcome.set(TaskOutcome.Part.CLEANUP, TaskOutcome.Status.FAILED, "合成旧件清理失败，备份保留");
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        require(action(activity, "重新核验清理清单").isEnabled()
                        && fixture.outcome.entries().get(1).status == TaskOutcome.Status.SUCCESS
                        && fixture.outcome.entries().get(2).status == TaskOutcome.Status.SUCCESS,
                "Cleanup failure lost its single-step action or successful preceding stages");
        // Do not click cleanup: even a read-only cleanup recheck must not inspect any stored user backup.
        fixture.outcome.set(TaskOutcome.Part.CLEANUP, TaskOutcome.Status.RETAINED, "备份保留");
        fixture.outcome.set(TaskOutcome.Part.JSON, TaskOutcome.Status.FAILED, "合成JSON删除失败，来源保留");
        set(activity, "exportedUri", fixture.saved); ((CheckBox) field(activity, "deleteJson")).setChecked(true);
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        require(action(activity, "保留JSON，结束此可选步骤").performClick(), "JSON failure omitted its local optional-step completion");
        AlertDialog keep = (AlertDialog) field(activity, "workflowDialog");
        require(keep != null && keep.isShowing() && contains(keep.getWindow().getDecorView(), "不会重新生成、保存或清理文件"),
                "Keep-JSON confirmation omitted the no-file-operation boundary");
        require(keep.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(), "Keep-JSON Cancel did not run");
        require(fixture.outcome.entries().get(3).status == TaskOutcome.Status.FAILED
                        && ((CheckBox) field(activity, "deleteJson")).isChecked(),
                "Cancelling optional-step completion erased the failure or changed the deletion option");
        require(action(activity, "保留JSON，结束此可选步骤").performClick(), "JSON completion could not be reopened");
        keep = (AlertDialog) field(activity, "workflowDialog");
        require(keep.getButton(AlertDialog.BUTTON_POSITIVE).performClick(), "Explicit Keep JSON did not complete the optional step");
        require(fixture.outcome.entries().get(3).status == TaskOutcome.Status.RETAINED
                        && !((CheckBox) field(activity, "deleteJson")).isChecked()
                        && fixture.saved.equals(field(activity, "exportedUri")) && fixture.json.equals(field(activity, "jsonUri")),
                "Keep JSON did not retain the source/previously saved output and clear only its deletion option");
        for (int i = 0; i < 3; i++) require(fixture.outcome.entries().get(i).status == TaskOutcome.Status.SUCCESS,
                "Keep JSON changed a successful PDF, save or catalog stage");
        require(fixture.outcome.overall() == TaskOutcome.Overall.COMPLETE
                        && ((List<?>) field(activity, "resultActions")).isEmpty()
                        && !(Boolean) field(activity, "busy") && field(activity, "readJob") == null,
                "Keep JSON left a failure action or started another document operation");
        set(activity, "exportedUri", null);
        fixture.outcome.begin("合成生成失败"); fixture.outcome.set(TaskOutcome.Part.PDF, TaskOutcome.Status.FAILED, "合成输入错误");
        fixture.outcome.set(TaskOutcome.Part.SAVE, TaskOutcome.Status.SKIPPED, "未运行保存");
        invoke(activity, "renderOutcome"); invoke(activity, "updateButtons");
        require(action(activity, "返回目录核对").performClick(), "Generation failure did not offer return to review");
        require(!(Boolean) field(activity, "busy") && field(activity, "readJob") == null,
                "Returning from failure started a document operation");
    }

    private static void verifyReplacedPdfDraft(Fixture fixture) throws Exception {
        Activity activity = fixture.activity; fixture.document();
        String sourceHash = String.join("", Collections.nCopies(64, "a"));
        String finalHash = String.join("", Collections.nCopies(64, "c"));
        String jsonHash = String.join("", Collections.nCopies(64, "b"));
        set(activity, "pdfHash", sourceHash); set(activity, "jsonHash", jsonHash);
        PageAdjustments adjustments = (PageAdjustments) field(fixture.syntheticReview, "adjustments");
        adjustments.apply(adjustments.previewOffset(2));
        require(fixture.syntheticReview.rows().get(0).pdfPage == 4 && fixture.parsed.rows.get(0).pdfPage == 2,
                "Draft fixture did not keep distinct original and adjusted target pages");
        Constructor<?> retry = Class.forName(MainActivity.class.getName() + "$CatalogRetry").getDeclaredConstructor(
                Uri.class, String.class, Uri.class, String.class, int.class, List.class, boolean.class);
        retry.setAccessible(true);
        Object retryContext = retry.newInstance(fixture.saved, finalHash, fixture.json, jsonHash, 5,
                fixture.syntheticReview.rows(), true);
        set(activity, "catalogRetry", retryContext); set(activity, "sourceNeedsReload", true);
        Map<String, Object> scheduling = new LinkedHashMap<>();
        for (String name : new String[]{"draftRevision", "storedDraftRevision", "draftSave", "savedDraft", "draftError", "draftStore", "busy", "readJob"})
            scheduling.put(name, field(activity, name));
        ReviewDraft baseline = ReviewDraft.capture(fixture.pdf.toString(), fixture.json.toString(), sourceHash, jsonHash,
                fixture.parsed.rows, fixture.parsed.rows, 5, "", Collections.emptyList(), -1, 0, "", 0, 0);
        ReviewDraft captured = (ReviewDraft) invoke(activity, "captureReviewDraft");
        require(captured.matches(fixture.saved.toString(), fixture.json.toString(), finalHash, jsonHash)
                        && !captured.matches(fixture.pdf.toString(), fixture.json.toString(), sourceHash, jsonHash),
                "Draft after successful PDF replacement retained the obsolete source PDF identity");
        require(captured.originalTreeSha256.equals(baseline.originalTreeSha256)
                        && Arrays.equals(new int[]{4}, captured.targetPages()),
                "Draft after replacement lost its original tree baseline or current target adjustment");
        captured.validateAgainst(fixture.parsed.rows, 5);
        List<TocParser.Row> restored = captured.restoredRows(fixture.parsed.rows, 5);
        require(restored.size() == 1 && restored.get(0).pdfPage == 4
                        && restored.get(0).level == fixture.parsed.rows.get(0).level
                        && restored.get(0).title.equals(fixture.parsed.rows.get(0).title),
                "Captured draft could not restore the adjusted mapping against its original source tree");
        for (Map.Entry<String, Object> value : scheduling.entrySet())
            require(Objects.equals(value.getValue(), field(activity, value.getKey())),
                    "Pure draft capture changed persistence scheduling or workflow state: " + value.getKey());
        require(fixture.pdf.equals(field(activity, "pdfUri")) && sourceHash.equals(field(activity, "pdfHash"))
                        && (Boolean) field(activity, "sourceNeedsReload") && field(activity, "catalogRetry") == retryContext
                        && fixture.syntheticReview.rows().get(0).pdfPage == 4,
                "Draft capture mutated the current source, retry context or adjusted review");
        adjustments.apply(fixture.parsed.rows); fixture.document();
    }

    private static void verifyMenu(Instrumentation instrumentation, Fixture fixture, String item) throws Exception {
        onMain(instrumentation, () -> {
            Button more = button(fixture.activity.findViewById(android.R.id.content), "更多");
            require(more != null && more.isShown() && more.isEnabled(), "The top-level More control is not reachable");
            fixture.menuOpen = true; require(more.performClick(), "The top-level More control did not open");
        });
        tap(instrumentation, fixture.activity, item); fixture.menuOpen = false;
        instrumentation.waitForIdleSync();
    }

    private static final class Fixture {
        final Activity activity;
        final Uri pdf, json, saved;
        final TocParser.Parsed parsed = TocParser.parse(SOURCE, 5);
        final TaskOutcome outcome = new TaskOutcome();
        final MemoryPreferences memory = new MemoryPreferences();
        final Map<String, Object> fields = new LinkedHashMap<>();
        final List<ViewState> views = new ArrayList<>();
        final Map<SharedPreferences, Map<String, ?>> preferences = new LinkedHashMap<>();
        ReviewWorkspace syntheticReview;
        View focus;
        SharedPreferences updatePreferences;
        boolean hadAutomaticUpdate, automaticUpdate;
        int scrollX, scrollY;
        boolean captured, menuOpen, errorOpen;

        Fixture(Activity activity) {
            this.activity = activity;
            String authority = activity.getPackageName() + ".workflow140";
            pdf = DocumentsContract.buildDocumentUri(authority, "Workflow140/input.pdf");
            json = DocumentsContract.buildDocumentUri(authority, "Workflow140/input.json");
            saved = DocumentsContract.buildDocumentUri(authority, "Workflow140/workflow-140-saved.pdf");
        }

        void open() throws Exception {
            require(!(Boolean) field(activity, "busy") && !(Boolean) field(activity, "shareLoading")
                            && field(activity, "readJob") == null && (Integer) field(activity, "systemPickerRequest") == 0,
                    "Workflow smoke requires an idle activity");
            require(!activity.getPreferences(Context.MODE_PRIVATE).contains("pendingPdf")
                            && !activity.getPreferences(Context.MODE_PRIVATE).contains("pendingCleanup")
                            && !((EnjoyDirectorySync) field(activity, "enjoy")).hasPending()
                            && ((EnjoyDirectorySync) field(activity, "enjoy")).automaticCleanupRecord() == null,
                    "Existing recovery work must be left undisturbed");
            for (String modal : new String[]{"appearanceDialog", "workflowDialog", "filePicker"}) {
                Object value = field(activity, modal);
                require(!(value instanceof Dialog) || !((Dialog) value).isShowing(), "Existing " + modal + " must be left undisturbed");
            }
            require(field(activity, "parsed") == null && field(activity, "exportedUri") == null
                            && ((View) field(activity, "pathEditor")).getVisibility() != View.VISIBLE,
                    "An existing document review or result must be left undisturbed");
            for (String name : new String[]{"pdfUri", "jsonUri", "exportedUri", "pdfName", "jsonName", "exportedName", "pdfHash", "jsonHash", "parsed", "info", "diagnostics",
                    "outcome", "review", "enjoy", "readyVerified", "busy", "systemPickerRequest", "pendingPathRequest",
                    "pendingPath", "catalogRetry", "sourceNeedsReload"}) fields.put(name, field(activity, name));
            captureViews(activity.findViewById(android.R.id.content), (View) field(activity, "utilityPanel"), views);
            focus = activity.getCurrentFocus(); ScrollView scroll = (ScrollView) field(activity, "mainScroll");
            scrollX = scroll.getScrollX(); scrollY = scroll.getScrollY(); captured = true;
            SharedPreferences main = activity.getPreferences(Context.MODE_PRIVATE); preferences.put(main, copy(main));
            for (String name : new String[]{"ui_appearance", "review_ui", "enjoy-directory"}) {
                SharedPreferences prefs = activity.getSharedPreferences(name, Context.MODE_PRIVATE); preferences.put(prefs, copy(prefs));
            }
            updatePreferences = activity.getSharedPreferences(AppUpdates.PREFS, Context.MODE_PRIVATE);
            hadAutomaticUpdate = updatePreferences.contains("automatic"); automaticUpdate = updatePreferences.getBoolean("automatic", true);
            Context memoryContext = new ContextWrapper(activity) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    if (!"enjoy-directory".equals(name)) throw new AssertionError("Unexpected synthetic preference access: " + name);
                    return memory;
                }
            };
            set(activity, "enjoy", new EnjoyDirectorySync(memoryContext)); set(activity, "outcome", outcome);
            syntheticReview = new ReviewWorkspace(activity, UiAppearance.load(activity),
                    () -> { throw new AssertionError("Synthetic targets must not be applied"); },
                    (page, title) -> { throw new AssertionError("Workflow smoke must not request a PDF page"); });
            syntheticReview.setDocument(parsed, 5); set(activity, "review", syntheticReview);
            set(activity, "sourceNeedsReload", false); set(activity, "catalogRetry", null); set(activity, "exportedName", "");
        }

        void document() throws Exception {
            set(activity, "pdfUri", pdf); set(activity, "jsonUri", json); set(activity, "exportedUri", null);
            set(activity, "exportedName", ""); set(activity, "diagnostics", null);
            set(activity, "sourceNeedsReload", false); set(activity, "catalogRetry", null);
            set(activity, "pdfName", "input.pdf"); set(activity, "jsonName", "input.json"); set(activity, "parsed", parsed);
            Constructor<PdfBookmarks.Info> constructor = PdfBookmarks.Info.class.getDeclaredConstructor(int.class, List.class);
            constructor.setAccessible(true); set(activity, "info", constructor.newInstance(5, parsed.rows));
            invoke(activity, "updateButtons");
        }

        void close() throws Exception {
            if (!captured) return;
            AtomicReference<Throwable> restoration = new AtomicReference<>();
            for (String name : new String[]{"appearanceDialog", "workflowDialog"}) {
                attemptRestore(restoration, () -> { Dialog owned = (Dialog) field(activity, name); if (owned != null && owned.isShowing()) owned.dismiss(); });
            }
            attemptRestore(restoration, () -> { if (syntheticReview != null) { set(syntheticReview, "windowConfigured", false); syntheticReview.clearDocument(); } });
            for (Map.Entry<String, Object> value : fields.entrySet())
                attemptRestore(restoration, () -> set(activity, value.getKey(), value.getValue()));
            attemptRestore(restoration, () -> invoke(activity, "renderOutcome"));
            attemptRestore(restoration, () -> invoke(activity, "updateButtons"));
            for (ViewState view : views) attemptRestore(restoration, view::restoreChoice);
            for (ViewState view : views) attemptRestore(restoration, view::restoreDisplay);
            attemptRestore(restoration, () -> {
                ((ScrollView) field(activity, "mainScroll")).smoothScrollTo(scrollX, scrollY);
                if (focus != null && focus.isAttachedToWindow()) focus.requestFocus();
                for (Map.Entry<String, Object> value : fields.entrySet())
                    require(Objects.equals(value.getValue(), field(activity, value.getKey())), "Original transient field was not restored: " + value.getKey());
            });
            rethrow(restoration.get());
        }

        void assertPreferencesUnchanged() {
            for (Map.Entry<SharedPreferences, Map<String, ?>> value : preferences.entrySet())
                require(value.getValue().equals(copy(value.getKey())), "Synthetic workflow changed existing preferences");
            if (updatePreferences != null) require(updatePreferences.contains("automatic") == hadAutomaticUpdate
                            && updatePreferences.getBoolean("automatic", true) == automaticUpdate,
                    "Menu navigation changed automatic-update settings");
        }
    }

    private static final class ViewState {
        final View view; final int visibility; final boolean enabled, checked; final int selection; final String text;
        ViewState(View view) {
            this.view = view; visibility = view.getVisibility(); enabled = view.isEnabled();
            checked = view instanceof CompoundButton && ((CompoundButton) view).isChecked();
            selection = view instanceof RadioGroup ? ((RadioGroup) view).getCheckedRadioButtonId() : -1;
            text = view instanceof TextView ? ((TextView) view).getText().toString() : null;
        }
        void restoreChoice() {
            if (view instanceof CompoundButton) ((CompoundButton) view).setChecked(checked);
            if (view instanceof RadioGroup) ((RadioGroup) view).check(selection);
        }
        void restoreDisplay() {
            if (text != null && !text.contentEquals(((TextView) view).getText())) ((TextView) view).setText(text);
            view.setVisibility(visibility); view.setEnabled(enabled);
        }
    }

    /** Read-only in-memory journal marker: any attempted write fails before reaching real storage. */
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, String> values = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { return fallback; }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return fallback; }
        @Override public Editor edit() { throw new AssertionError("Navigation must not write a recovery record"); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }

    private static Button action(Activity activity, String label) throws Exception {
        Object value = field(activity, "resultActions"); require(value instanceof List, "Missing actual result action list");
        List<?> actions = (List<?>) value; require(actions.size() == 1, "Expected exactly one applicable result continuation: " + label);
        require(actions.get(0) instanceof Button && label.contentEquals(((Button) actions.get(0)).getText()),
                "Wrong result continuation for the failed stage: " + label);
        return (Button) actions.get(0);
    }

    private static String text(Activity activity, String field) throws Exception { return ((TextView) field(activity, field)).getText().toString(); }
    private static void captureViews(View view, View updatePanel, List<ViewState> states) {
        states.add(new ViewState(view));
        // Its automatic checkbox and asynchronous update notice are not touched by this smoke.
        if (view == updatePanel) return;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) captureViews(group.getChildAt(i), updatePanel, states); }
    }
    private static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { Button result = button(group.getChildAt(i), label); if (result != null) return result; } }
        return null;
    }
    private static boolean contains(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), value)) return true; }
        return false;
    }
    private static int visibleButtons(View view, String label) {
        int count = view instanceof Button && view.isShown() && label.contentEquals(((Button) view).getText()) ? 1 : 0;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) count += visibleButtons(group.getChildAt(i), label); }
        return count;
    }
    private static void awaitText(Instrumentation instrumentation, Activity activity, String label) throws Exception { waitForNode(instrumentation, activity, label, false); }
    private static void tap(Instrumentation instrumentation, Activity activity, String label) throws Exception { waitForNode(instrumentation, activity, label, true); }
    private static void waitForNode(Instrumentation instrumentation, Activity activity, String label, boolean click) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    if (root.getPackageName() != null && activity.getPackageName().contentEquals(root.getPackageName())) {
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
                        try {
                            for (AccessibilityNodeInfo node : nodes) if (node.getText() != null && label.contentEquals(node.getText()) && node.isVisibleToUser()) {
                                if (!click) return;
                                AccessibilityNodeInfo candidate = AccessibilityNodeInfo.obtain(node);
                                try {
                                    for (int i = 0; candidate != null && i < 4; i++) {
                                        if (candidate.isEnabled() && candidate.isClickable() && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                                        AccessibilityNodeInfo parent = candidate.getParent(); candidate.recycle(); candidate = parent;
                                    }
                                } finally { if (candidate != null) candidate.recycle(); }
                            }
                        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(35);
        }
        throw new AssertionError("Actual app UI did not expose " + (click ? "clickable " : "visible ") + label);
    }
    private static void dismissOwnedMenu(Instrumentation instrumentation, Activity activity) throws Exception {
        awaitText(instrumentation, activity, "版本与更新"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    }
    private static Map<String, ?> copy(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> value : preferences.getAll().entrySet())
            result.put(value.getKey(), value.getValue() instanceof Set ? new HashSet<>((Set<?>) value.getValue()) : value.getValue());
        return result;
    }
    private static Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static Object invoke(Object target, String name) throws Exception { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { rethrow(error.getCause()); throw new AssertionError(error); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface MainAction { void run() throws Exception; }
    private static void attemptRestore(AtomicReference<Throwable> failure, MainAction action) {
        try { action.run(); } catch (Throwable error) { if (failure.get() == null) failure.set(error); else failure.get().addSuppressed(error); }
    }
    private static void onMain(Instrumentation instrumentation, MainAction action) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { thrown.set(error); } });
        rethrow(thrown.get());
    }
    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        if (error != null) throw new RuntimeException(error);
    }
}
