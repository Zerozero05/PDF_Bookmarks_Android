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
    private static final int INK=0xff1d3439,ACCENT=0xff147a70;
    // Activity销毁后回滚仍可能运行；新界面不能提前清除它的恢复记录。
    private static final Object replacementLock=new Object();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService readers=Executors.newCachedThreadPool();
    private final ArrayList<Button> buttons=new ArrayList<>();
    private Uri pdfUri,jsonUri,exportedUri;
    private String pdfName="",jsonName="",pdfHash="",jsonHash="";
    private File sourceFile,readyFile;
    private TocParser.Parsed parsed;
    private PdfBookmarks.Info info;
    private boolean busy,readyVerified,deleteOnExport;
    private int previewGeneration;
    private Bitmap shown;
    private TextView pdfLabel,jsonLabel,status,summary,pageLabel,outputSettings;
    private LinearLayout tree;
    private ImageView pageImage;
    private CheckBox replace,confirmed,overwrite,deleteJson;
    private Button generate,export,open,recover,exportBackup,restoreMissing,cancelRead,cleanup,moreToggle;
    private LinearLayout moreActions;
    private CheckBox syncEnjoy;
    private RadioGroup catalogMode;
    private RadioButton keepCatalog,replaceCatalog;
    private Button syncCatalog,recoverCatalog;
    private EnjoyDirectorySync enjoy;
    private ReadJob readJob;
    private EditText pathInput;
    private LinearLayout pathEditor;
    private int pathRequest;
    private int pendingPathRequest;
    private String pendingPath="";

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
        synchronized void attach(Closeable stream)throws IOException {
            if(cancelled){stream.close();throw new InterruptedIOException("读取已取消。");}input=stream;
        }
        synchronized void detach(){input=null;}
        void check()throws IOException {if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("读取已取消。");}
        void closeInput(){try{signal.cancel();}finally{Closeable stream;synchronized(this){stream=input;input=null;}if(stream!=null)try{stream.close();}catch(IOException ignored){}}}
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);PDFBoxResourceLoader.init(getApplicationContext());enjoy=new EnjoyDirectorySync(this);
        if(Build.VERSION.SDK_INT>=27)getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // 重建Activity时旧工作线程可能尚未结束；每次使用独立目录避免交叉读写。
        File session=new File(getCacheDir(),"job-"+UUID.randomUUID());session.mkdirs();
        sourceFile=new File(session,"source.pdf");readyFile=new File(session,"ready.pdf");
        buildUi();
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        String p=prefs.getString("pdf",null),j=prefs.getString("json",null);
        if(p!=null){pdfUri=Uri.parse(p);pdfName=prefs.getString("pdfName","PDF");pdfLabel.setText(inputLabel(pdfName,pdfUri));}
        if(j!=null){jsonUri=Uri.parse(j);jsonName=prefs.getString("jsonName","JSON");jsonLabel.setText(jsonName);}
        status.setText(pdfUri!=null||jsonUri!=null?"已记住上次选定的文件，点击预览重新核对。":"选择PDF和对应目录JSON，然后预览。原文件会保留。");
        updateButtons();checkRecovery();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(INK);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(LinearLayout parent,String title){
        LinearLayout v=column();v.setPadding(dp(18),dp(16),dp(18),dp(16));v.setBackground(background(Color.WHITE,14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(v,lp);
        TextView heading=text(title,19);heading.setTypeface(null,Typeface.BOLD);v.addView(heading);return v;
    }
    private Button button(LinearLayout parent,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(ACCENT);b.setOnClickListener(v->action.run());
        parent.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));buttons.add(b);return b;
    }
    private LinearLayout disclosure(LinearLayout parent,String label){
        LinearLayout content=column();content.setVisibility(View.GONE);
        Button toggle=button(parent,label+" ▾",()->{});
        toggle.setOnClickListener(v->{boolean expand=content.getVisibility()!=View.VISIBLE;content.setVisibility(expand?View.VISIBLE:View.GONE);toggle.setText(label+(expand?" ▴":" ▾"));});
        content.setTag(toggle);parent.addView(content);return content;
    }
    private void showMoreActions(){moreActions.setVisibility(View.VISIBLE);moreToggle.setText("更多操作与恢复 ▴");}
    private void buildUi(){
        LinearLayout shell=column();shell.setBackgroundColor(0xfff4f7f8);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setPadding(dp(16),dp(6),dp(16),dp(6));
        TextView toolbarTitle=text("PDF目录工具",19);toolbarTitle.setTypeface(null,Typeface.BOLD);toolbar.addView(toolbarTitle,new LinearLayout.LayoutParams(0,-2,1));
        cancelRead=new Button(this);cancelRead.setText("取消读取 / 返回");cancelRead.setAllCaps(false);cancelRead.setVisibility(View.GONE);cancelRead.setOnClickListener(v->cancelReading());toolbar.addView(cancelRead);
        shell.addView(toolbar);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff4f7f8);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout root=column();root.setPadding(dp(24),dp(22),dp(24),dp(22));scroll.addView(root);
        shell.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(shell);
        TextView title=text("PDF目录工具",30);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView intro=text("把章、节目录写入PDF，点击书签即可跳转。",16);intro.setPadding(0,dp(6),0,dp(20));root.addView(intro);
        LinearLayout files=card(root,"1  选择文件");
        pdfLabel=text("尚未选择PDF",15);files.addView(pdfLabel);button(files,"选择 PDF",()->pick(PICK_PDF));
        button(files,"粘贴 PDF 路径",()->inputPath(PICK_PDF));
        jsonLabel=text("尚未选择目录JSON",15);files.addView(jsonLabel);button(files,"选择目录 JSON",()->pick(PICK_JSON));
        button(files,"粘贴 JSON 路径",()->inputPath(PICK_JSON));
        pathEditor=column();pathEditor.setVisibility(View.GONE);files.addView(pathEditor);
        pathEditor.addView(text("粘贴路径后点击使用；误触可点取消，或按系统返回。",14));
        pathInput=new EditText(this);pathInput.setHint("/享做笔记/…/文件.pdf");pathInput.setMaxLines(3);pathInput.setMinLines(2);pathEditor.addView(pathInput);
        button(pathEditor,"使用此路径",()->resolvePath(pathRequest,pathInput.getText().toString()));
        button(pathEditor,"授权此路径所在文件夹",()->{pendingPathRequest=pathRequest;pendingPath=pathInput.getText().toString();authorizeFolder(pendingPath);});
        button(pathEditor,"取消路径输入 / 返回",this::closePathEditor);
        button(files,"授权路径所在文件夹",()->{pendingPathRequest=0;pendingPath="";authorizeFolder("/享做笔记/note/文件.pdf");});
        files.addView(text("支持享做复制的 /享做笔记/… 路径；首次授权所在文件夹，之后可直接粘贴。",14));
        files.addView(text("兼容桌面目录JSON和Jasminum目录，完整保留多级结构。",14));
        button(files,"读取文件并预览目录",this::loadPreview);
        LinearLayout preview=card(root,"2  核对目录与目标页");
        summary=text("预览会显示实际PDF总页数、目录层级和目标页。",15);preview.addView(summary);
        LinearLayout panes=new LinearLayout(this);boolean wide=getResources().getConfiguration().screenWidthDp>=700;
        panes.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);preview.addView(panes);
        LinearLayout directory=column();LinearLayout headings=new LinearLayout(this);headings.setPadding(dp(8),dp(10),dp(8),dp(10));
        TextView titleHeading=text("目录标题",14);titleHeading.setTypeface(null,Typeface.BOLD);headings.addView(titleHeading,new LinearLayout.LayoutParams(0,-2,1));
        TextView pageHeading=text("目标页",14);pageHeading.setTypeface(null,Typeface.BOLD);pageHeading.setGravity(Gravity.END);headings.addView(pageHeading,new LinearLayout.LayoutParams(dp(100),-2));directory.addView(headings);
        ScrollView treeScroll=new DirectoryScrollView(this);tree=column();treeScroll.addView(tree);emptyTree();
        directory.addView(treeScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=column();pageLabel=text("点击目录行查看对应PDF页",14);page.addView(pageLabel);
        pageImage=new ImageView(this);pageImage.setAdjustViewBounds(true);pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);page.addView(pageImage,new LinearLayout.LayoutParams(-1,dp(340)));
        if(wide){panes.addView(directory,new LinearLayout.LayoutParams(0,dp(400),1));panes.addView(page,new LinearLayout.LayoutParams(0,-2,1));}
        else{panes.addView(directory,new LinearLayout.LayoutParams(-1,dp(280)));panes.addView(page);}
        confirmed=new CheckBox(this);confirmed.setText("我已核对目录标题、层级和实际跳转页");confirmed.setOnCheckedChangeListener((b,c)->updateButtons());preview.addView(confirmed);
        replace=new CheckBox(this);replace.setText("替换PDF已有的整棵目录（有旧目录时才需要）");replace.setOnCheckedChangeListener((b,c)->{invalidateOutput();confirmed.setChecked(false);updateButtons();});preview.addView(replace);
        LinearLayout output=card(root,"3  生成并保存");
        outputSettings=text("",15);output.addView(outputSettings);
        generate=button(output,"生成带目录 PDF",this::confirmWrite);
        open=button(output,"打开已保存的 PDF",this::openOutput);
        cleanup=button(output,"清理旧文件与备份",this::confirmCleanup);
        LinearLayout options=disclosure(output,"处理选项");
        overwrite=new CheckBox(this);overwrite.setText("直接替换原 PDF（原路径与名称）");overwrite.setOnCheckedChangeListener((b,c)->updateButtons());options.addView(overwrite);
        overwrite.setChecked(true);
        options.addView(text("保持第1节所选PDF的原路径和名称，先核验新文件，再切换名称并备份原件。更新享做原笔记时，请选择笔记详情中的PDF路径。取消勾选可另存副本。",14));
        syncEnjoy=new CheckBox(this);syncEnjoy.setText("替换后同步享做目录");syncEnjoy.setOnCheckedChangeListener((b,c)->updateButtons());options.addView(syncEnjoy);syncEnjoy.setChecked(true);
        options.addView(text("享做目录处理方式",14));
        catalogMode=new RadioGroup(this);catalogMode.setOrientation(RadioGroup.VERTICAL);catalogMode.setSaveEnabled(false);options.addView(catalogMode);
        keepCatalog=new RadioButton(this);keepCatalog.setId(View.generateViewId());keepCatalog.setSaveEnabled(false);keepCatalog.setText("保留已有目录并添加新目录（默认）");catalogMode.addView(keepCatalog);
        replaceCatalog=new RadioButton(this);replaceCatalog.setId(View.generateViewId());replaceCatalog.setSaveEnabled(false);replaceCatalog.setText("用新目录替换全部已有目录（不保留旧目录备份）");catalogMode.addView(replaceCatalog);
        catalogMode.check(keepCatalog.getId());
        catalogMode.setOnCheckedChangeListener((group,id)->updateButtons());
        options.addView(text("仅用于已识别的享做资源PDF。同步前请保存笔记，并到系统设置强行停止享做，不要清除数据；完成后重开核对目录、跳转和原笔迹。未知格式、页面映射或文件变化时停止。",14));
        deleteJson=new CheckBox(this);deleteJson.setText("PDF及所选同步成功后，删除所选 JSON");deleteJson.setOnCheckedChangeListener((b,c)->updateButtons());options.addView(deleteJson);
        moreActions=disclosure(output,"更多操作与恢复");moreToggle=(Button)moreActions.getTag();
        export=button(moreActions,"保存已生成的 PDF…",this::saveOutput);
        syncCatalog=button(moreActions,"仅同步当前PDF目录到享做",this::confirmSyncCatalog);
        moreActions.addView(text("仅同步使用当前PDF与JSON中的相同目录，不替换PDF。备份导出及恢复在清理前可用。清理只处理成功记录关联的旧件和内部恢复备份，不扫描其他文件。",14));
        exportBackup=button(moreActions,"导出最近一次原 PDF 备份",this::saveBackup);
        restoreMissing=button(moreActions,"从备份恢复消失的 PDF…",this::chooseRestoreFolder);
        recoverCatalog=button(output,"恢复上次目录同步前的享做目录",this::confirmRecoverCatalog);
        recover=button(output,"恢复上次替换前的原 PDF",this::confirmRecovery);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);progress.setTag("progress");root.addView(progress);
        status=text("",15);status.setPadding(0,dp(8),0,dp(8));root.addView(status);
        button(root,"使用说明",()->new AlertDialog.Builder(this).setTitle("使用说明").setMessage(
            "选择PDF与JSON，或粘贴共享存储路径 → 读取并预览 → 滑动目录、点击标题或页码核对目标页 → 生成并保存。目录标题和目标页在独立两栏显示。\n\n路径输入可随时取消。系统文件选择器可按系统返回取消；没有选定文件时保留原来的选择。读取时顶部有取消按钮，也可按系统返回。\n\n第3节的“处理选项”可切换同名替换/另存、享做目录保留添加/全部替换以及成功后删除JSON。“更多操作与恢复”保留单独同步、保存已生成PDF、导出备份和恢复消失PDF；未完成任务的恢复入口直接显示。\n\n同名替换需要原文件夹的读写授权：先在该文件夹写好并核验新PDF，再将原件改名为备份，最后把新PDF改为原名。程序不截断或自动删除原PDF。文件来源不支持这些操作时，请另存。\n\n同步享做目录默认勾选，仅适配已识别的共享存储资源文件。请先保存笔记，并到系统设置强行停止享做，不要清除数据。默认保留已有享做目录并添加新目录，也可选择用新目录替换全部已有目录，包括手动目录；保留并添加模式保留原目录备份；全部替换模式核验成功后自动删除本次旧目录及内部目录恢复副本，不能再用它们恢复，失败时保留恢复材料。两种模式均保留PDF备份、原笔迹及其他配置。换输入文件或重开界面时恢复默认保留模式；未知结构、页面映射或文件变化时停止。PDF已含本次JSON的相同书签时，可在“更多操作与恢复”只同步目录，不必再生成PDF。无需无障碍服务，也不联网。\n\n核对新PDF、享做目录、跳转和原笔迹正常后，保存并强行停止享做，可用一个“清理旧文件与备份”按钮。清单列出本次成功记录关联的外部旧PDF、旧目录及应用内恢复备份，确认后统一清理。清理后无法再靠这些备份恢复。保留当前PDF、活动catalog/temp、笔迹和其他配置；原始JSON只按独立勾选处理，不纳入统一清理。不会扫描旧版失去关联的备份、其他任务或未知文件。文件变化时停止；中断可按原清单重试。\n\n只有PDF及所选享做同步均成功后才可选删除JSON。中断保留未删除备份与进度；第3节目录恢复只恢复同步前目录，不改PDF和笔迹。清理前可导出备份；不要卸载程序或清除数据。\n\n加密或含签名字段的PDF会拒绝处理。桌面OCR、批量功能尚未移植。\n\n版本 "+getString(R.string.app_version)+" · GNU AGPL v3\nPDFBox Android / Gson: Apache 2.0\nBouncy Castle: MIT").setPositiveButton("知道了",null).show());
        button(root,"许可与开源说明",this::showLicenses);
    }
    private void pick(int request){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(request==PICK_PDF?"application/pdf":"*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        clearPendingPath();closePathEditor();launchPicker(intent,request);
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}
        catch(Exception ignored){}return "所选文件";
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
        if(result!=RESULT_OK||data==null||data.getData()==null){clearPendingPath();status.setText("已取消文件选择 / 授权，原来的选择保留。可以继续操作。");return;}
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
            if(jsonUri!=null){jsonUri=PathDocuments.resolve(this,jsonUri.toString());prefs.putString("json",jsonUri.toString());}
            prefs.apply();updateButtons();
        }catch(IOException e){error(message(e));}
    }
    private void selectInput(int request,Uri uri){
        invalidatePlan();String name=nameHint(uri);deleteJson.setChecked(false);catalogMode.check(keepCatalog.getId());
        if(request==PICK_PDF){pdfUri=uri;pdfName=name;pdfLabel.setText(inputLabel(name,uri));getPreferences(MODE_PRIVATE).edit().putString("pdf",uri.toString()).putString("pdfName",name).apply();}
        else{jsonUri=uri;jsonName=name;jsonLabel.setText(name);getPreferences(MODE_PRIVATE).edit().putString("json",uri.toString()).putString("jsonName",name).apply();}
        status.setText("文件已选择，点击读取并预览。");updateButtons();
    }
    private void inputPath(int request){
        clearPendingPath();pathRequest=request;pathInput.setText("");pathInput.setHint(request==PICK_PDF?"粘贴 PDF 路径":"粘贴 JSON 路径");pathEditor.setVisibility(View.VISIBLE);pathInput.requestFocus();
        status.setText("输入路径后点击使用；取消不会更改文件选择。");
    }
    private void clearPendingPath(){pendingPathRequest=0;pendingPath="";}
    private void closePathEditor(){clearPendingPath();pathEditor.setVisibility(View.GONE);pathInput.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(pathInput.getWindowToken(),0);}
    private String nameHint(Uri uri){
        try{String id=DocumentsContract.getDocumentId(uri);return id.substring(id.lastIndexOf('/')+1).replaceFirst("^primary:","");}
        catch(IllegalArgumentException e){return "已选文件（读取后显示名称）";}
    }
    private String inputLabel(String name,Uri uri){
        try{if("com.android.externalstorage.documents".equals(uri.getAuthority())){String id=DocumentsContract.getDocumentId(uri);if(id.startsWith("primary:"))return name+"\n/storage/emulated/0/"+id.substring(8);}}
        catch(IllegalArgumentException ignored){}return name;
    }
    private void launchPicker(Intent intent,int request){
        try{startActivityForResult(intent,request);}
        catch(ActivityNotFoundException|SecurityException e){clearPendingPath();status.setText("未能打开系统文件选择器；当前选择保留。");error(message(e));}
    }
    private void resolvePath(int request,String path){
        try{Uri uri=PathDocuments.resolve(this,path);closePathEditor();selectInput(request,uri);}
        catch(IOException|IllegalArgumentException e){pendingPathRequest=request;pendingPath=path;new AlertDialog.Builder(this).setTitle("路径暂时无法读取").setMessage(message(e))
            .setNegativeButton("返回编辑",(d,w)->clearPendingPath()).setOnCancelListener(d->clearPendingPath()).setPositiveButton("授权所在文件夹",(d,w)->authorizeFolder(path)).show();}
    }
    private void authorizeFolder(String path){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try{intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,PathDocuments.initialFolder(path));}catch(IOException|IllegalArgumentException ignored){}
        launchPicker(intent,PICK_FOLDER);
    }
    private void emptyTree(){tree.removeAllViews();TextView hint=text("尚未加载目录。请点击第1节的“读取文件并预览目录”。",15);hint.setPadding(dp(8),dp(16),dp(8),dp(16));tree.addView(hint,new LinearLayout.LayoutParams(-1,-2));}
    private void invalidateOutput(){readyVerified=false;if(readyFile!=null&&readyFile.exists())readyFile.delete();exportedUri=null;}
    private void invalidatePlan(){previewGeneration++;parsed=null;info=null;invalidateOutput();confirmed.setChecked(false);emptyTree();clearImage();summary.setText("文件已更改，需要重新预览。");}
    private void updateButtons(){
        for(Button b:buttons)b.setEnabled(!busy);
        boolean pdfPending=getPreferences(MODE_PRIVATE).contains("pendingPdf"),catalogPending=enjoy.hasPending();
        boolean cleanupPending=hasCleanupTask(),pending=pdfPending||catalogPending||cleanupPending;
        if(generate!=null)generate.setEnabled(!busy&&!pending&&parsed!=null&&jsonUri!=null&&confirmed.isChecked());
        if(generate!=null)generate.setText(overwrite!=null&&overwrite.isChecked()?"生成并替换原 PDF（同路径、同名）":"生成带目录副本（待另存）");
        if(export!=null){export.setEnabled(!busy&&!pending&&readyVerified);export.setText(overwrite!=null&&overwrite.isChecked()?"重试保存 / 替换已生成的 PDF":"另存已生成的 PDF…");}
        if(open!=null){open.setEnabled(!busy&&exportedUri!=null);open.setVisibility(exportedUri!=null?View.VISIBLE:View.GONE);}
        if(cleanup!=null){BackupRecord record=backupRecord(getPreferences(MODE_PRIVATE).getString("lastReplacement",null));boolean available=cleanupPending||(record!=null&&sameDocument(pdfUri,Uri.parse(record.outputUri)))||enjoy.recordFor(pdfUri)!=null;
            cleanup.setVisibility(available?View.VISIBLE:View.GONE);cleanup.setEnabled(!busy&&!pdfPending&&!catalogPending&&available);cleanup.setText(cleanupPending?"继续清理旧文件与备份":"清理旧文件与备份");}
        if(confirmed!=null)confirmed.setEnabled(!busy&&parsed!=null);
        if(replace!=null)replace.setEnabled(!busy);
        if(overwrite!=null)overwrite.setEnabled(!busy);
        if(deleteJson!=null)deleteJson.setEnabled(!busy);
        if(syncEnjoy!=null)syncEnjoy.setEnabled(!busy);
        if(keepCatalog!=null)keepCatalog.setEnabled(!busy);
        if(replaceCatalog!=null)replaceCatalog.setEnabled(!busy);
        if(syncCatalog!=null)syncCatalog.setEnabled(!busy&&!pending&&parsed!=null&&info.existingCount>0&&confirmed.isChecked()&&EnjoyDirectorySync.recognizes(pdfUri));
        if(recoverCatalog!=null){recoverCatalog.setVisibility(catalogPending?View.VISIBLE:View.GONE);recoverCatalog.setEnabled(!busy&&!pdfPending);}
        if(recover!=null){recover.setVisibility(pdfPending?View.VISIBLE:View.GONE);recover.setEnabled(!busy&&!catalogPending);}
        if(exportBackup!=null){exportBackup.setVisibility(getPreferences(MODE_PRIVATE).contains("lastBackup")?View.VISIBLE:View.GONE);exportBackup.setEnabled(!busy);}
        if(restoreMissing!=null){restoreMissing.setVisibility(getPreferences(MODE_PRIVATE).contains("lastBackup")?View.VISIBLE:View.GONE);restoreMissing.setEnabled(!busy&&!catalogPending&&!cleanupPending);}
        if(cancelRead!=null){cancelRead.setVisibility(readJob!=null?View.VISIBLE:View.GONE);cancelRead.setEnabled(readJob!=null);}
        if(outputSettings!=null&&overwrite!=null&&syncEnjoy!=null&&replaceCatalog!=null&&deleteJson!=null){
            String settings=overwrite.isChecked()?"原路径、原名称替换":"另存带目录副本";
            settings+=overwrite.isChecked()&&syncEnjoy.isChecked()?(replaceCatalog.isChecked()?" · 享做目录全部替换":" · 享做目录保留并添加"):" · 不同步享做目录";
            settings+=deleteJson.isChecked()?" · 成功后删除JSON":" · 保留JSON";outputSettings.setText(settings+"\n可展开“处理选项”调整。");}
    }
    private void setBusy(boolean value,String message){
        busy=value;status.setText(message);updateButtons();
        View progress=getWindow().getDecorView().findViewWithTag("progress");if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.GONE);
        if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void onUi(Runnable action){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())action.run();});}
    private void loadPreview(){
        if(pdfUri==null||jsonUri==null){error("请先选择PDF和JSON文件。");return;}
        closePathEditor();
        invalidatePlan();ReadJob job=new ReadJob();readJob=job;
        File session=new File(getCacheDir(),"read-"+UUID.randomUUID());session.mkdirs();File localSource=new File(session,"source.pdf"),localReady=new File(session,"ready.pdf");
        setBusy(true,"正在读取PDF和目录；可用顶部按钮或系统返回取消。");
        Uri p=pdfUri,j=jsonUri;
        job.future=readers.submit(()->{
            try{
                String sourceHash=copySource(p,localSource,job);job.check();PdfBookmarks.Info readInfo=PdfBookmarks.inspect(localSource);job.check();
                byte[] bytes=readSmall(j,job);String raw=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                TocParser.Parsed readPlan=TocParser.parse(raw,readInfo.pageCount);String tocHash=digest(bytes);
                String pName=displayName(p,job),jName=displayName(j,job);job.check();
                onUi(()->{if(readJob!=job||job.cancelled)return;readJob=null;sourceFile=localSource;readyFile=localReady;info=readInfo;parsed=readPlan;pdfHash=sourceHash;jsonHash=tocHash;
                    pdfName=pName;jsonName=jName;pdfLabel.setText(inputLabel(pName,p));jsonLabel.setText(jName);getPreferences(MODE_PRIVATE).edit().putString("pdfName",pName).putString("jsonName",jName).apply();showTree();
                    summary.setText(String.format(Locale.CHINA,"PDF共 %d 页 · 导入 %d 条目录 · 已有 %d 条书签",info.pageCount,parsed.rows.size(),info.existingCount));
                    setBusy(false,info.existingCount>0?"PDF已有目录。默认跳过；需要更新时勾选替换，并核对新目录。":"预览完成。点击目录核对目标页，再确认生成。");});
            }catch(Exception e){onUi(()->{if(readJob!=job||job.cancelled)return;readJob=null;setBusy(false,"读取失败，原文件未改动。");error(message(e));});}
        });
    }
    private String displayName(Uri uri,ReadJob job)throws IOException {
        job.check();try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null,job.signal)){job.check();if(c!=null&&c.moveToFirst())return c.getString(0);}return nameHint(uri);
    }
    private void cancelReading(){
        ReadJob job=readJob;if(job==null)return;job.cancelled=true;readJob=null;if(job.future!=null)job.future.cancel(true);
        readers.execute(job::closeInput);invalidatePlan();setBusy(false,"已取消读取并返回。原PDF和JSON未改动，可以重新选择文件。");
    }
    private void showTree(){
        tree.removeAllViews();for(TocParser.Row row:parsed.rows){
            LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setMinimumHeight(dp(48));item.setPadding(0,dp(10),0,dp(10));
            TextView title=text(row.title,15);title.setPadding(dp(8+Math.min(row.level-1,8)*16),0,dp(8),0);item.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            TextView target=text(String.valueOf(row.pdfPage),15);target.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);target.setPadding(dp(4),0,dp(8),0);
            target.setContentDescription((row.printedPage==null?"":"印刷页 "+row.printedPage+"，")+"PDF第 "+row.pdfPage+" 页");item.addView(target,new LinearLayout.LayoutParams(dp(100),-1));
            item.setBackgroundResource(android.R.drawable.list_selector_background);item.setOnClickListener(v->{if(!busy)renderPage(row.pdfPage,row.title);});tree.addView(item);
        }
    }
    private void renderPage(int number,String title){
        int token=++previewGeneration;pageLabel.setText("正在读取第 "+number+" 页…");
        worker.execute(()->{
            Bitmap bitmap=null;
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(sourceFile,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(number-1)){
                int width=1000,height=Math.max(1,Math.round(width*(float)page.getHeight()/page.getWidth()));
                bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap result=bitmap;onUi(()->{if(token!=previewGeneration){result.recycle();return;}clearImage();shown=result;pageImage.setImageBitmap(result);pageLabel.setText(title+" · PDF第 "+number+" 页");});
            }catch(Exception e){if(bitmap!=null)bitmap.recycle();onUi(()->{if(token==previewGeneration){clearImage();pageLabel.setText("该页预览失败："+message(e));confirmed.setChecked(false);}});}
        });
    }
    private void clearImage(){pageImage.setImageDrawable(null);if(shown!=null){shown.recycle();shown=null;}}
    private void confirmWrite(){
        if(parsed==null||!confirmed.isChecked())return;
        if(info.existingCount>0&&!replace.isChecked()){error("PDF已有目录，默认跳过。需要更新请勾选替换，并重新核对预览。");return;}
        String action=info.existingCount>0?"将替换PDF中的整棵旧书签目录。":"将在PDF中添加书签目录。";
        String destination=overwrite.isChecked()?"生成并校验后，直接覆盖原PDF："+pdfName+"。路径和名称不变，先保存原件备份。请先关闭享做中正在使用的该笔记。":"原PDF保留；生成后选择新PDF的保存位置。";
        new AlertDialog.Builder(this).setTitle("生成带目录PDF")
            .setMessage("共 "+parsed.rows.size()+" 条目录，PDF共 "+info.pageCount+" 页。\n"+action+"\n"+destination+syncNotice()+(deleteJson.isChecked()?"\nPDF及所选目录同步成功后删除JSON："+jsonName:"\nJSON保留。"))
            .setNegativeButton("取消",null).setPositiveButton("生成",(d,w)->generateOutput()).show();
    }
    private void generateOutput(){
        List<TocParser.Row> rows=parsed.rows;boolean replaceOld=replace.isChecked(),inPlace=overwrite.isChecked(),deleteAfter=deleteJson.isChecked();Uri p=pdfUri,j=jsonUri;
        previewGeneration++;invalidateOutput();setBusy(true,"正在生成并校验目录，请保持应用在前台…");
        worker.execute(()->{
            try{
                if(!pdfHash.equals(hashUri(p))||!jsonHash.equals(digest(readSmall(j))))throw new IOException("源文件在预览后发生变化，请重新读取预览。");
                PdfBookmarks.write(sourceFile,readyFile,rows,replaceOld);
                onUi(()->{readyVerified=true;setBusy(false,"生成并校验成功。可以保存PDF。");if(inPlace)replaceOriginalOutput(deleteAfter);else showMoreActions();});
            }catch(Exception e){if(readyFile.exists())readyFile.delete();onUi(()->{setBusy(false,"生成失败，原文件未改动。");confirmed.setChecked(false);error(message(e));});}
        });
    }
    private void saveOutput(){
        if(overwrite.isChecked()){
            new AlertDialog.Builder(this).setTitle("替换原PDF").setMessage("将覆盖 "+pdfName+"，原路径和名称不变，并保留原件备份。请先关闭享做中的该笔记。"+syncNotice()+(deleteJson.isChecked()?"\n成功后删除JSON："+jsonName:"\nJSON保留。"))
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
        setBusy(true,"正在保存到你选择的位置并核对文件…");
        worker.execute(()->{
            try{
                if(!readyVerified||!readyFile.isFile())throw new IOException("生成的文件不可用，请重新生成。");
                String expected=hashFile(readyFile);
                try(InputStream input=new FileInputStream(readyFile);OutputStream output=getContentResolver().openOutputStream(destination,"wt")){
                    if(output==null)throw new IOException("无法打开输出位置。");transfer(input,output);output.flush();
                }
                if(!expected.equals(hashUri(destination)))throw new IOException("保存后的文件校验不一致，请另选位置重新保存。");
                finishSave(destination,"保存并核验成功："+displayName(destination)+"。可以打开或导入享做。",deleteAfter);
            }catch(Exception e){onUi(()->{setBusy(false,"保存未完成；原文件及已生成副本仍保留，可重新保存。");showMoreActions();error(message(e));});}
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
        List<TocParser.Row> rows=parsed.rows;int pageCount=info.pageCount;
        setBusy(true,"正在原文件夹核验新PDF并安全切换名称；原件将保留为备份…");
        worker.execute(()->{
            synchronized(replacementLock){
            try{
                if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask())throw new IOException("有未处理的恢复记录，请先核对或恢复。");
                if(!readyVerified||!readyFile.isFile())throw new IOException("请先生成并校验PDF。");
                StagedDocuments documents=StagedDocuments.forDocument(this,destination);
                if(!originalHash.equals(hashUri(destination)))throw new IOException("原PDF在预览后变化，请重新预览。");
                EnjoyDirectorySync.Prepared catalog=synchronize?enjoy.prepare(destination,pageCount,rows,originalHash,replaceExisting):null;
                File folder=new File(getFilesDir(),"pdf-backups");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法建立原PDF备份目录。");
                File backup=new File(folder,UUID.randomUUID()+".pdf");
                try(InputStream in=new FileInputStream(sourceFile);FileOutputStream out=new FileOutputStream(backup)){transfer(in,out);out.getFD().sync();}
                if(!originalHash.equals(hashFile(backup)))throw new IOException("原PDF备份校验失败，未替换原文件。");
                String outputHash=hashFile(readyFile);
                boolean recorded=getPreferences(MODE_PRIVATE).edit().putString("pendingPdf",destination.toString()).putString("pendingBackup",backup.getAbsolutePath())
                    .putString("pendingOriginalHash",originalHash).putString("pendingOutputHash",outputHash).putString("pendingName",name)
                    .putString("lastBackup",backup.getAbsolutePath()).putString("lastBackupName",name).commit();
                if(!recorded)throw new IOException("恢复记录保存失败，未替换原文件。");
                StagedReplacement.Result result=StagedReplacement.replace(documents,name,readyFile,originalHash,journal->saveJournal(documents.folder,journal));
                Uri finalUri=Uri.parse(result.document.uri);
                // 持久授权来自父文件夹；改名后的新标识必须保存，禁止继续使用旧URI。
                String cleanupRecord=new Gson().toJson(new BackupRecord(documents,result,backup));
                if(!getPreferences(MODE_PRIVATE).edit().putString("pdf",finalUri.toString()).putString("pdfName",name).putString("lastReplacement",cleanupRecord).commit())throw new IOException("最终PDF已核验，但新文件标识未保存；恢复记录保留。");
                clearRecovery();
                onUi(()->{pdfUri=finalUri;pdfLabel.setText(inputLabel(name,finalUri));});
                String success="PDF已在原路径同名替换并核验："+name+"。两份原PDF备份保留。";
                if(catalog!=null){
                    try{success+="\n"+enjoy.apply(catalog,outputHash,this::discardReplacedCatalog);}
                    catch(Exception e){finishSave(finalUri,success+"\n"+catalogFailureNotice()+"\n"+message(e),false);return;}
                }
                finishSave(finalUri,success+"\n请重新打开享做核对目录、跳转与原笔迹。",deleteAfter);
            }catch(Exception e){
                boolean needsAuthorization=e instanceof PathDocuments.FolderAuthorizationRequiredException||e instanceof SecurityException;
                onUi(()->{setBusy(false,"未确认替换成功，JSON与备份保留。请按具体错误提示处理后重新核对。");confirmed.setChecked(false);if(readyVerified)showMoreActions();
                    AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("替换未完成").setMessage(message(e)).setNegativeButton("返回",null);
                    if(needsAuthorization)dialog.setPositiveButton("授权原文件夹",(d,w)->authorizeFolder(destination.toString()));
                    dialog.show();});
            }
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
        if(!sameRows(info.existingRows,parsed.rows)){error("当前PDF书签与所选JSON不一致。请先生成并替换PDF，或选择与当前PDF书签相同的JSON；不会只同步不匹配的目录。");return;}
        boolean replaceExisting=replaceCatalog.isChecked();
        new AlertDialog.Builder(this).setTitle("仅同步享做目录")
            .setMessage("PDF已有与JSON相同的书签，不再替换PDF。\n"+catalogModeNotice(replaceExisting)+"\n\n请先保存笔记，并在系统设置中强行停止享做（不要清除数据），然后继续。")
            .setNegativeButton("取消",null).setPositiveButton("已停止享做，同步",(d,w)->{
                Uri source=pdfUri,toc=jsonUri;String expected=pdfHash,tocHash=jsonHash;int count=info.pageCount;List<TocParser.Row> rows=parsed.rows;
                setBusy(true,"正在核验并同步享做目录，PDF与笔迹保留…");
                worker.execute(()->{synchronized(replacementLock){try{
                    if(getPreferences(MODE_PRIVATE).contains("pendingPdf")||enjoy.hasPending()||hasCleanupTask())throw new IOException("有待恢复记录，请先恢复。");
                    if(!tocHash.equals(digest(readSmall(toc))))throw new IOException("JSON在预览后变化，请重新读取。");
                    EnjoyDirectorySync.Prepared plan=enjoy.prepare(source,count,rows,expected,replaceExisting);String result=enjoy.apply(plan,expected,this::discardReplacedCatalog);
                    onUi(()->{exportedUri=source;confirmed.setChecked(false);setBusy(false,result+"\nPDF与JSON未改动。请重新打开享做核对目录、跳转与原笔迹。");});
                }catch(Exception e){onUi(()->{confirmed.setChecked(false);setBusy(false,catalogFailureNotice());error(message(e));});}}});
            }).show();
    }
    private static boolean sameRows(List<TocParser.Row> actual,List<TocParser.Row> expected){
        if(actual.size()!=expected.size())return false;
        for(int i=0;i<actual.size();i++){TocParser.Row a=actual.get(i),b=expected.get(i);if(a.level!=b.level||a.pdfPage!=b.pdfPage||!Objects.equals(a.title,b.title))return false;}
        return true;
    }
    private void confirmRecoverCatalog(){
        if(busy||!enjoy.hasPending())return;
        new AlertDialog.Builder(this).setTitle("恢复享做目录")
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
    /** 自动清理只列出本次目录副本；PDF恢复关联完全保留。 */
    private void discardReplacedCatalog(EnjoyDirectorySync.Record record)throws IOException{
        CleanupPlan plan=catalogCleanupPlan(record);String raw=new Gson().toJson(plan);
        BackupCleanup.Item item=enjoy.cleanupItem(plan.catalogRecord,false);SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        CatalogReplacementCleanup.discard(item,()->{
            verifyCleanupPlan(plan,raw,false);
            if(!prefs.edit().putString("pendingCleanup",raw).commit())throw new IOException("无法保存旧目录清理进度，未开始删除。");
        },()->showAutomaticCleanup(item),()->verifyCleanupPlan(plan,raw,true),()->{
            enjoy.finishCleanup(plan.catalogRecord);verifyCleanupPlan(plan,raw,true);
            if(!prefs.edit().remove("pendingCleanup").commit()){
                prefs.edit().putString("pendingCleanup",raw).commit();
                throw new IOException("旧目录已删除，但完成记录未保存；可按同一清单重试。");
            }
        });
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
            StringBuilder list=new StringBuilder("请先确认新PDF、享做目录、跳转及原笔迹正常，再保存并强行停止享做。\n\n任务PDF（保留）：\n"+cleanupLocation(nameHint(Uri.parse(plan.pdfUri)),plan.pdfUri)+"\n\n计划删除的文件（仅以下关联旧件）：\n");
            for(BackupCleanup.Item item:items)list.append("\n• ").append(cleanupLocation(item.backup.name,item.backup.uri)).append("\n• ").append(item.privateBackup.getAbsolutePath()).append("\n");
            list.append("\n同时删除应用内恢复备份，之后不能用这些备份恢复。当前PDF、活动目录/temp、笔迹、其他配置及原始JSON不在清单中。清理前可取消并在“更多操作与恢复”导出备份。旧版失去关联的备份保留，不扫描删除。");
            if(plan.retainedNotice!=null&&!plan.retainedNotice.isEmpty())list.append("\n\n以下旧件未通过核验，本次保留，不删除：\n").append(plan.retainedNotice);
            if(resume)list.append("\n\n这是已确认清理的重试，部分旧件可能已删除；只继续核验和清理同一清单。");
            onUi(()->{setBusy(false,"清单已核验，等待确认；尚未继续删除。");ScrollView scroll=new ScrollView(this);TextView content=text(list.toString(),15);content.setPadding(dp(20),dp(12),dp(20),dp(12));scroll.addView(content);
                AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("清理旧文件与备份").setView(scroll).setNegativeButton("取消",null).setPositiveButton("清理待删除清单",(d,w)->runCleanup(shownRaw,resume));
                if(resume)dialog.setNeutralButton("保留剩余文件，结束清理",(d,w)->stopCleanup(shownRaw));dialog.show();});
        }catch(Exception e){onUi(()->cleanupFailure(e,"清理核验未通过，没有继续删除；请查看具体提示。"));}}});
    }
    private void runCleanup(String raw,boolean resume){
        setBusy(true,"正在核验并清理列明旧件，内部恢复备份最后删除…");
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
            onUi(()->setBusy(false,"待删除清单内的旧文件与关联恢复备份已全部清理。当前PDF、享做目录和笔迹保留；这些备份已不能用于恢复。"+(plan.retainedNotice==null||plan.retainedNotice.isEmpty()?"":"\n以下旧件未通过核验并保留：\n"+plan.retainedNotice)));
        }catch(Exception e){onUi(()->cleanupFailure(e,"清理尚未全部完成，已删除的旧件不会重复删除。进度与剩余文件保留，请按提示重试；当前PDF和笔迹受保护。"));}}});
    }
    private void cleanupFailure(Exception failure,String notice){
        setBusy(false,notice);AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("清理未完成").setMessage(message(failure)).setNegativeButton("返回",null);
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
            onUi(()->setBusy(false,"已结束本次清理，剩余文件及完整关联记录保留；已删除的旧件不会恢复。可继续其他任务。未通过校验的旧件仍不会删除。"));
        }catch(Exception e){onUi(()->{setBusy(false,"未能结束清理，进度与剩余文件保留。");error(message(e));});}}});
    }
    private void discardCleanupFor(Uri restored,String name)throws IOException{
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);BackupRecord record=backupRecord(prefs.getString("lastReplacement",null));
        if(record==null||!name.equals(record.outputName))return;
        Uri folder=PathDocuments.folderForDocument(this,restored);
        if(sameDocument(folder,Uri.parse(record.folder))&&!prefs.edit().remove("lastReplacement").commit())throw new IOException("原件已恢复，但旧备份清理记录未更新，请重新核对。");
    }
    /** 此方法仅在输出重读校验成功后由工作线程调用。 */
    private void finishSave(Uri destination,String success,boolean deleteAfter){
        boolean deleted=false;String note="";
        if(deleteAfter&&jsonUri!=null){
            try{
                if(Thread.currentThread().isInterrupted())throw new IOException("任务已中断，JSON保留。");
                if(sameDocument(destination,jsonUri))throw new IOException("输出与JSON指向同一文档。");
                if(!displayName(jsonUri).toLowerCase(Locale.ROOT).endsWith(".json"))throw new IOException("来源未确认这是JSON文件，未执行删除。");
                if(!jsonHash.equals(digest(readSmall(jsonUri))))throw new IOException("JSON在预览后已变化。");
                if(!supports(jsonUri,DocumentsContract.Document.FLAG_SUPPORTS_DELETE))throw new IOException("JSON未获删除授权或来源不支持删除；可重新选择JSON授权。");
                if(!DocumentsContract.deleteDocument(getContentResolver(),jsonUri))throw new IOException("文件来源未完成删除。");
                deleted=true;getPreferences(MODE_PRIVATE).edit().remove("json").remove("jsonName").apply();note="\n所选JSON已删除。";
            }catch(Exception e){note="\nPDF已保存，JSON未删除："+message(e);}
        }
        boolean removed=deleted;String result=success+note;
        onUi(()->{exportedUri=destination;confirmed.setChecked(false);if(removed){jsonUri=null;jsonName="";jsonLabel.setText("所选JSON已删除；下次任务请选择新JSON");deleteJson.setChecked(false);}setBusy(false,result);});
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
        new AlertDialog.Builder(this).setTitle("恢复原PDF").setMessage("恢复同名原件："+name+"。使用保留的备份，JSON不删除。请先关闭享做中的该笔记。")
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
        new AlertDialog.Builder(this).setTitle("恢复消失的原 PDF").setMessage("请先关闭享做笔记，然后选择原PDF所在文件夹。将恢复为原名：\n"+name+"\n\n不会覆盖同名的其他文件。备份仍保留；取消可返回。")
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
            new AlertDialog.Builder(this).setTitle("许可与开源说明").setItems(names,(dialog,index)->{
                try(InputStream input=getAssets().open("licenses/"+names[index]);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    transfer(input,bytes);ScrollView scroll=new ScrollView(this);TextView body=text(new String(bytes.toByteArray(),StandardCharsets.UTF_8),14);body.setTextIsSelectable(true);body.setPadding(dp(16),dp(12),dp(16),dp(12));scroll.addView(body);
                    new AlertDialog.Builder(this).setTitle(names[index]).setView(scroll).setPositiveButton("关闭",null).show();
                }catch(IOException e){error(message(e));}
            }).setNegativeButton("关闭",null).show();
        }catch(IOException e){error(message(e));}
    }
    private InputStream readInput(Uri uri,ReadJob job)throws IOException {
        if(job==null){InputStream input=getContentResolver().openInputStream(uri);if(input==null)throw new IOException("无法读取文件。");return input;}
        job.check();android.content.res.AssetFileDescriptor fd=getContentResolver().openAssetFileDescriptor(uri,"r",job.signal);
        if(fd==null)throw new IOException("文件来源未能打开文档。");
        InputStream input;try{input=fd.createInputStream();}catch(IOException e){fd.close();throw e;}
        job.attach(input);return input;
    }
    private String copySource(Uri uri,File target,ReadJob job)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try(InputStream input=readInput(uri,job);FileOutputStream output=new FileOutputStream(target)){
            if(input==null)throw new IOException("无法读取PDF，请重新选择文件。");byte[] buffer=new byte[1024*1024];int n;
            while((n=input.read(buffer))!=-1){job.check();output.write(buffer,0,n);sha.update(buffer,0,n);}output.getFD().sync();
        }finally{job.detach();}return hex(sha.digest());
    }
    private byte[] readSmall(Uri uri)throws IOException{return readSmall(uri,null);}
    private byte[] readSmall(Uri uri,ReadJob job)throws IOException{
        try(InputStream input=readInput(uri,job);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(input==null)throw new IOException("无法读取JSON，请重新选择文件。");byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){if(job!=null)job.check();if(output.size()+n>4*1024*1024)throw new IOException("目录JSON超过4MB，请确认所选文件。");output.write(buffer,0,n);}return output.toByteArray();
        }finally{if(job!=null)job.detach();}
    }
    private static void transfer(InputStream input,OutputStream output)throws IOException{byte[] buffer=new byte[1024*1024];int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("保存已取消。");output.write(buffer,0,n);}}
    private String hashUri(Uri uri)throws Exception{try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("无法核对文件。");return hash(input);}}
    private static String hashFile(File file)throws Exception{try(InputStream input=new FileInputStream(file)){return hash(input);}}
    private static String hash(InputStream input)throws Exception{MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[1024*1024];int n;while((n=input.read(buffer))!=-1)sha.update(buffer,0,n);return hex(sha.digest());}
    private static String digest(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void error(String message){new AlertDialog.Builder(this).setTitle("需要处理").setMessage(message).setPositiveButton("知道了",null).show();}
    @Override public void onBackPressed(){
        if(pathEditor.getVisibility()==View.VISIBLE){closePathEditor();status.setText("已取消路径输入，原来的文件选择保留。");return;}
        if(readJob!=null){cancelReading();return;}
        if(busy){new AlertDialog.Builder(this).setMessage("正在处理文件，请等待完成后退出，避免保存中断。").setPositiveButton("继续等待",null).show();return;}super.onBackPressed();
    }
    @Override protected void onDestroy(){previewGeneration++;if(readJob!=null){readJob.cancelled=true;if(readJob.future!=null)readJob.future.cancel(true);readers.execute(readJob::closeInput);}readers.shutdown();worker.shutdown();clearImage();super.onDestroy();}
}
