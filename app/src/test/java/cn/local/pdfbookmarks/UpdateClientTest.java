package cn.local.pdfbookmarks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class UpdateClientTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final byte[] PAYLOAD="an actual streamed payload, not an APK identity fixture".getBytes(StandardCharsets.UTF_8);
    private static String sha(byte[] bytes)throws Exception{return UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static final class Response extends HttpURLConnection {
        byte[] body;int code=200;String location;long length=-1;boolean closed;
        Response(URL url,byte[] body){super(url);this.body=body;}
        @Override public void disconnect(){closed=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public int getResponseCode(){return code;}
        @Override public long getContentLengthLong(){return length;}
        @Override public String getHeaderField(String name){return "Location".equals(name)?location:null;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
    }
    private static UpdateClient.Offer offer(String hash,long size)throws Exception{
        return new UpdateClient.Offer(UpdateRelease.cached("1.1.0",size,hash,"说明"),hash);
    }
    @Test public void checkFetchesOnlyOfficialReleaseAndChecksumThenReturnsVerifiedOffer()throws Exception{
        String hash=sha(PAYLOAD);List<String> urls=new ArrayList<>();
        UpdateClient client=new UpdateClient(url->{urls.add(url.toString());String text=urls.size()==1?UpdateReleaseTest.release("1.1.0",PAYLOAD.length)
                :hash+"  PDF_Bookmarks_v1.1.0_android.apk\n";return new Response(url,text.getBytes(StandardCharsets.UTF_8));});
        UpdateClient.Offer result=client.check("1.0.0",new UpdateClient.Cancel());
        assertEquals(hash,result.sha256);assertEquals(2,urls.size());assertEquals(UpdateRelease.API,urls.get(0));assertEquals(result.release.checksumUrl,urls.get(1));
    }
    @Test public void noNewReleaseDoesNotDownloadAnythingElse()throws Exception{
        List<String> calls=new ArrayList<>();UpdateClient client=new UpdateClient(url->{calls.add(url.toString());return new Response(url,UpdateReleaseTest.release("0.1.9",95).getBytes(StandardCharsets.UTF_8));});
        assertNull(client.check("1.0.0",new UpdateClient.Cancel()));assertEquals(1,calls.size());
    }
    @Test public void completeDownloadIsVerifiedAndExistingUnrelatedFileIsPreserved()throws Exception{
        File output=new File(folder.getRoot(),"ready.apk"),other=folder.newFile("user-note.txt");Files.write(other.toPath(),new byte[]{7,8});
        List<Long> progress=new ArrayList<>();Response response=new Response(new URL("https://github.com"),PAYLOAD);
        UpdateClient client=new UpdateClient(url->response);client.download(offer(sha(PAYLOAD),PAYLOAD.length),output,new UpdateClient.Cancel(),progress::add);
        assertArrayEquals(PAYLOAD,Files.readAllBytes(output.toPath()));assertFalse(new File(output+".part").exists());assertTrue(response.closed);
        assertEquals(Long.valueOf(PAYLOAD.length),progress.get(progress.size()-1));assertArrayEquals(new byte[]{7,8},Files.readAllBytes(other.toPath()));
    }
    @Test public void wrongHashTruncatedOrExcessPayloadNeverBecomesReady()throws Exception{
        for(int mode=0;mode<3;mode++){
            File output=new File(folder.getRoot(),"rejected-"+mode+".apk");String hash=mode==0?UpdateReleaseTest.HASH:sha(PAYLOAD);
            long size=mode==1?PAYLOAD.length+1:mode==2?PAYLOAD.length-1:PAYLOAD.length;
            UpdateClient client=new UpdateClient(url->new Response(url,PAYLOAD));
            UpdateReleaseTest.fails(()->client.download(offer(hash,size),output,new UpdateClient.Cancel(),n->{}));
            assertFalse(output.exists());assertFalse(new File(output+".part").exists());
        }
    }
    @Test public void contentLengthMismatchCannotPublishReadyFile()throws Exception{
        File output=new File(folder.getRoot(),"rejected.apk");Response response=new Response(new URL("https://github.com"),PAYLOAD);response.length=999;
        UpdateReleaseTest.fails(()->new UpdateClient(url->response).download(offer(sha(PAYLOAD),PAYLOAD.length),output,new UpdateClient.Cancel(),n->{}));
        assertFalse(output.exists());assertTrue(response.closed);
    }
    @Test public void cancelDuringStreamingClosesResponseAndDoesNotPublish()throws Exception{
        File output=new File(folder.getRoot(),"cancelled.apk");UpdateClient.Cancel cancel=new UpdateClient.Cancel();Response response=new Response(new URL("https://github.com"),PAYLOAD);
        UpdateReleaseTest.fails(()->new UpdateClient(url->response).download(offer(sha(PAYLOAD),PAYLOAD.length),output,cancel,n->{cancel.cancelled=true;}));
        assertFalse(output.exists());assertFalse(new File(output+".part").exists());assertTrue(response.closed);
    }
    @Test public void untrustedRedirectIsRejectedBeforeOpeningDestination()throws Exception{
        List<String> calls=new ArrayList<>();UpdateClient client=new UpdateClient(url->{calls.add(url.toString());Response r=new Response(url,PAYLOAD);r.code=302;r.location="https://evil.example/new.apk";return r;});
        UpdateReleaseTest.fails(()->client.check("1.0.0",new UpdateClient.Cancel()));assertEquals(1,calls.size());
    }
    @Test public void githubAssetRedirectIsFollowedWithAutomaticRedirectsDisabled()throws Exception{
        List<Response> calls=new ArrayList<>();File output=new File(folder.getRoot(),"redirect.apk");
        UpdateClient client=new UpdateClient(url->{Response r=new Response(url,PAYLOAD);calls.add(r);if(calls.size()==1){r.code=302;r.location="https://release-assets.githubusercontent.com/assets/123";}return r;});
        client.download(offer(sha(PAYLOAD),PAYLOAD.length),output,new UpdateClient.Cancel(),n->{});
        assertEquals(2,calls.size());assertFalse(calls.get(0).getInstanceFollowRedirects());assertTrue(calls.get(0).closed);assertTrue(calls.get(1).closed);
    }
    @Test public void redirectLoopIsBounded()throws Exception{
        List<Response> calls=new ArrayList<>();UpdateClient client=new UpdateClient(url->{Response r=new Response(url,PAYLOAD);r.code=302;r.location="https://github.com/again";calls.add(r);return r;});
        UpdateReleaseTest.fails(()->client.check("1.0.0",new UpdateClient.Cancel()));assertEquals(6,calls.size());for(Response r:calls)assertTrue(r.closed);
    }
    @Test public void unsafeUrlsAndPathsCannotBeUsed()throws Exception{
        for(String url:new String[]{"http://github.com/a","https://github.com.evil.example/a","https://user@github.com/a","https://github.com:444/a","https://github.com/a#part","file:///a"})
            UpdateReleaseTest.fails(()->UpdateClient.trusted(url));
        for(String name:new String[]{"../ready.apk","/ready.apk","ready.apk","update-../../x.apk"})UpdateReleaseTest.fails(()->UpdateClient.cacheFile(folder.getRoot(),name));
        String name="update-12345678-1234-1234-1234-123456789abc.apk";
        assertEquals(new File(folder.getRoot().getCanonicalFile(),name),UpdateClient.cacheFile(folder.getRoot(),name));
    }
    @Test public void existingFinalOrPartialFilesAreNotOverwritten()throws Exception{
        File output=folder.newFile("existing.apk");Files.write(output.toPath(),new byte[]{9});
        UpdateClient client=new UpdateClient(url->{throw new AssertionError("No network expected");});
        UpdateReleaseTest.fails(()->client.download(offer(sha(PAYLOAD),PAYLOAD.length),output,new UpdateClient.Cancel(),n->{}));assertArrayEquals(new byte[]{9},Files.readAllBytes(output.toPath()));
        File second=new File(folder.getRoot(),"partial.apk"),part=new File(second+".part");Files.write(part.toPath(),new byte[]{4});
        UpdateReleaseTest.fails(()->client.download(offer(sha(PAYLOAD),PAYLOAD.length),second,new UpdateClient.Cancel(),n->{}));assertArrayEquals(new byte[]{4},Files.readAllBytes(part.toPath()));
    }
    @Test public void readyCacheIsRecheckedBeforeInstallation()throws Exception{
        File file=folder.newFile("cache.apk");Files.write(file.toPath(),PAYLOAD);UpdateClient.verifyFile(file,PAYLOAD.length,sha(PAYLOAD),new UpdateClient.Cancel());
        Files.write(file.toPath(),new byte[PAYLOAD.length]);UpdateReleaseTest.fails(()->UpdateClient.verifyFile(file,PAYLOAD.length,sha(PAYLOAD),new UpdateClient.Cancel()));
    }
    @Test public void networkFailureIsReportedAndCancelBeforeRequestMakesNoConnection()throws Exception{
        UpdateClient client=new UpdateClient(url->{throw new IOException("offline");});UpdateReleaseTest.fails(()->client.check("1.0.0",new UpdateClient.Cancel()));
        UpdateClient.Cancel cancel=new UpdateClient.Cancel();cancel.cancelled=true;
        UpdateReleaseTest.fails(()->new UpdateClient(url->{throw new AssertionError("Cancelled request connected");}).check("1.0.0",cancel));
    }
    @Test public void metadataLimitAndRateLimitErrorsCloseConnections()throws Exception{
        Response huge=new Response(new URL("https://api.github.com"),new byte[2*1024*1024+1]);
        UpdateReleaseTest.fails(()->new UpdateClient(url->huge).check("1.0.0",new UpdateClient.Cancel()));assertTrue(huge.closed);
        Response limited=new Response(new URL("https://api.github.com"),new byte[0]);limited.code=403;
        UpdateReleaseTest.fails(()->new UpdateClient(url->limited).check("1.0.0",new UpdateClient.Cancel()));assertTrue(limited.closed);
    }
}
