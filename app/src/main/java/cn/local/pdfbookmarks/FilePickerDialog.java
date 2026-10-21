package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.Dialog;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
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
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
    private final Activity activity;
    private final UiAppearance appearance;
    private final FilePickerState state;
    private final ExecutorService queries=Executors.newCachedThreadPool();
    private CancellationSignal signal;
    private final TextView location,notice;
    private final Button up;
    private final BaseAdapter adapter;

    FilePickerDialog(Activity activity,boolean pdf,Consumer<Uri> selected,Runnable cancelled,
                     Runnable systemPicker,Runnable authorizeFolder){
        super(UiAppearance.load(activity).dialogContext(activity));this.activity=activity;
        appearance=UiAppearance.load(activity);state=new FilePickerState(pdf);setOwnerActivity(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=column();shell.setPadding(space(20),space(18),space(20),space(16));shell.setBackgroundColor(appearance.page);
        LinearLayout header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(getContext().getString(pdf?R.string.picker_pdf_title:R.string.picker_json_title),21);heading.setTypeface(null,Typeface.BOLD);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button cancel=action(getContext().getString(R.string.picker_cancel),this::cancel);header.addView(cancel);shell.addView(header);
        location=text(getContext().getString(R.string.picker_granted_locations),15);location.setPadding(0,space(14),0,space(8));shell.addView(location);
        up=action(getContext().getString(R.string.picker_up),()->{if(state.up())load();});shell.addView(up);
        notice=text(getContext().getString(R.string.picker_loading_locations),14);notice.setPadding(0,space(12),0,space(12));notice.setTextColor(appearance.secondaryText);shell.addView(notice);
        ListView list=new ListView(getContext());list.setDivider(new ColorDrawable(appearance.outline));list.setDividerHeight(dp(1));
        list.setBackgroundColor(appearance.surface);list.setCacheColorHint(Color.TRANSPARENT);
        adapter=new BaseAdapter(){
            @Override public int getCount(){return state.entries().size();}
            @Override public Object getItem(int position){return state.entries().get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                LinearLayout row;
                if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;
                else{
                    row=column();row.setPadding(space(12),space(12),space(12),space(12));
                    row.setMinimumHeight(dp(Math.max(appearance.touchDp(),appearance.spacingDp(64))));
                    StateListDrawable background=new StateListDrawable();
                    background.addState(new int[]{android.R.attr.state_activated},new ColorDrawable(appearance.selectedRow));
                    background.addState(new int[]{android.R.attr.state_selected},new ColorDrawable(appearance.selectedRow));
                    background.addState(new int[]{android.R.attr.state_focused},new ColorDrawable(appearance.selectedRow));
                    background.addState(new int[]{},new ColorDrawable(appearance.surface));
                    row.setBackground(new RippleDrawable(ColorStateList.valueOf((appearance.accent&0x00ffffff)|0x29000000),background,null));
                    row.addView(text("",16));row.addView(text("",13));
                }
                FilePickerState.Entry entry=state.entries().get(position);
                row.setTag(entry);
                ((TextView)row.getChildAt(0)).setText(entry.name);
                TextView detail=(TextView)row.getChildAt(1);detail.setTextColor(appearance.secondaryText);
                String type=getContext().getString(entry.folder?R.string.picker_folder_type:(pdf?R.string.picker_pdf_type:R.string.picker_json_type));
                detail.setText(state.location()==null?getContext().getString(R.string.picker_location_detail,type,locationName(entry)):type);return row;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,index,id)->{
            Object tag=view.getTag();if(!(tag instanceof FilePickerState.Entry)||!state.entries().contains(tag))return;
            FilePickerState.Entry entry=(FilePickerState.Entry)tag;
            if(entry.folder){state.enter(entry);load();}
            else{state.select(entry);dismiss();selected.accept(Uri.parse(entry.uri));}
        });
        shell.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text(getContext().getString(R.string.picker_scope_hint),13);hint.setTextColor(appearance.secondaryText);
        hint.setPadding(0,space(12),0,space(6));shell.addView(hint);
        shell.addView(action(getContext().getString(R.string.picker_authorize_folder),()->{dismiss();authorizeFolder.run();}));
        shell.addView(action(getContext().getString(R.string.picker_more_sources),()->{dismiss();systemPicker.run();}));
        setContentView(shell);setCancelable(true);setCanceledOnTouchOutside(true);
        setOnCancelListener(dialog->cancelled.run());
        setOnDismissListener(dialog->{state.cancel();cancelQuery();queries.shutdown();});
    }
    @Override public void show(){super.show();resize();load();}
    void resize(){
        Window window=getWindow();if(window==null)return;
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        View decor=activity.getWindow().getDecorView();
        int width=decor.getWidth()>0?decor.getWidth():metrics.widthPixels;
        int height=decor.getHeight()>0?decor.getHeight():metrics.heightPixels;
        window.setLayout(Math.max(1,Math.min(dp(840),width-dp(24))),Math.max(1,Math.min(height-dp(16),Math.max(dp(280),(int)(height*.9f)))));
        window.setBackgroundDrawable(new ColorDrawable(appearance.page));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void cancelQuery(){CancellationSignal old=signal;signal=null;if(old!=null)queries.execute(old::cancel);}
    private void load(){
        cancelQuery();int token=state.beginLoad();FilePickerState.Entry folder=state.location();
        up.setEnabled(folder!=null);location.setText(folder==null?getContext().getString(R.string.picker_granted_locations):locationName(folder));
        setNotice(getContext().getString(R.string.picker_loading),false);adapter.notifyDataSetChanged();
        CancellationSignal query=new CancellationSignal();signal=query;
        queries.execute(()->{
            try{
                List<FilePickerState.Entry> found=folder==null?roots(query):children(Uri.parse(folder.uri),query);
                activity.runOnUiThread(()->{
                    if(!isShowing()||activity.isDestroyed()||!state.publish(token,found))return;
                    adapter.notifyDataSetChanged();setNotice(getContext().getString(state.entries().isEmpty()
                            ?(folder==null?R.string.picker_no_locations:R.string.picker_no_files)
                            :R.string.picker_choose_hint),false);
                });
            }catch(Exception e){
                activity.runOnUiThread(()->{
                    if(!isShowing()||activity.isDestroyed()||!state.publish(token,new ArrayList<>()))return;
                    adapter.notifyDataSetChanged();setNotice(getContext().getString(R.string.picker_read_error,
                            e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),true);
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
    private void setNotice(String value,boolean failure){
        notice.setText(value);notice.setTextColor(failure?appearance.error:appearance.secondaryText);
        notice.setBackgroundColor(failure?appearance.errorBg:Color.TRANSPARENT);
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private int space(int value){return dp(appearance.spacingDp(value));}
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(getContext());view.setText(value);appearance.styleText(view,size);return view;}
    private Button action(String label,Runnable action){
        Button button=new Button(getContext());button.setText(label);appearance.styleButton(button,false);button.setTextSize(appearance.textSp(14));
        button.setOnClickListener(view->action.run());return button;
    }
}
