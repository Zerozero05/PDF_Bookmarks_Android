package cn.local.pdfbookmarks;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** 文件选择只授权用户选定的文档；所有PDF操作在工作线程上完成。 */
public final class MainActivity extends Activity {
    private static final int PICK_PDF=1,PICK_JSON=2,SAVE_PDF=3,PICK_FOLDER=4,SAVE_BACKUP=5,RESTORE_FOLDER=6;

    // Activity销毁后回滚仍可能运行；新界面不能提前清除它的恢复记录。
    private static final Object replacementLock=new Object();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService readers=Executors.newCachedThreadPool();
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private Future<?> previewTask;
    private UiAppearance appearance;
    private ReviewWorkspace review;
    private ScrollView mainScroll;
    private LinearLayout shellView,resultRows;
    private TextView stageLabel,resultHeading;
    private AlertDialog appearanceDialog;
    private final TaskOutcome outcome=new TaskOutcome();
    private final ArrayList<Button> buttons=new ArrayList<>();
    private final ArrayList<Button> resultActions=new ArrayList<>();
    private Uri pdfUri,jsonUri,exportedUri;
    private String pdfName="",jsonName="",pdfHash="",jsonHash="",exportedName="";
    private File sourceFile,readyFile;
    private TocParser.Parsed parsed;
    private PdfBookmarks.Info info;
    private volatile boolean busy;
    private boolean readyVerified,deleteOnExport;
    private volatile int previewGeneration;
    private Bitmap shown;
    private TextView pdfLabel,jsonLabel,status,summary,outputSettings;
    private CheckBox replace,confirmed,overwrite,deleteJson;
    private Button generate,export,open,recover,exportBackup,restoreMissing,cancelRead,cleanup,moreToggle;
    private LinearLayout moreActions;
    private CheckBox syncEnjoy;
    private RadioGroup catalogMode;
    private RadioButton keepCatalog,replaceCatalog;
    private Button syncCatalog,recoverCatalog;
    private EnjoyDirectorySync enjoy;
    private AppUpdates updates;
    private volatile ReadJob readJob;
    private EditText pathInput;
    private LinearLayout pathEditor;
    private int pathRequest;
    private int pendingPathRequest;
    private String pendingPath="";
    private FilePickerDialog filePicker;
    private volatile int systemPickerRequest;
    private final Handler draftHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService draftWriter=Executors.newSingleThreadExecutor();
    private DraftStore draftStore;
    private JsonInputStore jsonInputs;
    private JsonInputRef sharedJson;
    private PdfBookmarks.VerifiedOutput verifiedOutput;
    private PreparedOutput preparedOutput;
    private WorkMetrics outputMetrics;
    private ReviewDraft savedDraft,restoreDraft;
    private Runnable draftSave;
    private volatile long draftRevision,storedDraftRevision=-1;
    private String draftError;
    private volatile boolean shareLoading;
    private boolean restoringDraft,draftCompleted,sourceNeedsReload;
    private int shareGeneration;
    private TextView draftNotice,filePair,changeSummary;
    private Button continueDraft,fileToggle,inspectDirectory,readAgain;
    private LinearLayout inputBody,optionsPanel,rootContent,inspectionRow;
    private View reviewCard,outputCard;
    private final TextView[] steps=new TextView[3];
    private TocDiagnostics.Report diagnostics;
    private AlertDialog workflowDialog;
    private LinearLayout utilityPanel,enjoyOptions,catalogChoices;
    private TextView syncHint,resultFile;
    private Runnable helpAction;
    private CatalogRetry catalogRetry;

    private static final class PreparedOutput {
        final Uri pdf,json;final String pdfHash,jsonHash;final boolean replaceAll;
        final List<TocParser.Row> rows;final EnjoyDirectorySync.Prepared catalog;
        PreparedOutput(Uri pdf,Uri json,String pdfHash,String jsonHash,boolean replaceAll,List<TocParser.Row> rows,EnjoyDirectorySync.Prepared catalog){
            this.pdf=pdf;this.json=json;this.pdfHash=pdfHash;this.jsonHash=jsonHash;this.replaceAll=replaceAll;this.rows=new ArrayList<>(rows);this.catalog=catalog;
        }
        boolean matches(Uri pdf,Uri json,String pdfHash,String jsonHash,boolean replaceAll,List<TocParser.Row> rows){
            return this.pdf.equals(pdf)&&this.json.equals(json)&&this.pdfHash.equals(pdfHash)&&this.jsonHash.equals(jsonHash)&&this.replaceAll==replaceAll&&sameRows(this.rows,rows);
        }
    }

    /** Optional source cleanup is independent of PDF recovery and never blocks new tasks. */
    private static final class JsonCleanup {
        String taskId,sourceUri,name,hash,workingId,reason;
        boolean sourceDeleted,copyDeleted;
    }

    /** Only the successful PDF revision may be used to continue its failed directory step. */
    private static final class CatalogRetry {
        final Uri pdf,json;final String pdfHash,jsonHash;final int pages;
        final List<TocParser.Row> rows;final boolean replaceAll;
        CatalogRetry(Uri pdf,String pdfHash,Uri json,String jsonHash,int pages,List<TocParser.Row> rows,boolean replaceAll){
            this.pdf=pdf;this.pdfHash=pdfHash;this.json=json;this.jsonHash=jsonHash;this.pages=pages;
            this.rows=Collections.unmodifiableList(new ArrayList<>(rows));this.replaceAll=replaceAll;
        }
    }


    /** 只记录一次已核验成功的替换，不能从旧版备份文件名猜测关联。 */
    private static final class BackupRecord {
        String folder,outputUri,outputName,outputHash,backupUri,backupName,originalHash,privateBackup;
        BackupRecord(StagedDocuments documents,StagedReplacement.Result result,File backup){
            folder=documents.folder.toString();outputUri=result.document.uri;outputName=result.document.name;
            outputHash=result.journal.outputHash;backupUri=result.backup.uri;backupName=result.backup.name;
            originalHash=result.journal.originalHash;privateBackup=backup.getAbsolutePath();
        }
    }

    /** 用户确认的整批清单持久保存，删除中断后不重新猜测文件归属。 */
    private static final class CleanupPlan {
        String pdfUri,pdfRecord,catalogRecord,retainedNotice;
    }

    static final class ReadJob {
        final CancellationSignal signal=new CancellationSignal();
        volatile boolean cancelled;
        private Closeable input;
        Future<?> future;

        JsonInputRef jsonRef;
        int inputRequest=PICK_JSON;
        synchronized void attach(Closeable stream)throws IOException {
            if(cancelled){stream.close();throw new InterruptedIOException("读取已取消。");}input=stream;
        }
        synchronized void detach(){input=null;}
        void check()throws IOException {if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("读取已取消。");}
        void closeInput(){try{signal.cancel();}finally{Closeable stream;synchronized(this){stream=input;input=null;}if(stream!=null)try{stream.close();}catch(IOException ignored){}}}
    }

    @Override public void onCreate(Bundle state){
        appearance=UiAppearance.applyBeforeCreate(this);super.onCreate(state);PDFBoxResourceLoader.init(getApplicationContext());enjoy=new EnjoyDirectorySync(this);
        draftStore=new DraftStore(getFilesDir());
        jsonInputs=new JsonInputStore(getFilesDir());
        updates=new AppUpdates(this,this::canInstallUpdate,b->styleButton(b,false));
        // 重建Activity时旧工作线程可能尚未结束；每次使用独立目录避免交叉读写。
        File session=new File(getCacheDir(),"job-"+UUID.randomUUID());session.mkdirs();
        sourceFile=new File(session,"source.pdf");readyFile=new File(session,"ready.pdf");
        buildUi();
        appearance.applySystemBars(this);
        if(state!=null)systemPickerRequest=state.getInt("systemPickerRequest",0);
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        String p=prefs.getString("pdf",null),j=prefs.getString("json",null);
        if(p!=null){pdfUri=Uri.parse(p);pdfName=prefs.getString("pdfName","PDF");pdfLabel.setText(pdfName);}
        if(j!=null){jsonUri=Uri.parse(j);jsonName=prefs.getString("jsonName","JSON");jsonLabel.setText(jsonName);restoreJsonInput(prefs.getString("selectedJsonId",null));}
        status.setText(inputGuidance());
        review.setOnStateChangedListener(()->scheduleDraft(false));
        updateButtons();checkRecovery();loadSavedDraft();
        if(state==null)receiveShare(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        // 从桌面图标回到已有任务时，只取消尚未完成的系统选择，不重建预览。
        if(systemPickerRequest!=0)cancelCurrentInput();
        receiveShare(intent);
    }
    private void loadSavedDraft(){
        draftWriter.execute(()->{DraftStore.LoadResult result=draftStore.load();onUi(()->{
            if(savedDraft==null)savedDraft=result.draft;
            draftError=result.error;
            updateDraftNotice();
        });});
    }
    private void updateDraftNotice(){
        if(continueDraft==null)return;
        continueDraft.setVisibility(savedDraft!=null&&!draftCompleted&&parsed==null?View.VISIBLE:View.GONE);
        if(draftError!=null){draftNotice.setText(draftError);draftNotice.setVisibility(View.VISIBLE);return;}
        if(savedDraft!=null&&!draftCompleted&&parsed==null){draftNotice.setText("已保存上次核对进度 · 继续前会重新核验文件");draftNotice.setVisibility(View.VISIBLE);}
        else if(parsed!=null)draftNotice.setVisibility(View.GONE);
    }
    private void continueSavedDraft(){
        if(savedDraft==null||!canInstallUpdate())return;
        ReviewDraft draft=savedDraft;
        showWorkflow(dialogBuilder().setTitle("继续上次核对")
            .setMessage("将重新读取上次的PDF和JSON，核验内容后恢复目录页码与浏览位置。已核对状态需要重新确认。\n\n若来源授权已失效，请重新选择原文件。")
            .setNegativeButton("取消",null).setPositiveButton("读取并恢复",(d,w)->{
                synchronized(replacementLock){selectInput(PICK_PDF,Uri.parse(draft.pdfUri));selectInput(PICK_JSON,Uri.parse(draft.jsonUri));}
                restoreDraft=draft;loadPreview();
            }).create());
    }
    private void scheduleDraft(boolean immediate){
        if(draftSave!=null){draftHandler.removeCallbacks(draftSave);draftSave=null;}
        if(restoringDraft||draftCompleted||parsed==null||info==null||pdfUri==null||jsonUri==null||review==null||pdfHash.isEmpty()||jsonHash.isEmpty()||(sourceNeedsReload&&catalogRetry==null))return;
        draftSave=()->{
            draftSave=null;
            final ReviewDraft snapshot;
            try{snapshot=captureReviewDraft();}
            catch(IllegalArgumentException e){draftError="核对草稿未保存："+message(e);updateDraftNotice();return;}
            long revision=++draftRevision;draftWriter.execute(()->{if(revision!=draftRevision)return;try{
                draftStore.save(snapshot);storedDraftRevision=revision;onUi(()->{if(revision==draftRevision){savedDraft=snapshot;draftError=null;updateDraftNotice();}});
            }catch(IOException e){onUi(()->{if(revision==draftRevision){draftError=message(e);updateDraftNotice();}});}});
        };
        if(immediate)draftSave.run();else draftHandler.postDelayed(draftSave,400);
    }
    private ReviewDraft captureReviewDraft(){
        ReviewWorkspace.State state=review.snapshotState();ArrayList<Integer> collapsed=new ArrayList<>();for(int index:state.collapsedSourceIndices)collapsed.add(index);
        CatalogRetry retry=sourceNeedsReload?catalogRetry:null;
        return ReviewDraft.capture(retry==null?pdfUri.toString():retry.pdf.toString(),jsonUri.toString(),retry==null?pdfHash:retry.pdfHash,jsonHash,parsed.rows,currentRows(),info.pageCount,state.query,collapsed,state.selectedSourceIndex,state.previewPage,state.previewTitle,Math.max(0,state.firstListPosition),state.listTopOffset);
    }
    private void completeDraft(){
        draftCompleted=true;savedDraft=null;restoreDraft=null;draftError=null;++draftRevision;
        if(draftSave!=null){draftHandler.removeCallbacks(draftSave);draftSave=null;}
        draftWriter.execute(()->{try{draftStore.clear();}catch(IOException e){onUi(()->{draftError="任务已完成，但核对草稿未清除："+message(e);updateDraftNotice();});}});
        updateDraftNotice();
    }
    private void restoreReviewIfRequested(){
        ReviewDraft candidate=restoreDraft;restoreDraft=null;
        if(candidate==null)return;
        if(!candidate.matches(pdfUri.toString(),jsonUri.toString(),pdfHash,jsonHash)){
            status.setText("文件内容或标识已变化，未应用旧草稿。当前显示新读入的目录，请重新核对。");return;
        }
        try{
            candidate.validateAgainst(parsed.rows,info.pageCount);
            int[] collapsed=new int[candidate.collapsedSourceIndices().size()];for(int i=0;i<collapsed.length;i++)collapsed[i]=candidate.collapsedSourceIndices().get(i);
            restoringDraft=true;review.restoreState(new ReviewWorkspace.State(candidate.query,collapsed,candidate.selectedSourceIndex,candidate.previewPage,candidate.previewTitle,candidate.listFirstPosition,candidate.listTopOffset,candidate.targetPages()));
            confirmed.setChecked(false);status.setText("已恢复核对草稿。请继续抽查页面，并重新确认目录。");
        }catch(IllegalArgumentException e){status.setText(getString(R.string.workflow_draft_mismatch,message(e)));}
        finally{restoringDraft=false;}
    }
    private void receiveShare(Intent intent){
        if(intent==null||(!Intent.ACTION_SEND.equals(intent.getAction())&&!Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())&&!Intent.ACTION_VIEW.equals(intent.getAction())))return;
        if(!canInstallUpdate()){status.setText("请先完成或取消当前操作，再重新分享文件；当前任务保留。");return;}
        final ArrayList<Uri> uris=new ArrayList<>();
        try{
            if(Intent.ACTION_VIEW.equals(intent.getAction())){if(intent.getData()!=null)uris.add(intent.getData());}
            else if(Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())){
                ArrayList<Uri> values=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(values!=null)uris.addAll(values);
            }else{Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)uris.add(uri);}
            if(!Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getClipData()!=null)for(int i=0;i<intent.getClipData().getItemCount();i++){
                Uri uri=intent.getClipData().getItemAt(i).getUri();if(uri!=null&&uris.stream().noneMatch(old->sameDocument(old,uri)))uris.add(uri);
            }
            for(int i=uris.size()-1;i>=0;i--){for(int k=0;k<i;k++)if(sameDocument(uris.get(k),uris.get(i))){uris.remove(i);break;}}
            if(uris.isEmpty()||uris.size()>4)throw new IllegalArgumentException("每次请分享一个PDF、一个JSON，或各一个文件。");
            for(Uri uri:uris)if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null)throw new IllegalArgumentException("只接受文件应用明确分享的PDF或JSON文档地址。");
        }catch(RuntimeException e){showInputFailure("分享文件未导入",e);return;}
        shareLoading=true;int generation=++shareGeneration;status.setText("正在识别分享文件，可随时取消返回。");updateButtons();
        readers.execute(()->{int inputRequest=PICK_JSON;try{
            ArrayList<SharedImport.Document> incoming=new ArrayList<>();Map<String,byte[]> jsonBytes=new HashMap<>();
            for(Uri uri:uris){
                String name=displayName(uri);if(!name.toLowerCase(Locale.ROOT).endsWith(".json")&&!name.toLowerCase(Locale.ROOT).endsWith(".pdf")){String tail=uri.getLastPathSegment();if(tail!=null)name=tail;}
                incoming.add(new SharedImport.Document(uri.toString(),name));
            }
            ArrayList<SharedImport.Document> readable=new ArrayList<>();
            // Stream extras can refer to a URI different from the one carrying the system grant.
            // Keep only the unique actually readable document of each requested kind, never guess by name.
            for(boolean pdf:new boolean[]{false,true}){
                boolean requested=false;int accepted=0;Exception unavailable=null;
                for(SharedImport.Document document:incoming){if(document.pdf!=pdf)continue;requested=true;inputRequest=pdf?PICK_PDF:PICK_JSON;Uri uri=Uri.parse(document.uri);
                    try{
                        if(pdf){try(InputStream input=readInput(uri,null)){if(input.read()<0)throw new IOException("分享的PDF为空。");}}
                        else{byte[] bytes=readSmall(uri);validateJsonBytes(bytes);jsonBytes.put(document.uri,bytes);}
                        readable.add(document);accepted++;
                    }catch(SecurityException|FileNotFoundException denied){unavailable=denied;}
                }
                if(requested&&accepted==0){if(unavailable!=null)throw unavailable;throw new IOException("来源没有提供可读取的文件。");}
                if(accepted>1)throw new IllegalArgumentException("来源提供了多个不同且可读取的"+(pdf?"PDF":"JSON")+"，无法确定目标；请只分享一份。");
            }
            List<SharedImport.Document> documents=SharedImport.validate(readable);
            if(Intent.ACTION_VIEW.equals(intent.getAction())&&documents.get(0).pdf)throw new IllegalArgumentException("“其他应用打开”入口仅接受 JSON 目录；PDF 请在程序内选择或使用分享。");
            onUi(()->{if(!shareLoading||generation!=shareGeneration)return;shareLoading=false;updateButtons();StringBuilder notice=new StringBuilder();
                for(SharedImport.Document document:documents)notice.append(document.pdf?"PDF：":"JSON：").append(document.name).append("\n");
                notice.append("\nJSON将导入应用专用输入区，退出后也可继续。确认后只替换文件选择，仍需读取和核对，不会自动生成或覆盖PDF。");
                showWorkflow(dialogBuilder().setTitle("导入分享文件").setMessage(notice.toString()).setNegativeButton("取消",(d,w)->status.setText("已取消分享导入，当前选择保留。"))
                    .setPositiveButton("使用这些文件",(d,w)->installSharedInputs(documents,jsonBytes,intent)).create());
            });
        }catch(Exception e){int failedRequest=inputRequest;onUi(()->{if(generation!=shareGeneration)return;shareLoading=false;updateButtons();showInputFailure("分享文件未导入",e,failedRequest);});}});
    }
    private void installSharedInputs(List<SharedImport.Document> documents,Map<String,byte[]> bytes,Intent intent){

        shareLoading=true;int generation=++shareGeneration;updateButtons();status.setText(R.string.import_saving_json);

        readers.execute(()->{try{

            JsonInputRef imported=null;for(SharedImport.Document document:documents)if(!document.pdf)imported=jsonInputs.importShared(document.uri,document.name,bytes.get(document.uri));

            JsonInputRef ready=imported;

            runOnUiThread(()->{

                if(!shareLoading||generation!=shareGeneration||isFinishing()||isDestroyed()){releaseUnusedImport(ready);return;}

                Map<String,String> changes=new HashMap<>();
                for(SharedImport.Document document:documents){if(document.pdf){changes.put("pdf",document.uri);changes.put("pdfName",document.name);}
                    else{changes.put("json",document.uri);changes.put("jsonName",document.name);changes.put("selectedJsonId",ready.id);changes.put("selectedJsonHash",ready.sha256);}}
                InputSelectionCommit.Result saved;synchronized(replacementLock){saved=InputSelectionCommit.commit(getPreferences(MODE_PRIVATE),changes);}
                if(saved!=InputSelectionCommit.Result.COMMITTED){
                    shareLoading=false;
                    if(saved==InputSelectionCommit.Result.RESTORED){releaseUnusedImport(ready);showInputFailure("分享文件未导入",new IOException("文件选择未保存，原选择保留。"));}
                    else{invalidatePlan();status.setText(R.string.import_selection_uncertain);showWorkflow(dialogBuilder().setTitle(R.string.import_selection_failed).setMessage(R.string.import_selection_uncertain).setNegativeButton("返回",null).create());}
                    updateButtons();return;
                }

                invalidatePlan();restoreDraft=null;deleteJson.setChecked(true);replace.setChecked(true);catalogMode.check(replaceCatalog.getId());

                for(SharedImport.Document document:documents){Uri uri=Uri.parse(document.uri);persist(uri,intent);

                    if(document.pdf){pdfUri=uri;pdfName=document.name;pdfLabel.setText(pdfName);}

                    else{jsonUri=uri;sharedJson=ready;jsonName=document.name;jsonLabel.setText(jsonName);}}

                shareLoading=false;setInputsExpanded(true);status.setText(ready==null?"PDF已选入；临时分享的PDF退出后可能需重新分享。":"JSON已导入应用内输入区，无需授权QQ文件夹。请补齐PDF并读取核对。");updateButtons();

            });

        }catch(Exception e){onUi(()->{if(generation!=shareGeneration)return;shareLoading=false;updateButtons();showInputFailure("分享文件未导入",e);});}});

    }

    private void releaseUnusedImport(JsonInputRef ref){

        if(ref==null)return;

        try{worker.execute(()->{try{jsonInputs.deleteOwned(ref);}catch(IOException e){android.util.Log.w("PdfBookmarks.Import","Canceled import remains in the private input area",e);}});}

        catch(RejectedExecutionException e){android.util.Log.w("PdfBookmarks.Import","Private input retained after activity shutdown");}

    }

    private void restoreJsonInput(String id){
        if(id==null)return;try{JsonInputRef ref=jsonInputs.findById(id);if(ref!=null&&jsonUri!=null&&ref.sourceUri.equals(jsonUri.toString()))sharedJson=ref;}
        catch(IOException|IllegalArgumentException e){status.setText(R.string.import_json_unverified);}
    }
    private void showInputFailure(String title,Exception failure){showInputFailure(title,failure,PICK_JSON);}
    private void showInputFailure(String title,Exception failure,int request){
        String detail=message(failure);boolean permission=failure instanceof SecurityException||detail.contains("Permission Denial")||detail.contains("not exported");boolean missing=failure instanceof FileNotFoundException;
        status.setText(permission?"分享读取授权已失效或来源没有授予读取权限；原选择保留。":missing?getString(R.string.import_source_missing):"文件导入未完成；原选择和草稿保留。");
        String notice=permission||missing?getString(R.string.import_source_recovery):detail;
        showWorkflow(dialogBuilder().setTitle(title).setMessage(notice).setNegativeButton("返回",null).setNeutralButton("查看详情",(d,w)->showTextDetails("导入详情",detail)).setPositiveButton(request==PICK_PDF?R.string.import_select_pdf:R.string.import_select_json,(d,w)->pick(request)).create());
    }
    private static String validateJsonBytes(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length==0||bytes.length>4*1024*1024)throw new IOException("目录JSON为空或超过4MB。");
        String raw;try{raw=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw new IOException("目录JSON不是有效UTF-8文本，请重新导出JSON。",e);}
        try(com.google.gson.stream.JsonReader reader=new com.google.gson.stream.JsonReader(new StringReader(raw))){
            reader.setStrictness(com.google.gson.Strictness.STRICT);com.google.gson.JsonElement value=com.google.gson.JsonParser.parseReader(reader);
            if((!value.isJsonObject()&&!value.isJsonArray())||reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IOException("目录JSON格式无效。");
        }catch(com.google.gson.JsonParseException|IllegalStateException e){throw new IOException("目录JSON格式无效："+e.getMessage(),e);}
        return raw;
    }
    private void cancelShare(){shareLoading=false;++shareGeneration;status.setText("已取消分享识别，当前文件和核对进度保留。");updateButtons();}
    private void showWorkflow(AlertDialog dialog){
        workflowDialog=dialog;dialog.setOnDismissListener(d->{if(workflowDialog==dialog)workflowDialog=null;updateButtons();});dialog.show();appearance.styleDialog(dialog);
    }
    private void showTextDetails(String title,String value){
        TextView content=text(value,15);content.setTextIsSelectable(true);content.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView scroll=new ScrollView(appearance.dialogContext(this));scroll.addView(content);
        showWorkflow(dialogBuilder().setTitle(title).setView(scroll).setPositiveButton("关闭",null).setNeutralButton("复制详情",(d,w)->copyText(title,value)).create());
    }
    private void copyText(String label,String value){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText(label,value));}
    private void setInputsExpanded(boolean expanded){
        if(inputBody==null)return;inputBody.setVisibility(expanded?View.VISIBLE:View.GONE);fileToggle.setText(expanded?"收起文件选择":"更换或重新读取文件");
    }
    private void focusSection(View section){if(section!=null&&mainScroll!=null)mainScroll.post(()->{int[] child=new int[2],parent=new int[2];section.getLocationOnScreen(child);mainScroll.getLocationOnScreen(parent);mainScroll.smoothScrollBy(0,child[1]-parent[1]-dp(12));});}
    private void focusResult(){setInputsExpanded(false);if(optionsPanel!=null){optionsPanel.setVisibility(View.GONE);((Button)optionsPanel.getTag()).setText("处理选项 ▾");}focusSection(outputCard);}
    private boolean canInstallUpdate(){
        return !busy&&!shareLoading&&!FilePickerDialog.isGrantChangeRunning()&&readJob==null&&systemPickerRequest==0
            &&(pathEditor==null||pathEditor.getVisibility()!=View.VISIBLE)&&(filePicker==null||!filePicker.isShowing())
            &&(review==null||!review.hasModalUi())&&(appearanceDialog==null||!appearanceDialog.isShowing())
            &&(workflowDialog==null||!workflowDialog.isShowing())
            &&!getPreferences(MODE_PRIVATE).contains("pendingPdf")&&!enjoy.hasPending()&&!hasCleanupTask();
    }
    @Override protected void onResume(){super.onResume();if(updates!=null)updates.onResume();}
    @Override protected void onPause(){if(review!=null)review.persistUi();scheduleDraft(true);if(updates!=null)updates.onPause();super.onPause();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);appearance.styleText(v,size);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(LinearLayout parent,String title){
        LinearLayout v=column();setSurface(v,"card",18,16,18,16);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(v,lp);
        TextView heading=text(title,19);heading.setTypeface(null,Typeface.BOLD);v.addView(heading);return v;
    }
    private Button button(LinearLayout parent,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(48));
        b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setStateListAnimator(null);styleButton(b,false);b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);
        parent.addView(b,lp);buttons.add(b);return b;
    }
    private void styleButton(Button button,boolean primary){
        button.setTag(R.id.ui_primary_action,primary);appearance.styleButton(button,primary);
    }
    private TextView inputTile(LinearLayout parent,String label,int request){
        LinearLayout tile=column();setSurface(tile,"tile",12,12,12,12);
        LinearLayout.LayoutParams tileLp=new LinearLayout.LayoutParams(-1,-2);tileLp.topMargin=dp(12);parent.addView(tile,tileLp);
        TextView heading=text(label,14);heading.setTypeface(null,Typeface.BOLD);tile.addView(heading);
        TextView value=text(request==PICK_PDF?"尚未选择 PDF":"尚未选择目录 JSON",15);value.setPadding(0,dp(6),0,dp(4));value.setTextIsSelectable(true);tile.addView(value);
        value.setOnLongClickListener(v->{Uri uri=request==PICK_PDF?pdfUri:jsonUri;if(uri==null)return false;showTextDetails("文件位置",inputLabel(request==PICK_PDF?pdfName:jsonName,uri)+"\n"+uri);return true;});
        LinearLayout actions=new LinearLayout(this);tile.addView(actions);
        Button browse=button(actions,"浏览文件",()->pick(request));Button paste=button(actions,"粘贴路径",()->inputPath(request));
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.topMargin=dp(6);left.rightMargin=dp(4);browse.setLayoutParams(left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.topMargin=dp(6);right.leftMargin=dp(4);paste.setLayoutParams(right);
        if(request==PICK_JSON){TextView help=text("找不到下载的 JSON？查看快捷导入方法",14);help.setPadding(0,dp(8),0,dp(8));help.setOnClickListener(v->showJsonImportHelp());tile.addView(help);}
        return value;
    }
    private LinearLayout disclosure(LinearLayout parent,String label){
        LinearLayout content=column();content.setVisibility(View.GONE);
        Button toggle=button(parent,label+" ▾",()->{});
        toggle.setOnClickListener(v->{boolean expand=content.getVisibility()!=View.VISIBLE;content.setVisibility(expand?View.VISIBLE:View.GONE);toggle.setText(label+(expand?" ▴":" ▾"));});
        content.setTag(toggle);parent.addView(content);return content;
    }
    private void showMoreActions(){moreActions.setVisibility(View.VISIBLE);moreToggle.setText("更多操作与恢复 ▴");}
    private void buildUi(){
        LinearLayout shell=column();shellView=shell;setSurface(shell,"page",0,0,0,0);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(20),dp(10),dp(20),dp(10));
        TextView toolbarTitle=text("PDF目录工具",23);toolbarTitle.setTypeface(null,Typeface.BOLD);toolbar.addView(toolbarTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button(toolbar,"更多",()->showAppMenu(toolbar));
        settings.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
        cancelRead=new Button(this);cancelRead.setText("取消 / 返回");cancelRead.setAllCaps(false);cancelRead.setTextSize(14);cancelRead.setVisibility(View.GONE);cancelRead.setOnClickListener(v->cancelCurrentInput());toolbar.addView(cancelRead);
        shell.addView(toolbar);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);progress.setTag("progress");shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        ScrollView scroll=new ScrollView(this);mainScroll=scroll;scroll.setFillViewport(true);setSurface(scroll,"page",0,0,0,0);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout root=column();rootContent=root;int side=contentSidePadding();setSurface(root,"page",side,18,side,22);scroll.addView(root);
        shell.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(shell);
        LinearLayout stepBar=new LinearLayout(this);stepBar.setPadding(0,0,0,dp(14));
        String[] stepNames={"1  文件","2  核对","3  结果"};
        for(int i=0;i<steps.length;i++){final int step=i;steps[i]=text(stepNames[i],14);steps[i].setGravity(Gravity.CENTER);steps[i].setPadding(dp(8),dp(10),dp(8),dp(10));steps[i].setMinHeight(dp(48));steps[i].setFocusable(true);steps[i].setOnClickListener(v->{if(busy||shareLoading||FilePickerDialog.isGrantChangeRunning())return;if(step==0){setInputsExpanded(true);focusSection(inputBody);}else if(step==1&&parsed!=null)focusSection(reviewCard);else if(step==2&&outputCard.getVisibility()==View.VISIBLE)focusSection(outputCard);else status.setText(inputGuidance());});LinearLayout.LayoutParams stepLp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)stepLp.leftMargin=dp(6);stepBar.addView(steps[i],stepLp);}root.addView(stepBar);
        status=text("",14);setSurface(status,"status",12,10,12,10);status.setTextIsSelectable(true);
        LinearLayout.LayoutParams statusLp=new LinearLayout.LayoutParams(-1,-2);statusLp.bottomMargin=dp(14);root.addView(status,statusLp);
        LinearLayout files=card(root,"1  选择文件");
        filePair=text("",14);filePair.setTextIsSelectable(true);files.addView(filePair);
        draftNotice=text("",13);files.addView(draftNotice);draftNotice.setVisibility(View.GONE);
        continueDraft=button(files,"继续上次核对",this::continueSavedDraft);continueDraft.setVisibility(View.GONE);
        inputBody=column();files.addView(inputBody);
        pdfLabel=inputTile(inputBody,"PDF 原文件",PICK_PDF);
        jsonLabel=inputTile(inputBody,"目录 JSON",PICK_JSON);
        pathEditor=column();pathEditor.setVisibility(View.GONE);inputBody.addView(pathEditor);
        pathEditor.addView(text("粘贴路径后点击使用；误触可点取消，或按系统返回。",14));
        pathInput=new EditText(this);pathInput.setHint("/享做笔记/…/文件.pdf");pathInput.setMaxLines(3);pathInput.setMinLines(2);pathEditor.addView(pathInput);
        button(pathEditor,"使用此路径",()->resolvePath(pathRequest,pathInput.getText().toString()));
        button(pathEditor,"授权此路径所在文件夹",()->{pendingPathRequest=pathRequest;pendingPath=pathInput.getText().toString();authorizeFolder(pendingPath);});
        button(pathEditor,"取消路径输入 / 返回",this::closePathEditor);
        LinearLayout permissions=disclosure(inputBody,"文件夹授权与说明");
        button(permissions,"授权路径所在文件夹",()->{pendingPathRequest=0;pendingPath="";authorizeFolder("/享做笔记/note/文件.pdf");});
        permissions.addView(text("支持享做复制的 /享做笔记/… 路径。首次授权所在文件夹，之后可浏览或直接粘贴；不需要全盘访问权限。兼容桌面JSON和Jasminum多级目录。",14));
        readAgain=button(inputBody,"重新读取目录",this::loadPreview);
        fileToggle=button(files,"收起文件选择",()->setInputsExpanded(inputBody.getVisibility()!=View.VISIBLE));
        LinearLayout preview=card(root,"2  核对目录与目标页");
        reviewCard=preview;
        summary=text("预览会显示实际PDF总页数、目录层级和目标页。",15);preview.addView(summary);
        LinearLayout checks=new LinearLayout(this);inspectionRow=checks;checks.setGravity(Gravity.CENTER_VERTICAL);changeSummary=text("读取后可查看导入检查与目录变化",13);changeSummary.setTextColor(appearance.secondaryText);checks.addView(changeSummary,new LinearLayout.LayoutParams(0,-2,1));inspectDirectory=button(checks,"检查与变化",this::showInspectionMenu);inspectDirectory.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));preview.addView(checks);
        review=new ReviewWorkspace(this,appearance,this::onRowsApplied,this::renderPage);
        preview.addView(review,new LinearLayout.LayoutParams(-1,-2));
        review.onWindowChanged(getResources().getConfiguration().screenWidthDp,getResources().getConfiguration().screenHeightDp);
        confirmed=new CheckBox(this);confirmed.setText("我已核对目录标题、层级和实际跳转页");confirmed.setOnCheckedChangeListener((b,c)->updateButtons());preview.addView(confirmed);
        replace=new CheckBox(this);replace.setText("PDF书签内容：替换已有的整棵书签目录");replace.setChecked(true);replace.setOnCheckedChangeListener((b,c)->{invalidateOutput();confirmed.setChecked(false);updateButtons();});preview.addView(replace);
        LinearLayout output=card(root,"3  生成并保存");
        outputCard=output;
        outputSettings=text("",15);output.addView(outputSettings);
        resultFile=text("",14);resultFile.setTextIsSelectable(true);resultFile.setMaxLines(3);resultFile.setEllipsize(android.text.TextUtils.TruncateAt.END);resultFile.setVisibility(View.GONE);output.addView(resultFile);

        open=button(output,"打开已保存的 PDF",this::openOutput);
        cleanup=button(output,"清理旧文件与备份",this::confirmCleanup);
        LinearLayout options=disclosure(output,"处理选项");optionsPanel=options;
        overwrite=new CheckBox(this);overwrite.setText("PDF保存方式：原路径同名替换");overwrite.setOnCheckedChangeListener((b,c)->updateButtons());options.addView(overwrite);
        overwrite.setChecked(true);
        options.addView(text("关闭后另存副本；原路径替换会先核验并保留PDF备份。",14));
        enjoyOptions=column();options.addView(enjoyOptions);
        syncEnjoy=new CheckBox(this);syncEnjoy.setText("同时同步享做目录");syncEnjoy.setChecked(true);syncEnjoy.setOnCheckedChangeListener((b,c)->updateButtons());enjoyOptions.addView(syncEnjoy);
        syncHint=text("",13);enjoyOptions.addView(syncHint);
        catalogChoices=column();enjoyOptions.addView(catalogChoices);
        catalogChoices.addView(text("享做目录处理",14));
        catalogMode=new RadioGroup(this);catalogMode.setOrientation(RadioGroup.VERTICAL);catalogMode.setSaveEnabled(false);catalogChoices.addView(catalogMode);
        keepCatalog=new RadioButton(this);keepCatalog.setId(View.generateViewId());keepCatalog.setSaveEnabled(false);keepCatalog.setText("保留已有目录并添加新目录");catalogMode.addView(keepCatalog);
        replaceCatalog=new RadioButton(this);replaceCatalog.setId(View.generateViewId());replaceCatalog.setSaveEnabled(false);replaceCatalog.setText("用新目录替换全部已有目录（不保留旧目录备份）");catalogMode.addView(replaceCatalog);
        catalogMode.check(replaceCatalog.getId());
        catalogMode.setOnCheckedChangeListener((group,id)->updateButtons());
        catalogChoices.addView(text("同步前保存并强行停止享做，不要清除数据。完成后重开核对目录、跳转与笔迹。",13));
        deleteJson=new CheckBox(this);deleteJson.setText(R.string.import_delete_json_option);deleteJson.setChecked(true);deleteJson.setOnCheckedChangeListener((b,c)->updateButtons());options.addView(deleteJson);
        moreActions=disclosure(output,"更多操作与恢复");moreToggle=(Button)moreActions.getTag();
        export=button(moreActions,"保存已生成的 PDF…",this::saveOutput);
        button(moreActions,"重新生成当前目录",this::confirmWrite);
        syncCatalog=button(moreActions,"仅同步当前PDF目录到享做",this::confirmSyncCatalog);
        button(moreActions,"JSON源文件清理情况",this::showJsonCleanup);
        moreActions.addView(text("仅同步使用当前PDF与JSON中的相同目录，不替换PDF。备份导出及恢复在清理前可用。清理只处理成功记录关联的旧件和内部恢复备份，不扫描其他文件。",14));
        exportBackup=button(moreActions,"导出最近一次原 PDF 备份",this::saveBackup);
        restoreMissing=button(moreActions,"从备份恢复消失的 PDF…",this::chooseRestoreFolder);
        recoverCatalog=button(output,"恢复上次目录同步前的享做目录",this::confirmRecoverCatalog);
        recover=button(output,"恢复上次替换前的原 PDF",this::confirmRecovery);
        resultHeading=text(getString(R.string.ui_result_title),17);resultHeading.setTypeface(null,Typeface.BOLD);output.addView(resultHeading);
        resultRows=column();output.addView(resultRows);renderOutcome();
        utilityPanel=column();utilityPanel.setVisibility(View.GONE);root.addView(utilityPanel);
        utilityPanel.addView(text(getString(R.string.update_panel,getString(R.string.app_version)),17));
        updates.attach(utilityPanel);
        button(utilityPanel,"收起版本与更新",()->utilityPanel.setVisibility(View.GONE));
        helpAction=()->dialogBuilder().setTitle("使用说明").setMessage(
            "选择PDF与JSON，或粘贴共享存储路径 → 读取并预览 → 滑动目录、点击标题或页码核对目标页 → 生成并保存。目录标题和目标页在独立两栏显示。底部主按钮随步骤变化；搜索与折叠只影响显示，生成始终使用完整目录。目录更多菜单可展开、折叠、定位或试算整体/分段页码；试算后需明确应用，再核对实际页面，原JSON不会改动。页面支持双指缩放、双击、拖动与全屏；拖动分隔条调整两栏比例。顶部“更多 → 外观”可调整主题、字号与间距，Ctrl+F可定位搜索。\n\n浏览文件默认使用应用内列表，右上角固定“取消选择”，不选文件也能返回；空目录和加载中均可取消。列表只浏览已授权位置，返回上级不会访问授权外的目录。在“已授权的位置”可左滑显示详情和红色“移除授权”，也可用位置菜单或长按进入选择，支持全选和批量移除；确认前会列出完整清单。此操作仅解除本工具的访问授权，不删除文件夹、PDF或笔记；以后可重新授权。子目录仍用于单文件选择。首次授权或“更多文件来源”仍使用系统界面；可用系统返回取消；若无法返回，回到系统主屏幕后点击本工具图标，可回到原界面并取消未完成的系统选择。顶部也保留“取消系统选择”入口。路径输入和读取也可在顶部取消，原选择及核对状态保留。\n\n第3节的“处理选项”可切换同名替换/另存、享做目录保留添加/全部替换以及成功后删除JSON。“更多操作与恢复”保留单独同步、保存已生成PDF、导出备份和恢复消失PDF；未完成任务的恢复入口直接显示。\n\n同名替换需要原文件夹的读写授权：先在该文件夹写好并核验新PDF，再将原件改名为备份，最后把新PDF改为原名。程序不截断或自动删除原PDF。文件来源不支持这些操作时，请另存。\n\n同步享做目录默认勾选，仅适配已识别的共享存储资源文件。请先保存笔记，并到系统设置强行停止享做，不要清除数据。默认用新目录替换全部已有享做目录，包括手动目录，也可选择保留已有目录并添加新目录；保留并添加模式保留原目录备份；全部替换模式核验成功后自动删除本次旧目录及内部目录恢复副本，不能再用它们恢复，失败时保留恢复材料。两种模式均保留PDF备份、原笔迹及其他配置。换输入文件或重开界面时恢复默认替换模式；未知结构、页面映射或文件变化时停止。PDF已含本次JSON的相同书签时，可在“更多操作与恢复”只同步目录，不必再生成PDF。无需无障碍服务。PDF处理离线完成；“版本与更新”默认每天检查一次本项目GitHub正式版，可关闭自动检查。确认下载并校验后可安装更新，安装仍需系统确认，应用数据保留。\n\n核对新PDF、享做目录、跳转和原笔迹正常后，保存并强行停止享做，可用一个“清理旧文件与备份”按钮。清单列出本次成功记录关联的外部旧PDF、旧目录及应用内恢复备份，确认后统一清理。清理后无法再靠这些备份恢复。保留当前PDF、活动catalog/temp、笔迹和其他配置；原始JSON只按独立勾选处理，不纳入统一清理。不会扫描旧版失去关联的备份、其他任务或未知文件。文件变化时停止；中断可按原清单重试。\n\n默认在PDF及所选享做同步均成功后删除本次JSON源文件及导入副本；只读来源无法删除时，清理导入副本后仍完成主任务，在结果中提示手动处理源文件。分享JSON确认后导入应用专用输入区，无需授权Android/data，重启仍可继续核对。中断保留未删除备份与进度；第3节目录恢复只恢复同步前目录，不改PDF和笔迹。清理前可导出备份；不要卸载程序或清除数据。\n\n加密或含签名字段的PDF会拒绝处理。桌面OCR、批量功能尚未移植。\n\n版本 "+getString(R.string.app_version)+" · GNU AGPL v3\nPDFBox Android / Gson: Apache 2.0\nBouncy Castle: MIT").setPositiveButton("知道了",null).show();
        LinearLayout footer=column();setSurface(footer,"card",16,6,16,10);
        stageLabel=text(getString(R.string.ui_stage_ready),13);stageLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);footer.addView(stageLabel);
        generate=button(footer,getString(R.string.ui_primary_read),this::primaryAction);styleButton(generate,true);
        shell.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        applyAppearanceToTree(shell);updates.applyAppearance(appearance);
    }
    private void pick(int request){
        if(busy||shareLoading||FilePickerDialog.isGrantChangeRunning()||(filePicker!=null&&filePicker.isShowing()))return;
        clearPendingPath();closePathEditor();
        filePicker=new FilePickerDialog(this,request==PICK_PDF,
            uri->selectInput(request,uri),
            ()->{status.setText("已取消文件选择，原来的文件和核对状态保留。");updateButtons();},
            ()->pickSystemFile(request),()->authorizeFolder(request==PICK_PDF?"/享做笔记/note/文件.pdf":"/Documents/目录.json"),
            this::canManageFolderGrants,this::onFolderGrantsChanged);
        filePicker.show();
    }
    private boolean canManageFolderGrants(){
        return !busy&&!shareLoading&&readJob==null&&systemPickerRequest==0
            &&!getPreferences(MODE_PRIVATE).contains("pendingPdf")&&!enjoy.hasPending()&&!hasCleanupTask();
    }
    private void onFolderGrantsChanged(){
        status.setText(FilePickerDialog.isGrantChangeRunning()?"正在更新授权，请稍候；当前文件与核对状态保留。"
            :"授权列表已更新；原来的文件选择、核对状态和草稿保留。若来源已失去访问权限，请重新授权所在文件夹。");
        updateButtons();
    }
    private void pickSystemFile(int request){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(request==PICK_PDF?"application/pdf":"*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if(request==PICK_JSON)intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
        clearPendingPath();closePathEditor();launchPicker(intent,request);
    }
    private void showJsonImportHelp(){
        if(!canInstallUpdate())return;
        showWorkflow(dialogBuilder().setTitle("快速导入 JSON").setMessage("推荐把 JSON 保存到内部存储的 Download（下载）文件夹，再点“下载 / 最近文件”。\n\n如果文件在 QQ 等应用的 Android/data 中，可在来源应用或华为文件管理器里选中这个 JSON，使用“分享”或“用其他应用打开”选择“PDF目录工具”；本工具收到后会先请你确认。来源应用必须提供可读取的文件授权。\n\n系统限制本工具浏览 Android/data 和 Android/obb，显示隐藏文件无法解除。若来源应用不支持分享，请先将 JSON 复制到 Download。临时分享授权失效时需重新分享，原 PDF 和笔记不会因此修改。")
            .setNegativeButton("返回",null).setPositiveButton("下载 / 最近文件",(d,w)->pickSystemFile(PICK_JSON)).create());
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String name=c.getString(0);if(name!=null&&!name.trim().isEmpty())return name;}}
        catch(Exception ignored){}return nameHint(uri);
    }
    private boolean persist(Uri uri,Intent data){
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{
            if(flags==(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else if(flags==Intent.FLAG_GRANT_READ_URI_PERMISSION)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else if(flags==Intent.FLAG_GRANT_WRITE_URI_PERMISSION)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }catch(SecurityException ignored){return false;}
        for(UriPermission grant:getContentResolver().getPersistedUriPermissions())if(uri.equals(grant.getUri())&&grant.isReadPermission()&&grant.isWritePermission())return true;
        return false;
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(updates!=null&&updates.onActivityResult(request))return;
        if(request==systemPickerRequest)systemPickerRequest=0;
        updateButtons();
        if(result!=RESULT_OK||data==null||data.getData()==null){clearPendingPath();if(request==SAVE_PDF&&readyVerified){outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.AWAITING_USER,getString(R.string.ui_save_cancelled));outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));}status.setText("已取消文件选择 / 授权，原来的选择保留。可以继续操作。");return;}
        Uri uri=data.getData();
        if(request==PICK_PDF||request==PICK_JSON){
            persist(uri,data);selectInput(request,uri);
        }else if(request==SAVE_PDF)exportTo(uri);
        else if(request==SAVE_BACKUP)exportBackupTo(uri);
        else if(request==PICK_FOLDER){
            boolean granted=persist(uri,data);refreshGrants();clearPendingPath();status.setText(granted?"文件夹授权已保存。请点击使用路径或重新读取；不会自动重复打开选择器。":"文件来源没有保留完整读写授权；可另存副本，或重新授权原文件夹。");
        }else if(request==RESTORE_FOLDER){persist(uri,data);restoreBackupIntoFolder(uri);}
    }
    private void refreshGrants(){
        try{
            SharedPreferences.Editor prefs=getPreferences(MODE_PRIVATE).edit();
            if(pdfUri!=null){pdfUri=PathDocuments.resolve(this,pdfUri.toString());prefs.putString("pdf",pdfUri.toString());}
            if(jsonUri!=null&&sharedJson==null){jsonUri=PathDocuments.resolve(this,jsonUri.toString());prefs.putString("json",jsonUri.toString());}
            prefs.apply();updateButtons();
        }catch(IOException e){error(message(e));}
    }
    private void selectInput(int request,Uri uri){
        invalidatePlan();restoreDraft=null;String name=nameHint(uri);deleteJson.setChecked(true);replace.setChecked(true);catalogMode.check(replaceCatalog.getId());
        synchronized(replacementLock){if(request==PICK_PDF){pdfUri=uri;pdfName=name;pdfLabel.setText(name);getPreferences(MODE_PRIVATE).edit().putString("pdf",uri.toString()).putString("pdfName",name).apply();}
        else{sharedJson=null;jsonUri=uri;jsonName=name;jsonLabel.setText(name);getPreferences(MODE_PRIVATE).edit().putString("json",uri.toString()).putString("jsonName",name).remove("selectedJsonId").remove("selectedJsonHash").apply();}}
        status.setText(inputGuidance());updateButtons();
    }
    private void inputPath(int request){
        setInputsExpanded(true);
        clearPendingPath();pathRequest=request;pathInput.setText("");pathInput.setHint(request==PICK_PDF?"粘贴 PDF 路径":"粘贴 JSON 路径");pathEditor.setVisibility(View.VISIBLE);pathInput.requestFocus();
        status.setText("输入路径后点击使用；取消不会更改文件选择。");updateButtons();
    }
    private void clearPendingPath(){pendingPathRequest=0;pendingPath="";}
    private void closePathEditor(){clearPendingPath();pathEditor.setVisibility(View.GONE);pathInput.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(pathInput.getWindowToken(),0);updateButtons();}
    private String nameHint(Uri uri){
        try{String id=DocumentsContract.getDocumentId(uri);return id.substring(id.lastIndexOf('/')+1).replaceFirst("^primary:","");}
        catch(IllegalArgumentException e){return "已选文件（读取后显示名称）";}
    }
    private String inputLabel(String name,Uri uri){
        try{if("com.android.externalstorage.documents".equals(uri.getAuthority())){String id=DocumentsContract.getDocumentId(uri);if(id.startsWith("primary:"))return name+"\n/storage/emulated/0/"+id.substring(8);}}
        catch(IllegalArgumentException ignored){}return name;
    }
    private void launchPicker(Intent intent,int request){
        try{systemPickerRequest=request;updateButtons();startActivityForResult(intent,request);}
        catch(ActivityNotFoundException|SecurityException e){systemPickerRequest=0;clearPendingPath();if(request==SAVE_PDF&&readyVerified)outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.AWAITING_USER,"尚未打开保存位置，已生成PDF保留，可继续保存。");updateButtons();status.setText("未能打开系统文件选择器；当前选择保留。");error(message(e));}
    }
    private void cancelCurrentInput(){
        if(shareLoading){cancelShare();return;}
        if(readJob!=null){cancelReading();return;}
        if(systemPickerRequest!=0){int request=systemPickerRequest;systemPickerRequest=0;finishActivity(request);clearPendingPath();
            if(request==SAVE_PDF&&readyVerified){outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.AWAITING_USER,getString(R.string.ui_save_cancelled));outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));}
            status.setText("已取消系统选择 / 授权，原来的选择和已生成文件保留。");updateButtons();return;}
        if(pathEditor.getVisibility()==View.VISIBLE){closePathEditor();status.setText("已取消路径输入，原来的文件选择保留。");}
    }
    private void resolvePath(int request,String path){
        try{Uri uri=PathDocuments.resolve(this,path);closePathEditor();selectInput(request,uri);}
        catch(SharedPath.RestrictedPathException e){showRestrictedPath(request,e);}
        catch(IOException|IllegalArgumentException e){pendingPathRequest=request;pendingPath=path;dialogBuilder().setTitle("路径暂时无法读取").setMessage(message(e))
            .setNegativeButton("返回编辑",(d,w)->clearPendingPath()).setOnCancelListener(d->clearPendingPath()).setPositiveButton("授权所在文件夹",(d,w)->authorizeFolder(path)).show();}
    }
    private void showRestrictedPath(int request,SharedPath.RestrictedPathException error){
        clearPendingPath();showWorkflow(dialogBuilder().setTitle("此目录受系统限制").setMessage(message(error)+"\n\n请从来源应用分享具体文件，或先将它复制到 Download（下载）后选择。当前文件和核对状态保留。")
            .setNegativeButton("返回编辑",null).setPositiveButton("下载 / 最近文件",(d,w)->pickSystemFile(request)).create());
    }
    private void authorizeFolder(String path){
        if(path!=null&&path.trim().startsWith("content://")){
            Uri source=Uri.parse(path.trim());
            if(!DocumentsContract.isDocumentUri(this,source)){
                clearPendingPath();showWorkflow(dialogBuilder().setTitle("分享地址没有父文件夹授权").setMessage("这是来源应用分享的单文件地址，不能用它授权QQ的Android/data。请重新分享JSON，或在来源应用复制到Download后选择。原选择保留。")
                    .setNegativeButton("返回",null).setPositiveButton("选择JSON",(d,w)->pick(PICK_JSON)).create());return;
            }
        }
        try{Uri existing=PathDocuments.resolve(this,path);PathDocuments.folderForDocument(this,existing);
            clearPendingPath();status.setText("此位置已有持久读写授权，无需重复授权。可直接使用路径或重新读取。");updateButtons();return;
        }catch(SharedPath.RestrictedPathException e){showRestrictedPath(pathRequest==PICK_JSON?PICK_JSON:PICK_PDF,e);return;}
        catch(IOException|IllegalArgumentException ignored){}

        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try{intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,PathDocuments.initialFolder(path));}
        catch(SharedPath.RestrictedPathException e){showRestrictedPath((pendingPathRequest==PICK_JSON||pathRequest==PICK_JSON)?PICK_JSON:PICK_PDF,e);return;}
        catch(IOException|IllegalArgumentException ignored){}
        launchPicker(intent,PICK_FOLDER);
    }
    private void emptyTree(){if(review!=null)review.clearDocument();}
    private void invalidateOutput(){catalogRetry=null;verifiedOutput=null;preparedOutput=null;outputMetrics=null;readyVerified=false;if(readyFile!=null&&readyFile.exists())readyFile.delete();exportedUri=null;exportedName="";}
    private void invalidatePlan(){scheduleDraft(true);previewGeneration++;parsed=null;info=null;diagnostics=null;draftCompleted=false;invalidateOutput();confirmed.setChecked(false);clearImage();emptyTree();outcome.begin("");renderOutcome();summary.setText("文件已更改，需要重新预览。");}
    private void showInspectionMenu(){
        if(diagnostics==null&&parsed==null)return;
        showWorkflow(dialogBuilder().setTitle("检查与目录变化").setItems(new String[]{"导入检查与问题","PDF 书签变化","享做目录变化"},(d,which)->{
            if(which==0)showTextDetails("导入检查",diagnostics==null?"请先重新读取文件。":diagnostics.describe());
            else if(which==1)showPdfChanges();
            else previewEnjoyChanges();
        }).create());
    }
    private void previewEnjoyChanges(){
        if(parsed==null||info==null||sourceNeedsReload){error("请先重新读取当前文件，再核对享做目录变化。");return;}
        if(!EnjoyDirectorySync.recognizes(pdfUri)){showTextDetails("享做目录变化","当前文件不是已识别的享做笔记资源PDF。本工具不会猜测其笔记目录；普通PDF可继续处理内部书签。");return;}
        if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask()){error("请先处理已有恢复或清理记录。");return;}
        Uri selected=pdfUri;String expected=pdfHash;int count=info.pageCount;List<TocParser.Row> rows=new ArrayList<>(currentRows());boolean replaceAll=replaceCatalog.isChecked();
        setBusy(true,"正在只读核对享做目录变化…");worker.execute(()->{try{
            EnjoyDirectorySync.Prepared prepared=enjoy.prepare(selected,count,rows,expected,replaceAll);
            CatalogDiff.Summary diff=prepared.previewChanges();
            onUi(()->{setBusy(false,"享做目录变化已核对，此操作未修改文件。");showChangeList("享做目录变化",(replaceAll?"替换全部目录 · ":"保留并添加 · ")+diff.counts(),diff.details,index->{
                DirectoryDiff.Change change=diff.changes.get(index);int page=diff.targetPages.get(index);
                if(change.after!=null&&page>0){int source=uniqueSourceRow(rows,change.after,change.afterPath,page);if(source>=0){revealDifference(rows,source);return;}}
                if(page>0&&page<=count){focusSection(reviewCard);renderPage(page,change.after!=null?change.after.title:change.before.title);status.setText(R.string.workflow_change_previewed);}
                else showTextDetails("目录变化详情",diff.details.get(index)+"\n\n原笔记页面无法可靠映射到当前PDF，不猜测跳转页。");
            });});
        }catch(Exception e){onUi(()->{setBusy(false,"享做目录变化尚未核实；当前文件未改动。");showTextDetails("尚不能核对享做目录",message(e)+"\n\n请核对所选PDF及原文件夹授权。未知结构或映射不会被当成空目录。");});}});
    }
    private void refreshWorkflowSummary(){
        if(filePair!=null){filePair.setText(parsed==null?"分别选择PDF和目录JSON；也可从文件应用分享导入。":getString(R.string.workflow_file_pair,pdfName,jsonName));}
        if(changeSummary!=null){
            if(parsed!=null&&info!=null){DirectoryDiff.Summary diff=DirectoryDiff.compare(info.existingRows,currentRows());int issues=diagnostics==null?0:diagnostics.issues.size();changeSummary.setText(issues>0?getString(R.string.workflow_check_hint,issues):getString(R.string.workflow_pdf_changes,diff.counts(),""));changeSummary.setOnClickListener(issues>0?v->showTextDetails("导入检查",diagnostics.describe()):null);changeSummary.setClickable(issues>0);}
            else {changeSummary.setText(diagnostics!=null?"导入检查发现问题，点击查看详情":"读取后可查看导入检查与目录变化");changeSummary.setOnClickListener(diagnostics!=null?v->showTextDetails("导入检查",diagnostics.describe()):null);changeSummary.setClickable(diagnostics!=null);}
        }
        if(inspectDirectory!=null)inspectDirectory.setEnabled(!busy&&!shareLoading&&!FilePickerDialog.isGrantChangeRunning()&&(diagnostics!=null||parsed!=null));
        int active=exportedUri!=null||getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask()?2:parsed!=null?1:0;
        for(int i=0;i<steps.length;i++)if(steps[i]!=null){steps[i].setBackground(background(i==active?appearance.selectedRow:appearance.surfaceVariant,10));steps[i].setTextColor(i==active?appearance.accent:appearance.secondaryText);steps[i].setTypeface(null,i==active?Typeface.BOLD:Typeface.NORMAL);}
        updateDraftNotice();
    }
    private void updateButtons(){
        boolean locked=busy||shareLoading||FilePickerDialog.isGrantChangeRunning();
        for(Button b:buttons)b.setEnabled(!locked);
        for(Button b:resultActions)b.setEnabled(!locked);
        boolean pdfPending=getPreferences(MODE_PRIVATE).contains("pendingPdf"),catalogPending=enjoy.hasPending();
        boolean cleanupPending=hasCleanupTask(),pending=pdfPending||catalogPending||cleanupPending;
        if(reviewCard!=null)reviewCard.setVisibility(parsed!=null||diagnostics!=null?View.VISIBLE:View.GONE);
        if(outputCard!=null)outputCard.setVisibility(parsed!=null||readyVerified||exportedUri!=null||pending||outcome.overall()!=TaskOutcome.Overall.EMPTY?View.VISIBLE:View.GONE);
        if(generate!=null){
            boolean canOpen=exportedUri!=null&&!confirmed.isChecked();
            generate.setEnabled(!locked&&(pending||canOpen||(pdfUri!=null&&jsonUri!=null)));
            generate.setText(busy?getString(R.string.ui_primary_busy):pending?getString(R.string.ui_primary_recovery)
                :canOpen?getString(R.string.ui_primary_open):onlySyncSuggested()&&confirmed.isChecked()?"仅同步享做目录":sourceNeedsReload?getString(R.string.workflow_read_again):parsed==null?getString(R.string.ui_primary_read)
                :!confirmed.isChecked()?getString(R.string.ui_primary_review)
                :readyVerified?getString(R.string.ui_primary_save):getString(overwrite.isChecked()?R.string.ui_primary_replace:R.string.ui_primary_copy));
        }
        if(review!=null)review.setBusy(locked);
        if(stageLabel!=null&&!locked)stageLabel.setText(getString(pending?R.string.ui_stage_recovery:exportedUri!=null&&!confirmed.isChecked()?R.string.ui_stage_saved:parsed==null?R.string.ui_stage_ready:R.string.ui_stage_review));
        if(export!=null){export.setEnabled(!locked&&!pending&&readyVerified);export.setText(overwrite!=null&&overwrite.isChecked()?"重试保存 / 替换已生成的 PDF":"另存已生成的 PDF…");}
        if(open!=null){open.setEnabled(!locked&&exportedUri!=null);open.setVisibility(View.GONE);}
        if(cleanup!=null){BackupRecord record=backupRecord(getPreferences(MODE_PRIVATE).getString("lastReplacement",null));boolean available=cleanupPending||(record!=null&&sameDocument(pdfUri,Uri.parse(record.outputUri)))||enjoy.recordFor(pdfUri)!=null;
            cleanup.setVisibility(available?View.VISIBLE:View.GONE);cleanup.setEnabled(!locked&&!pdfPending&&!catalogPending&&available);cleanup.setText(cleanupPending?"继续清理旧文件与备份":"清理旧文件与备份");}
        if(confirmed!=null)confirmed.setEnabled(!locked&&parsed!=null&&!sourceNeedsReload);
        if(confirmed!=null)confirmed.setVisibility(parsed!=null?View.VISIBLE:View.GONE);
        if(review!=null)review.setVisibility(parsed!=null?View.VISIBLE:View.GONE);
        if(replace!=null)replace.setVisibility(parsed!=null&&info.existingCount>0?View.VISIBLE:View.GONE);
        if(inspectionRow!=null)inspectionRow.setVisibility(parsed!=null||diagnostics!=null?View.VISIBLE:View.GONE);
        if(readAgain!=null)readAgain.setVisibility(parsed!=null||diagnostics!=null?View.VISIBLE:View.GONE);
        if(replace!=null)replace.setEnabled(!locked);
        if(overwrite!=null)overwrite.setEnabled(!locked);
        if(deleteJson!=null)deleteJson.setEnabled(!locked);
        if(syncEnjoy!=null)syncEnjoy.setEnabled(!locked);
        if(keepCatalog!=null)keepCatalog.setEnabled(!locked);
        if(replaceCatalog!=null)replaceCatalog.setEnabled(!locked);
        if(syncCatalog!=null)syncCatalog.setEnabled(!locked&&!pending&&parsed!=null&&info.existingCount>0&&confirmed.isChecked()&&EnjoyDirectorySync.recognizes(pdfUri));
        if(recoverCatalog!=null){recoverCatalog.setVisibility(catalogPending?View.VISIBLE:View.GONE);recoverCatalog.setEnabled(!locked&&!pdfPending);}
        if(recover!=null){recover.setVisibility(pdfPending?View.VISIBLE:View.GONE);recover.setEnabled(!locked&&!catalogPending);}
        if(exportBackup!=null){exportBackup.setVisibility(getPreferences(MODE_PRIVATE).contains("lastBackup")?View.VISIBLE:View.GONE);exportBackup.setEnabled(!locked);}
        if(restoreMissing!=null){restoreMissing.setVisibility(getPreferences(MODE_PRIVATE).contains("lastBackup")?View.VISIBLE:View.GONE);restoreMissing.setEnabled(!locked&&!catalogPending&&!cleanupPending);}
        if(cancelRead!=null){boolean available=shareLoading||readJob!=null||systemPickerRequest!=0||(pathEditor!=null&&pathEditor.getVisibility()==View.VISIBLE);
            cancelRead.setVisibility(available?View.VISIBLE:View.GONE);cancelRead.setEnabled(available);
            cancelRead.setText(shareLoading?"取消分享导入":readJob!=null?"取消读取 / 返回":systemPickerRequest!=0?"取消系统选择":"取消路径 / 返回");}
        updateContextOptions();
        if(resultFile!=null){resultFile.setVisibility(exportedUri!=null?View.VISIBLE:View.GONE);if(exportedUri!=null){String name=exportedName.isEmpty()?nameHint(exportedUri):exportedName,location=inputLabel(name,exportedUri);resultFile.setText(getString(R.string.workflow_saved_location,location.equals(name)?name+"\n"+exportedUri:location));}}
        if(outputSettings!=null&&overwrite!=null&&syncEnjoy!=null&&replaceCatalog!=null&&deleteJson!=null){
            String settings=overwrite.isChecked()?"原路径、原名称替换":"另存带目录副本";
            settings+=overwrite.isChecked()&&syncEnjoy.isChecked()&&EnjoyDirectorySync.recognizes(pdfUri)?(replaceCatalog.isChecked()?" · 享做目录全部替换":" · 享做目录保留并添加"):" · 不同步享做目录";
            settings+=deleteJson.isChecked()?" · 成功后删除JSON":" · 保留JSON";
            String target=pdfUri==null?"尚未选择目标PDF":pdfName;
            outputSettings.setText(getString(R.string.workflow_output_summary,parsed!=null?getString(R.string.workflow_output_count,target,currentRows().size()):target,settings));}
        refreshWorkflowSummary();
        if(updates!=null)updates.refresh();
    }
    private void setBusy(boolean value,String message){
        busy=value;status.setText(message);updateButtons();if(value)setStage(R.string.ui_stage_working);
        View progress=getWindow().getDecorView().findViewWithTag("progress");if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.GONE);
        if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void onUi(Runnable action){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())action.run();});}
    private void loadPreview(){
        if(busy||shareLoading||FilePickerDialog.isGrantChangeRunning())return;
        if(pdfUri==null||jsonUri==null){status.setText(inputGuidance());error(inputGuidance());return;}
        closePathEditor();
        invalidatePlan();ReadJob job=new ReadJob();readJob=job;
        File session=new File(getCacheDir(),"read-"+UUID.randomUUID());session.mkdirs();File localSource=new File(session,"source.pdf"),localReady=new File(session,"ready.pdf");
        setBusy(true,"正在读取PDF和目录；可用顶部按钮或系统返回取消。");
        Uri p=pdfUri,j=jsonUri;
        job.future=readers.submit(()->{
            WorkMetrics metrics=new WorkMetrics("preview");WorkMetrics.attach(metrics);
            try{
                setReadStage(job,R.string.ui_stage_toc);byte[] bytes=readJsonInput(j,job);String raw=validateJsonBytes(bytes);job.check();metrics.mark("json");
                job.inputRequest=PICK_PDF;setReadStage(job,R.string.ui_stage_read);String sourceHash=copySource(p,localSource,job);job.check();setReadStage(job,R.string.ui_stage_inspect);PdfBookmarks.Info readInfo=PdfBookmarks.inspect(localSource);job.check();
                metrics.mark("pdf_read_inspect");job.inputRequest=PICK_JSON;setReadStage(job,R.string.ui_stage_toc);
                TocDiagnostics.Report readDiagnostics=TocDiagnostics.inspect(raw,readInfo.pageCount);job.check();
                if(readDiagnostics.hasErrors()){
                    onUi(()->{if(readJob!=job||job.cancelled)return;readJob=null;diagnostics=readDiagnostics;setBusy(false,"目录有需要修正的问题，尚未生成或修改任何原文件。");showTextDetails("导入问题清单",readDiagnostics.describe());});return;
                }
                TocParser.Parsed readPlan=readDiagnostics.parsed;String tocHash=digest(bytes);
                String pName=displayName(p,job),jName=job.jsonRef!=null&&job.jsonRef.sourceUri.equals(j.toString())?job.jsonRef.displayName:displayName(j,job);job.check();
                onUi(()->{if(readJob!=job||job.cancelled)return;readJob=null;sourceFile=localSource;readyFile=localReady;info=readInfo;parsed=readPlan;pdfHash=sourceHash;jsonHash=tocHash;
                    sharedJson=job.jsonRef;synchronized(replacementLock){SharedPreferences.Editor inputPrefs=getPreferences(MODE_PRIVATE).edit().putString("selectedJsonHash",tocHash);if(sharedJson!=null)inputPrefs.putString("selectedJsonId",sharedJson.id);else inputPrefs.remove("selectedJsonId");inputPrefs.apply();}diagnostics=readDiagnostics;draftCompleted=false;sourceNeedsReload=false;pdfName=pName;jsonName=jName;pdfLabel.setText(pName);jsonLabel.setText(jName);getPreferences(MODE_PRIVATE).edit().putString("pdfName",pName).putString("jsonName",jName).apply();review.setDocument(parsed,info.pageCount);
                    summary.setText(String.format(Locale.CHINA,"PDF共 %d 页 · 导入 %d 条目录 · 已有 %d 条书签",info.pageCount,currentRows().size(),info.existingCount));
                    setBusy(false,info.existingCount>0?"PDF已有书签。核对后选择保留或替换；相同目录可仅同步享做。":"预览完成。点击目录核对目标页，再确认生成。");restoreReviewIfRequested();updateButtons();scheduleDraft(false);setInputsExpanded(false);focusSection(reviewCard);});
            }catch(Exception e){onUi(()->{if(readJob!=job||job.cancelled)return;readJob=null;setBusy(false,"读取失败，原文件未改动。");showInputFailure("文件读取未完成",e,job.inputRequest);});}
            finally{android.util.Log.i("PdfBookmarks.Perf",metrics.summary());WorkMetrics.detach();}
        });
    }
    private String displayName(Uri uri,ReadJob job)throws IOException {
        job.check();try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null,job.signal)){job.check();if(c!=null&&c.moveToFirst()){String name=c.getString(0);if(name!=null&&!name.trim().isEmpty())return name;}}return nameHint(uri);
    }
    private void cancelReading(){
        ReadJob job=readJob;if(job==null)return;job.cancelled=true;readJob=null;if(job.future!=null)job.future.cancel(true);
        readers.execute(job::closeInput);invalidatePlan();setBusy(false,"已取消读取并返回。原PDF和JSON未改动，可以重新选择文件。");
    }
    private List<TocParser.Row> currentRows(){return review!=null?review.rows():parsed.rows;}
    private void renderPage(int number,String title){
        if(sourceFile==null||!sourceFile.isFile())return;
        int token=++previewGeneration;File source=sourceFile;if(previewTask!=null)previewTask.cancel(false);
        review.showLoading(number,title);
        previewTask=previews.submit(()->{
            Bitmap bitmap=null;
            try{
                if(token!=previewGeneration)return;
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(number-1)){
                    int width=1400,height=Math.max(1,Math.round(width*(float)page.getHeight()/page.getWidth()));
                    if((long)width*height>6000000){double scale=Math.sqrt(6000000d/((long)width*height));width=Math.max(1,(int)(width*scale));height=Math.max(1,(int)(height*scale));}
                    bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                Bitmap result=bitmap;runOnUiThread(()->{if(isFinishing()||isDestroyed()||token!=previewGeneration){result.recycle();return;}clearImage();shown=result;review.showBitmap(result,number,title);});
            }catch(Exception e){if(bitmap!=null)bitmap.recycle();onUi(()->{if(token==previewGeneration){clearImage();review.showPageError(message(e));confirmed.setChecked(false);}});}
        });
    }
    private void clearImage(){if(review!=null)review.clearBitmap();if(shown!=null){shown.recycle();shown=null;}}
    private void confirmWrite(){
        if(parsed==null||!confirmed.isChecked())return;
        if(sourceNeedsReload){loadPreview();return;}
        if(info.existingCount>0&&!replace.isChecked()){error("PDF已有目录。需要更新请勾选替换，并重新核对预览。");return;}
        String action=info.existingCount>0?"将替换PDF中的整棵旧书签目录。":"将在PDF中添加书签目录。";
        String destination=overwrite.isChecked()?"生成并校验后，直接覆盖原PDF："+pdfName+"。路径和名称不变，先保存原件备份。请先关闭享做中正在使用的该笔记。":"原PDF保留；生成后选择新PDF的保存位置。";
        dialogBuilder().setTitle("确认生成")
            .setMessage("目标PDF："+pdfName+"\n目录："+currentRows().size()+" 条 · PDF共 "+info.pageCount+" 页\nPDF书签："+DirectoryDiff.compare(info.existingRows,currentRows()).counts()+"\n\n"+action+"\n"+destination+syncNotice()+(deleteJson.isChecked()?"\n成功后仅清理以下JSON：\n"+jsonDeleteNotice():"\nJSON保留。"))
            .setNegativeButton("取消",null).setPositiveButton("生成",(d,w)->generateOutput()).show();
    }
    private void generateOutput(){
        List<TocParser.Row> rows=new ArrayList<>(currentRows());boolean replaceOld=replace.isChecked(),inPlace=overwrite.isChecked(),deleteAfter=deleteJson.isChecked();Uri p=pdfUri,j=jsonUri;
        boolean sync=inPlace&&syncEnjoy.isChecked()&&EnjoyDirectorySync.recognizes(p),replaceExisting=replaceCatalog.isChecked();
        String expectedPdf=pdfHash,expectedJson=jsonHash;int pages=info.pageCount;
        previewGeneration++;invalidateOutput();beginOutcome(true,sync,deleteAfter,inPlace);outcome.set(TaskOutcome.Part.PDF,TaskOutcome.Status.WAITING,"先检查保存条件");renderOutcome();setBusy(true,"正在只读检查保存条件，尚未生成或改动原文件…");setStage(R.string.workflow_stage_preflight);
        WorkMetrics metrics=new WorkMetrics("generate_save");outputMetrics=metrics;
        worker.execute(()->{
            WorkMetrics.attach(metrics);
            boolean writing=false;
            try{
                if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask())throw new IOException("请先处理未完成任务，再生成新PDF。");
                if(!expectedJson.equals(digest(readJsonInput(j))))throw new IOException("JSON在预览后发生变化，请重新读取预览。");
                // Metadata and snapshots only: never create/rename a file to probe provider support.
                if(inPlace)StagedDocuments.forDocument(this,p);
                EnjoyDirectorySync.Prepared catalog=sync?enjoy.prepare(p,pages,rows,expectedPdf,replaceExisting):null;
                if(!sync&&!expectedPdf.equals(hashUri(p)))throw new IOException("PDF在预览后发生变化，请重新读取预览。");
                PreparedOutput preparation=new PreparedOutput(p,j,expectedPdf,expectedJson,replaceExisting,rows,catalog);metrics.mark("preflight");
                writing=true;outcomeSet(TaskOutcome.Part.PDF,TaskOutcome.Status.RUNNING,"保存条件检查通过，正在生成");setStage(R.string.ui_stage_generate);
                PdfBookmarks.VerifiedOutput artifact=PdfBookmarks.writeVerified(sourceFile,readyFile,rows,replaceOld);metrics.mark("generate_verify");
                onUi(()->{verifiedOutput=artifact;preparedOutput=preparation;readyVerified=true;outcome.set(TaskOutcome.Part.PDF,TaskOutcome.Status.SUCCESS,"完整目录已写入，新PDF已重读校验。");renderOutcome();setBusy(false,"生成并校验成功。可以保存PDF。");if(inPlace)replaceOriginalOutput(deleteAfter);else saveOutput();});
            }catch(Exception e){if(readyFile.exists())readyFile.delete();boolean generated=writing;outcomeSet(TaskOutcome.Part.PDF,generated?TaskOutcome.Status.FAILED:TaskOutcome.Status.SKIPPED,generated?message(e):"保存条件未通过，尚未生成PDF");outcomeSet(TaskOutcome.Part.SAVE,generated?TaskOutcome.Status.SKIPPED:TaskOutcome.Status.FAILED,generated?getString(R.string.ui_not_run):message(e));outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.SKIPPED,getString(R.string.ui_not_run));outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));onUi(()->{setBusy(false,generated?"生成失败，原文件未改动。":"保存条件尚未满足，原文件未改动。请处理下方具体原因。");confirmed.setChecked(false);focusResult();AlertDialog.Builder alert=dialogBuilder().setTitle(generated?"生成未完成":"保存条件检查未通过").setMessage(message(e)).setNegativeButton("返回",null);if(e instanceof PathDocuments.FolderAuthorizationRequiredException)alert.setPositiveButton("授权原文件夹一次",(d,w)->authorizeFolder(p.toString()));alert.show();});}
            finally{android.util.Log.i("PdfBookmarks.Perf",metrics.summary());WorkMetrics.detach();}
        });
    }
    private void saveOutput(){
        if(!readyVerified||readyFile==null||!readyFile.isFile()){error("请先生成并校验PDF，再选择保存方式。");return;}
        outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.AWAITING_USER,"PDF已生成，等待选择位置或确认保存。");
        if(overwrite.isChecked()){
            dialogBuilder().setTitle("替换原PDF").setMessage("将覆盖 "+pdfName+"，原路径和名称不变，并保留原件备份。请先关闭享做中的该笔记。"+syncNotice()+(deleteJson.isChecked()?"\n成功后仅清理以下JSON：\n"+jsonDeleteNotice():"\nJSON保留。"))
                .setNegativeButton("取消",null).setPositiveButton("替换",(d,w)->replaceOriginalOutput(deleteJson.isChecked())).show();return;
        }
        deleteOnExport=deleteJson.isChecked();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
        String base=pdfName.toLowerCase(Locale.ROOT).endsWith(".pdf")?pdfName.substring(0,pdfName.length()-4):pdfName;
        intent.putExtra(Intent.EXTRA_TITLE,base+"_带目录.pdf");launchPicker(intent,SAVE_PDF);
    }
    private void exportTo(Uri destination){
        if(sameDocument(destination,pdfUri)||sameDocument(destination,jsonUri)){error("请选择新的输出文件，不能覆盖输入文件。");return;}
        boolean deleteAfter=deleteOnExport;
        outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.RUNNING,"");setBusy(true,"正在保存到你选择的位置并核对文件…");setStage(R.string.ui_stage_save);
        worker.execute(()->{
            try{
                if(!readyVerified||!readyFile.isFile())throw new IOException("生成的文件不可用，请重新生成。");
                String expected=verifiedOutput!=null&&verifiedOutput.file.equals(readyFile)?verifiedOutput.sha256:hashFile(readyFile);
                try(InputStream input=new FileInputStream(readyFile);OutputStream output=getContentResolver().openOutputStream(destination,"wt")){
                    if(output==null)throw new IOException("无法打开输出位置。");if(!expected.equals(transferDigest(input,output)))throw new IOException("已生成PDF在保存前变化，未确认保存成功。");output.flush();
                }
                if(!expected.equals(hashUri(destination)))throw new IOException("保存后的文件校验不一致，请另选位置重新保存。");
                outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.SUCCESS,"已另存并重新读取核对SHA256："+displayName(destination));finishSave(destination,"保存并核验成功："+displayName(destination)+"。可以打开或导入享做。",deleteAfter);
            }catch(Exception e){outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.FAILED,message(e));outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));onUi(()->{setBusy(false,"保存未完成；原文件及已生成副本仍保留，可重新保存。");showMoreActions();error(message(e));});}
        });
    }
    private boolean supports(Uri uri,int flag){
        if(uri==null||checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false;
        try(Cursor c=getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){
            return c!=null&&c.moveToFirst()&&(c.getInt(0)&flag)!=0;
        }catch(Exception e){return false;}
    }
    private void replaceOriginalOutput(boolean deleteAfter){
        if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask()){error("上次替换或目录同步尚待恢复，请先处理恢复记录。");return;}
        Uri destination=pdfUri;String originalHash=pdfHash,name=pdfName;
        boolean synchronize=syncEnjoy.isChecked()&&EnjoyDirectorySync.recognizes(destination);
        boolean replaceExisting=replaceCatalog.isChecked();
        List<TocParser.Row> rows=currentRows();int pageCount=info.pageCount;
        outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.RUNNING,"");outcomeSet(TaskOutcome.Part.CATALOG,synchronize?TaskOutcome.Status.WAITING:TaskOutcome.Status.SKIPPED,synchronize?"":getString(R.string.ui_catalog_not_requested));outcomeSet(TaskOutcome.Part.JSON,deleteAfter?TaskOutcome.Status.WAITING:TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));setBusy(true,"正在原文件夹核验新PDF并安全切换名称；原件将保留为备份…");setStage(R.string.ui_stage_save);
        worker.execute(()->{
            WorkMetrics metrics=outputMetrics==null?new WorkMetrics("save_retry"):outputMetrics;WorkMetrics.attach(metrics);
            synchronized(replacementLock){
            try{
                if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask())throw new IOException("有未处理的恢复记录，请先核对或恢复。");
                if(!readyVerified||!readyFile.isFile())throw new IOException("请先生成并校验PDF。");
                StagedDocuments documents=StagedDocuments.forDocument(this,destination);
                if(!originalHash.equals(hashUri(destination)))throw new IOException("原PDF在预览后变化，请重新预览。");
                PreparedOutput reusable=preparedOutput;preparedOutput=null;
                EnjoyDirectorySync.Prepared catalog=synchronize?(reusable!=null&&reusable.catalog!=null&&reusable.matches(destination,jsonUri,originalHash,jsonHash,replaceExisting,rows)?reusable.catalog:enjoy.prepare(destination,pageCount,rows,originalHash,replaceExisting)):null;
                metrics.mark("replace_preflight");
                File folder=new File(getFilesDir(),"pdf-backups");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法建立原PDF备份目录。");
                File backup=new File(folder,UUID.randomUUID()+".pdf");
                try(InputStream in=new FileInputStream(sourceFile);FileOutputStream out=new FileOutputStream(backup)){transfer(in,out);out.getFD().sync();}
                if(!originalHash.equals(hashFile(backup)))throw new IOException("原PDF备份校验失败，未替换原文件。");
                PdfBookmarks.VerifiedOutput artifact=verifiedOutput;
                String outputHash=artifact!=null&&artifact.file.equals(readyFile)?artifact.sha256:hashFile(readyFile);metrics.mark("backup");
                boolean recorded=getPreferences(MODE_PRIVATE).edit().putString("pendingPdf",destination.toString()).putString("pendingBackup",backup.getAbsolutePath())
                    .putString("pendingOriginalHash",originalHash).putString("pendingOutputHash",outputHash).putString("pendingName",name)
                    .putString("lastBackup",backup.getAbsolutePath()).putString("lastBackupName",name).commit();
                if(!recorded)throw new IOException("恢复记录保存失败，未替换原文件。");
                StagedReplacement.Result result=artifact!=null&&artifact.file.equals(readyFile)
                    ?StagedReplacement.replaceVerified(documents,name,readyFile,originalHash,artifact.sha256,artifact.length,journal->saveJournal(documents.folder,journal))
                    :StagedReplacement.replace(documents,name,readyFile,originalHash,journal->saveJournal(documents.folder,journal));
                metrics.mark("stage_promote_verify");
                Uri finalUri=Uri.parse(result.document.uri);
                // 持久授权来自父文件夹；改名后的新标识必须保存，禁止继续使用旧URI。
                String cleanupRecord=new Gson().toJson(new BackupRecord(documents,result,backup));
                if(!getPreferences(MODE_PRIVATE).edit().putString("pdf",finalUri.toString()).putString("pdfName",name).putString("lastReplacement",cleanupRecord).commit())throw new IOException("最终PDF已核验，但新文件标识未保存；恢复记录保留。");
                clearRecovery();
                outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.SUCCESS,"已在原路径以原名称替换，内容与最终名称均已重新核验："+name);
                CatalogRetry retry=catalog==null?null:new CatalogRetry(finalUri,outputHash,jsonUri,jsonHash,pageCount,rows,replaceExisting);
                onUi(()->{pdfUri=finalUri;sourceNeedsReload=true;catalogRetry=retry;pdfLabel.setText(inputLabel(name,finalUri));if(retry!=null){draftCompleted=false;scheduleDraft(true);}});
                String success="PDF已在原路径同名替换并核验："+name+"。两份原PDF备份保留。";
                if(catalog!=null){
                    try{setStage(R.string.ui_stage_catalog);outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.RUNNING,"");String synced=applyCatalog(catalog,outputHash);metrics.mark("catalog");success+="\n"+synced;onUi(()->catalogRetry=null);}
                    catch(Exception e){finishSave(finalUri,success+"\n"+catalogFailureNotice()+"\n"+message(e),false);return;}
                }
                finishSave(finalUri,success+"\n请重新打开享做核对目录、跳转与原笔迹。",deleteAfter);
            }catch(Exception e){
                boolean needsAuthorization=e instanceof PathDocuments.FolderAuthorizationRequiredException||e instanceof SecurityException;
                outcomeSet(TaskOutcome.Part.SAVE,TaskOutcome.Status.FAILED,message(e));outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.SKIPPED,getString(R.string.ui_not_run));outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));
                onUi(()->{setBusy(false,"未确认替换成功，JSON与备份保留。请按具体错误提示处理后重新核对。");confirmed.setChecked(false);if(readyVerified)showMoreActions();
                    AlertDialog.Builder dialog=dialogBuilder().setTitle("替换未完成").setMessage(message(e)).setNegativeButton("返回",null);
                    if(needsAuthorization)dialog.setPositiveButton("授权原文件夹",(d,w)->authorizeFolder(destination.toString()));
                    dialog.show();});
            }finally{android.util.Log.i("PdfBookmarks.Perf",metrics.summary());WorkMetrics.detach();}
            }
        });
    }
    private String syncNotice(){
        return overwrite.isChecked()&&syncEnjoy.isChecked()&&EnjoyDirectorySync.recognizes(pdfUri)
            ?"\n"+catalogModeNotice(replaceCatalog.isChecked())+"\n请先保存笔记，并在系统设置强行停止享做（不要清除数据）。":"";
    }
    private String catalogModeNotice(boolean replaceExisting){
        return replaceExisting?"享做目录模式：用新目录替换全部已有目录，包括手动目录；成功核验后自动删除本次旧目录及内部目录恢复副本，不能再用它们恢复。写入失败时保留恢复材料。PDF备份、笔迹和其他配置保留。"
            :"享做目录模式：保留已有目录并添加新目录；原目录先完整备份，手动目录和笔迹保留。";
    }
    private void confirmSyncCatalog(){
        if(busy||parsed==null||!confirmed.isChecked()||!EnjoyDirectorySync.recognizes(pdfUri))return;
        if(!sameRows(info.existingRows,currentRows())){error("当前PDF书签与所选JSON不一致。请先生成并替换PDF，或选择与当前PDF书签相同的JSON；不会只同步不匹配的目录。");return;}
        boolean replaceExisting=replaceCatalog.isChecked(),deleteAfter=deleteJson.isChecked();
        dialogBuilder().setTitle("仅同步享做目录")
            .setMessage("PDF已有与JSON相同的书签，不再替换PDF。\n"+catalogModeNotice(replaceExisting)+(deleteAfter?"\n成功后仅清理以下JSON：\n"+jsonDeleteNotice():"\nJSON保留。")+"\n\n请先保存笔记，并在系统设置中强行停止享做（不要清除数据），然后继续。")
            .setNegativeButton("取消",null).setPositiveButton("已停止享做，同步",(d,w)->{
                Uri source=pdfUri,toc=jsonUri;String expected=pdfHash,tocHash=jsonHash;int count=info.pageCount;List<TocParser.Row> rows=currentRows();
                beginOutcome(false,true,deleteAfter,false);outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.RUNNING,"");setBusy(true,"正在核验并同步享做目录，PDF与笔迹保留…");setStage(R.string.ui_stage_catalog);
                worker.execute(()->{synchronized(replacementLock){try{
                    if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask())throw new IOException("有待恢复记录，请先恢复。");
                    if(!tocHash.equals(digest(readJsonInput(toc))))throw new IOException("JSON在预览后变化，请重新读取。");
                    EnjoyDirectorySync.Prepared plan=enjoy.prepare(source,count,rows,expected,replaceExisting);String result=applyCatalog(plan,expected);
                    finishSave(source,result+"\nPDF与笔迹未改动。请重新打开享做核对目录、跳转与原笔迹。",deleteAfter,false);
                }catch(Exception e){onUi(()->{if(outcome.entries().stream().anyMatch(entry->entry.part==TaskOutcome.Part.CATALOG&&entry.status==TaskOutcome.Status.RUNNING))outcome.set(TaskOutcome.Part.CATALOG,TaskOutcome.Status.FAILED,catalogFailureNotice()+"\n"+message(e));renderOutcome();confirmed.setChecked(false);setBusy(false,catalogFailureNotice());error(message(e));});}}});
            }).show();
    }
    private static boolean sameRows(List<TocParser.Row> actual,List<TocParser.Row> expected){
        if(actual.size()!=expected.size())return false;
        for(int i=0;i<actual.size();i++){TocParser.Row a=actual.get(i),b=expected.get(i);if(a.level!=b.level||a.pdfPage!=b.pdfPage||!Objects.equals(a.title,b.title))return false;}
        return true;
    }
    private void confirmRecoverCatalog(){
        if(busy||!enjoy.hasPending())return;
        dialogBuilder().setTitle("恢复享做目录")
            .setMessage("恢复上次同步前的享做目录。已生成的新PDF保留，笔迹和其他配置不改动；恢复后重新预览并同步。\n\n请先保存并强行停止享做，不要清除数据。目录已被享做改写时会停止，避免覆盖新内容。")
            .setNegativeButton("取消",null).setPositiveButton("已停止享做，恢复目录",(d,w)->{
                setBusy(true,"正在恢复并核验享做目录…");
                worker.execute(()->{synchronized(replacementLock){try{
                    if(getPreferences(MODE_PRIVATE).contains("pendingPdf"))throw new IOException("请先处理PDF恢复记录。");
                    enjoy.recover();onUi(()->{invalidatePlan();setBusy(false,"同步前的享做目录已恢复并核验。PDF和笔迹未改动，请重新预览后继续。");});
                }catch(Exception e){onUi(()->{setBusy(false,"目录恢复未确认完成，备份与恢复记录保留。");error(message(e));});}}});
            }).show();
    }
    private void saveJournal(Uri folder,StagedReplacement.Journal journal)throws IOException {
        if(!getPreferences(MODE_PRIVATE).edit().putString("pendingFolder",folder.toString()).putString("pendingJournal",new Gson().toJson(journal)).commit())throw new IOException("无法保存替换恢复记录；未继续切换名称。");
    }
    private BackupRecord backupRecord(String raw){
        if(raw==null)return null;
        try{BackupRecord record=new Gson().fromJson(raw,BackupRecord.class);
            if(record==null||record.folder==null||record.outputUri==null||record.outputName==null||record.outputHash==null
                ||record.backupUri==null||record.backupName==null||record.originalHash==null||record.privateBackup==null)return null;
            return record;
        }catch(RuntimeException e){return null;}
    }
    private String cleanupLocation(String name,String uri){
        String label=inputLabel(name,Uri.parse(uri));return label.equals(name)?name+"\n"+uri:label;
    }
    private boolean hasCleanupTask(){
        return getPreferences(MODE_PRIVATE).contains("pendingCleanup")||enjoy.automaticCleanupRecord()!=null;
    }
    private String catalogFailureNotice(){
        return !enjoy.hasPending()&&(enjoy.automaticCleanupRecord()!=null||getPreferences(MODE_PRIVATE).contains("pendingCleanup"))
            ?"享做新目录已替换，但旧目录清理未完成；JSON与剩余恢复材料保留。请用第3节继续清理，或保留剩余文件并结束清理。"
            :"享做目录同步未确认完成；PDF、JSON及备份保留，请处理恢复记录后重新预览并仅同步目录。";
    }
    private CleanupPlan catalogCleanupPlan(EnjoyDirectorySync.Record record){
        CleanupPlan plan=new CleanupPlan();Uri folder=Uri.parse(record.folder);
        plan.pdfUri=DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getDocumentId(folder)+"/"+record.pdfName).toString();
        plan.catalogRecord=record.snapshot;return plan;
    }
    private String pendingCleanupPlan(){
        String raw=getPreferences(MODE_PRIVATE).getString("pendingCleanup",null);
        if(raw!=null)return raw;
        String catalog=enjoy.automaticCleanupRecord();if(catalog==null)return null;
        EnjoyDirectorySync.Record record=new Gson().fromJson(catalog,EnjoyDirectorySync.Record.class);record.snapshot=catalog;
        return new Gson().toJson(catalogCleanupPlan(record));
    }
    /** Cleanup failure must not hide a directory that was already saved and verified. */
    private String applyCatalog(EnjoyDirectorySync.Prepared prepared,String expectedHash)throws IOException{
        boolean[] verified={false};
        try{
            String result=enjoy.apply(prepared,expectedHash,record->{
                verified[0]=true;outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.SUCCESS,"享做新目录已保存并核验；旧目录副本的清理结果单独列出。");discardReplacedCatalog(record);
            });
            outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.SUCCESS,result);return result;
        }catch(IOException|RuntimeException failure){
            if(!verified[0])outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.FAILED,catalogFailureNotice()+"\n"+message(failure));throw failure;
        }
    }
    /** 自动清理只列出本次目录副本；PDF恢复关联完全保留。 */
    private void discardReplacedCatalog(EnjoyDirectorySync.Record record)throws IOException{
        CleanupPlan plan=catalogCleanupPlan(record);String raw=new Gson().toJson(plan);
        BackupCleanup.Item item=enjoy.cleanupItem(plan.catalogRecord,false);SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        setStage(R.string.ui_stage_cleanup);outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.RUNNING,"仅处理列明的本次旧目录及内部目录恢复副本；PDF备份保留。");
        try{CatalogReplacementCleanup.discard(item,()->{
            verifyCleanupPlan(plan,raw,false);
            if(!prefs.edit().putString("pendingCleanup",raw).commit())throw new IOException("无法保存旧目录清理进度，未开始删除。");
        },()->showAutomaticCleanup(item),()->verifyCleanupPlan(plan,raw,true),()->{
            enjoy.finishCleanup(plan.catalogRecord);verifyCleanupPlan(plan,raw,true);
            if(!prefs.edit().remove("pendingCleanup").commit()){
                prefs.edit().putString("pendingCleanup",raw).commit();
                throw new IOException("旧目录已删除，但完成记录未保存；可按同一清单重试。");
            }
        });outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.SUCCESS,"本次旧目录及内部目录恢复副本已删除；PDF备份保留。");}
        catch(IOException|RuntimeException failure){outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.FAILED,message(failure));throw failure;}
    }
    private void showAutomaticCleanup(BackupCleanup.Item item)throws IOException{
        CountDownLatch shown=new CountDownLatch(1);boolean[] displayed={false};
        runOnUiThread(()->{
            if(!isFinishing()&&!isDestroyed()){
                status.setText("新目录已替换并核验，将按所选模式自动删除以下本次旧目录副本：\n"
                    +cleanupLocation(item.backup.name,item.backup.uri)+"\n"+item.privateBackup.getAbsolutePath()
                    +"\n仅这两项；PDF备份、当前目录及笔迹保留。删除后不能用这些目录副本恢复。");displayed[0]=true;
            }
            shown.countDown();
        });
        try{if(!shown.await(10,TimeUnit.SECONDS)||!displayed[0])throw new IOException("删除清单尚未显示，目录副本与进度保留。");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("显示删除清单时中断，目录副本与进度保留。",interrupted);}
    }
    private CleanupPlan cleanupPlan(String raw)throws IOException{
        final CleanupPlan plan;
        try{plan=new Gson().fromJson(raw,CleanupPlan.class);}catch(RuntimeException e){throw new IOException("清理记录无法读取，所有文件保留。",e);}
        if(plan==null||plan.pdfUri==null||(plan.pdfRecord==null&&plan.catalogRecord==null))throw new IOException("缺少完整的关联清理记录。");
        if(plan.pdfRecord!=null){BackupRecord record=backupRecord(plan.pdfRecord);if(record==null||!sameDocument(Uri.parse(plan.pdfUri),Uri.parse(record.outputUri)))throw new IOException("清理记录与目标PDF不一致。");}
        if(plan.catalogRecord!=null){EnjoyDirectorySync.Record record=new Gson().fromJson(plan.catalogRecord,EnjoyDirectorySync.Record.class);
            if(record==null||record.folder==null||record.pdfName==null)throw new IOException("目录清理记录与目标PDF不一致。");
            Uri folder=Uri.parse(record.folder),expected=DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getDocumentId(folder)+"/"+record.pdfName);
            if(!sameDocument(Uri.parse(plan.pdfUri),expected))throw new IOException("目录清理记录与目标PDF不一致。");}
        return plan;
    }
    private void verifyCleanupPlan(CleanupPlan plan,String raw,boolean resume)throws IOException{
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);String pending=prefs.getString("pendingCleanup",null);
        String automatic=enjoy.automaticCleanupRecord();boolean fromAutomatic=resume&&pending==null&&plan.pdfRecord==null&&automatic!=null&&automatic.equals(plan.catalogRecord);
        if(prefs.contains("pendingPdf")||enjoy.hasPending()||(resume?!raw.equals(pending)&&!fromAutomatic:pending!=null)
                ||(automatic!=null&&!automatic.equals(plan.catalogRecord)))throw new IOException("存在恢复任务或清理进度已变化，未执行删除。");
        if(plan.pdfRecord!=null&&!plan.pdfRecord.equals(prefs.getString("lastReplacement",null)))throw new IOException("PDF成功记录已变化，未执行清理。");
        if(plan.catalogRecord!=null)enjoy.verifyCleanupRecord(plan.catalogRecord,resume);
    }
    private BackupCleanup.Item pdfCleanupItem(String raw)throws IOException{
        BackupRecord record=backupRecord(raw);if(record==null)throw new IOException("PDF成功记录不完整。");
        File suppliedFolder=new File(getFilesDir(),"pdf-backups").getAbsoluteFile(),folder=new File(getFilesDir().getCanonicalFile(),"pdf-backups");
        File saved=new File(record.privateBackup).getAbsoluteFile(),file=saved.getCanonicalFile();
        if(!folder.equals(suppliedFolder.getCanonicalFile())||(!suppliedFolder.equals(saved.getParentFile())&&!folder.equals(saved.getParentFile()))
                ||!file.equals(new File(folder,saved.getName()))||!file.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf"))throw new IOException("应用内原PDF备份位置无法确认。");
        Uri parent=Uri.parse(record.folder);
        if(!record.outputName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ||!record.backupName.matches("pdf-bookmarks-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-backup\\.pdf"))throw new IOException("旧PDF备份名称无法确认。");
        // 文件夹直接列举并核对成功记录的实际URI，兼容文件来源的不透明文档ID。
        StagedDocuments documents=new StagedDocuments(this,parent);
        StagedReplacement.Document output=new StagedReplacement.Document(record.outputUri,record.outputName),old=new StagedReplacement.Document(record.backupUri,record.backupName);
        if(sameDocument(Uri.parse(output.uri),Uri.parse(old.uri)))throw new IOException("旧备份与新PDF指向同一文件，禁止删除。");
        return new BackupCleanup.Item(new BackupCleanup.Provider(){
            public List<StagedReplacement.Document> find(String name)throws IOException{return documents.find(name);}
            public InputStream openRead(StagedReplacement.Document document)throws IOException{return documents.openRead(document);}
            public boolean delete(StagedReplacement.Document document)throws IOException{
                if(!record.backupName.equals(document.name))throw new IOException("删除目标名称不一致，停止清理。");
                List<StagedReplacement.Document> current=documents.find(record.backupName);
                if(current.size()!=1||!record.backupName.equals(current.get(0).name)||!sameDocument(Uri.parse(document.uri),Uri.parse(current.get(0).uri)))throw new IOException("旧备份的名称或身份已变化，停止清理。");
                Uri uri=Uri.parse(current.get(0).uri);
                if(sameDocument(uri,Uri.parse(record.outputUri))||!sameDocument(uri,Uri.parse(record.backupUri)))throw new IOException("删除目标身份不一致，停止清理。");
                if(!supports(uri,DocumentsContract.Document.FLAG_SUPPORTS_DELETE))throw new IOException("旧备份未获删除授权或来源不支持删除。");
                return DocumentsContract.deleteDocument(getContentResolver(),uri);
            }
        },output,old,record.outputHash,record.originalHash,file);
    }
    private List<BackupCleanup.Item> cleanupItems(CleanupPlan plan,boolean resume)throws IOException{
        List<BackupCleanup.Item> items=new ArrayList<>();
        if(plan.pdfRecord!=null)items.add(pdfCleanupItem(plan.pdfRecord));
        if(plan.catalogRecord!=null)items.add(enjoy.cleanupItem(plan.catalogRecord,resume));
        return items;
    }
    private CleanupPlan eligibleCleanupPlan(CleanupPlan candidate)throws IOException{
        CleanupPlan plan=new CleanupPlan();plan.pdfUri=candidate.pdfUri;StringBuilder kept=new StringBuilder();
        if(candidate.pdfRecord!=null){CleanupPlan group=new CleanupPlan();group.pdfUri=candidate.pdfUri;group.pdfRecord=candidate.pdfRecord;
            try{verifyCleanupPlan(group,new Gson().toJson(group),false);BackupCleanup.verifyAll(cleanupItems(group,false),false);plan.pdfRecord=group.pdfRecord;}
            catch(IOException e){BackupRecord record=backupRecord(candidate.pdfRecord);kept.append("旧PDF组保留：").append(cleanupLocation(record.backupName,record.backupUri)).append("\n").append(record.privateBackup).append("\n原因：").append(message(e)).append("\n");}}
        if(candidate.catalogRecord!=null){CleanupPlan group=new CleanupPlan();group.pdfUri=candidate.pdfUri;group.catalogRecord=candidate.catalogRecord;
            try{verifyCleanupPlan(group,new Gson().toJson(group),false);BackupCleanup.verifyAll(cleanupItems(group,false),false);plan.catalogRecord=group.catalogRecord;}
            catch(IOException e){EnjoyDirectorySync.Record record=new Gson().fromJson(candidate.catalogRecord,EnjoyDirectorySync.Record.class);kept.append("旧目录组保留：").append(cleanupLocation(record.backupName,record.backupUri)).append("\n").append(record.privateBackup).append("\n原因：").append(message(e)).append("\n");}}
        if(plan.pdfRecord==null&&plan.catalogRecord==null)throw new IOException("没有通过核验的旧文件；全部保留。\n"+kept);
        plan.retainedNotice=kept.toString();return plan;
    }
    private void confirmCleanup(){
        if(busy)return;
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);String pending=pendingCleanupPlan(),raw;
        if(pending!=null)raw=pending;
        else{
            CleanupPlan plan=new CleanupPlan();if(pdfUri==null)return;plan.pdfUri=pdfUri.toString();
            String pdfRaw=prefs.getString("lastReplacement",null);BackupRecord pdfRecord=backupRecord(pdfRaw);
            if(pdfRecord!=null&&sameDocument(pdfUri,Uri.parse(pdfRecord.outputUri)))plan.pdfRecord=pdfRaw;
            EnjoyDirectorySync.Record catalog=enjoy.recordFor(pdfUri);if(catalog!=null)plan.catalogRecord=catalog.snapshot;
            raw=new Gson().toJson(plan);
        }
        boolean resume=pending!=null;setBusy(true,"正在核验整批旧文件并整理清理清单，尚未删除…");
        worker.execute(()->{synchronized(replacementLock){try{
            CleanupPlan candidate=cleanupPlan(raw),plan=resume?candidate:eligibleCleanupPlan(candidate);String shownRaw=resume?raw:new Gson().toJson(plan);
            verifyCleanupPlan(plan,shownRaw,resume);List<BackupCleanup.Item> items=cleanupItems(plan,resume);BackupCleanup.verifyAll(items,resume);verifyCleanupPlan(plan,shownRaw,resume);
            StringBuilder list=new StringBuilder("请先确认新PDF、目录、跳转与笔迹正常，并保存、停止享做。\n\n将删除 · "+items.size()+" 组关联旧件及其应用内恢复副本\n");
            for(BackupCleanup.Item item:items)list.append("\n• ").append(cleanupLocation(item.backup.name,item.backup.uri)).append("\n• ").append(item.privateBackup.getAbsolutePath()).append("\n");
            list.append("\n本次保留\n当前PDF：\n").append(cleanupLocation(nameHint(Uri.parse(plan.pdfUri)),plan.pdfUri)).append("\n活动目录/temp、笔迹、其他配置、来源JSON及无关联备份。\n\n恢复影响：上述旧件和应用内恢复备份删除后不能恢复。可取消并先导出备份；不会扫描其他文件。");
            if(plan.retainedNotice!=null&&!plan.retainedNotice.isEmpty())list.append("\n\n以下旧件未通过核验，本次保留，不删除：\n").append(plan.retainedNotice);
            if(resume)list.append("\n\n这是已确认清理的重试，部分旧件可能已删除；只继续核验和清理同一清单。");
            onUi(()->{setBusy(false,"清单已核验，等待确认；尚未继续删除。");ScrollView scroll=new ScrollView(this);TextView content=text(list.toString(),15);content.setPadding(dp(20),dp(12),dp(20),dp(12));scroll.addView(content);
                AlertDialog.Builder dialog=dialogBuilder().setTitle("清理旧文件与备份").setView(scroll).setNegativeButton("取消",null).setPositiveButton("清理待删除清单",(d,w)->runCleanup(shownRaw,resume));
                if(resume)dialog.setNeutralButton("保留剩余文件，结束清理",(d,w)->stopCleanup(shownRaw));AlertDialog shown=dialog.show();appearance.styleDialog(shown);shown.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(appearance.error);});
        }catch(Exception e){onUi(()->cleanupFailure(e,"清理核验未通过，没有继续删除；请查看具体提示。"));}}});
    }
    private void runCleanup(String raw,boolean resume){
        outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.RUNNING,"仅处理已展示并确认的本次清单。");setBusy(true,"正在核验并清理列明旧件，内部恢复备份最后删除…");setStage(R.string.ui_stage_cleanup);
        worker.execute(()->{synchronized(replacementLock){try{
            CleanupPlan plan=cleanupPlan(raw);verifyCleanupPlan(plan,raw,resume);List<BackupCleanup.Item> items=cleanupItems(plan,resume);BackupCleanup.verifyAll(items,resume);
            SharedPreferences prefs=getPreferences(MODE_PRIVATE);
            if(!prefs.edit().putString("pendingCleanup",raw).commit())throw new IOException("无法保存清理进度，未开始删除。");
            BackupCleanup.removeAll(items,resume,()->verifyCleanupPlan(plan,raw,true));
            verifyCleanupPlan(plan,raw,true);
            if(plan.catalogRecord!=null)enjoy.finishCleanup(plan.catalogRecord);
            verifyCleanupPlan(plan,raw,true);
            String savedBackup=prefs.getString("lastBackup",null),savedName=prefs.getString("lastBackupName",null);
            BackupRecord pdfRecord=backupRecord(plan.pdfRecord);boolean removeBackup=pdfRecord!=null&&savedBackup!=null&&new File(savedBackup).getCanonicalFile().equals(new File(pdfRecord.privateBackup).getCanonicalFile());
            SharedPreferences.Editor editor=prefs.edit().remove("pendingCleanup");if(pdfRecord!=null)editor.remove("lastReplacement");if(removeBackup)editor.remove("lastBackup").remove("lastBackupName");
            if(!editor.commit()){
                SharedPreferences.Editor restore=prefs.edit().putString("pendingCleanup",raw);if(pdfRecord!=null)restore.putString("lastReplacement",plan.pdfRecord);
                if(removeBackup){restore.putString("lastBackup",savedBackup);if(savedName!=null)restore.putString("lastBackupName",savedName);}restore.commit();
                throw new IOException("旧件已核验删除，但进度未保存；可按同一清单重试完成。");
            }
            outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.SUCCESS,"已完成本次确认清单；未通过核验的旧件保留，当前PDF、享做目录及笔迹保留。");
            onUi(()->{setBusy(false,"待删除清单内的旧文件与关联恢复备份已全部清理。当前PDF、享做目录和笔迹保留；这些备份已不能用于恢复。"+(plan.retainedNotice==null||plan.retainedNotice.isEmpty()?"":"\n以下旧件未通过核验并保留：\n"+plan.retainedNotice));finishCatalogDraftAfterCleanup(plan.pdfUri);});
        }catch(Exception e){onUi(()->cleanupFailure(e,"清理尚未全部完成，已删除的旧件不会重复删除。进度与剩余文件保留，请按提示重试；当前PDF和笔迹受保护。"));}}});
    }
    private void cleanupFailure(Exception failure,String notice){
        outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.FAILED,notice+"\n"+message(failure));setBusy(false,notice);AlertDialog.Builder dialog=dialogBuilder().setTitle("清理未完成").setMessage(message(failure)).setNegativeButton("返回",null);
        String pending=pendingCleanupPlan();
        if(pending!=null)dialog.setPositiveButton("保留剩余文件，结束清理",(d,w)->stopCleanup(pending));dialog.show();
    }
    private void stopCleanup(String raw){
        setBusy(true,"正在保存清理记录，不删除任何剩余文件…");worker.execute(()->{synchronized(replacementLock){try{
            SharedPreferences prefs=getPreferences(MODE_PRIVATE);if(!raw.equals(pendingCleanupPlan()))throw new IOException("清理进度已变化，请重新核对。");
            CleanupPlan plan=cleanupPlan(raw);
            File folder=new File(getFilesDir(),"cleanup-records");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法保留清理记录。");
            File record=new File(folder,UUID.randomUUID()+".json");byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
            try(FileOutputStream output=new FileOutputStream(record)){output.write(bytes);output.getFD().sync();}
            if(!digest(bytes).equals(hashFile(record)))throw new IOException("清理记录保存校验失败，进度保留。");
            String saved=prefs.getString("pendingCleanup",null);
            if(saved!=null&&!prefs.edit().remove("pendingCleanup").commit()){prefs.edit().putString("pendingCleanup",saved).commit();throw new IOException("进度未成功更新，可重新结束清理。");}
            if(plan.catalogRecord!=null&&plan.catalogRecord.equals(enjoy.automaticCleanupRecord()))enjoy.stopAutomaticCleanup(plan.catalogRecord);
            outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.RETAINED,"已结束清理；剩余文件与完整关联记录保留，已删除旧件不会恢复。");
            onUi(()->{setBusy(false,"已结束本次清理，剩余文件及完整关联记录保留；已删除的旧件不会恢复。可继续其他任务。未通过校验的旧件仍不会删除。");finishCatalogDraftAfterCleanup(plan.pdfUri);});
        }catch(Exception e){outcomeSet(TaskOutcome.Part.CLEANUP,TaskOutcome.Status.FAILED,message(e));onUi(()->{setBusy(false,"未能结束清理，进度与剩余文件保留。");error(message(e));});}}});
    }
    private void finishCatalogDraftAfterCleanup(String cleanedPdf){
        if(catalogRetry!=null&&sameDocument(pdfUri,catalogRetry.pdf)&&sameDocument(catalogRetry.pdf,Uri.parse(cleanedPdf))
                &&!enjoy.hasPending()&&!hasCleanupTask()&&outcome.entries().stream().anyMatch(entry->entry.part==TaskOutcome.Part.CATALOG&&entry.status==TaskOutcome.Status.SUCCESS)){
            catalogRetry=null;completeDraft();
        }
    }
    private void discardCleanupFor(Uri restored,String name)throws IOException{
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);BackupRecord record=backupRecord(prefs.getString("lastReplacement",null));
        if(record==null||!name.equals(record.outputName))return;
        Uri folder=PathDocuments.folderForDocument(this,restored);
        if(sameDocument(folder,Uri.parse(record.folder))&&!prefs.edit().remove("lastReplacement").commit())throw new IOException("原件已恢复，但旧备份清理记录未更新，请重新核对。");
    }
    /** 此方法仅在输出重读校验成功后由工作线程调用。 */

    private void finishSave(Uri destination,String success,boolean deleteAfter){finishSave(destination,success,deleteAfter,true);}

    private void finishSave(Uri destination,String success,boolean deleteAfter,boolean pdfChanged){

        boolean deleted=false;String note="";

        if(deleteAfter&&jsonUri!=null){

            setStage(R.string.ui_stage_json);outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RUNNING,"");

            try{

                if(Thread.currentThread().isInterrupted())throw new IOException("任务已中断，JSON保留。");

                if(sameDocument(destination,jsonUri))throw new IOException("输出与JSON指向同一文档。");

                synchronized(replacementLock){

                JsonCleanup cleanup=new JsonCleanup();cleanup.taskId=UUID.randomUUID().toString();cleanup.sourceUri=jsonUri.toString();cleanup.name=jsonName;cleanup.hash=jsonHash;

                cleanup.workingId=sharedJson!=null&&sharedJson.sourceUri.equals(cleanup.sourceUri)?sharedJson.id:null;cleanup.copyDeleted=cleanup.workingId==null;

                List<JsonCleanup> pending=jsonCleanups();pending.add(cleanup);saveJsonCleanups(pending);

                String result=performJsonCleanup(cleanup,pending);

                deleted=cleanup.sourceDeleted||(cleanup.workingId!=null&&cleanup.copyDeleted);



                note="\n"+result;outcomeSet(TaskOutcome.Part.JSON,cleanup.sourceDeleted&&cleanup.copyDeleted?TaskOutcome.Status.SUCCESS:cleanup.copyDeleted?TaskOutcome.Status.RETAINED:TaskOutcome.Status.FAILED,result);

                }

            }catch(Exception e){note="\nPDF已保存，JSON清理未全部完成："+message(e);outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.FAILED,message(e));}

        }else outcomeSet(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,getString(R.string.ui_json_kept));

        boolean removed=deleted;String result=success+note;String savedName=displayName(destination);

        onUi(()->{sourceNeedsReload=pdfChanged&&sameDocument(destination,pdfUri);exportedUri=destination;exportedName=savedName;confirmed.setChecked(false);if(removed){sharedJson=null;jsonUri=null;jsonName="";jsonLabel.setText(R.string.import_json_cleared);}setBusy(false,result);if(catalogRetry!=null||enjoy.hasPending()||hasCleanupTask()){draftCompleted=false;scheduleDraft(true);}else completeDraft();focusResult();});

    }

    private String jsonDeleteNotice(){

        String text="源JSON："+jsonName+"\n"+jsonUri;

        if(sharedJson!=null)text+="\n应用输入副本："+new File(getFilesDir(),"imports/json/"+sharedJson.id+".json").getAbsolutePath();

        return text+"\n来源只给读取权时，源文件需在QQ或文件管理器删除，处理结果不受影响。";

    }

    private List<JsonCleanup> jsonCleanups()throws IOException{

        String raw=getPreferences(MODE_PRIVATE).getString("jsonCleanup",null);if(raw==null)return new ArrayList<>();

        try{JsonCleanup[] entries=new Gson().fromJson(raw,JsonCleanup[].class);if(entries==null)throw new IllegalArgumentException();List<JsonCleanup> list=new ArrayList<>();

            for(JsonCleanup entry:entries){if(entry==null||entry.taskId==null||!UUID.fromString(entry.taskId).toString().equals(entry.taskId))throw new IllegalArgumentException();new JsonInputRef(entry.workingId==null?entry.taskId:entry.workingId,entry.sourceUri,entry.name,entry.hash);list.add(entry);}return list;

        }catch(RuntimeException e){throw new IOException("JSON清理记录尚未核实，未自动删除文件。",e);}

    }

    private void saveJsonCleanups(List<JsonCleanup> entries)throws IOException{

        SharedPreferences.Editor edit=getPreferences(MODE_PRIVATE).edit();if(entries.isEmpty())edit.remove("jsonCleanup");else edit.putString("jsonCleanup",new Gson().toJson(entries));

        if(!edit.commit())throw new IOException("JSON清理进度未保存，未继续删除。");

    }

    private String performJsonCleanup(JsonCleanup task,List<JsonCleanup> pending)throws Exception{

        Uri source=Uri.parse(task.sourceUri);task.reason="";

        if(!task.sourceDeleted){

            try{

                if(checkUriPermission(source,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("来源未授予删除权限");

                boolean document=DocumentsContract.isDocumentUri(this,source);

                if(!document&&!standardFileProvider(source))throw new IOException("尚未核实这个来源的单文件删除接口");

                if(!task.hash.equals(digest(readSmall(source))))throw new IOException("源JSON内容已变化，不能删除");

                if(document&&!supports(source,DocumentsContract.Document.FLAG_SUPPORTS_DELETE))throw new IOException("来源不支持删除这个JSON");

                boolean removed=document?DocumentsContract.deleteDocument(getContentResolver(),source):getContentResolver().delete(source,null,null)>0;

                if(!removed)throw new IOException("来源没有确认删除");task.sourceDeleted=true;

            }catch(Exception e){task.reason="源JSON未删除："+(e instanceof SecurityException?"来源没有提供删除权限":message(e))+"。请在QQ或华为文件管理器处理；无需授权Android/data。";}

            // A failed durable commit stops before any further file deletion.

            persistJsonCleanupProgress(task,pending);

        }

        if(!task.copyDeleted){

            try{jsonInputs.deleteOwned(new JsonInputRef(task.workingId,task.sourceUri,task.name,task.hash));task.copyDeleted=true;}

            catch(Exception e){task.reason+=(task.reason.isEmpty()?"":"\n")+"应用内副本未清理："+message(e);}

            persistJsonCleanupProgress(task,pending);

        }

        if(task.sourceDeleted&&task.copyDeleted){pending.remove(task);persistJsonCleanupProgress(task,pending);return "本次JSON源文件及导入副本已清理。";}

        persistJsonCleanupProgress(task,pending);return (task.copyDeleted?"本次应用内JSON副本已清理。\n":"")+task.reason;

    }

    private boolean standardFileProvider(Uri source){

        android.content.pm.ProviderInfo provider=getPackageManager().resolveContentProvider(source.getAuthority(),0);

        return provider!=null&&source.getPathSegments().size()>=2&&("androidx.core.content.FileProvider".equals(provider.name)||"android.support.v4.content.FileProvider".equals(provider.name));

    }

    private void persistJsonCleanupProgress(JsonCleanup task,List<JsonCleanup> pending)throws IOException{

        SharedPreferences prefs=getPreferences(MODE_PRIVATE);SharedPreferences.Editor edit=prefs.edit();

        if(pending.isEmpty())edit.remove("jsonCleanup");else edit.putString("jsonCleanup",new Gson().toJson(pending));

        boolean current=task.sourceUri.equals(prefs.getString("json",null))&&task.hash.equals(prefs.getString("selectedJsonHash",null))

            &&(task.workingId==null?!prefs.contains("selectedJsonId"):task.workingId.equals(prefs.getString("selectedJsonId",null)));

        if(current&&(task.sourceDeleted||task.workingId!=null&&task.copyDeleted))edit.remove("json").remove("jsonName").remove("selectedJsonId").remove("selectedJsonHash");

        if(!edit.commit())throw new IOException("JSON清理进度未保存，已停止后续删除。");

    }

    private void refreshJsonSelectionAfterCleanup(){

        SharedPreferences prefs=getPreferences(MODE_PRIVATE);

        if(jsonUri!=null&&!prefs.contains("json")){sharedJson=null;jsonUri=null;jsonName="";jsonLabel.setText(R.string.import_json_cleared);}

    }

    private void showJsonCleanup(){

        if(busy||shareLoading)return;

        try{List<JsonCleanup> pending=jsonCleanups();if(pending.isEmpty()){showTextDetails("JSON清理情况","没有待处理的JSON源文件或输入副本。");return;}

            String[] names=pending.stream().map(task->task.name+(task.copyDeleted?" · 源文件待处理":" · 副本待清理")).toArray(String[]::new);

            showWorkflow(dialogBuilder().setTitle("JSON清理情况").setItems(names,(dialog,index)->showJsonCleanup(pending.get(index))).setNegativeButton("返回",null).create());

        }catch(IOException e){error(message(e));}

    }

    private void showJsonCleanup(JsonCleanup selected){

        String details=selected.name+"\n源地址："+selected.sourceUri+"\n应用副本："+(selected.copyDeleted?"已清理":new File(getFilesDir(),"imports/json/"+selected.workingId+".json").getAbsolutePath())+"\n"+(selected.reason==null?"":selected.reason);

        AlertDialog.Builder dialog=dialogBuilder().setTitle("仅处理这份JSON").setMessage(details+"\n\nPDF和目录不会再次生成或替换。无法访问QQ源文件时，请在QQ或华为文件管理器手动处理。").setNegativeButton("返回",null)

            .setNeutralButton("已手动处理／保留源文件",(d,w)->{setBusy(true,"正在保存你的处理选择…");worker.execute(()->{synchronized(replacementLock){try{List<JsonCleanup> pending=jsonCleanups();pending.removeIf(task->task.taskId.equals(selected.taskId)&&task.copyDeleted);saveJsonCleanups(pending);onUi(()->setBusy(false,selected.copyDeleted?"已记录你的处理选择，没有再次操作源文件。":"应用副本仍待清理，记录保留。"));}catch(IOException e){onUi(()->{setBusy(false,"处理记录保留。");error(message(e));});}}});});

        Uri source=Uri.parse(selected.sourceUri);boolean canDelete=checkUriPermission(source,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED;

        if(!selected.copyDeleted||canDelete)dialog.setPositiveButton("重试本次清理",(d,w)->{setBusy(true,"正在核对这份JSON的清理状态…");worker.execute(()->{synchronized(replacementLock){try{List<JsonCleanup> pending=jsonCleanups();JsonCleanup task=pending.stream().filter(item->item.taskId.equals(selected.taskId)).findFirst().orElseThrow(()->new IOException("记录已处理"));String result=performJsonCleanup(task,pending);onUi(()->{refreshJsonSelectionAfterCleanup();setBusy(false,result);showJsonCleanup();});}catch(Exception e){onUi(()->{setBusy(false,"JSON清理尚未完成，PDF和目录保留。");error(message(e));});}}});});

        showWorkflow(dialog.create());

    }

    private void clearRecovery()throws IOException{
        if(!getPreferences(MODE_PRIVATE).edit().remove("pendingPdf").remove("pendingBackup").remove("pendingOriginalHash").remove("pendingOutputHash").remove("pendingName").remove("pendingFolder").remove("pendingJournal").commit())throw new IOException("PDF核验完成，但恢复记录未成功清除；JSON保留，请重新打开程序核对。");
    }
    private void checkRecovery(){
        if(hasCleanupTask()&&!enjoy.hasPending()){status.setText("上次旧文件清理尚未全部完成。请用第3节“继续清理旧文件与备份”核对同一清单并重试；当前PDF、目录及笔迹保留。其他写入暂时停用，避免覆盖清理记录。");updateButtons();return;}
        if(enjoy.hasPending()){status.setText("上次享做目录同步尚待恢复。请保存并强行停止享做，再使用第3节目录恢复按钮；PDF和笔迹不会因此改写。请勿卸载或清除数据。");updateButtons();return;}
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);if(!prefs.contains("pendingPdf"))return;
        status.setText("上次替换尚待恢复。第3节可恢复原PDF或导出备份；原文件已消失时可从备份恢复到原文件夹。请勿卸载或清除数据。");updateButtons();
    }
    private void confirmRecovery(){
        String name=getPreferences(MODE_PRIVATE).getString("pendingName","原PDF");
        dialogBuilder().setTitle("恢复原PDF").setMessage("恢复同名原件："+name+"。使用保留的备份，JSON不删除。请先关闭享做中的该笔记。")
            .setNegativeButton("取消",null).setPositiveButton("恢复",(d,w)->{
                SharedPreferences prefs=getPreferences(MODE_PRIVATE);String saved=prefs.getString("pendingPdf",null);if(saved==null)return;
                File backup=new File(prefs.getString("pendingBackup",""));String expected=prefs.getString("pendingOriginalHash","");
                setBusy(true,"正在恢复并核验原PDF…");worker.execute(()->{synchronized(replacementLock){try{
                    if(enjoy.hasPending()||hasCleanupTask())throw new IOException("目录同步或旧件清理尚待完成，不能同时恢复PDF。");
                    if(!saved.equals(prefs.getString("pendingPdf",null))||!backup.getAbsolutePath().equals(prefs.getString("pendingBackup","")))throw new IOException("恢复记录已更新，请重新核对。");
                    if(!expected.equals(hashFile(backup)))throw new IOException("原件备份校验失败，未执行恢复。");
                    String raw=prefs.getString("pendingJournal",null);Uri restored;
                    if(raw!=null){
                        StagedDocuments documents=new StagedDocuments(this,Uri.parse(prefs.getString("pendingFolder","")));
                        StagedReplacement.Journal journal=new Gson().fromJson(raw,StagedReplacement.Journal.class);
                        StagedReplacement.Result result=StagedReplacement.recover(documents,journal,entry->saveJournal(documents.folder,entry));restored=Uri.parse(result.document.uri);
                    }else{
                        StagedDocuments documents=StagedDocuments.forMissingDocument(this,Uri.parse(saved));restored=restoreMissingDocument(documents,backup,prefs.getString("pendingName","原PDF.pdf"),expected);
                    }
                    if(!getPreferences(MODE_PRIVATE).edit().putString("pdf",restored.toString()).putString("pdfName",name).commit())throw new IOException("原件已恢复，但文件标识未保存；恢复记录保留，请重新核对。");
                    discardCleanupFor(restored,name);clearRecovery();onUi(()->{pdfUri=restored;pdfName=name;pdfLabel.setText(inputLabel(name,restored));invalidatePlan();setBusy(false,"原PDF已恢复并校验，JSON保留。请重新预览后继续。");});
                }catch(Exception e){onUi(()->{setBusy(false,"恢复未完成，备份和恢复记录仍保留。");error(message(e));});}}});
            }).show();
    }
    private void chooseRestoreFolder(){
        String name=getPreferences(MODE_PRIVATE).getString("lastBackupName","原PDF.pdf");
        dialogBuilder().setTitle("恢复消失的原 PDF").setMessage("请先关闭享做笔记，然后选择原PDF所在文件夹。将恢复为原名：\n"+name+"\n\n不会覆盖同名的其他文件。备份仍保留；取消可返回。")
            .setNegativeButton("取消",null).setPositiveButton("选择原文件夹",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                try{if(pdfUri!=null)intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,PathDocuments.initialFolder(pdfUri.toString()));}catch(Exception ignored){}
                launchPicker(intent,RESTORE_FOLDER);
            }).show();
    }
    private void restoreBackupIntoFolder(Uri folder){
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);File backup=new File(prefs.getString("lastBackup",""));String name=prefs.getString("lastBackupName","原PDF.pdf");
        setBusy(true,"正在原文件夹恢复同名原件，并核验文件…");
        worker.execute(()->{synchronized(replacementLock){try{
            if(enjoy.hasPending()||hasCleanupTask())throw new IOException("目录同步或旧件清理尚待完成，不能同时恢复PDF。");
            StagedDocuments documents=new StagedDocuments(this,DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder)));
            Uri restored=restoreMissingDocument(documents,backup,name,hashFile(backup));
            String pending=prefs.getString("pendingName",null);boolean completesPending=pending!=null&&pending.equals(name)&&prefs.getString("pendingJournal",null)==null&&isPendingFolder(documents.folder)&&prefs.getString("pendingOriginalHash","").equals(hashFile(backup));
            if(!prefs.edit().putString("pdf",restored.toString()).putString("pdfName",name).commit())throw new IOException("原件已恢复，但文件选择记录未保存，请重新选择PDF。");
            // 只有原路径、原名、原内容均核验且新URI持久化后，才清除旧版恢复记录。
            discardCleanupFor(restored,name);if(completesPending)clearRecovery();
            onUi(()->{pdfUri=restored;pdfName=name;pdfLabel.setText(inputLabel(name,restored));invalidatePlan();setBusy(false,"已在所选文件夹恢复并核验："+name+"。备份保留，请在享做重新打开核对。");});
        }catch(Exception e){onUi(()->{setBusy(false,"恢复未完成，应用内原件备份保留。可取消后导出备份。");error(message(e));});}}});
    }
    private boolean isPendingFolder(Uri chosen){
        try{String saved=getPreferences(MODE_PRIVATE).getString("pendingPdf",null);if(saved==null)return false;Uri expected=PathDocuments.folderForDocument(this,Uri.parse(saved));
            return Objects.equals(chosen.getAuthority(),expected.getAuthority())&&DocumentsContract.getDocumentId(chosen).equals(DocumentsContract.getDocumentId(expected));
        }catch(Exception e){return false;}
    }
    private Uri restoreMissingDocument(StagedDocuments documents,File backup,String name,String expected)throws Exception {
        if(!expected.equals(hashFile(backup)))throw new IOException("备份校验失败，未恢复。");
        List<StagedReplacement.Document> existing=documents.find(name);
        if(existing.size()==1&&expected.equals(hashUri(Uri.parse(existing.get(0).uri))))return Uri.parse(existing.get(0).uri);
        if(!existing.isEmpty())throw new IOException("文件夹已有同名文件，未覆盖。请核对原文件夹，或先导出备份。");
        String stagingName="PDF恢复中-"+UUID.randomUUID()+".pdf";
        StagedReplacement.Document stage=documents.create(stagingName);
        try(InputStream in=new FileInputStream(backup);OutputStream out=documents.openWriteNew(stage)){transfer(in,out);}
        if(!expected.equals(hashUri(Uri.parse(stage.uri))))throw new IOException("恢复副本校验失败，原备份保留。");
        if(!documents.find(name).isEmpty())throw new IOException("恢复时出现同名文件，未覆盖；已核验的恢复副本保留。" );
        try{documents.rename(stage,name);}catch(Exception e){
            List<StagedReplacement.Document> renamed=documents.find(name);
            if(renamed.size()!=1||!expected.equals(hashUri(Uri.parse(renamed.get(0).uri))))throw e;
        }
        List<StagedReplacement.Document> result=documents.find(name);
        if(result.size()!=1||!expected.equals(hashUri(Uri.parse(result.get(0).uri))))throw new IOException("原文件名下尚无唯一可核验PDF；恢复副本与原备份保留。");
        return Uri.parse(result.get(0).uri);
    }
    private void saveBackup(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE,getPreferences(MODE_PRIVATE).getString("lastBackupName","原PDF")+".原件备份.pdf");launchPicker(intent,SAVE_BACKUP);
    }
    private void exportBackupTo(Uri destination){
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);String pending=prefs.getString("pendingPdf",null);
        if(sameDocument(destination,pdfUri)||sameDocument(destination,jsonUri)||(pending!=null&&sameDocument(destination,Uri.parse(pending)))){error("备份必须另存，不能覆盖输入文件。");return;}
        File backup=new File(prefs.getString("lastBackup",""));setBusy(true,"正在导出原PDF备份…");
        worker.execute(()->{try{
            String expected=hashFile(backup);try(InputStream in=new FileInputStream(backup);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("无法保存备份。");transfer(in,out);}
            if(!expected.equals(hashUri(destination)))throw new IOException("导出备份校验不一致。");onUi(()->setBusy(false,"原PDF备份已导出并校验。"));
        }catch(Exception e){onUi(()->{setBusy(false,"备份仍保留在程序中，导出未完成。");error(message(e));});}});
    }
    private void openOutput(){
        try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(exportedUri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(ActivityNotFoundException e){error("请从文件管理打开保存的PDF，或在享做中导入。");}
    }
    private boolean sameDocument(Uri a,Uri b){
        if(a==null||b==null)return false;if(a.equals(b))return true;
        try{return Objects.equals(a.getAuthority(),b.getAuthority())
            &&DocumentsContract.isDocumentUri(this,a)&&DocumentsContract.isDocumentUri(this,b)
            &&DocumentsContract.getDocumentId(a).equals(DocumentsContract.getDocumentId(b));}
        catch(IllegalArgumentException e){return false;}
    }
    private void showLicenses(){
        try{
            String[] names=getAssets().list("licenses");if(names==null||names.length==0)throw new IOException("许可文件未找到。");
            dialogBuilder().setTitle("许可与开源说明").setItems(names,(dialog,index)->{
                try(InputStream input=getAssets().open("licenses/"+names[index]);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    transfer(input,bytes);ScrollView scroll=new ScrollView(this);TextView body=text(new String(bytes.toByteArray(),StandardCharsets.UTF_8),14);body.setTextIsSelectable(true);body.setPadding(dp(16),dp(12),dp(16),dp(12));scroll.addView(body);
                    dialogBuilder().setTitle(names[index]).setView(scroll).setPositiveButton("关闭",null).show();
                }catch(IOException e){error(message(e));}
            }).setNegativeButton("关闭",null).show();
        }catch(IOException e){error(message(e));}
    }
    private InputStream readInput(Uri uri,ReadJob job)throws IOException {
        if(job==null){InputStream input=getContentResolver().openInputStream(uri);if(input==null)throw new IOException("无法读取文件。");return WorkMetrics.providerRead(input);}
        job.check();android.content.res.AssetFileDescriptor fd=getContentResolver().openAssetFileDescriptor(uri,"r",job.signal);
        if(fd==null)throw new IOException("文件来源未能打开文档。");
        InputStream input;try{input=fd.createInputStream();}catch(IOException e){fd.close();throw e;}
        input=WorkMetrics.providerRead(input);job.attach(input);return input;
    }
    private String copySource(Uri uri,File target,ReadJob job)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try(InputStream input=readInput(uri,job);FileOutputStream output=new FileOutputStream(target)){
            if(input==null)throw new IOException("无法读取PDF，请重新选择文件。");byte[] buffer=new byte[1024*1024];int n;
            while((n=input.read(buffer))!=-1){job.check();output.write(buffer,0,n);sha.update(buffer,0,n);}output.getFD().sync();
        }finally{job.detach();}return hex(sha.digest());
    }
    private byte[] readSmall(Uri uri)throws IOException{return readSmall(uri,null);}
    private byte[] readJsonInput(Uri uri)throws IOException{return readJsonInput(uri,null);}
    private byte[] readJsonInput(Uri uri,ReadJob job)throws IOException{
        if(job!=null)job.check();JsonInputRef ref=sharedJson;
        if((ref==null||!ref.sourceUri.equals(uri.toString()))&&restoreDraft!=null&&restoreDraft.jsonUri.equals(uri.toString())){
            ref=jsonInputs.find(uri.toString(),restoreDraft.jsonSha256);
        }
        if(ref!=null&&ref.sourceUri.equals(uri.toString())){byte[] bytes=jsonInputs.read(ref);if(job!=null){job.check();job.jsonRef=ref;}return bytes;}
        return readSmall(uri,job);
    }
    private byte[] readSmall(Uri uri,ReadJob job)throws IOException{
        try(InputStream input=readInput(uri,job);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(input==null)throw new IOException("无法读取JSON，请重新选择文件。");byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){if(job!=null)job.check();if(output.size()+n>4*1024*1024)throw new IOException("目录JSON超过4MB，请确认所选文件。");output.write(buffer,0,n);}return output.toByteArray();
        }finally{if(job!=null)job.detach();}
    }
    private static void transfer(InputStream input,OutputStream output)throws IOException{byte[] buffer=new byte[1024*1024];int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("保存已取消。");output.write(buffer,0,n);}}
    private static String transferDigest(InputStream input,OutputStream output)throws IOException{

        try{MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[1024*1024];int count;

            while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("保存已取消。");output.write(buffer,0,count);sha.update(buffer,0,count);}return hex(sha.digest());

        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}

    }

    private String hashUri(Uri uri)throws Exception{try(InputStream input=WorkMetrics.providerRead(getContentResolver().openInputStream(uri))){if(input==null)throw new IOException("无法核对文件。");return hash(input);}}
    private static String hashFile(File file)throws Exception{try(InputStream input=new FileInputStream(file)){return hash(input);}}
    private static String hash(InputStream input)throws Exception{MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[1024*1024];int n;while((n=input.read(buffer))!=-1)sha.update(buffer,0,n);return hex(sha.digest());}
    private static String digest(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private AlertDialog.Builder dialogBuilder(){
        return new AlertDialog.Builder(appearance.dialogContext(this)){
            @Override public AlertDialog.Builder setItems(CharSequence[] items,DialogInterface.OnClickListener listener){return setAdapter(appearance.dialogListAdapter(getContext(),items),listener);}
            @Override public AlertDialog.Builder setItems(int items,DialogInterface.OnClickListener listener){return setItems(getResources().getTextArray(items),listener);}
            @Override public AlertDialog create(){AlertDialog dialog=super.create();dialog.setOnShowListener(ignored->appearance.styleDialog(dialog));return dialog;}
        };
    }
    private void setSurface(View view,String kind,int left,int top,int right,int bottom){
        view.setTag(R.id.ui_surface_kind,kind);view.setTag(R.id.ui_padding_dp,new int[]{left,top,right,bottom});styleSurface(view);
    }
    private void styleSurface(View view){
        Object kind=view.getTag(R.id.ui_surface_kind);if(kind==null)return;
        String name=kind.toString();int color="page".equals(name)?appearance.page:"tile".equals(name)?appearance.surfaceVariant:"status".equals(name)?appearance.statusBg:appearance.surface;
        GradientDrawable surface=background(color,"card".equals(name)?16:"page".equals(name)?0:10);
        if("card".equals(name))surface.setStroke(dp(1),(appearance.outline&0x00ffffff)|0x59000000);
        view.setBackground(surface);
        int[] padding=(int[])view.getTag(R.id.ui_padding_dp);if(padding!=null&&view!=shellView)view.setPadding(dp(appearance.spacingDp(padding[0])),dp(appearance.spacingDp(padding[1])),dp(appearance.spacingDp(padding[2])),dp(appearance.spacingDp(padding[3])));
        if("status".equals(name)&&view instanceof TextView)((TextView)view).setTextColor(appearance.statusText);
    }
    private void applyAppearanceToTree(View view){
        if(view instanceof ReviewWorkspace){review.applyAppearance(appearance);return;}
        if(view instanceof CompoundButton)appearance.styleChoice((CompoundButton)view);
        else if(view instanceof Button)appearance.styleButton((Button)view,Boolean.TRUE.equals(view.getTag(R.id.ui_primary_action)));
        else if(view instanceof EditText)appearance.styleInput((EditText)view);
        else if(view instanceof TextView){Object base=view.getTag(R.id.appearance_base_text_size);appearance.styleText((TextView)view,base instanceof Number?((Number)base).floatValue():14);}
        styleSurface(view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)applyAppearanceToTree(group.getChildAt(i));}
    }
    private void applyCurrentAppearance(){
        appearance=UiAppearance.load(this);appearance.applyTheme(this);appearance.applySystemBars(this);
        if(rootContent!=null){int side=contentSidePadding();rootContent.setTag(R.id.ui_padding_dp,new int[]{side,18,side,22});}
        if(shellView!=null)applyAppearanceToTree(shellView);if(updates!=null)updates.applyAppearance(appearance);renderOutcome();refreshWorkflowSummary();
    }
    private int contentSidePadding(){int width=getResources().getConfiguration().screenWidthDp;return width<600?14:Math.max(22,(width-1120)/2);}
    private Spinner appearanceSpinner(LinearLayout panel,int label,int entries,int selected){
        panel.addView(text(getString(label),15));Spinner spinner=new Spinner(appearance.dialogContext(this));spinner.setAdapter(appearance.themedAdapter(spinner.getContext(),getResources().getStringArray(entries)));appearance.styleSpinner(spinner);spinner.setSelection(selected);panel.addView(spinner);return spinner;
    }
    private void showAppearance(){
        if(appearanceDialog!=null&&appearanceDialog.isShowing())return;
        LinearLayout content=column();content.setPadding(dp(20),dp(12),dp(20),dp(12));
        Spinner theme=appearanceSpinner(content,R.string.ui_theme,R.array.appearance_theme_labels,appearance.mode);
        Spinner size=appearanceSpinner(content,R.string.ui_text_size,R.array.appearance_text_size_labels,appearance.textSize);
        Spinner density=appearanceSpinner(content,R.string.ui_density,R.array.appearance_density_labels,appearance.density);
        ScrollView scroll=new ScrollView(appearance.dialogContext(this));scroll.addView(content);
        appearanceDialog=dialogBuilder().setTitle(R.string.ui_appearance).setView(scroll).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_apply,(d,w)->{
            UiAppearance.save(this,theme.getSelectedItemPosition(),size.getSelectedItemPosition(),density.getSelectedItemPosition());applyCurrentAppearance();
        }).create();appearanceDialog.setOnDismissListener(d->{appearanceDialog=null;updateButtons();});appearanceDialog.show();appearance.styleDialog(appearanceDialog);
    }
    private String inputGuidance(){
        if(pdfUri==null&&jsonUri==null)return "选择PDF和对应的目录JSON，然后读取核对。";
        if(pdfUri==null)return "还需选择PDF原文件。";
        if(jsonUri==null)return "还需选择目录JSON。";
        return "两个文件已选好，点击底部“读取并预览”。";
    }
    private boolean onlySyncSuggested(){
        return parsed!=null&&info!=null&&!sourceNeedsReload&&overwrite.isChecked()&&syncEnjoy.isChecked()
            &&EnjoyDirectorySync.recognizes(pdfUri)&&info.existingCount>0&&!replace.isChecked()&&sameRows(info.existingRows,currentRows());
    }
    private void updateContextOptions(){
        if(enjoyOptions==null)return;boolean recognized=EnjoyDirectorySync.recognizes(pdfUri);
        enjoyOptions.setVisibility(recognized?View.VISIBLE:View.GONE);
        syncEnjoy.setVisibility(overwrite.isChecked()?View.VISIBLE:View.GONE);
        boolean directOnly=parsed!=null&&info!=null&&!sourceNeedsReload&&sameRows(info.existingRows,currentRows());
        boolean applicable=recognized&&((overwrite.isChecked()&&syncEnjoy.isChecked())||directOnly);
        catalogChoices.setVisibility(applicable?View.VISIBLE:View.GONE);
        syncHint.setText(!overwrite.isChecked()?"另存副本不自动同步享做；已有相同书签时可单独同步。":!syncEnjoy.isChecked()?"本次生成不自动同步；单独同步仍使用以下目录模式。":"同步前请保存并停止享做。未知结构会停止处理。");
    }
    private void showAppMenu(View anchor){
        PopupMenu menu=new PopupMenu(appearance.dialogContext(this),anchor);
        menu.getMenu().add("外观").setOnMenuItemClickListener(item->{showAppearance();return true;});
        menu.getMenu().add("版本与更新").setOnMenuItemClickListener(item->{utilityPanel.setVisibility(View.VISIBLE);focusSection(utilityPanel);return true;});
        menu.getMenu().add("使用帮助").setOnMenuItemClickListener(item->{showHelpMenu();return true;});
        menu.getMenu().add("许可与开源说明").setOnMenuItemClickListener(item->{showLicenses();return true;});menu.show();
    }
    private void showHelpMenu(){
        showWorkflow(dialogBuilder().setTitle("使用帮助").setItems(new String[]{"选择文件与授权","目录、页码与差异核对","生成、享做同步与清理","完整操作说明"},(dialog,index)->{
            if(index==3){helpAction.run();return;}
            String[] messages={"选择PDF和对应JSON，可浏览已授权位置或粘贴路径。取消随时返回，原选择保留。路径不等于授权；首次使用须授权所在文件夹。移除授权只撤销本工具的访问权，不删除文件。",
                "目录和页码分别显示，点击行预览PDF实际页。搜索与折叠不改变完整生成范围。变化清单可点选定位；移除项只预览旧目标页。调整页码须先试算，再明确应用并重新核对。",
                "PDF书签内容、PDF保存方式、享做目录处理是三个独立选择。享做同步前保存笔记并在系统设置强行停止，不要清除数据。待保存、部分成功或待恢复时按结果中的下一步继续。确认新PDF和笔迹正常后，唯一清理入口会列明本次旧件；确认删除恢复备份后不能再用它们恢复。"};
            showTextDetails(index==0?"选择文件与授权":index==1?"目录核对":"保存与清理",messages[index]);
        }).setNegativeButton("关闭",null).create());
    }
    private void focusPendingTask(){
        focusSection(outputCard);Button target=getPreferences(MODE_PRIVATE).contains("pendingPdf")?recover:enjoy.hasPending()?recoverCatalog:cleanup;
        if(target!=null){target.setVisibility(View.VISIBLE);target.requestFocus();target.announceForAccessibility("请核对本项未完成任务后继续，不会自动恢复或删除。");}
        status.setText("请处理第3节中已标出的未完成任务；当前文件和恢复记录保留。");
    }
    private void showPdfChanges(){
        if(parsed==null||info==null||sourceNeedsReload){error("请先重新读取当前PDF与JSON，再核对变化。");return;}
        List<TocParser.Row> rows=new ArrayList<>(currentRows());DirectoryDiff.Summary diff=DirectoryDiff.compare(info.existingRows,rows);
        ArrayList<String> details=new ArrayList<>();for(DirectoryDiff.Change change:diff.changes)details.add(change.describe());
        showChangeList("PDF书签变化",diff.counts()+(!replace.isChecked()&&info.existingCount>0?"\n未允许覆盖PDF旧书签":""),details,index->{
            DirectoryDiff.Change change=diff.changes.get(index);
            if(change.afterIndex>=0){revealDifference(rows,change.afterIndex);return;}
            int page=change.before.pdfPage;if(page>0&&page<=info.pageCount){focusSection(reviewCard);renderPage(page,change.before.title);status.setText("正在预览将移除书签的旧目标页；没有添加或修改条目。");}
            else showTextDetails("书签变化详情",change.describe()+"\n\n旧目标未解析，不能可靠跳页。");
        });
    }
    private void showChangeList(String title,String counts,List<String> details,java.util.function.IntConsumer locate){
        if(details.isEmpty()){showTextDetails(title,counts+"\n\n没有需要定位的变化。此检查未改动文件。");return;}
        LinearLayout panel=column();panel.setPadding(dp(18),dp(12),dp(18),0);panel.addView(text(counts+"\n点击变化项定位；这里只预览，不写入文件。",14));
        ListView list=new ListView(appearance.dialogContext(this));list.setAdapter(appearance.dialogListAdapter(list.getContext(),details.toArray(new String[0])));
        panel.addView(list,new LinearLayout.LayoutParams(-1,dp(Math.max(160,Math.min(460,getResources().getConfiguration().screenHeightDp-260)))));
        AlertDialog dialog=dialogBuilder().setTitle(title).setView(panel).setNegativeButton("关闭",null).create();
        list.setOnItemClickListener((parent,view,index,id)->{dialog.dismiss();locate.accept(index);});showWorkflow(dialog);
    }
    private void revealDifference(List<TocParser.Row> snapshot,int index){
        if(parsed==null||sourceNeedsReload||!sameRows(snapshot,currentRows())){error("目录已变化，请重新打开变化清单。");return;}
        if(review.revealSourceRow(index)){focusSection(reviewCard);status.setText("已定位变化条目；只调整浏览位置，生成仍使用完整目录。");}
    }
    private static int uniqueSourceRow(List<TocParser.Row> rows,TocParser.Row target,String targetPath,int page){
        ArrayList<String> parents=new ArrayList<>();int found=-1;
        for(int i=0;i<rows.size();i++){TocParser.Row row=rows.get(i);while(parents.size()>=row.level)parents.remove(parents.size()-1);parents.add(row.title);
            if(row.level==target.level&&row.pdfPage==page&&Objects.equals(row.title,target.title)&&String.join(" › ",parents).equals(targetPath)){if(found>=0)return -1;found=i;}}
        return found;
    }
    private void confirmRetryCatalog(){
        CatalogRetry retry=catalogRetry;if(retry==null||busy)return;
        if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask()){focusPendingTask();return;}
        showWorkflow(dialogBuilder().setTitle("继续未完成的目录同步").setMessage("只处理这次已保存PDF的享做目录，不重新生成或替换PDF。\n"+catalogModeNotice(retry.replaceAll)+"\n\n请保存并强行停止享做，不要清除数据。来源JSON保留。").setNegativeButton("取消",null).setPositiveButton("已停止享做，继续同步",(d,w)->{
            setBusy(true,"正在重新核验已保存PDF和目录来源…");outcomeSet(TaskOutcome.Part.CATALOG,TaskOutcome.Status.RUNNING,"");
            worker.execute(()->{synchronized(replacementLock){try{
                if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask())throw new IOException("请先处理未完成的恢复或清理记录。");
                if(!retry.jsonHash.equals(digest(readJsonInput(retry.json))))throw new IOException("来源JSON已变化，请重新读取核对。PDF不会重新生成。");
                EnjoyDirectorySync.Prepared prepared=enjoy.prepare(retry.pdf,retry.pages,retry.rows,retry.pdfHash,retry.replaceAll);
                String result=applyCatalog(prepared,retry.pdfHash);
                onUi(()->{catalogRetry=null;outcome.set(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,"单独续办同步保留JSON");renderOutcome();setBusy(false,result+"\nPDF未重新写入，JSON保留。请重开享做核对。");completeDraft();focusResult();});
            }catch(Exception e){onUi(()->{if(outcome.entries().stream().anyMatch(entry->entry.part==TaskOutcome.Part.CATALOG&&entry.status==TaskOutcome.Status.RUNNING))outcome.set(TaskOutcome.Part.CATALOG,enjoy.hasPending()?TaskOutcome.Status.RECOVERY_REQUIRED:TaskOutcome.Status.FAILED,message(e));renderOutcome();setBusy(false,catalogFailureNotice()+"\n"+message(e));});}}});
        }).create());
    }
    private void primaryAction(){
        if(busy||shareLoading||FilePickerDialog.isGrantChangeRunning())return;
        if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask()){focusPendingTask();return;}
        if(exportedUri!=null&&!confirmed.isChecked()){openOutput();return;}
        if(parsed==null||sourceNeedsReload){loadPreview();return;}
        if(!confirmed.isChecked()){
            int[] child=new int[2],parent=new int[2];confirmed.getLocationOnScreen(child);mainScroll.getLocationOnScreen(parent);
            mainScroll.smoothScrollBy(0,child[1]-parent[1]-mainScroll.getHeight()/2);confirmed.requestFocus();status.setText(getString(R.string.ui_review_first));return;
        }
        if(readyVerified)saveOutput();else if(onlySyncSuggested())confirmSyncCatalog();else confirmWrite();
    }
    private void onRowsApplied(){
        if(!sourceNeedsReload)draftCompleted=false;
        previewGeneration++;invalidateOutput();confirmed.setChecked(false);clearImage();outcome.begin("");renderOutcome();
        summary.setText(getString(R.string.ui_review_summary,info.pageCount,currentRows().size(),info.existingCount)+(review.isAdjusted()?getString(R.string.ui_adjusted):""));
        status.setText(sourceNeedsReload?"原PDF已替换。继续调整前请重新读取，更新文件基准；当前尚未再次写入。":getString(R.string.ui_mapping_applied));updateButtons();scheduleDraft(false);
    }
    private void setStage(int text){onUi(()->{if(stageLabel!=null&&busy)stageLabel.setText(text);});}
    private void setReadStage(ReadJob job,int text){onUi(()->{if(readJob==job&&!job.cancelled&&stageLabel!=null&&busy)stageLabel.setText(text);});}
    private void outcomeSet(TaskOutcome.Part part,TaskOutcome.Status state,String detail){onUi(()->{outcome.set(part,state,detail);renderOutcome();});}
    private void beginOutcome(boolean generating,boolean sync,boolean deleting,boolean replacing){
        outcome.begin(getString(R.string.ui_result_title));
        outcome.set(TaskOutcome.Part.PDF,generating?TaskOutcome.Status.RUNNING:TaskOutcome.Status.RETAINED,generating?getString(R.string.ui_generating):getString(R.string.ui_pdf_kept));
        outcome.set(TaskOutcome.Part.SAVE,generating?TaskOutcome.Status.WAITING:TaskOutcome.Status.NOT_REQUESTED,"");
        outcome.set(TaskOutcome.Part.CATALOG,sync?TaskOutcome.Status.WAITING:TaskOutcome.Status.SKIPPED,sync?"":getString(R.string.ui_catalog_not_requested));
        outcome.set(TaskOutcome.Part.JSON,deleting?TaskOutcome.Status.WAITING:TaskOutcome.Status.RETAINED,deleting?"":getString(R.string.ui_json_kept));
        outcome.set(TaskOutcome.Part.CLEANUP,replacing?TaskOutcome.Status.RETAINED:TaskOutcome.Status.NOT_REQUESTED,replacing?getString(R.string.ui_backups_kept):"");renderOutcome();
    }
    private int outcomeLabel(TaskOutcome.Part part){switch(part){case PDF:return R.string.ui_result_pdf;case SAVE:return R.string.ui_result_save;case CATALOG:return R.string.ui_result_catalog;case JSON:return R.string.ui_result_json;default:return R.string.ui_result_cleanup;}}
    private int outcomeState(TaskOutcome.Status state){switch(state){case AWAITING_USER:return R.string.workflow_waiting_user;case RECOVERY_REQUIRED:return R.string.workflow_recovery_required;case WAITING:return R.string.ui_waiting;case RUNNING:return R.string.ui_running;case SUCCESS:return R.string.ui_success;case RETAINED:return R.string.ui_retained;case SKIPPED:return R.string.ui_skipped;case FAILED:return R.string.ui_failed;default:return R.string.ui_not_requested;}}
    private void renderOutcome(){
        if(resultRows==null||resultHeading==null)return;boolean pending=getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask();boolean visible=outcome.overall()!=TaskOutcome.Overall.EMPTY||pending;resultRows.setVisibility(visible?View.VISIBLE:View.GONE);resultHeading.setVisibility(visible?View.VISIBLE:View.GONE);
        resultRows.removeAllViews();resultActions.clear();if(!visible)return;resultHeading.setText(getString(R.string.ui_result_heading,pending?"有待恢复或清理事项，请先处理":outcome.summary()));
        for(TaskOutcome.Entry entry:outcome.entries())if(entry.status!=TaskOutcome.Status.NOT_REQUESTED){
            LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(0,dp(7),0,dp(7));
            line.setContentDescription(getString(R.string.ui_result_line,getString(outcomeLabel(entry.part)),getString(outcomeState(entry.status))));
            TextView label=text(getString(outcomeLabel(entry.part)),15);label.setTypeface(null,Typeface.BOLD);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            TextView badge=text(getString(outcomeState(entry.status)),13);badge.setPadding(dp(10),dp(5),dp(10),dp(5));
            boolean failed=entry.status==TaskOutcome.Status.FAILED,success=entry.status==TaskOutcome.Status.SUCCESS;
            badge.setTextColor(failed?appearance.error:success?appearance.accent:appearance.secondaryText);badge.setBackground(background(failed?appearance.errorBg:success?appearance.selectedRow:appearance.surfaceVariant,8));line.addView(badge);resultRows.addView(line);
            if((failed||entry.status==TaskOutcome.Status.RECOVERY_REQUIRED||entry.status==TaskOutcome.Status.AWAITING_USER)&&!entry.detail.isEmpty()){
                TextView reason=text(entry.detail.split("\n",2)[0],13);reason.setTextColor(failed?appearance.error:appearance.secondaryText);reason.setTextIsSelectable(true);resultRows.addView(reason);
            }
            if(entry.status==TaskOutcome.Status.FAILED||entry.status==TaskOutcome.Status.AWAITING_USER||entry.status==TaskOutcome.Status.RECOVERY_REQUIRED){
                if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask()){
                    if(resultActions.isEmpty())resultAction("处理未完成任务",this::focusPendingTask);
                }else if(entry.part==TaskOutcome.Part.SAVE)resultAction(readyVerified?"继续保存已生成的PDF":"检查文件与保存选项",readyVerified?this::saveOutput:this::focusOutputOptions);
                else if(entry.part==TaskOutcome.Part.CATALOG&&catalogRetry!=null)resultAction("仅重试未完成的享做目录同步",this::confirmRetryCatalog);
                else if(entry.part==TaskOutcome.Part.CLEANUP)resultAction("重新核验清理清单",this::confirmCleanup);
                else if(entry.part==TaskOutcome.Part.PDF)resultAction("返回目录核对",()->focusSection(reviewCard));
                else if(entry.part==TaskOutcome.Part.JSON)resultAction("保留JSON，结束此可选步骤",this::confirmKeepJson);
            }
        }
        if(pending&&resultActions.isEmpty())resultAction("处理未完成任务",this::focusPendingTask);
        Button details=new Button(this);details.setText(R.string.ui_result_details);styleButton(details,false);details.setOnClickListener(v->showResultDetails());resultRows.addView(details);
    }
    private void resultAction(String label,Runnable action){
        Button button=new Button(this);button.setText(label);styleButton(button,false);button.setMinHeight(dp(48));
        button.setEnabled(!busy&&!shareLoading&&!FilePickerDialog.isGrantChangeRunning());button.setOnClickListener(v->{if(!busy&&!shareLoading&&!FilePickerDialog.isGrantChangeRunning())action.run();});
        resultActions.add(button);resultRows.addView(button,new LinearLayout.LayoutParams(-1,-2));
    }
    private void focusOutputOptions(){optionsPanel.setVisibility(View.VISIBLE);((Button)optionsPanel.getTag()).setText("处理选项 ▴");setInputsExpanded(true);focusSection(outputCard);}
    private void confirmKeepJson(){
        if(busy)return;
        showWorkflow(dialogBuilder().setTitle("结束JSON删除步骤？").setMessage("不再重试本次JSON删除。已保存PDF及成功同步的目录保持，不会重新生成、保存或清理文件。来源JSON及其授权保留。")
            .setNegativeButton("返回",null).setPositiveButton("保留JSON",(d,w)->{deleteJson.setChecked(false);outcome.set(TaskOutcome.Part.JSON,TaskOutcome.Status.RETAINED,"已选择结束本次自动删除，未继续操作来源JSON。");renderOutcome();status.setText(R.string.workflow_json_kept_end);}).create());
    }
    private void showResultDetails(){
        StringBuilder detail=new StringBuilder();for(TaskOutcome.Entry e:outcome.entries())if(e.status!=TaskOutcome.Status.NOT_REQUESTED)detail.append(getString(outcomeLabel(e.part))).append("：").append(getString(outcomeState(e.status))).append("\n").append(e.detail).append("\n\n");
        if(exportedUri!=null)detail.append(getString(R.string.ui_result_path)).append("\n").append(inputLabel(exportedName.isEmpty()?nameHint(exportedUri):exportedName,exportedUri)).append("\n").append(exportedUri);
        String value=detail.toString();TextView content=text(value,15);content.setTextIsSelectable(true);content.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView scroll=new ScrollView(appearance.dialogContext(this));scroll.addView(content);
        dialogBuilder().setTitle(R.string.ui_result_title).setView(scroll).setPositiveButton(R.string.ui_close,null).setNeutralButton(R.string.ui_copy_details,(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_result_title),value));}).show();
    }
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_F&&event.isCtrlPressed()&&review!=null){review.focusSearch();return true;}
        return super.onKeyDown(keyCode,event);
    }
    private void error(String message){dialogBuilder().setTitle("需要处理").setMessage(message).setPositiveButton("知道了",null).show();}
    @Override public void onBackPressed(){
        if(review!=null&&review.handleBack())return;
        if(shareLoading){cancelShare();return;}
        if(systemPickerRequest!=0){cancelCurrentInput();return;}
        if(pathEditor.getVisibility()==View.VISIBLE){closePathEditor();status.setText("已取消路径输入，原来的文件选择保留。");return;}
        if(readJob!=null){cancelReading();return;}
        if(busy){dialogBuilder().setMessage("正在处理文件，请等待完成后退出，避免保存中断。").setPositiveButton("继续等待",null).show();return;}
        if(parsed!=null&&!draftCompleted&&(!sourceNeedsReload||catalogRetry!=null)){scheduleDraft(true);showWorkflow(dialogBuilder().setTitle("离开核对界面？").setMessage("核对进度会保存为应用内草稿，下次可继续。原PDF和JSON不会因此改动。")
            .setNegativeButton("继续核对",null).setPositiveButton("保存草稿并离开",(d,w)->leaveAfterDraft()).create());return;}
        super.onBackPressed();
    }
    private void leaveAfterDraft(){
        final ReviewDraft expected;try{expected=captureReviewDraft();}catch(IllegalArgumentException e){error("核对草稿未保存："+message(e));return;}
        scheduleDraft(true);long revision=draftRevision;
        draftWriter.execute(()->{DraftStore.LoadResult saved=draftStore.load();onUi(()->{
            if(revision!=draftRevision)return;
            if(storedDraftRevision!=revision||saved.draft==null||!saved.draft.matches(expected.pdfUri,expected.jsonUri,expected.pdfSha256,expected.jsonSha256)){
                error(saved.error==null?"核对草稿尚未保存，请稍后再试；当前核对界面保留。":saved.error);return;
            }
            MainActivity.super.onBackPressed();
        });});
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("systemPickerRequest",systemPickerRequest);super.onSaveInstanceState(state);}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);applyCurrentAppearance();review.onWindowChanged(configuration.screenWidthDp,configuration.screenHeightDp);
        if(filePicker!=null&&filePicker.isShowing())filePicker.resize();
    }
    @Override protected void onDestroy(){scheduleDraft(true);if(draftSave!=null)draftHandler.removeCallbacks(draftSave);if(workflowDialog!=null)workflowDialog.dismiss();if(updates!=null)updates.close();if(filePicker!=null)filePicker.dismiss();if(appearanceDialog!=null)appearanceDialog.dismiss();previewGeneration++;if(previewTask!=null)previewTask.cancel(false);previews.shutdown();if(readJob!=null){readJob.cancelled=true;if(readJob.future!=null)readJob.future.cancel(true);readers.execute(readJob::closeInput);}readers.shutdown();worker.shutdown();draftWriter.shutdown();clearImage();super.onDestroy();}
}
