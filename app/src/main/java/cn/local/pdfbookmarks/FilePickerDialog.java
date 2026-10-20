package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.Dialog;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 只读浏览已授予的SAF范围；取消按钮不参与查询任务的忙碌状态。 */
final class FilePickerDialog extends Dialog {
    private static final int INK=0xff1d3439,ACCENT=0xff147a70;
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
    private final Activity activity;
    private final FilePickerState state;
    private final ExecutorService queries=Executors.newCachedThreadPool();
    private CancellationSignal signal;
    private final TextView location,notice;
    private final Button up;
    private final BaseAdapter adapter;

    FilePickerDialog(Activity activity,boolean pdf,Consumer<Uri> selected,Runnable cancelled,
                     Runnable systemPicker,Runnable authorizeFolder){
        super(activity);this.activity=activity;state=new FilePickerState(pdf);setOwnerActivity(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=column();shell.setPadding(dp(20),dp(18),dp(20),dp(16));shell.setBackgroundColor(0xfff4f7f8);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(pdf?"选择 PDF":"选择目录 JSON",21);heading.setTypeface(null,Typeface.BOLD);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button cancel=action("取消选择",this::cancel);header.addView(cancel);shell.addView(header);
        location=text("已授权的位置",15);location.setPadding(0,dp(14),0,dp(8));shell.addView(location);
        up=action("返回上级",()->{if(state.up())load();});shell.addView(up);
        notice=text("正在读取文件位置…",14);notice.setPadding(0,dp(12),0,dp(12));shell.addView(notice);
        ListView list=new ListView(activity);list.setDividerHeight(dp(1));
        adapter=new BaseAdapter(){
            @Override public int getCount(){return state.entries().size();}
            @Override public Object getItem(int position){return state.entries().get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                LinearLayout row;
                if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;
                else{
                    row=column();row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setMinimumHeight(dp(64));
                    row.setBackgroundColor(0xffffffff);row.addView(text("",16));row.addView(text("",13));
                }
                FilePickerState.Entry entry=state.entries().get(position);
                row.setTag(entry);
                ((TextView)row.getChildAt(0)).setText(entry.name);
                TextView detail=(TextView)row.getChildAt(1);detail.setTextColor(0xff52676c);
                detail.setText((entry.folder?"文件夹  ›":(pdf?"PDF 文件":"JSON 目录文件"))
                        +(state.location()==null?" · "+locationName(entry):""));return row;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,index,id)->{
            Object tag=view.getTag();if(!(tag instanceof FilePickerState.Entry)||!state.entries().contains(tag))return;
            FilePickerState.Entry entry=(FilePickerState.Entry)tag;
            if(entry.folder){state.enter(entry);load();}
            else{state.select(entry);dismiss();selected.accept(Uri.parse(entry.uri));}
        });
        shell.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text("只显示已授权的文件夹和匹配文件。未选文件也能随时取消，原来的选择保留。",13);
        hint.setPadding(0,dp(12),0,dp(6));shell.addView(hint);
        shell.addView(action("添加文件夹授权…",()->{dismiss();authorizeFolder.run();}));
        shell.addView(action("更多文件来源（系统）…",()->{dismiss();systemPicker.run();}));
        setContentView(shell);setCancelable(true);setCanceledOnTouchOutside(true);
        setOnCancelListener(dialog->cancelled.run());
        setOnDismissListener(dialog->{state.cancel();cancelQuery();queries.shutdown();});
    }
    @Override public void show(){super.show();resize();load();}
    void resize(){
        Window window=getWindow();if(window==null)return;
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        window.setLayout(Math.min(dp(840),metrics.widthPixels-dp(24)),Math.max(dp(280),(int)(metrics.heightPixels*0.9f)));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void cancelQuery(){CancellationSignal old=signal;signal=null;if(old!=null)queries.execute(old::cancel);}
    private void load(){
        cancelQuery();int token=state.beginLoad();FilePickerState.Entry folder=state.location();
        up.setEnabled(folder!=null);location.setText(folder==null?"已授权的位置":locationName(folder));
        notice.setText("正在读取列表…可随时取消。");adapter.notifyDataSetChanged();
        CancellationSignal query=new CancellationSignal();signal=query;
        queries.execute(()->{
            try{
                List<FilePickerState.Entry> found=folder==null?roots(query):children(Uri.parse(folder.uri),query);
                activity.runOnUiThread(()->{
                    if(!isShowing()||activity.isDestroyed()||!state.publish(token,found))return;
                    adapter.notifyDataSetChanged();notice.setText(state.entries().isEmpty()
                            ?(folder==null?"还没有可用位置。可添加文件夹授权，或选择更多文件来源。":"这个文件夹没有匹配文件。可返回上级或取消选择。")
                            :"点击文件夹进入；点击文件完成选择。");
                });
            }catch(Exception e){
                activity.runOnUiThread(()->{
                    if(!isShowing()||activity.isDestroyed()||!state.publish(token,new ArrayList<>()))return;
                    adapter.notifyDataSetChanged();notice.setText("无法读取此位置："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())+"。可返回上级、重新授权或取消。");
                });
            }
        });
    }
    private List<FilePickerState.Entry> roots(CancellationSignal query)throws IOException{
        List<FilePickerState.Entry> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(UriPermission permission:activity.getContentResolver().getPersistedUriPermissions()){
            query.throwIfCanceled();if(!permission.isReadPermission())continue;Uri uri=permission.getUri();
            try{
                if(DocumentsContract.isTreeUri(uri)&&uri.getPathSegments().size()==2)
                    uri=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));
                else if(!DocumentsContract.isDocumentUri(activity,uri))continue;
                if(!seen.add(uri.toString()))continue;
                try(Cursor cursor=activity.getContentResolver().query(uri,COLUMNS,null,null,null,query)){
                    if(cursor!=null&&cursor.moveToFirst())result.add(entry(cursor,uri));
                }
            }catch(SecurityException|IllegalArgumentException e){
                // 不把失效或无法解释的旧授权扩展为其他文件夹的访问权。
            }
        }
        return result;
    }
    private List<FilePickerState.Entry> children(Uri folder,CancellationSignal query)throws IOException{
        List<FilePickerState.Entry> result=new ArrayList<>();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(folder,DocumentsContract.getDocumentId(folder));
        try(Cursor cursor=activity.getContentResolver().query(children,COLUMNS,null,null,null,query)){
            if(cursor==null)throw new IOException("文件来源没有返回列表");
            while(cursor.moveToNext()){
                query.throwIfCanceled();String id=cursor.getString(0);
                result.add(entry(cursor,DocumentsContract.buildDocumentUriUsingTree(folder,id)));
            }
        }
        return result;
    }
    private FilePickerState.Entry entry(Cursor cursor,Uri uri){
        String name=cursor.getString(1),mime=cursor.getString(2);
        boolean directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        return new FilePickerState.Entry(uri.toString(),name==null?(directory?"已授权文件夹":"未命名文件"):name,mime,directory);
    }
    private String locationName(FilePickerState.Entry entry){
        Uri uri=Uri.parse(entry.uri);
        try{return DocumentsContract.getDocumentId(uri).replaceFirst("^primary:","/内部存储/");}
        catch(IllegalArgumentException e){return entry.name;}
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(INK);return view;}
    private Button action(String label,Runnable action){
        Button button=new Button(activity);button.setText(label);button.setTextSize(14);button.setTextColor(ACCENT);
        button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(view->action.run());return button;
    }
}
