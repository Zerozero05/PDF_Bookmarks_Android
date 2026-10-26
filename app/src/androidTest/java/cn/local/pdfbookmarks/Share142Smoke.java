package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.view.accessibility.AccessibilityNodeInfo;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real cross-UID URI-grant regression; refuses to run in the user's production package. */
public final class Share142Smoke {
    private static final String HOST = "cn.local.pdfbookmarks.testhost";
    private static final String SOURCE = "cn.local.pdfbookmarks.sharefixture";
    private static final String AUTHORITY = SOURCE + ".files";
    private static final String DOCUMENTS = SOURCE + ".documents";
    private static final long TIMEOUT = 20000;
    private final Instrumentation instrumentation;
    private final Activity activity;
    private int assertions;

    private Share142Smoke(Instrumentation instrumentation, Activity activity) {
        this.instrumentation = instrumentation; this.activity = activity;
    }

    public static String verify(Instrumentation instrumentation, Activity activity) throws Exception {
        return new Share142Smoke(instrumentation, activity).run();
    }

    /** Two distinct instrumentation invocations, with a real process stop between them. */
    public static String restartPhase(Instrumentation instrumentation, Activity activity, String phase) throws Exception {
        Share142Smoke checks = new Share142Smoke(instrumentation, activity);
        checks.require(HOST.equals(activity.getPackageName()), "Restart142 may run only in the isolated testhost");
        if ("prepare".equals(phase)) return checks.prepareRestart();
        if ("verify".equals(phase)) return checks.verifyRestart();
        throw new IllegalArgumentException("Unknown restart142 phase");
    }

    /** Main generation/save integration, isolated from all real documents and Enjoy resources. */
    public static String pipeline(Instrumentation instrumentation, Activity activity) throws Exception {
        Share142Smoke checks = new Share142Smoke(instrumentation, activity);
        checks.require(HOST.equals(activity.getPackageName()), "Pipeline142 may run only in the isolated testhost");
        String first = checks.pipelineCase(false), second = checks.pipelineCase(true);
        return "PASS: two actual Main generation/save cases; picker cancellation retains verified output and JSON; readonly revoked source keeps source/pending cleanup and removes copy; writable document source removes both; originals unchanged; " + first + " | " + second;
    }

    private String pipelineCase(boolean deletable) throws Exception {
        onMain(() -> invoke(activity, "invalidatePlan", new Class<?>[0]));
        String name = deletable ? "deletable.json" : "readonly.json";
        JsonInputRef input = accept(fresh(), name, "SEND", "both", deletable, deletable);
        File directory = new File(activity.getCacheDir(), "perf142/mainflow142");
        require(directory.isDirectory() || directory.mkdirs(), "Could not create the isolated pipeline directory");
        File pdf = new File(directory, fresh() + "-input.pdf"); require(pdf.createNewFile(), "Synthetic Main input already exists");
        PdfDocument synthetic = new PdfDocument();
        try (FileOutputStream stream = new FileOutputStream(pdf)) {
            Paint paint = new Paint(); paint.setTextSize(18);
            for (int page = 1; page <= 5; page++) {
                PdfDocument.Page sheet = synthetic.startPage(new PdfDocument.PageInfo.Builder(420, 594, page).create());
                sheet.getCanvas().drawText("Main pipeline fixture page " + page, 32, 64, paint); synthetic.finishPage(sheet);
            }
            synthetic.writeTo(stream); stream.getFD().sync();
        } finally { synthetic.close(); }
        String originalPdfHash = fileHash(pdf); Uri pdfUri = Uri.fromFile(pdf), source = Uri.parse(input.sourceUri);
        onMain(() -> { invoke(activity, "selectInput", new Class<?>[]{int.class, Uri.class}, 1, pdfUri); invoke(activity, "loadPreview", new Class<?>[0]); });
        await(() -> field(activity, "parsed") != null && field(activity, "readJob") == null && !(Boolean) field(activity, "busy"), "Main pipeline did not read the synthetic PDF and imported JSON");
        require(((TocParser.Parsed) field(activity, "parsed")).rows.size() == 3, "Main preview did not contain all imported rows");
        require(((CheckBox) field(activity, "replace")).isChecked() && ((RadioButton) field(activity, "replaceCatalog")).isChecked()
                        && ((CheckBox) field(activity, "deleteJson")).isChecked(), "New task did not default to replacing both directories and cleaning JSON");
        require(!EnjoyDirectorySync.recognizes(pdfUri), "Synthetic pipeline must never select an Enjoy resource");
        if (!deletable) revokeAndCheckWorkingInput(input);

        IntentFilter filter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT); filter.addCategory(Intent.CATEGORY_OPENABLE); filter.addDataType("application/pdf");
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(filter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            onMain(() -> {
                ((CheckBox) field(activity, "overwrite")).setChecked(false);
                ((CheckBox) field(activity, "confirmed")).setChecked(true);
                invoke(activity, "confirmWrite", new Class<?>[0]);
            });
            clickGenerateConfirmation();
            await(() -> (Boolean) field(activity, "readyVerified") && !(Boolean) field(activity, "busy")
                            && (Integer) field(activity, "systemPickerRequest") == 0 && monitor.getHits() == 1,
                    "Main generation or canceled save picker did not settle");
        } finally { instrumentation.removeMonitor(monitor); }
        TaskOutcome outcome = (TaskOutcome) field(activity, "outcome");
        require(outcome.overall() == TaskOutcome.Overall.WAITING_ACTION, "Canceling the save picker was reported as a completed task");
        require(statusOf(outcome, TaskOutcome.Part.PDF) == TaskOutcome.Status.SUCCESS
                        && statusOf(outcome, TaskOutcome.Part.SAVE) == TaskOutcome.Status.AWAITING_USER,
                "Canceled save lost the verified generation result or continuation state");
        require(input.equals(shared()) && input.sha256.equals(hash(store().read(input))), "Canceling save removed the selected JSON or its working copy");
        require(sourceStatus(source).getBoolean("exists", false), "Canceling save deleted the source JSON");
        require(originalPdfHash.equals(fileHash(pdf)), "Generation changed the original synthetic PDF");

        File output = new File(directory, fresh() + "-saved.pdf"); require(!output.exists(), "Synthetic save destination already exists");
        onMain(() -> invoke(activity, "exportTo", new Class<?>[]{Uri.class}, Uri.fromFile(output)));
        await(() -> !(Boolean) field(activity, "busy") && field(activity, "exportedUri") != null
                        && Uri.fromFile(output).equals(field(activity, "exportedUri")), "Main continuation save did not finish");
        require(outcome.overall() == TaskOutcome.Overall.COMPLETE && statusOf(outcome, TaskOutcome.Part.PDF) == TaskOutcome.Status.SUCCESS
                        && statusOf(outcome, TaskOutcome.Part.SAVE) == TaskOutcome.Status.SUCCESS,
                "Actual generation/save did not complete successfully");
        require(statusOf(outcome, TaskOutcome.Part.JSON) == (deletable ? TaskOutcome.Status.SUCCESS : TaskOutcome.Status.RETAINED),
                "JSON outcome confused source cleanup with the PDF task result");
        require(output.isFile() && originalPdfHash.equals(fileHash(pdf)), "Save continuation changed the original or did not produce the output");
        String savedHash = fileHash(output);
        require(savedHash.equals(((PdfBookmarks.VerifiedOutput) field(activity, "verifiedOutput")).sha256), "Independent saved SHA differs from the verified generated artifact");
        assertSavedOutline(output);
        require(shared() == null && field(activity, "jsonUri") == null && !activity.getPreferences(0).contains("selectedJsonId")
                        && !activity.getPreferences(0).contains("json") && !activity.getPreferences(0).contains("selectedJsonHash"),
                "Completed JSON cleanup retained an active source or working-input selection");
        require(store().findById(input.id) == null && !new File(activity.getFilesDir(), "imports/json/" + input.id + ".json").exists(),
                "Successful save did not remove its exact working copy and record");
        Bundle sourceState = sourceStatus(source);
        require(sourceState.getBoolean("exists", false) != deletable, "Source JSON existence does not match its reported cleanup capability");
        require(sourceState.getInt("delete", -1) == (deletable ? 1 : 0), "Main cleanup issued an unexpected source deletion count");
        @SuppressWarnings("unchecked") List<Object> pending = (List<Object>) invoke(activity, "jsonCleanups", new Class<?>[0]);
        boolean found = false;
        for (Object task : pending) if (input.sourceUri.equals(field(task, "sourceUri")) && input.id.equals(field(task, "workingId"))) {
            found = true; require((Boolean) field(task, "copyDeleted") && !(Boolean) field(task, "sourceDeleted"), "Pending source cleanup retained an incorrect partial state");
        }
        require(found != deletable, "Main did not retain only the optional unremovable source cleanup record");
        require((Boolean) invoke(activity, "canInstallUpdate", new Class<?>[0]), "Optional JSON cleanup blocks a new idle operation");
        require(!activity.getPreferences(0).contains("pendingPdf") && !((EnjoyDirectorySync) field(activity, "enjoy")).hasPending(),
                "Synthetic save unexpectedly created PDF/Enjoy recovery work");
        return (deletable ? "doc" : "readonly") + " output=" + output.getAbsolutePath() + " sha256=" + savedHash;
    }

    private void clickGenerateConfirmation() throws Exception {
        await(() -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            try {
                if (!HOST.contentEquals(root.getPackageName())) return false;
                boolean title = false;
                List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByText("确认生成");
                for (AccessibilityNodeInfo node : titles) {
                    if (node.isVisibleToUser() && "确认生成".contentEquals(node.getText())) title = true;
                    node.recycle();
                }
                if (!title) return false;
                List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText("生成");
                boolean clicked = false;
                for (AccessibilityNodeInfo node : buttons) {
                    if (!clicked && node.isVisibleToUser() && node.isEnabled() && "生成".contentEquals(node.getText()))
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle();
                }
                return clicked;
            } finally { root.recycle(); }
        }, "Expected generation confirmation could not be clicked");
    }

    private void assertSavedOutline(File output) throws Exception {
        try (PDDocument pdf = PDDocument.load(output)) {
            require(pdf.getNumberOfPages() == 5, "Saved PDF page count changed");
            require(pdf.getDocumentCatalog().getDocumentOutline() != null, "Saved PDF has no actual outline");
            PDOutlineItem chapter = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild();
            require(chapter != null && "Fixture chapter".equals(chapter.getTitle()), "Saved root title changed");
            PDOutlineItem section = chapter.getFirstChild(), end = chapter.getNextSibling();
            require(section != null && "Fixture section".equals(section.getTitle()) && section.getNextSibling() == null && section.getFirstChild() == null,
                    "Saved child hierarchy differs from the imported JSON");
            require(end != null && "Fixture end".equals(end.getTitle()) && end.getFirstChild() == null && end.getNextSibling() == null,
                    "Saved root hierarchy contains missing or extra bookmarks");
            require(pdf.getPages().indexOf(chapter.findDestinationPage(pdf)) == 0
                            && pdf.getPages().indexOf(section.findDestinationPage(pdf)) == 1
                            && pdf.getPages().indexOf(end.findDestinationPage(pdf)) == 2,
                    "Saved bookmark destinations do not point to physical pages 1, 2, 3");
        }
    }

    private static TaskOutcome.Status statusOf(TaskOutcome outcome, TaskOutcome.Part part) {
        for (TaskOutcome.Entry entry : outcome.entries()) if (entry.part == part) return entry.status;
        throw new AssertionError("Missing task outcome part " + part);
    }

    private static String fileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536];
        try (InputStream input = new FileInputStream(file)) { for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count); }
        StringBuilder text = new StringBuilder(); for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return text.toString();
    }

    private String prepareRestart() throws Exception {
        require(field(activity, "parsed") == null && !(Boolean) field(activity, "busy"), "Restart fixture requires a fresh isolated review");
        JsonInputRef imported = accept(fresh(), "readonly.json", "SEND", "both", false);
        File directory = new File(activity.getCacheDir(), "perf142/restart142");
        require(directory.isDirectory() || directory.mkdirs(), "Could not create isolated restart fixture directory");
        File pdf = new File(directory, fresh() + ".pdf"); require(pdf.createNewFile(), "Synthetic restart PDF already exists");
        PdfDocument document = new PdfDocument();
        try (FileOutputStream output = new FileOutputStream(pdf)) {
            Paint paint = new Paint(); paint.setTextSize(18);
            for (int page = 1; page <= 5; page++) {
                PdfDocument.Page sheet = document.startPage(new PdfDocument.PageInfo.Builder(420, 594, page).create());
                sheet.getCanvas().drawText("Restart fixture page " + page, 32, 64, paint); document.finishPage(sheet);
            }
            document.writeTo(output); output.getFD().sync();
        } finally { document.close(); }
        Uri pdfUri = Uri.fromFile(pdf);
        onMain(() -> { invoke(activity, "selectInput", new Class<?>[]{int.class, Uri.class}, 1, pdfUri); invoke(activity, "loadPreview", new Class<?>[0]); });
        await(() -> field(activity, "parsed") != null && field(activity, "readJob") == null && !(Boolean) field(activity, "busy"), "Synthetic restart preview did not finish");
        require(((TocParser.Parsed) field(activity, "parsed")).rows.size() == 3, "Restart fixture did not load all three rows");
        onMain(() -> invoke(activity, "scheduleDraft", new Class<?>[]{boolean.class}, true));
        await(() -> {
            DraftStore.LoadResult result = new DraftStore(activity.getFilesDir()).load();
            return result.draft != null && imported.sourceUri.equals(result.draft.jsonUri) && imported.sha256.equals(result.draft.jsonSha256);
        }, "Restart fixture draft was not saved");
        revokeAndCheckWorkingInput(imported);
        require(activity.getSharedPreferences("restart142", 0).edit().putString("id", imported.id)
                        .putString("source", imported.sourceUri).putString("sha256", imported.sha256).putString("pdf", pdfUri.toString())
                        .putInt("pid", android.os.Process.myPid()).commit(), "Restart expectations were not recorded");
        require(!((CheckBox) field(activity, "confirmed")).isChecked(), "Restart prepare must not mark the directory as confirmed");
        return "PASS: synthetic 5-page PDF and 3-row JSON reviewed; draft saved; source permission revoked; durable input recorded for a subsequent process";
    }

    private String verifyRestart() throws Exception {
        SharedPreferences expected = activity.getSharedPreferences("restart142", 0);
        String id = expected.getString("id", null), source = expected.getString("source", null), sha = expected.getString("sha256", null);
        require(id != null && source != null && sha != null, "Restart prepare record is absent");
        require(expected.getInt("pid", -1) != android.os.Process.myPid(), "Verify ran in the prepare process; a real force-stop/restart is required");
        Uri sourceUri = Uri.parse(source);
        require(activity.checkUriPermission(sourceUri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                != PackageManager.PERMISSION_GRANTED, "A temporary source grant unexpectedly survived the process restart");
        require(shared() != null && id.equals(shared().id) && source.equals(field(activity, "jsonUri").toString()), "Cold launch did not restore the selected working JSON and original identity");
        require(sha.equals(hash(store().read(shared()))), "Cold-restored working JSON failed its hash");
        await(() -> field(activity, "savedDraft") != null, "Cold launch did not load its saved review draft");
        onMain(() -> invoke(activity, "continueSavedDraft", new Class<?>[0]));
        AlertDialog confirmation = awaitDialog();
        require("读取并恢复".contentEquals(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).getText()), "Saved draft did not show the explicit restore confirmation");
        onMain(() -> confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(() -> field(activity, "parsed") != null && field(activity, "readJob") == null && !(Boolean) field(activity, "busy"), "Cold draft restore did not finish");
        require(((TocParser.Parsed) field(activity, "parsed")).rows.size() == 3, "Cold draft restore lost directory rows");
        require(source.equals(field(activity, "jsonUri").toString()) && id.equals(shared().id), "Draft restore substituted another source or input copy");
        require(sha.equals(hash((byte[]) invoke(activity, "readJsonInput", new Class<?>[]{Uri.class}, sourceUri))), "Cold draft restore still depends on a source grant");
        require(!((CheckBox) field(activity, "confirmed")).isChecked(), "Draft restore incorrectly restored write confirmation");
        require(expected.getString("pdf", "").equals(field(activity, "pdfUri").toString()), "Cold draft restore selected a different synthetic PDF");
        return "PASS: distinct process PID; revoked source remains unreadable; selected working input and v1 draft restored all 3 rows; confirmation remains unchecked";
    }

    private String run() throws Exception {
        require(HOST.equals(activity.getPackageName()), "Share142 may run only in the isolated testhost");
        ProviderInfo provider = activity.getPackageManager().resolveContentProvider(AUTHORITY, 0);
        require(provider != null && !provider.exported && provider.grantUriPermissions, "Source provider must be non-exported and URI-grant enabled");
        require(provider.applicationInfo.uid != android.os.Process.myUid(), "Source provider must have an independent UID");
        ProviderInfo documents = activity.getPackageManager().resolveContentProvider(DOCUMENTS, 0);
        require(documents != null && documents.exported && documents.grantUriPermissions
                        && "android.permission.MANAGE_DOCUMENTS".equals(documents.readPermission)
                        && "android.permission.MANAGE_DOCUMENTS".equals(documents.writePermission),
                "Document fixture must retain the standard system permission protection");
        require(activity.checkSelfPermission("android.permission.MANAGE_DOCUMENTS") != PackageManager.PERMISSION_GRANTED,
                "Testhost must not hold the document-management system permission");
        require(activity.getPackageManager().checkPermission(SOURCE + ".CONTROL", HOST) == PackageManager.PERMISSION_GRANTED,
                "Install the same-signed source fixture and isolated host before running this test");
        require(!(Boolean) field(activity, "busy") && !(Boolean) field(activity, "shareLoading")
                        && field(activity, "readJob") == null && field(activity, "workflowDialog") == null,
                "Isolated activity must be idle");
        require(field(activity, "parsed") == null, "Run sharing checks before installing an isolated PDF review");

        JsonInputRef sent = accept(fresh(), "readonly.json", "SEND", "both", false);
        require(sent.sourceUri.equals(field(activity, "jsonUri").toString()), "JSON source identity must not become the working file path");
        require(sent.id.equals(activity.getPreferences(0).getString("selectedJsonId", null)), "Selected working ID was not saved");
        require(sourceCount(Uri.parse(sent.sourceUri), "fixture_open_count") == 1, "Duplicate Extra and ClipData URI was read more than once");
        revokeAndCheckWorkingInput(sent);
        accept(fresh(), "readonly.json", "VIEW", "both", false);
        accept(fresh(), "readonly.json", "SEND", "clip", false);
        accept(fresh(), "readonly.json", "SEND", "mismatch", false);

        reject(fresh(), "readonly.json", "both", false);
        reject(fresh(), "readonly.json", "mismatch", true, true);
        reject(fresh(), "malformed.json", "both", true);
        reject(fresh(), "oversize.json", "both", true);
        reject(fresh(), "broken.json", "both", true);
        verifyDocumentWithoutGrant();
        verifyBusyRejected();
        verifyConfirmationCancel();
        verifyReadCancel();
        verifyConfirmedInstallCancel();
        verifyCleanup();

        return "PASS: independent non-exported source UID; granted SEND/VIEW/ClipData import; revoked source with durable working input; no-grant/mismatch/malformed/oversize/broken rejection; MANAGE_DOCUMENTS-protected document grant checks; busy and cancellation; exact-document/copy cleanup, unknown-provider refusal and changed-source protection; "
                + assertions + " assertions. Metadata rehydration is covered; process cold restart is a separate check.";
    }

    private JsonInputRef accept(String run, String name, String mode, String clip, boolean write) throws Exception {
        return accept(run, name, mode, clip, write, false);
    }
    private JsonInputRef accept(String run, String name, String mode, String clip, boolean write, boolean document) throws Exception {
        JsonInputRef before = shared();
        source(run, name, "share", mode, clip, true, write, document);
        AlertDialog confirmation = awaitDialog();
        require(isImport(confirmation), "Granted source did not reach import confirmation: " + status());
        if ("mismatch".equals(clip)) {
            TextView message = confirmation.findViewById(android.R.id.message);
            require(message != null && message.getText().toString().contains("other.json"),
                    "Confirmation did not show the actually readable ClipData JSON name");
        }
        onMain(() -> confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(() -> !(Boolean) field(activity, "shareLoading") && shared() != null
                && (before == null || !before.id.equals(shared().id)), "Confirmed share did not install its JSON input");
        JsonInputRef installed = shared();
        String selectedName = "mismatch".equals(clip) ? "other.json" : name;
        require((document ? document(run, selectedName) : uri(run, selectedName)).toString().equals(installed.sourceUri), "Installed a different source URI");
        require(selectedName.equals(installed.displayName), "Input name does not describe the selected readable URI");
        byte[] working = store().read(installed);
        require(installed.sha256.equals(hash(working)), "Stored input hash does not match its record");
        require(TocParser.parse(new String(working, StandardCharsets.UTF_8), 3).rows.size() == 3, "Stored fixture tree changed");
        require(installed.sourceUri.equals(activity.getPreferences(0).getString("json", null)), "Persisted source URI was replaced by a private path");
        return installed;
    }

    private void verifyDocumentWithoutGrant() throws Exception {
        Choice before = new Choice(); String run = fresh(); Uri document = document(run, "readonly.json");
        source(run, "readonly.json", "share", "SEND", "both", false, false, true);
        AlertDialog error = awaitDialog();
        require(!isImport(error), "Document source without an exact grant bypassed MANAGE_DOCUMENTS");
        boolean denied = false;
        try (InputStream ignored = activity.getContentResolver().openInputStream(document)) { }
        catch (SecurityException expected) { denied = true; }
        require(denied, "Document fixture was readable without system permission or URI grant");
        before.verify(); onMain(() -> error.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()); instrumentation.waitForIdleSync(); before.verify();
    }

    private void reject(String run, String name, String clip, boolean read) throws Exception {
        reject(run, name, clip, read, false);
    }
    private void reject(String run, String name, String clip, boolean read, boolean grantPrimary) throws Exception {
        Choice before = new Choice();
        source(run, name, "share", "SEND", clip, read, false, false, grantPrimary);
        AlertDialog error = awaitDialog();
        require(!isImport(error), "Invalid or ungranted source was advertised as ready to import");
        if ("broken.json".equals(name)) {
            TextView message = error.findViewById(android.R.id.message);
            String prompt = message == null ? "" : message.getText().toString();
            require(prompt.contains("重新分享") && prompt.contains("Download"),
                    "Missing share source has no useful re-share/download recovery");
            require(!prompt.contains("No content provider") && !prompt.contains("Permission Denial"),
                    "Missing share source exposes the raw provider/permission failure in its primary prompt");
        }
        if (grantPrimary) {
            require(sourceCount(uri(run, name), "fixture_open_count") == 1
                            && sourceCount(uri(run, "other.json"), "fixture_open_count") == 1,
                    "Ambiguity rejection did not exercise two genuinely readable source URIs");
        }
        before.verify();
        onMain(() -> error.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        instrumentation.waitForIdleSync();
        require(field(activity, "workflowDialog") == null, "Rejected import could not return to the program");
        before.verify();
    }

    private void revokeAndCheckWorkingInput(JsonInputRef imported) throws Exception {
        Uri source = Uri.parse(imported.sourceUri);
        String[] parts = path(source);
        source(parts[0], parts[1], "revoke", "SEND", "both", false, false);
        await(() -> activity.checkUriPermission(source, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        != PackageManager.PERMISSION_GRANTED, "Source URI grant was not revoked");
        boolean rejected = false;
        try (InputStream ignored = activity.getContentResolver().openInputStream(source)) { }
        catch (SecurityException expected) { rejected = true; }
        require(rejected, "Non-exported provider remained readable after revocation");
        byte[] bytes = (byte[]) invoke(activity, "readJsonInput", new Class<?>[]{Uri.class}, source);
        require(imported.sha256.equals(hash(bytes)), "Reading selected JSON still depends on revoked source permission");
        JsonInputRef reopened = new JsonInputStore(activity.getFilesDir()).findById(imported.id);
        require(imported.equals(reopened), "Working input metadata did not survive reopening the store");
        onMain(() -> { set(activity, "sharedJson", null); invoke(activity, "restoreJsonInput", new Class<?>[]{String.class}, imported.id); });
        require(imported.equals(shared()), "Activity input rehydration did not find its saved working ID");
        require(imported.sha256.equals(hash((byte[]) invoke(activity, "readJsonInput", new Class<?>[]{Uri.class}, source))),
                "Rehydrated input incorrectly opened the revoked provider");
    }

    private void verifyBusyRejected() throws Exception {
        Choice before = new Choice();
        onMain(() -> { set(activity, "busy", true); invoke(activity, "updateButtons", new Class<?>[0]); });
        try {
            source(fresh(), "readonly.json", "share", "SEND", "both", true, false);
            await(() -> status().contains("请先完成或取消"), "Busy import did not stop at the current-task guard");
            require(field(activity, "workflowDialog") == null && !(Boolean) field(activity, "shareLoading"), "Busy share started another import");
            before.verify();
        } finally { onMain(() -> { set(activity, "busy", false); invoke(activity, "updateButtons", new Class<?>[0]); }); }
    }

    private void verifyConfirmationCancel() throws Exception {
        Choice before = new Choice();
        source(fresh(), "readonly.json", "share", "SEND", "both", true, false);
        AlertDialog confirmation = awaitDialog(); require(isImport(confirmation), "Cancel case did not receive a valid import confirmation");
        onMain(() -> confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        instrumentation.waitForIdleSync(); before.verify();
        require(field(activity, "workflowDialog") == null, "Import confirmation cancellation left a modal open");
    }

    private void verifyReadCancel() throws Exception {
        Choice before = new Choice(); String run = fresh();
        source(run, "slow.json", "share", "SEND", "both", true, false);
        await(() -> (Boolean) field(activity, "shareLoading"), "Slow fixture did not enter share reading");
        onMain(() -> invoke(activity, "cancelShare", new Class<?>[0]));
        require(!(Boolean) field(activity, "shareLoading"), "Share reading did not cancel immediately");
        long until = SystemClock.uptimeMillis() + 2300;
        while (SystemClock.uptimeMillis() < until) { SystemClock.sleep(40); instrumentation.waitForIdleSync(); before.verify(); }
        require(field(activity, "workflowDialog") == null, "Canceled background read installed a late confirmation");
        require(sourceCount(uri(run, "slow.json"), "fixture_open_count") <= 1, "Canceled source was reopened");
    }

    private void verifyConfirmedInstallCancel() throws Exception {
        Choice before = new Choice();
        source(fresh(), "readonly.json", "share", "SEND", "both", true, false);
        AlertDialog confirmation = awaitDialog(); require(isImport(confirmation), "Install cancellation did not reach confirmation");
        Field lockField = JsonInputStore.class.getDeclaredField("LOCK"); lockField.setAccessible(true); Object inputLock = lockField.get(null);
        synchronized (inputLock) {
            onMain(() -> confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            instrumentation.waitForIdleSync();
            require((Boolean) field(activity, "shareLoading"), "Confirmation did not actually start input installation before cancellation");
            await(() -> ((java.util.concurrent.ThreadPoolExecutor) field(activity, "readers")).getActiveCount() > 0,
                    "Input installer did not reach its blocked store operation");
            onMain(() -> invoke(activity, "cancelShare", new Class<?>[0]));
        }
        await(() -> ((java.util.concurrent.ThreadPoolExecutor) field(activity, "readers")).getActiveCount() == 0,
                "Canceled input installation did not finish its bounded I/O");
        instrumentation.waitForIdleSync();
        ((java.util.concurrent.ExecutorService) field(activity, "worker")).submit(() -> { }).get(TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS);
        instrumentation.waitForIdleSync(); before.verify();
        require(!(Boolean) field(activity, "shareLoading") && field(activity, "workflowDialog") == null,
                "Canceled installation left an active share or confirmation");
    }

    private void verifyCleanup() throws Exception {
        // Give the host access to a separate same-name and same-content source, then cancel its import.
        String guardRun = fresh(); Uri guard = document(guardRun, "deletable.json");
        source(guardRun, "deletable.json", "share", "SEND", "both", true, true, true);
        AlertDialog guardDialog = awaitDialog(); require(isImport(guardDialog), "Guard fixture could not be prepared");
        onMain(() -> guardDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()); instrumentation.waitForIdleSync();

        JsonInputRef deletable = accept(fresh(), "deletable.json", "SEND", "both", true, true);
        require(DocumentsContract.isDocumentUri(activity, Uri.parse(deletable.sourceUri)), "Positive deletion did not exercise the platform document route");
        Object deleted = cleanup(deletable);
        require((Boolean) field(deleted, "sourceDeleted") && (Boolean) field(deleted, "copyDeleted"), "Writable source and its working copy were not both deleted");
        require(!sourceStatus(Uri.parse(deletable.sourceUri)).getBoolean("exists", true), "Source still exists after claimed deletion");
        require(store().findById(deletable.id) == null, "Working input metadata remains after claimed cleanup");

        JsonInputRef readOnly = accept(fresh(), "readonly.json", "SEND", "both", false);
        Object kept = cleanup(readOnly);
        require(!(Boolean) field(kept, "sourceDeleted") && (Boolean) field(kept, "copyDeleted"), "Read-only source cleanup falsely claimed both files were deleted");
        require(sourceCount(Uri.parse(readOnly.sourceUri), "fixture_exists") == 1
                        && sourceCount(Uri.parse(readOnly.sourceUri), "fixture_delete_count") == 0,
                "Read-only source was deleted or a deletion was attempted");
        require(store().findById(readOnly.id) == null, "Read-only source prevented cleaning the owned copy");
        require(((String) field(kept, "reason")).contains("源JSON未删除"), "Read-only source did not leave a clear pending reason");

        JsonInputRef unknown = accept(fresh(), "deletable.json", "SEND", "both", true);
        Object unknownKept = cleanup(unknown);
        require(!(Boolean) field(unknownKept, "sourceDeleted") && (Boolean) field(unknownKept, "copyDeleted"),
                "Unknown generic provider was accepted as a safe single-document deletion route");
        require(sourceCount(Uri.parse(unknown.sourceUri), "fixture_exists") == 1
                        && sourceCount(Uri.parse(unknown.sourceUri), "fixture_delete_count") == 0,
                "Cleanup used a generic provider delete operation");

        JsonInputRef changed = accept(fresh(), "deletable.json", "SEND", "both", true, true);
        String[] location = path(Uri.parse(changed.sourceUri));
        source(location[0], location[1], "mutate", "SEND", "both", false, false, true);
        await(() -> !changed.sha256.equals(hash(readSource(Uri.parse(changed.sourceUri)))), "Synthetic source did not change after import");
        byte[] changedBytes = readSource(Uri.parse(changed.sourceUri)); Object stopped = cleanup(changed);
        require(!(Boolean) field(stopped, "sourceDeleted") && (Boolean) field(stopped, "copyDeleted"), "Changed source protection did not preserve the source and clean the owned copy");
        require(((String) field(stopped, "reason")).contains("内容已变化"), "Changed-source rejection lost its reason");
        require(java.util.Arrays.equals(changedBytes, readSource(Uri.parse(changed.sourceUri)))
                        && sourceCount(Uri.parse(changed.sourceUri), "fixture_delete_count") == 0,
                "Changed source was altered or deleted by cleanup");
        require(store().findById(changed.id) == null, "Changed source prevented cleaning the owned copy");
        require(sourceCount(guard, "fixture_exists") == 1 && sourceCount(guard, "fixture_delete_count") == 0,
                "Cleanup touched a different same-name source");
    }

    private Object cleanup(JsonInputRef ref) throws Exception {
        Class<?> type = Class.forName(MainActivity.class.getName() + "$JsonCleanup");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); Object task = constructor.newInstance();
        set(task, "taskId", UUID.randomUUID().toString()); set(task, "sourceUri", ref.sourceUri); set(task, "name", ref.displayName);
        set(task, "hash", ref.sha256); set(task, "workingId", ref.id); set(task, "copyDeleted", false); set(task, "sourceDeleted", false);
        @SuppressWarnings("unchecked") List<Object> pending = (List<Object>) invoke(activity, "jsonCleanups", new Class<?>[0]);
        pending.add(task); invoke(activity, "saveJsonCleanups", new Class<?>[]{List.class}, pending);
        invoke(activity, "performJsonCleanup", new Class<?>[]{type, List.class}, task, pending);
        require(!new File(activity.getFilesDir(), "imports/json/" + ref.id + ".json").exists(), "Cleanup left the claimed-deleted working byte file");
        return task;
    }

    private void source(String run, String name, String operation, String mode, String clip, boolean read, boolean write) throws Exception {
        source(run, name, operation, mode, clip, read, write, false);
    }
    private void source(String run, String name, String operation, String mode, String clip, boolean read, boolean write, boolean document) throws Exception {
        source(run, name, operation, mode, clip, read, write, document, false);
    }
    private void source(String run, String name, String operation, String mode, String clip, boolean read, boolean write, boolean document, boolean grantPrimary) throws Exception {
        awaitHostFocus();
        Intent control = new Intent().setComponent(new ComponentName(SOURCE, SOURCE + ".ShareFixtureActivity"))
                .putExtra("operation", operation).putExtra("run", run).putExtra("file", name).putExtra("mode", mode)
                .putExtra("clip", clip).putExtra("read", read).putExtra("write", write).putExtra("document", document)
                .putExtra("grantPrimary", grantPrimary).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        onMain(() -> activity.startActivity(control));
    }
    private AlertDialog awaitDialog() throws Exception {
        await(() -> field(activity, "workflowDialog") instanceof AlertDialog && ((AlertDialog) field(activity, "workflowDialog")).isShowing(), "Source share produced no tracked dialog: " + status());
        return (AlertDialog) field(activity, "workflowDialog");
    }
    private boolean isImport(AlertDialog dialog) { Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE); return positive != null && "使用这些文件".contentEquals(positive.getText()); }
    private JsonInputRef shared() throws Exception { return (JsonInputRef) field(activity, "sharedJson"); }
    private JsonInputStore store() throws Exception { return (JsonInputStore) field(activity, "jsonInputs"); }
    private String status() throws Exception { return ((TextView) field(activity, "status")).getText().toString(); }
    private int sourceCount(Uri source, String column) throws Exception {
        try (Cursor cursor = activity.getContentResolver().query(source, new String[]{column}, null, null, null)) {
            require(cursor != null && cursor.moveToFirst(), "Source event query did not return a row"); return cursor.getInt(0);
        }
    }
    private Bundle sourceStatus(Uri uri) throws Exception {
        awaitHostFocus();
        String[] selected = path(uri); String token = fresh(); AtomicReference<Bundle> status = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (token.equals(intent.getStringExtra("token")) && selected[0].equals(intent.getStringExtra("run"))
                        && selected[1].equals(intent.getStringExtra("file"))) status.set(new Bundle(intent.getExtras()));
            }
        };
        IntentFilter filter = new IntentFilter(SOURCE + ".STATUS");
        int exportFlags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0;
        activity.registerReceiver(receiver, filter, SOURCE + ".CONTROL", null, exportFlags);
        try {
            Intent request = new Intent().setComponent(new ComponentName(SOURCE, SOURCE + ".ShareFixtureActivity"))
                    .putExtra("operation", "status").putExtra("run", selected[0]).putExtra("file", selected[1])
                    .putExtra("token", token).putExtra("document", DOCUMENTS.equals(uri.getAuthority())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            onMain(() -> activity.startActivity(request)); await(() -> status.get() != null, "Source owner did not return a protected status response");
            // The owner sends this response from onDestroy. Also require the actual host window
            // return before another NEW_TASK control rather than assuming a broadcast restored it.
            awaitHostFocus();
            return status.get();
        } finally { activity.unregisterReceiver(receiver); }
    }
    private void awaitHostFocus() throws Exception {
        await(() -> {
            AtomicReference<Boolean> focused = new AtomicReference<>(false);
            onMain(() -> focused.set(!activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()));
            return focused.get();
        }, "Isolated host did not regain its window before the next source operation");
    }
    private byte[] readSource(Uri source) throws Exception {
        try (InputStream input = activity.getContentResolver().openInputStream(source); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            require(input != null, "Source returned no stream"); byte[] bytes = new byte[8192];
            for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count); return output.toByteArray();
        }
    }
    private List<String> inputFiles() {
        String[] files = new File(activity.getFilesDir(), "imports/json").list();
        List<String> names = files == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(java.util.Arrays.asList(files));
        java.util.Collections.sort(names); return names;
    }
    private static Uri uri(String run, String name) { return Uri.parse("content://" + AUTHORITY + "/files/" + run + "/" + name); }
    private static Uri document(String run, String name) { return DocumentsContract.buildDocumentUri(DOCUMENTS, run + "/" + name); }
    private static String[] path(Uri uri) {
        if (DOCUMENTS.equals(uri.getAuthority())) return DocumentsContract.getDocumentId(uri).split("/", -1);
        List<String> parts = uri.getPathSegments(); return new String[]{parts.get(1), parts.get(2)};
    }
    private static String fresh() { return UUID.randomUUID().toString(); }
    private static String hash(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder value = new StringBuilder();
        for (byte item : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString();
    }

    private final class Choice {
        final Object json, working; final List<String> files;
        final String jsonPref, selectedPref;
        Choice() throws Exception {
            json = field(activity, "jsonUri"); working = field(activity, "sharedJson"); files = inputFiles();
            jsonPref = activity.getPreferences(0).getString("json", null); selectedPref = activity.getPreferences(0).getString("selectedJsonId", null);
        }
        void verify() throws Exception {
            require(java.util.Objects.equals(json, field(activity, "jsonUri")) && java.util.Objects.equals(working, field(activity, "sharedJson")), "Rejected/canceled share changed selected input");
            require(java.util.Objects.equals(jsonPref, activity.getPreferences(0).getString("json", null))
                            && java.util.Objects.equals(selectedPref, activity.getPreferences(0).getString("selectedJsonId", null)),
                    "Rejected/canceled share changed persisted selection");
            require(files.equals(inputFiles()), "Rejected/canceled share changed the working input files or left a partial file");
        }
    }
    private interface Checked { void run() throws Exception; }
    private interface Condition { boolean ready() throws Exception; }
    private void onMain(Checked action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>(); instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
    }
    private void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        do { instrumentation.waitForIdleSync(); if (condition.ready()) return; SystemClock.sleep(40); } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message + " / status=" + status());
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = (target instanceof MainActivity ? MainActivity.class : target.getClass()).getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = (target instanceof MainActivity ? MainActivity.class : target.getClass()).getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause(); if (cause instanceof Exception) throw (Exception) cause; if (cause instanceof Error) throw (Error) cause; throw wrapped;
        }
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
