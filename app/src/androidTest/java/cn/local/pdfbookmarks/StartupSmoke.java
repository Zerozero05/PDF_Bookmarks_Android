package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only smoke runner; it never selects or processes a PDF, JSON or note. */
public final class StartupSmoke extends Instrumentation {
    private static final long RECREATE_TIMEOUT_MS = 10000;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Activity current = null;
        Throwable failure = null;
        int appearanceChecks = 0;
        boolean recreated = false;
        String workflowResult = "NOT_RUN";
        String windowResult = "NOT_RUN";
        String grantResult = "NOT_RUN";
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .setClass(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            current = startActivitySync(launch);
            waitForIdleSync();
            assertReady(current);
            checkSystemBars(current);
            appearanceChecks += 3;

            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            try {
                Activity original = current;
                onMain(original::recreate);
                Activity replacement = waitForMonitorWithTimeout(monitor, RECREATE_TIMEOUT_MS);
                if (replacement == null || replacement == original) {
                    throw new AssertionError("MainActivity recreation did not complete within 10 seconds");
                }
                current = replacement;
            } finally {
                removeMonitor(monitor);
            }
            waitForIdleSync();
            assertReady(current);
            checkSystemBars(current);
            appearanceChecks += 3;
            recreated = true;
            windowResult = WindowSmoke.verify(this, current);
            workflowResult = WorkflowSmoke.verify(this, current);
            grantResult = GrantPickerSmoke.verify(this, current);
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (current != null) {
                Activity opened = current;
                try {
                    onMain(() -> { if (!opened.isFinishing()) opened.finish(); });
                    waitForIdleSync();
                } catch (Throwable closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        }

        Bundle result = new Bundle();
        result.putString("startupSmoke", failure == null ? "PASS" : "FAIL");
        result.putInt("sdk", Build.VERSION.SDK_INT);
        result.putString("model", Build.MODEL);
        result.putInt("systemBarChecks", appearanceChecks);
        result.putBoolean("activityRecreated", recreated);
        result.putString("workflowSmoke", workflowResult);
        result.putString("windowSmoke", windowResult);
        result.putString("grantPickerSmoke", grantResult);
        result.putString("scope", "Activity startup, system bars, recreation, synthetic review/draft UI and read-only grant picker gestures; no document operation or grant revocation");
        if (failure == null) {
            result.putString("stream", "\nPASS: MainActivity startup and recreation; 6 system-bar checks; appearance preferences unchanged.\n");
        } else {
            String trace = Log.getStackTraceString(failure);
            result.putString("failure", trace);
            result.putString("stream", "\nFAIL: StartupSmoke\n" + trace + "\n");
        }
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void assertReady(Activity activity) throws Throwable {
        onMain(() -> {
            if (!(activity instanceof MainActivity) || activity.isFinishing() || activity.isDestroyed()) {
                throw new AssertionError("MainActivity is not active");
            }
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0
                    || !containsTitle(content)) {
                throw new AssertionError("MainActivity has no completed PDF tool content");
            }
        });
    }

    private static boolean containsTitle(View view) {
        if (view instanceof TextView && "PDF目录工具".contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsTitle(group.getChildAt(i))) return true;
        }
        return false;
    }

    private void checkSystemBars(Activity activity) throws Throwable {
        Context target = getTargetContext();
        Map<String, ?> before = new HashMap<>(target.getSharedPreferences("ui_appearance", Context.MODE_PRIVATE).getAll());
        boolean systemDark = (target.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        try {
            for (int mode : new int[]{UiAppearance.SYSTEM, UiAppearance.LIGHT, UiAppearance.DARK}) {
                onMain(() -> UiAppearance.of(mode, UiAppearance.STANDARD, UiAppearance.COMFORTABLE, systemDark)
                        .applySystemBars(activity));
                // Includes the helper's posted View/WindowInsetsController work on a real main queue.
                waitForIdleSync();
                assertReady(activity);
            }
        } finally {
            onMain(() -> UiAppearance.load(target).applySystemBars(activity));
            waitForIdleSync();
        }
        Map<String, ?> after = target.getSharedPreferences("ui_appearance", Context.MODE_PRIVATE).getAll();
        if (!before.equals(after)) throw new AssertionError("System-bar checks changed appearance preferences");
    }

    @FunctionalInterface private interface MainAction { void run() throws Throwable; }

    private void onMain(MainAction action) throws Throwable {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { thrown.set(error); }
        });
        if (thrown.get() != null) throw thrown.get();
    }
}
