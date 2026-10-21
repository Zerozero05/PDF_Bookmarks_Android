package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 更新状态独立于PDF任务；下载需确认，安装只由用户点击并交给系统。 */
final class AppUpdates {
    static final String PREFS="app_updates";
    private static final int INSTALL_PERMISSION=1001;
    private final Activity activity;
    private final BooleanSupplier installAllowed;
    private final Consumer<Button> style;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final UpdateClient client=new UpdateClient();
    private final String version;
    private UiAppearance appearance;
    private CheckBox automatic;
    private final ArrayList<Button> buttons=new ArrayList<>();
    private Button check,action,cancel;
    private TextView notice;
    private UpdateClient.Cancel job;
    private UpdateClient.Offer offer;
    private File ready;
    private AlertDialog dialog;
    private boolean resumed,promptPending;
    private volatile boolean closed;

    AppUpdates(Activity activity,BooleanSupplier installAllowed,Consumer<Button> style){
        this.activity=activity;this.installAllowed=installAllowed;this.style=style;
        appearance=UiAppearance.load(activity);
        prefs=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);version=activity.getString(R.string.app_version);
    }
    void attach(LinearLayout panel){
        automatic=new CheckBox(activity);automatic.setText("启动时自动检查更新（每天最多一次）");automatic.setChecked(prefs.getBoolean("automatic",true));panel.addView(automatic);
        automatic.setOnCheckedChangeListener((button,value)->{prefs.edit().putBoolean("automatic",value).apply();if(value)automaticCheck();});
        notice=new TextView(activity);notice.setText(activity.getString(R.string.update_current,version));panel.addView(notice);
        check=button(panel,"检查更新",()->check(true));action=button(panel,"下载更新",this::act);cancel=button(panel,"取消更新操作",this::cancel);
        button(panel,"打开发行页",()->openRelease(offer==null?"https://github.com/"+UpdateRelease.REPOSITORY+"/releases/latest":offer.release.releaseUrl));
        applyAppearance(appearance);
        refresh();restoreReady();
    }
    private Button button(LinearLayout parent,String title,Runnable run){
        Button button=new Button(activity);button.setText(title);button.setTextSize(15);button.setAllCaps(false);button.setMinHeight(dp(48));button.setPadding(dp(12),dp(10),dp(12),dp(10));style.accept(button);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);parent.addView(button,params);button.setOnClickListener(view->run.run());buttons.add(button);return button;
    }
    /** Repaint only; do not check versions, alter preferences or restart update work. */
    void applyAppearance(UiAppearance value){
        appearance=value;
        if(automatic!=null)appearance.styleChoice(automatic);
        if(notice!=null){appearance.styleText(notice,14);notice.setTextColor(appearance.secondaryText);}
        for(Button button:buttons){
            appearance.styleButton(button,false);
            if(button.getLayoutParams() instanceof LinearLayout.LayoutParams){
                LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)button.getLayoutParams();
                params.topMargin=dp(appearance.spacingDp(8));button.setLayoutParams(params);
            }
        }
        styleOpenDialog();
    }
    private void styleOpenDialog(){
        if(dialog==null||!dialog.isShowing()||dialog.getWindow()==null)return;
        appearance.styleDialog(dialog);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(appearance.surface));
        styleDialogContent(dialog.getWindow().getDecorView());
    }
    private void styleDialogContent(View view){
        if(view instanceof TextView){
            TextView text=(TextView)view;Object base=text.getTag(R.id.appearance_base_text_size);
            float size=base instanceof Number?((Number)base).floatValue():text.getTextSize()/activity.getResources().getDisplayMetrics().scaledDensity;
            appearance.styleText(text,size);
            if(view instanceof Button){
                text.setTextColor(appearance.accent);text.setBackgroundTintList(ColorStateList.valueOf(appearance.surface));
            }else text.setBackgroundColor(Color.TRANSPARENT);
        }
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;group.setBackgroundColor(appearance.surface);
            for(int index=0;index<group.getChildCount();index++)styleDialogContent(group.getChildAt(index));
        }
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    void onResume(){resumed=true;automaticCheck();refresh();}
    void onPause(){resumed=false;}
    private void automaticCheck(){
        if(!resumed||job!=null||closed)return;
        if(UpdateRelease.automaticDue(prefs.getBoolean("automatic",true),prefs.getLong("last_check",0),System.currentTimeMillis()))check(false);
    }
    void refresh(){
        if(check==null||closed)return;
        check.setEnabled(job==null);action.setVisibility(offer==null?View.GONE:View.VISIBLE);action.setEnabled(job==null);
        action.setText(offer==null?"下载更新":activity.getString(ready==null?R.string.update_download:R.string.update_install,offer.release.version));
        cancel.setVisibility(job==null?View.GONE:View.VISIBLE);cancel.setEnabled(job!=null&&!job.cancelled);
        activity.getWindow().getDecorView().post(this::maybeNotify);
    }
    private void check(boolean manual){
        if(job!=null||closed)return;
        UpdateClient.Cancel token=new UpdateClient.Cancel();job=token;prefs.edit().putLong("last_check",System.currentTimeMillis()).apply();
        notice.setText(manual?R.string.update_check_manual:R.string.update_check_background);refresh();
        worker.execute(()->{
            try{
                UpdateClient.Offer found=client.check(version,token);
                finish(token,()->{
                    if(found==null){offer=null;ready=null;forgetReady();notice.setText(activity.getString(R.string.update_none,version));}
                    else{
                        if(offer==null||!offer.release.version.equals(found.release.version)||!offer.sha256.equals(found.sha256)){ready=null;forgetReady();}
                        offer=found;notice.setText(activity.getString(R.string.update_found,found.release.version,size(found.release.size),ready==null?"，可查看说明并下载。":"，已下载并校验。"));promptPending=true;
                    }
                });
            }catch(Exception e){failed(token,e);}
        });
    }
    private void maybeNotify(){
        if(closed||!resumed||!promptPending||offer==null||job!=null||dialog!=null||!installAllowed.getAsBoolean())return;
        promptPending=false;showOffer();
    }
    private void showOffer(){
        if(offer==null)return;
        String notes=offer.release.notes;if(notes.length()>8000)notes=notes.substring(0,8000)+"\n完整说明见发行页。";
        dialog=new AlertDialog.Builder(appearance.dialogContext(activity)).setTitle(activity.getString(R.string.update_offer_title,offer.release.version))
                .setMessage(activity.getString(R.string.update_offer_message,version,size(offer.release.size),notes))
                .setNegativeButton("稍后",null).setNeutralButton("发行说明",(d,w)->openRelease(offer.release.releaseUrl))
                .setPositiveButton(ready==null?"下载更新":"安装更新",(d,w)->{if(ready==null)download();else install();}).create();
        dialog.setOnDismissListener(d->dialog=null);dialog.show();styleOpenDialog();
    }
    private void act(){if(job!=null)return;if(ready==null)showOffer();else install();}
    private void download(){
        if(job!=null||offer==null||closed)return;
        UpdateClient.Offer selected=offer;UpdateClient.Cancel token=new UpdateClient.Cancel();job=token;notice.setText(activity.getString(R.string.update_downloading,selected.release.version));refresh();
        worker.execute(()->{
            try{
                File directory=new File(activity.getCacheDir(),"updates");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建应用内更新缓存。");
                if(new android.os.StatFs(directory.getAbsolutePath()).getAvailableBytes()<selected.release.size+4L*1024*1024)throw new IOException("更新缓存空间不足，请释放空间后重试。");
                File file=UpdateClient.cacheFile(directory,"update-"+UUID.randomUUID()+".apk");long[] last={0};
                client.download(selected,file,token,bytes->{long now=System.nanoTime();if(now-last[0]>200000000L){last[0]=now;ui(()->{if(job==token&&!token.cancelled)notice.setText(activity.getString(R.string.update_progress,bytes*100/selected.release.size,size(bytes),size(selected.release.size)));});}});
                token.check();ui(()->{if(job==token)notice.setText(R.string.update_verifying);});
                UpdateApkVerifier.verify(activity,file,selected,token);token.check();
                if(!prefs.edit().putString("ready_file",file.getName()).putString("ready_version",selected.release.version)
                        .putLong("ready_size",selected.release.size).putString("ready_sha",selected.sha256).putString("ready_notes",selected.release.notes).commit())
                    throw new IOException("未能保存已校验安装包状态，请重试。");
                finish(token,()->{offer=selected;ready=file;notice.setText(activity.getString(R.string.update_ready,selected.release.version));});
            }catch(Exception e){failed(token,e);}
        });
    }
    private void install(){
        if(job!=null||offer==null||ready==null||closed)return;
        if(!installAllowed.getAsBoolean()){notice.setText("请先完成当前文件、恢复或清理操作，再安装更新。");return;}
        File selected=ready;UpdateClient.Offer selectedOffer=offer;UpdateClient.Cancel token=new UpdateClient.Cancel();job=token;notice.setText("正在重新核验安装包…");refresh();
        worker.execute(()->{
            try{UpdateApkVerifier.verify(activity,selected,selectedOffer,token);finish(token,()->launchInstaller(selected));}
            catch(Exception e){finish(token,()->{ready=null;forgetReady();notice.setText(activity.getString(R.string.update_install_stopped,message(e)));});}
        });
    }
    private void launchInstaller(File file){
        if(!installAllowed.getAsBoolean()){notice.setText("安装包已准备。请完成当前任务后再点击安装。");return;}
        if(!activity.getPackageManager().canRequestPackageInstalls()){
            dialog=new AlertDialog.Builder(appearance.dialogContext(activity)).setTitle("允许本工具安装更新")
                    .setMessage("系统需要你允许此来源安装应用。授权后返回，再点击“安装更新”；取消也能继续使用当前版本。")
                    .setNegativeButton("返回",null).setPositiveButton("前往授权",(d,w)->{
                        try{activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),INSTALL_PERMISSION);}
                        catch(ActivityNotFoundException|SecurityException e){notice.setText("此系统未提供安装来源设置，请从发行页手动覆盖安装。");}
                    }).create();dialog.setOnDismissListener(d->dialog=null);dialog.show();styleOpenDialog();return;
        }
        try{
            Uri uri=UpdateApkProvider.uri(activity,file.getName());
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,UpdateApkProvider.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);notice.setText("已打开系统安装界面。请按系统提示确认；取消后当前版本和文件保留。");
        }catch(ActivityNotFoundException|SecurityException e){notice.setText("系统无法打开安装界面，可从发行页手动覆盖安装。");}
    }
    boolean onActivityResult(int request){
        if(request!=INSTALL_PERMISSION)return false;
        notice.setText(activity.getPackageManager().canRequestPackageInstalls()?"安装来源已允许。请点击“安装更新”。":"尚未允许安装来源；可稍后重试或继续使用当前版本。");refresh();return true;
    }
    private void restoreReady(){
        if(!prefs.contains("ready_file"))return;
        UpdateClient.Cancel token=new UpdateClient.Cancel();job=token;notice.setText("正在核对上次已下载的更新…");refresh();
        worker.execute(()->{
            try{
                UpdateRelease release=UpdateRelease.cached(prefs.getString("ready_version",""),prefs.getLong("ready_size",0),prefs.getString("ready_sha",""),prefs.getString("ready_notes",""));
                if(UpdateRelease.compareVersions(release.version,version)<=0){forgetReady();finish(token,()->notice.setText(activity.getString(R.string.update_old_cache,version)));return;}
                File file=UpdateClient.cacheFile(new File(activity.getCacheDir(),"updates"),prefs.getString("ready_file",null));
                UpdateClient.Offer selected=new UpdateClient.Offer(release,release.digest);UpdateApkVerifier.verify(activity,file,selected,token);
                finish(token,()->{offer=selected;ready=file;notice.setText(activity.getString(R.string.update_restored,release.version));});
            }catch(Exception e){if(!token.cancelled)forgetReady();failed(token,e);}
        });
    }
    private void forgetReady(){prefs.edit().remove("ready_file").remove("ready_version").remove("ready_size").remove("ready_sha").remove("ready_notes").apply();}
    private void finish(UpdateClient.Cancel token,Runnable success){ui(()->{
        if(job!=token)return;job=null;
        if(token.cancelled)notice.setText("已取消更新操作。当前程序及文件保持，可重新检查或下载。");else success.run();
        refresh();if(!token.cancelled)automaticCheck();
    });}
    private void failed(UpdateClient.Cancel token,Exception error){finish(token,()->notice.setText(activity.getString(R.string.update_failed,message(error))));}
    private static String message(Exception error){return error.getMessage()==null?"更新未完成，请稍后重试。":error.getMessage();}
    private static String size(long bytes){return String.format(Locale.ROOT,"%.1f MB",bytes/(1024.0*1024));}
    private void cancel(){
        UpdateClient.Cancel token=job;if(token==null)return;token.cancelled=true;new Thread(token::close,"update-cancel").start();notice.setText("正在取消更新操作…");refresh();
    }
    private void ui(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed())action.run();});}
    private void openRelease(String url){
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(ActivityNotFoundException|SecurityException e){notice.setText(R.string.update_browser_missing);}
    }
    void close(){closed=true;if(dialog!=null)dialog.dismiss();UpdateClient.Cancel token=job;if(token!=null){token.cancelled=true;new Thread(token::close,"update-close").start();}worker.shutdown();}
}
