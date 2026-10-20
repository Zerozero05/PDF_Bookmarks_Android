package cn.local.pdfbookmarks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/** 独立更新网络队列使用；每次重定向先检查，下载完成前不注册为可安装文件。 */
final class UpdateClient {
    private static final List<String> HOSTS=Arrays.asList("api.github.com","github.com",
            "release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com");
    interface Connections {HttpURLConnection open(URL url)throws IOException;}
    static final class Cancel {
        volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        void check()throws InterruptedIOException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("更新操作已取消。");}
        void close(){HttpURLConnection current=connection;if(current!=null)current.disconnect();}
    }
    static final class Offer {
        final UpdateRelease release;final String sha256;
        Offer(UpdateRelease release,String sha256){this.release=release;this.sha256=sha256;}
    }
    private final Connections connections;
    UpdateClient(){this(url->(HttpURLConnection)url.openConnection());}
    UpdateClient(Connections connections){this.connections=connections;}
    static URL trusted(String value)throws IOException{
        try{
            URI uri=new URI(value);
            if(!"https".equals(uri.getScheme())||!HOSTS.contains(uri.getHost())||uri.getRawUserInfo()!=null
                    ||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getRawFragment()!=null)throw new IOException("更新仅允许GitHub的HTTPS地址。");
            return uri.toURL();
        }catch(URISyntaxException|IllegalArgumentException e){throw new IOException("更新地址无效。",e);}
    }
    private HttpURLConnection open(String value,Cancel cancel)throws IOException{
        String next=value;
        for(int count=0;count<6;count++){
            cancel.check();URL url=trusted(next);HttpURLConnection connection=connections.open(url);cancel.connection=connection;
            try{
                cancel.check();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent","PDF-Bookmarks-Android-Updater");connection.setRequestProperty("Accept","application/vnd.github+json");
                connection.setRequestProperty("Accept-Encoding","identity");int code=connection.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){
                    String location=connection.getHeaderField("Location");
                    if(location==null)throw new IOException("GitHub重定向缺少目标。");
                    next=new URL(url,location).toString();trusted(next);connection.disconnect();continue;
                }
                if(code!=200)throw new IOException("GitHub暂时无法访问（HTTP "+code+"），可稍后手动重试。");
                return connection;
            }catch(IOException|RuntimeException e){connection.disconnect();throw e;}
        }
        throw new IOException("GitHub重定向次数过多，请稍后重试。");
    }
    private String readText(String url,int limit,Cancel cancel)throws IOException{
        HttpURLConnection connection=open(url,cancel);
        try(InputStream stream=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int size;
            while((size=stream.read(buffer))!=-1){cancel.check();if(bytes.size()+size>limit)throw new IOException("更新信息过大，已停止读取。");bytes.write(buffer,0,size);}
            cancel.check();return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }finally{connection.disconnect();cancel.connection=null;}
    }
    Offer check(String current,Cancel cancel)throws IOException{
        UpdateRelease release=UpdateRelease.parse(readText(UpdateRelease.API,2*1024*1024,cancel),current);
        return release==null?null:new Offer(release,release.checksum(readText(release.checksumUrl,65536,cancel)));
    }
    void download(Offer offer,File output,Cancel cancel,LongConsumer progress)throws IOException{
        if(output.exists())throw new IOException("更新缓存已存在，请重新选择下载。");
        File part=new File(output.getPath()+".part");
        if(!part.createNewFile())throw new IOException("本次下载缓存已占用。");
        boolean complete=false;HttpURLConnection connection=null;
        try{
            cancel.check();connection=open(offer.release.url,cancel);long declared=connection.getContentLengthLong();
            if(declared!=-1&&declared!=offer.release.size)throw new IOException("下载大小与发行记录不一致。");
            MessageDigest digest=digest();long total=0;
            try(InputStream stream=connection.getInputStream();FileOutputStream target=new FileOutputStream(part)){
                byte[] buffer=new byte[65536];int size;
                while((size=stream.read(buffer))!=-1){
                    cancel.check();total+=size;if(total>offer.release.size)throw new IOException("下载超过声明大小，已停止。");
                    target.write(buffer,0,size);digest.update(buffer,0,size);progress.accept(total);
                }
                target.getFD().sync();
            }
            cancel.check();
            if(total!=offer.release.size||!offer.sha256.equals(hex(digest.digest())))throw new IOException("安装包未通过大小和SHA256校验，请重试下载。");
            Files.move(part.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE);complete=true;
        }finally{
            if(connection!=null)connection.disconnect();cancel.connection=null;
            if(!complete)Files.deleteIfExists(part.toPath());
        }
    }
    static void verifyFile(File file,long expectedSize,String expectedHash,Cancel cancel)throws IOException{
        if(!file.isFile()||file.length()!=expectedSize)throw new IOException("安装包缓存缺失或大小变化，请重新下载。");
        MessageDigest digest=digest();
        try(InputStream stream=Files.newInputStream(file.toPath())){
            byte[] buffer=new byte[65536];int size;
            while((size=stream.read(buffer))!=-1){cancel.check();digest.update(buffer,0,size);}
        }
        cancel.check();if(!expectedHash.equals(hex(digest.digest())))throw new IOException("安装包缓存SHA256变化，请重新下载。");
    }
    static File cacheFile(File directory,String name)throws IOException{
        if(name==null||!name.matches("update-[0-9a-f-]{36}\\.apk"))throw new IOException("更新缓存文件名无效。");
        File file=new File(directory.getCanonicalFile(),name);
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("更新缓存不能指向其他位置。");
        return file;
    }
    static String hex(byte[] bytes){char[] chars="0123456789abcdef".toCharArray();StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes){result.append(chars[(value&255)>>>4]);result.append(chars[value&15]);}return result.toString();}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
