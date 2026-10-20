package cn.local.pdfbookmarks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** 仅提供已校验且登记的单个APK，其他缓存、备份和文档没有访问入口。 */
public final class UpdateApkProvider extends ContentProvider {
    static final String MIME="application/vnd.android.package-archive";
    static Uri uri(Context context,String name){return new Uri.Builder().scheme("content").authority(context.getPackageName()+".updates").appendPath("apk").appendPath(name).build();}
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri)throws IOException{
        Context context=getContext();
        if(context==null||!"content".equals(uri.getScheme())||!(context.getPackageName()+".updates").equals(uri.getAuthority())
                ||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPathSegments().size()!=2||!"apk".equals(uri.getPathSegments().get(0)))
            throw new IOException("无效的更新文件地址。");
        SharedPreferences prefs=context.getSharedPreferences(AppUpdates.PREFS,Context.MODE_PRIVATE);
        String name=prefs.getString("ready_file",null);
        if(name==null||!name.equals(uri.getLastPathSegment()))throw new IOException("此安装包尚未校验或已失效。");
        File file=UpdateClient.cacheFile(new File(context.getCacheDir(),"updates"),name);
        UpdateClient.verifyFile(file,prefs.getLong("ready_size",0),prefs.getString("ready_sha",""),new UpdateClient.Cancel());
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("更新文件只读。");
        try{return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
        catch(IOException e){FileNotFoundException failure=new FileNotFoundException(e.getMessage());failure.initCause(e);throw failure;}
    }
    @Override public String getType(Uri uri){return MIME;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String order){
        try{
            File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor result=new MatrixCursor(columns,1);Object[] values=new Object[columns.length];
            String version=getContext().getSharedPreferences(AppUpdates.PREFS,Context.MODE_PRIVATE).getString("ready_version","");
            for(int i=0;i<columns.length;i++){
                if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]="PDF_Bookmarks_v"+version+"_android.apk";
                if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();
            }
            result.addRow(values);return result;
        }catch(IOException e){throw new IllegalArgumentException(e.getMessage(),e);}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("只读更新文件。");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("只读更新文件。");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("只读更新文件。");}
}
