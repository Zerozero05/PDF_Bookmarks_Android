package cn.local.pdfbookmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Android稳定发行契约；复用现有APK命名和SHA256SUMS，不读取用户文档。 */
final class UpdateRelease {
    static final String REPOSITORY="Zerozero05/PDF_Bookmarks_Android";
    static final String API="https://api.github.com/repos/"+REPOSITORY+"/releases/latest";
    static final String PACKAGE="cn.local.pdfbookmarks";
    static final long MAX_APK=256L*1024*1024,DAY=24L*60*60*1000;
    final String version,file,url,checksumUrl,releaseUrl,notes,digest;
    final long size;

    private UpdateRelease(String version,long size,String digest,String notes){
        this.version=version;this.size=size;this.digest=digest;this.notes=notes;
        file="PDF_Bookmarks_v"+version+"_android.apk";
        String base="https://github.com/"+REPOSITORY+"/releases/";
        releaseUrl=base+"tag/v"+version;url=base+"download/v"+version+"/"+file;
        checksumUrl=base+"download/v"+version+"/SHA256SUMS.txt";
    }
    static boolean automaticDue(boolean enabled,long last,long now){
        return enabled&&(last<=0||now<last||now-last>=DAY);
    }
    static int compareVersions(String first,String second)throws IOException{
        int[] a=versionParts(first),b=versionParts(second);
        for(int i=0;i<3;i++)if(a[i]!=b[i])return Integer.compare(a[i],b[i]);
        return 0;
    }
    private static int[] versionParts(String value)throws IOException{
        if(value==null||!value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new IOException("正式版版本号无效，请从发行页核对。");
        String[] parts=value.split("\\.");int[] result=new int[3];
        try{for(int i=0;i<3;i++)result[i]=Integer.parseInt(parts[i]);}
        catch(NumberFormatException e){throw new IOException("版本号超出支持范围。",e);}return result;
    }
    static UpdateRelease parse(String text,String current)throws IOException{
        try(JsonReader reader=new JsonReader(new StringReader(text))){
            reader.setStrictness(Strictness.STRICT);JsonElement data=JsonParser.parseReader(reader);
            if(reader.peek()!=JsonToken.END_DOCUMENT||!data.isJsonObject())throw new IOException("发行信息不是完整JSON对象。");
            JsonObject release=data.getAsJsonObject();
            if(flag(release,"draft")||flag(release,"prerelease"))return null;
            String tag=string(release,"tag_name");
            if(!tag.startsWith("v"))throw new IOException("发行标签须为v加三段版本号。");
            String version=tag.substring(1);
            if(compareVersions(version,current)<=0)return null;
            String body=release.has("body")&&!release.get("body").isJsonNull()?string(release,"body"):"此版本未提供更新说明。";
            JsonElement all=release.get("assets");
            if(all==null||!all.isJsonArray())throw new IOException("发行版缺少下载文件。");
            JsonArray assets=all.getAsJsonArray();Set<String> names=new HashSet<>();JsonObject apk=null,sums=null;
            String wanted="PDF_Bookmarks_v"+version+"_android.apk";
            for(JsonElement element:assets){
                if(!element.isJsonObject())throw new IOException("发行附件格式无效。");
                JsonObject asset=element.getAsJsonObject();String name=string(asset,"name");
                if(!names.add(name))throw new IOException("发行版有重复附件，请从发行页核对。");
                if(wanted.equals(name))apk=asset;if("SHA256SUMS.txt".equals(name))sums=asset;
            }
            if(apk==null||sums==null)throw new IOException("新版尚未备齐APK和SHA256SUMS.txt，请稍后重试。");
            JsonElement sizeValue=apk.get("size");
            if(sizeValue==null||!sizeValue.isJsonPrimitive()||!sizeValue.getAsJsonPrimitive().isNumber())throw new IOException("安装包大小格式无效。");
            long size=sizeValue.getAsBigDecimal().longValueExact();
            if(size<=0||size>MAX_APK)throw new IOException("安装包大小超出支持范围。");
            String digest="";
            if(apk.has("digest")&&!apk.get("digest").isJsonNull()){
                String value=string(apk,"digest");
                if(!value.matches("sha256:[0-9a-fA-F]{64}"))throw new IOException("发行附件的SHA256信息无效。");
                digest=value.substring(7).toLowerCase(Locale.ROOT);
            }
            UpdateRelease result=new UpdateRelease(version,size,digest,body);
            if(!result.releaseUrl.equals(string(release,"html_url"))||!result.url.equals(string(apk,"browser_download_url"))
                    ||!result.checksumUrl.equals(string(sums,"browser_download_url")))throw new IOException("更新地址与本项目发行版不一致。");
            return result;
        }catch(JsonParseException|IllegalStateException|IllegalArgumentException|NullPointerException|ArithmeticException e){throw new IOException("发行信息格式不完整，请稍后重试。",e);}
    }
    private static boolean flag(JsonObject object,String key)throws IOException{
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IOException("发行状态格式无效。");
        return value.getAsBoolean();
    }
    private static String string(JsonObject object,String key)throws IOException{
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("发行字段缺失："+key);
        return value.getAsString();
    }
    String checksum(String text)throws IOException{
        String found=null;
        for(String line:text.replace("\uFEFF","").split("\\r?\\n")){
            if(line.trim().isEmpty())continue;
            String[] fields=line.trim().split("[ \\t]+",2);
            if(fields.length!=2||!fields[0].matches("[0-9a-fA-F]{64}"))throw new IOException("SHA256清单格式无效。");
            String name=fields[1].startsWith("*")?fields[1].substring(1):fields[1];
            if(file.equals(name)){
                if(found!=null)throw new IOException("SHA256清单重复列出安装包。");
                found=fields[0].toLowerCase(Locale.ROOT);
            }
        }
        if(found==null||(!digest.isEmpty()&&!digest.equals(found)))throw new IOException("安装包SHA256与发行记录不一致。");
        return found;
    }
    static UpdateRelease cached(String version,long size,String sha,String notes)throws IOException{
        versionParts(version);
        if(size<=0||size>MAX_APK||sha==null||!sha.matches("[0-9a-f]{64}"))throw new IOException("更新缓存信息不完整，请重新下载。");
        return new UpdateRelease(version,size,sha,notes==null?"":notes);
    }
    void verifyIdentity(String packageName,String versionName,long versionCode,long currentCode,int minimumSdk,int deviceSdk,
                        String signer,String currentSigner,String expectedSigner)throws IOException{
        if(!PACKAGE.equals(packageName)||!version.equals(versionName)||versionCode<=currentCode)
            throw new IOException("安装包的程序身份或版本不匹配，已停止安装。");
        if(minimumSdk>deviceSdk)throw new IOException("新版需要更高的系统版本，当前程序可继续使用。");
        if(expectedSigner==null||!expectedSigner.matches("[0-9a-f]{64}")||!expectedSigner.equals(signer)||!expectedSigner.equals(currentSigner))
            throw new IOException("安装包升级签名与当前正式签名不一致，已停止安装。");
    }
}
