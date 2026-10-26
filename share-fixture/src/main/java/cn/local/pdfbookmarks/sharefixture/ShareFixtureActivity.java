package cn.local.pdfbookmarks.sharefixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;

/** Explicit test controller. It can grant only its own fixed fixtures to the isolated test host. */
public final class ShareFixtureActivity extends Activity {
    private static final String TARGET = "cn.local.pdfbookmarks.testhost";
    private Intent statusResponse;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        dispatch(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatch(intent);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (statusResponse != null) {
            sendBroadcast(statusResponse, "cn.local.pdfbookmarks.sharefixture.CONTROL");
            statusResponse = null;
        }
    }

    private void dispatch(Intent request) {
        try {
            String run = value(request, "run", "run"), name = value(request, "file", "readonly.json");
            String operation = value(request, "operation", "share");
            Log.i("ShareFixture", "control operation=" + operation + " run=" + run + " file=" + name);
            boolean documents = request.getBooleanExtra("document", false);
            Uri document = document(run, name, documents);
            if ("revoke".equals(operation)) {
                revokeUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Log.i("ShareFixture", "revoked " + document); finish(); return;
            }
            if ("status".equals(operation)) {
                String stats = FixtureProvider.stats(this, run, name);
                Log.i("ShareFixture", stats);
                statusResponse = new Intent("cn.local.pdfbookmarks.sharefixture.STATUS").setPackage(TARGET)
                        .putExtras(FixtureProvider.status(this, run, name))
                        .putExtra("token", value(request, "token", ""));
                setResult(RESULT_OK, new Intent().putExtra("stats", stats)); finish(); return;
            }
            if ("mutate".equals(operation)) {
                FixtureProvider.mutate(this, run, name);
                Log.i("ShareFixture", "mutated " + document); finish(); return;
            }
            if (!"share".equals(operation)) throw new IllegalArgumentException("Unknown fixture operation");
            FixtureProvider.prepare(this, run, name);
            String mode = value(request, "mode", "SEND"), clip = value(request, "clip", "both");
            Intent incoming;
            if ("VIEW".equals(mode)) incoming = new Intent(Intent.ACTION_VIEW).setDataAndType(document, getContentResolver().getType(document));
            else if ("SEND_MULTIPLE".equals(mode)) incoming = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*");
            else if ("SEND".equals(mode)) incoming = new Intent(Intent.ACTION_SEND).setType(getContentResolver().getType(document));
            else throw new IllegalArgumentException("Unknown share mode");
            incoming.setComponent(new ComponentName(TARGET, "cn.local.pdfbookmarks.MainActivity"));
            incoming.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (request.getBooleanExtra("read", true)) incoming.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (request.getBooleanExtra("write", false)) incoming.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            ArrayList<Uri> shared = new ArrayList<>(); shared.add(document);
            if ("SEND_MULTIPLE".equals(mode)) {
                String[] extra = request.getStringArrayExtra("files");
                if (extra == null) extra = new String[]{"other.json"};
                for (String other : extra) {
                    Uri uri = document(run, other, documents); FixtureProvider.prepare(this, run, other);
                    if (!shared.contains(uri)) shared.add(uri);
                }
            }
            if (!"VIEW".equals(mode) && !"clip".equals(clip)) {
                if (Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) incoming.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shared);
                else incoming.putExtra(Intent.EXTRA_STREAM, document);
            }
            if (!"extra".equals(clip)) {
                ArrayList<Uri> clipped = new ArrayList<>(shared);
                if ("mismatch".equals(clip)) {
                    FixtureProvider.prepare(this, run, "other.json"); clipped.clear(); clipped.add(document(run, "other.json", documents));
                } else if (!"clip".equals(clip) && !"both".equals(clip)) {
                    throw new IllegalArgumentException("Unknown ClipData mode");
                }
                ClipData data = ClipData.newUri(getContentResolver(), "Synthetic share", clipped.get(0));
                for (int index = 1; index < clipped.size(); index++) data.addItem(new ClipData.Item(clipped.get(index)));
                incoming.setClipData(data);
            }
            if (request.getBooleanExtra("grantPrimary", false)) {
                // Test-only ambiguity: both the Extra URI and the different ClipData URI are readable.
                grantUriPermission(TARGET, document, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            Log.i("ShareFixture", "share mode=" + mode + " clip=" + clip + " flags=" + incoming.getFlags() + " " + FixtureProvider.stats(this, run, name));
            startActivity(incoming);
            finish();
        } catch (Exception failure) {
            Log.e("ShareFixture", "Fixture controller failed", failure);
            TextView notice = new TextView(this); notice.setText("Synthetic share fixture failed: " + failure.getMessage()); setContentView(notice);
            setResult(RESULT_CANCELED);
        }
    }

    private static String value(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key); return value == null ? fallback : value;
    }
    private static Uri document(String run, String name, boolean documents) {
        return documents ? FixtureDocumentsProvider.document(run, name) : FixtureProvider.document(run, name);
    }
}
