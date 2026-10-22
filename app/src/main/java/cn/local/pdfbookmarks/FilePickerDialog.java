package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** 浏览已授予的SAF范围；根列表仅管理授权，永不删除文档。 */
final class FilePickerDialog extends Dialog {
    private static final AtomicInteger GRANT_CHANGES=new AtomicInteger();
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
    private final Activity activity;
    private final UiAppearance appearance;
    private final FilePickerState state;
    private final Consumer<Uri> selected;
    private final BooleanSupplier canManageGrants;
    private final Runnable grantsChanged;
    private final GrantSelection selection=new GrantSelection();
    private final Map<FilePickerState.Entry,RootItem> rootItems=new LinkedHashMap<>();
    private final ExecutorService queries=Executors.newCachedThreadPool();
    private CancellationSignal signal;
    private final TextView location,notice;
    private final Button up;
    private final Button choose,all,remove,tools,authorize,systemSource;
    private final TextView selectedCount;
    private final LinearLayout management,selectionBar;
    private final BaseAdapter adapter;
    private SwipeGrantRow openSwipe;
    private AlertDialog childDialog;
    private boolean revoking,disposed;

    static boolean isGrantChangeRunning(){return GRANT_CHANGES.get()>0;}

    private static final class RootItem {
        final FilePickerState.Entry entry;
        final GrantSelection.Grant grant;
        final String path,error;
        RootItem(FilePickerState.Entry entry,GrantSelection.Grant grant,String path,String error){
            this.entry=entry;this.grant=grant;this.path=path;this.error=error;
        }
        boolean readable(){return (grant.flags&GrantSelection.READ)!=0&&error==null;}
    }
    private static final class RootLoad {
        final List<FilePickerState.Entry> entries=new ArrayList<>();
        final Map<FilePickerState.Entry,RootItem> items=new LinkedHashMap<>();
        void add(RootItem item){entries.add(item.entry);items.put(item.entry,item);}
    }

    FilePickerDialog(Activity activity,boolean pdf,Consumer<Uri> selected,Runnable cancelled,
                     Runnable systemPicker,Runnable authorizeFolder,BooleanSupplier canManageGrants,Runnable grantsChanged){
        super(UiAppearance.load(activity).dialogContext(activity));this.activity=activity;
        this.selected=selected;
        this.canManageGrants=canManageGrants;this.grantsChanged=grantsChanged;
        appearance=UiAppearance.load(activity);state=new FilePickerState(pdf);setOwnerActivity(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=column();shell.setPadding(space(20),space(18),space(20),space(16));shell.setBackgroundColor(appearance.page);
        LinearLayout header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(getContext().getString(pdf?R.string.picker_pdf_title:R.string.picker_json_title),21);heading.setTypeface(null,Typeface.BOLD);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button cancel=action(getContext().getString(R.string.picker_cancel),this::cancel);header.addView(cancel);shell.addView(header);
        location=text(getContext().getString(R.string.picker_granted_locations),15);location.setPadding(0,space(14),0,space(8));shell.addView(location);
        up=action(getContext().getString(R.string.picker_up),()->{if(state.up())load();});shell.addView(up);
        management=column();
        LinearLayout managementHeader=new LinearLayout(getContext());managementHeader.setGravity(Gravity.CENTER_VERTICAL);
        choose=action(getContext().getString(R.string.grant_select),()->setSelectionMode(!selection.isSelectionMode()));
        managementHeader.addView(choose,new LinearLayout.LayoutParams(0,-2,1));
        tools=action(getContext().getString(R.string.grant_tools),this::showListTools);
        managementHeader.addView(tools,new LinearLayout.LayoutParams(0,-2,1));management.addView(managementHeader);
        selectionBar=column();LinearLayout selectionHeader=new LinearLayout(getContext());selectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        all=action(getContext().getString(R.string.grant_select_all),()->{
            if(selection.selectedSnapshot().size()==selection.currentSnapshot().size())selection.clearSelection();else selection.selectAll();
            selectionChanged();
        });selectionHeader.addView(all);
        selectedCount=text("",13);selectedCount.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);selectionHeader.addView(selectedCount,new LinearLayout.LayoutParams(0,-2,1));
        selectionBar.addView(selectionHeader);
        remove=action("",()->confirmRemoval(selection.selectedSnapshot()));styleRemove(remove);selectionBar.addView(remove,new LinearLayout.LayoutParams(-1,-2));
        management.addView(selectionBar);shell.addView(management);
        notice=text(getContext().getString(R.string.picker_loading_locations),14);notice.setPadding(0,space(12),0,space(12));notice.setTextColor(appearance.secondaryText);shell.addView(notice);
        ListView list=new ListView(getContext());list.setDivider(new ColorDrawable(appearance.outline));list.setDividerHeight(dp(1));
        list.setBackgroundColor(appearance.surface);list.setCacheColorHint(Color.TRANSPARENT);
        adapter=new BaseAdapter(){
            @Override public int getCount(){return state.entries().size();}
            @Override public Object getItem(int position){return state.entries().get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                FilePickerState.Entry entry=state.entries().get(position);
                if(state.location()==null){
                    RootItem root=rootItems.get(entry);if(root!=null)return grantRow(root);
                }
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
            if(state.location()==null){RootItem root=rootItems.get(entry);if(root!=null){openRoot(root);return;}}
            if(entry.folder){state.enter(entry);load();}
            else{state.select(entry);dismiss();selected.accept(Uri.parse(entry.uri));}
        });
        shell.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text(getContext().getString(R.string.picker_scope_hint),13);hint.setTextColor(appearance.secondaryText);
        hint.setPadding(0,space(12),0,space(6));shell.addView(hint);
        authorize=action(getContext().getString(R.string.picker_authorize_folder),()->{if(!revoking){dismiss();authorizeFolder.run();}});shell.addView(authorize);
        systemSource=action(getContext().getString(R.string.picker_more_sources),()->{if(!revoking){dismiss();systemPicker.run();}});shell.addView(systemSource);
        setContentView(shell);setCancelable(true);setCanceledOnTouchOutside(true);
        setOnCancelListener(dialog->cancelled.run());
        setOnDismissListener(dialog->{
            if(disposed)return;disposed=true;
            if(childDialog!=null)childDialog.dismiss();state.cancel();cancelQuery();queries.shutdown();
        });
    }
    @Override public void show(){super.show();resize();load();}
    @Override public void onBackPressed(){
        if(childDialog!=null&&childDialog.isShowing()){childDialog.dismiss();return;}
        if(closeSwipe())return;
        if(selection.isSelectionMode()){setSelectionMode(false);return;}
        super.onBackPressed();
    }
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
        if(disposed)return;
        closeSwipe();cancelQuery();int token=state.beginLoad();FilePickerState.Entry folder=state.location();
        if(folder!=null)selection.setSelectionMode(false);
        up.setVisibility(folder==null?View.GONE:View.VISIBLE);up.setEnabled(folder!=null);
        location.setText(folder==null?getContext().getString(R.string.picker_granted_locations):locationName(folder));
        setNotice(getContext().getString(R.string.picker_loading),false);adapter.notifyDataSetChanged();updateManagement();
        CancellationSignal query=new CancellationSignal();signal=query;
        queries.execute(()->{
            try{
                RootLoad roots=folder==null?roots(query):null;
                List<FilePickerState.Entry> found=roots==null?children(Uri.parse(folder.uri),query):roots.entries;
                activity.runOnUiThread(()->{
                    if(!isShowing()||activity.isDestroyed()||!state.publish(token,found))return;
                    rootItems.clear();if(roots!=null)rootItems.putAll(roots.items);
                    List<GrantSelection.Grant> visible=new ArrayList<>();
                    for(FilePickerState.Entry entry:state.entries()){
                        RootItem root=rootItems.get(entry);if(root!=null)visible.add(root.grant);
                    }
                    selection.replaceCurrent(visible);updateManagement();adapter.notifyDataSetChanged();setNotice(getContext().getString(state.entries().isEmpty()
                            ?(folder==null?R.string.picker_no_locations:R.string.picker_no_files)
                            :(folder==null?R.string.grant_root_hint:R.string.picker_choose_hint)),false);
                });
            }catch(Exception e){
                activity.runOnUiThread(()->{
                    if(!isShowing()||activity.isDestroyed()||!state.publish(token,new ArrayList<>()))return;
                    rootItems.clear();selection.replaceCurrent(new ArrayList<>());updateManagement();adapter.notifyDataSetChanged();setNotice(getContext().getString(R.string.picker_read_error,
                            e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),true);
                });
            }
        });
    }
    private RootLoad roots(CancellationSignal query){
        RootLoad result=new RootLoad();
        for(UriPermission permission:activity.getContentResolver().getPersistedUriPermissions()){
            query.throwIfCanceled();Uri original=permission.getUri(),uri=original;
            int flags=(permission.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)
                    |(permission.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);
            if(flags==0)continue;
            GrantSelection.Grant grant=new GrantSelection.Grant(original.toString(),flags);
            String path=original.toString(),name=original.toString(),error=null;
            FilePickerState.Entry entry=null;
            try{
                if(DocumentsContract.isTreeUri(uri)&&uri.getPathSegments().size()==2){
                    path=displayPath(DocumentsContract.getTreeDocumentId(uri));
                    uri=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));
                }else if(DocumentsContract.isDocumentUri(activity,uri))path=displayPath(DocumentsContract.getDocumentId(uri));
                else throw new IllegalArgumentException(getContext().getString(R.string.grant_unavailable));
                int slash=path.lastIndexOf('/');name=slash>=0&&slash<path.length()-1?path.substring(slash+1):path;
                if(!permission.isReadPermission())error=getContext().getString(R.string.grant_write_only);
                else try(Cursor cursor=activity.getContentResolver().query(uri,COLUMNS,null,null,null,query)){
                    if(cursor!=null&&cursor.moveToFirst())entry=entry(cursor,uri);
                    else error=getContext().getString(R.string.grant_unavailable);
                }
            }catch(Exception e){
                query.throwIfCanceled();error=getContext().getString(R.string.grant_unavailable);
            }
            // 无法读取的授权仍可管理，但不能借此取得其他路径的访问权。
            if(entry==null)entry=new FilePickerState.Entry(uri.toString(),name,DocumentsContract.Document.MIME_TYPE_DIR,true);
            result.add(new RootItem(entry,grant,path,error));
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
        try{return displayPath(DocumentsContract.getDocumentId(uri));}
        catch(IllegalArgumentException e){return entry.name;}
    }
    private static String displayPath(String documentId){return documentId.replaceFirst("^primary:","/内部存储/");}
    private void setSelectionMode(boolean enabled){
        closeSwipe();selection.setSelectionMode(enabled);selectionChanged();
    }
    private void selectionChanged(){updateManagement();adapter.notifyDataSetChanged();}
    private void updateManagement(){
        boolean root=state.location()==null,hasEntries=!state.entries().isEmpty();
        management.setVisibility(root?View.VISIBLE:View.GONE);
        choose.setText(getContext().getString(selection.isSelectionMode()?R.string.grant_exit_selection:R.string.grant_select));
        choose.setEnabled(!revoking&&hasEntries);tools.setEnabled(!revoking);
        authorize.setEnabled(!revoking);systemSource.setEnabled(!revoking);
        selectionBar.setVisibility(selection.isSelectionMode()?View.VISIBLE:View.GONE);
        int count=selection.selectedSnapshot().size();
        all.setText(getContext().getString(count>0&&count==selection.currentSnapshot().size()?R.string.grant_clear_selection:R.string.grant_select_all));
        all.setEnabled(!revoking&&hasEntries);
        selectedCount.setText(getContext().getString(R.string.grant_selected_count,count));
        remove.setText(getContext().getString(R.string.grant_remove_count,count));
        remove.setEnabled(!revoking&&hasEntries&&count>0&&canManageGrants.getAsBoolean());
    }
    private View grantRow(RootItem root){
        SwipeGrantRow row=new SwipeGrantRow(getContext());
        LinearLayout actions=new LinearLayout(getContext());actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        Button details=action(getContext().getString(R.string.grant_details),()->showDetails(root));
        Button revoke=action(getContext().getString(R.string.grant_remove),()->confirmRemoval(java.util.Collections.singletonList(root.grant)));
        styleRemove(revoke);revoke.setEnabled(!revoking&&canManageGrants.getAsBoolean());
        int actionWidth=dp(184);
        actions.addView(details,new LinearLayout.LayoutParams(dp(72),-1));
        actions.addView(revoke,new LinearLayout.LayoutParams(dp(112),-1));
        FrameLayout.LayoutParams actionsLayout=new FrameLayout.LayoutParams(actionWidth,-1,Gravity.END);
        row.addView(actions,actionsLayout);
        LinearLayout content=new LinearLayout(getContext());content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(space(12),space(10),space(4),space(10));content.setMinimumHeight(dp(Math.max(appearance.touchDp(),appearance.spacingDp(72))));
        StateListDrawable background=new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_activated},new ColorDrawable(appearance.selectedRow));
        background.addState(new int[]{android.R.attr.state_focused},new ColorDrawable(appearance.selectedRow));
        background.addState(new int[]{},new ColorDrawable(appearance.surface));
        content.setBackground(new RippleDrawable(ColorStateList.valueOf((appearance.accent&0x00ffffff)|0x29000000),background,null));
        boolean isSelected=false;for(GrantSelection.Grant grant:selection.selectedSnapshot())if(grant.uri.equals(root.grant.uri)){isSelected=true;break;}
        content.setActivated(isSelected);
        if(selection.isSelectionMode()){
            CheckBox check=new CheckBox(getContext());appearance.styleChoice(check);check.setChecked(isSelected);
            check.setContentDescription(getContext().getString(R.string.grant_select)+" "+root.entry.name);
            check.setEnabled(!revoking);check.setOnCheckedChangeListener((button,value)->toggle(root));content.addView(check);
        }
        LinearLayout labels=column();TextView name=text(root.entry.name,16);labels.addView(name);
        TextView path=text(root.path,13);path.setTextColor(appearance.secondaryText);labels.addView(path);
        if(!root.readable()){
            TextView unavailable=text(root.error==null?getContext().getString(R.string.grant_write_only):root.error,12);
            unavailable.setTextColor(appearance.error);labels.addView(unavailable);
        }
        content.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=action("⋮",()->showRootActions(root));menu.setContentDescription(getContext().getString(R.string.grant_actions,root.entry.name));
        menu.setMinimumWidth(dp(48));menu.setMinWidth(dp(48));content.addView(menu,new LinearLayout.LayoutParams(dp(48),-1));
        content.setFocusable(true);content.setOnClickListener(view->openRoot(root));
        content.setOnLongClickListener(view->{if(revoking||!isCurrentRoot(root))return false;setSelectionMode(true);toggle(root);return true;});
        row.addView(content,new FrameLayout.LayoutParams(-1,-2));row.setTag(root.entry);
        row.configure(content,actions,actionWidth,()->{if(openSwipe!=null&&openSwipe!=row)openSwipe.close(true);openSwipe=row;});
        row.allowSwipe(!selection.isSelectionMode()&&!revoking);return row;
    }
    private boolean isCurrentRoot(RootItem root){
        return !disposed&&state.location()==null&&rootItems.get(root.entry)==root&&state.entries().contains(root.entry);
    }
    private void toggle(RootItem root){
        if(revoking||!isCurrentRoot(root))return;
        if(!selection.isSelectionMode())selection.setSelectionMode(true);
        selection.toggle(root.grant.uri);selectionChanged();
    }
    private void openRoot(RootItem root){
        if(revoking||!isCurrentRoot(root))return;
        if(closeSwipe())return;
        if(selection.isSelectionMode()){toggle(root);return;}
        if(!root.readable()){showDetails(root);return;}
        if(root.entry.folder){state.enter(root.entry);load();}
        else{state.select(root.entry);dismiss();selected.accept(Uri.parse(root.entry.uri));}
    }
    private boolean closeSwipe(){
        SwipeGrantRow old=openSwipe;openSwipe=null;if(old==null)return false;
        boolean opened=old.isOpen();old.close(true);return opened;
    }
    private void showListTools(){
        closeSwipe();CharSequence[] labels=state.entries().isEmpty()
                ?new CharSequence[]{getContext().getString(R.string.grant_refresh)}
                :new CharSequence[]{getContext().getString(R.string.grant_refresh),getContext().getString(R.string.grant_select_all)};
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(R.string.grant_tools)
                .setAdapter(appearance.dialogListAdapter(getContext(),labels),(which,index)->{
                    if(revoking)return;
                    if(index==0)load();else if(!state.entries().isEmpty()){selection.selectAll();selectionChanged();}
                }).setNegativeButton(R.string.grant_cancel,null).create();
        showChild(dialog);
    }
    private void showRootActions(RootItem root){
        closeSwipe();CharSequence[] labels={getContext().getString(R.string.grant_details),getContext().getString(R.string.grant_select),getContext().getString(R.string.grant_remove)};
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(root.entry.name)
                .setAdapter(appearance.dialogListAdapter(getContext(),labels),(which,index)->{
                    if(index==0)showDetails(root);
                    else if(index==1){if(!revoking){setSelectionMode(true);toggle(root);}}
                    else confirmRemoval(java.util.Collections.singletonList(root.grant));
                }).setNegativeButton(R.string.grant_cancel,null).create();showChild(dialog);
    }
    private void showDetails(RootItem root){
        closeSwipe();String detail=root.path+"\n\n"+flags(root.grant)+"\n\n"
                +getContext().getString(R.string.grant_exact_uri,root.grant.uri);
        if(!root.readable())detail+="\n\n"+(root.error==null?getContext().getString(R.string.grant_unavailable):root.error);
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(root.entry.name).setMessage(detail)
                .setPositiveButton(R.string.grant_copy_path,(which,id)->{
                    ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if(clipboard!=null){
                        String copied=copyLocation(root);clipboard.setPrimaryClip(ClipData.newPlainText(root.entry.name,copied));
                        setNotice(getContext().getString(copied.equals(root.grant.uri)?R.string.grant_uri_copied:R.string.grant_copied),false);
                    }
                }).setNeutralButton(R.string.grant_remove,(which,id)->confirmRemoval(java.util.Collections.singletonList(root.grant)))
                .setNegativeButton(R.string.grant_cancel,null).create();showChild(dialog);
        Button revoke=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);revoke.setTextColor(appearance.error);
        revoke.setEnabled(!revoking&&canManageGrants.getAsBoolean());
    }
    private String copyLocation(RootItem root){
        Uri uri=Uri.parse(root.grant.uri);
        if("com.android.externalstorage.documents".equals(uri.getAuthority())){
            try{
                String id=DocumentsContract.isDocumentUri(activity,uri)?DocumentsContract.getDocumentId(uri):DocumentsContract.getTreeDocumentId(uri);
                if(id.startsWith("primary:"))return "/storage/emulated/0/"+id.substring("primary:".length());
            }catch(IllegalArgumentException ignored){
                // Provider-specific or unrecognized locations retain their exact authorized URI.
            }
        }
        return root.grant.uri;
    }
    private String flags(GrantSelection.Grant grant){
        return getContext().getString(R.string.grant_flags,getContext().getString((grant.flags&GrantSelection.READ)!=0?R.string.grant_yes:R.string.grant_no),
                getContext().getString((grant.flags&GrantSelection.WRITE)!=0?R.string.grant_yes:R.string.grant_no));
    }
    private String path(GrantSelection.Grant grant,Map<String,String> paths){String path=paths.get(grant.uri);return path==null?grant.uri:path;}
    private Map<String,String> paths(){
        Map<String,String> result=new LinkedHashMap<>();for(RootItem item:rootItems.values())result.put(item.grant.uri,item.path);return result;
    }
    private void confirmRemoval(List<GrantSelection.Grant> targets){
        closeSwipe();if(revoking||targets.isEmpty()||state.location()!=null)return;
        if(isGrantChangeRunning()||!canManageGrants.getAsBoolean()){setNotice(getContext().getString(R.string.grant_busy),true);return;}
        List<GrantSelection.Grant> confirmed=new ArrayList<>(targets);Map<String,String> paths=paths();
        StringBuilder message=new StringBuilder(getContext().getString(R.string.grant_confirm_intro));int index=0;
        for(GrantSelection.Grant grant:confirmed)message.append(getContext().getString(R.string.grant_confirm_item,++index,path(grant,paths),flags(grant),grant.uri)).append('\n');
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(getContext().getString(R.string.grant_confirm_title,confirmed.size()))
                .setMessage(message.toString()).setNegativeButton(R.string.grant_cancel,null)
                .setPositiveButton(R.string.grant_confirm_button,(which,id)->revoke(confirmed,paths)).create();
        showChild(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(appearance.error);
    }
    private void revoke(List<GrantSelection.Grant> confirmed,Map<String,String> paths){
        if(!isShowing()||disposed||activity.isDestroyed())return;
        if(revoking||isGrantChangeRunning()||!canManageGrants.getAsBoolean()){
            setNotice(getContext().getString(R.string.grant_busy),true);return;
        }
        revoking=true;closeSwipe();updateManagement();adapter.notifyDataSetChanged();
        setNotice(getContext().getString(R.string.grant_removing),false);
        GRANT_CHANGES.incrementAndGet();
        try{
            grantsChanged.run();
            queries.execute(()->{
            GrantSelection.BatchResult result=null;Exception failure=null;
            try{result=GrantSelection.revoke(new GrantSelection.Backend(){
                @Override public List<GrantSelection.Grant> current(){return persistedGrants();}
                @Override public void release(String uri,int flags)throws IOException{
                    if(!canManageGrants.getAsBoolean())throw new IOException(getContext().getString(R.string.grant_busy));
                    activity.getContentResolver().releasePersistableUriPermission(Uri.parse(uri),flags);
                    for(GrantSelection.Grant retained:persistedGrants())if(retained.uri.equals(uri)&&(retained.flags&flags)!=0)
                        throw new IOException("系统仍保留该项授权，请刷新后重试。");
                }
            },confirmed);}catch(Exception error){failure=error;}
            finally{GRANT_CHANGES.decrementAndGet();}
            GrantSelection.BatchResult finalResult=result;Exception finalFailure=failure;
            activity.runOnUiThread(()->{
                revoking=false;if(!activity.isDestroyed())grantsChanged.run();
                if(!isShowing()||activity.isDestroyed())return;
                load();showRemovalResult(finalResult,finalFailure,paths);
            });
        });}catch(RuntimeException error){
            GRANT_CHANGES.decrementAndGet();revoking=false;
            try{grantsChanged.run();}catch(RuntimeException refreshError){if(refreshError!=error)error.addSuppressed(refreshError);}
            updateManagement();showRemovalResult(null,error,paths);
        }
    }
    private List<GrantSelection.Grant> persistedGrants(){
        List<GrantSelection.Grant> result=new ArrayList<>();
        for(UriPermission permission:activity.getContentResolver().getPersistedUriPermissions()){
            int flags=(permission.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)
                    |(permission.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);
            if(flags!=0)result.add(new GrantSelection.Grant(permission.getUri().toString(),flags));
        }
        return result;
    }
    private void showRemovalResult(GrantSelection.BatchResult result,Exception failure,Map<String,String> paths){
        StringBuilder message=new StringBuilder();
        if(failure!=null)message.append(getContext().getString(R.string.grant_operation_failed,errorMessage(failure)));
        else{
            message.append(getContext().getString(R.string.grant_result_summary,result.released.size(),result.failed.size(),result.missing.size(),result.unprocessed.size()));
            if(result.changed)message.append("\n\n").append(getContext().getString(R.string.grant_result_changed));
            if(result.verificationError!=null)message.append("\n\n").append(getContext().getString(R.string.grant_result_verification_error,result.verificationError));
            for(GrantSelection.Grant grant:result.released)appendResult(message,grant,paths,getContext().getString(R.string.grant_result_released));
            for(GrantSelection.Failure failed:result.failed)appendResult(message,failed.grant,paths,getContext().getString(R.string.grant_result_failed,failed.message));
            for(GrantSelection.Grant grant:result.missing)appendResult(message,grant,paths,getContext().getString(R.string.grant_result_missing));
            for(GrantSelection.Grant grant:result.unprocessed)appendResult(message,grant,paths,getContext().getString(R.string.grant_result_unprocessed));
        }
        showChild(new AlertDialog.Builder(getContext()).setTitle(R.string.grant_result_title).setMessage(message.toString())
                .setPositiveButton(R.string.grant_done,null).create());
    }
    private void appendResult(StringBuilder message,GrantSelection.Grant grant,Map<String,String> paths,String status){
        message.append("\n\n").append(status).append("\n").append(path(grant,paths)).append("\n").append(grant.uri);
    }
    private static String errorMessage(Exception error){
        String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        for(Throwable extra:error.getSuppressed())message+="；"+(extra.getMessage()==null?extra.getClass().getSimpleName():extra.getMessage());
        return message;
    }
    private void showChild(AlertDialog dialog){
        if(childDialog!=null)childDialog.dismiss();childDialog=dialog;
        dialog.setOnDismissListener(which->{if(childDialog==dialog)childDialog=null;});dialog.show();appearance.styleDialog(dialog);
    }
    private void styleRemove(Button button){
        int[][] states={new int[]{-android.R.attr.state_enabled},new int[]{}};
        button.setTextColor(new ColorStateList(states,new int[]{appearance.disabledText,appearance.error}));
        button.setBackgroundTintList(new ColorStateList(states,new int[]{appearance.disabledBackground,appearance.errorBg}));
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
