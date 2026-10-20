package cn.local.pdfbookmarks;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 由Android读取真实APK身份和证书；SHA清单本身不能替代升级签名。 */
final class UpdateApkVerifier {
    private UpdateApkVerifier(){}
    static void verify(Context context,File file,UpdateClient.Offer offer,UpdateClient.Cancel cancel)throws IOException{
        UpdateClient.verifyFile(file,offer.release.size,offer.sha256,cancel);
        PackageManager manager=context.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=manager.getPackageArchiveInfo(file.getAbsolutePath(),flags);
        if(incoming==null||incoming.applicationInfo==null)throw new IOException("下载文件不是可识别的Android安装包。");
        try{
            PackageInfo installed=manager.getPackageInfo(context.getPackageName(),flags);
            offer.release.verifyIdentity(incoming.packageName,incoming.versionName,code(incoming),code(installed),
                    incoming.applicationInfo.minSdkVersion,Build.VERSION.SDK_INT,signer(incoming),signer(installed),context.getString(R.string.update_signer_sha256));
            cancel.check();
        }catch(PackageManager.NameNotFoundException e){throw new IOException("无法核对当前程序的升级身份。",e);}
    }
    private static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static String signer(PackageInfo info)throws IOException{
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        if(signatures==null||signatures.length!=1)throw new IOException("无法核对唯一的正式升级签名。");
        try{return UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
