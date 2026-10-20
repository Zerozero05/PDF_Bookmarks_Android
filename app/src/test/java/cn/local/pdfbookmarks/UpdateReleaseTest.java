package cn.local.pdfbookmarks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdateReleaseTest {
    static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static String release(String version,long size){
        String base="https://github.com/"+UpdateRelease.REPOSITORY+"/releases/";
        return "{\"tag_name\":\"v"+version+"\",\"draft\":false,\"prerelease\":false,\"html_url\":\""+base+"tag/v"+version
                +"\",\"body\":\"更新说明\",\"assets\":[{\"name\":\"PDF_Bookmarks_v"+version+"_android.apk\",\"size\":"+size
                +",\"browser_download_url\":\""+base+"download/v"+version+"/PDF_Bookmarks_v"+version+"_android.apk\"},"
                +"{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\""+base+"download/v"+version+"/SHA256SUMS.txt\"}]}";
    }
    interface Checked {void run()throws Exception;}
    static void fails(Checked action)throws Exception{try{action.run();fail("Expected safe rejection");}catch(IOException expected){assertNotNull(expected.getMessage());}}

    @Test public void versionsCompareNumericallyAcrossMajorAndMinor()throws Exception{
        assertTrue(UpdateRelease.compareVersions("1.0.0","0.1.10")>0);
        assertTrue(UpdateRelease.compareVersions("1.10.0","1.9.99")>0);
        assertTrue(UpdateRelease.compareVersions("1.0.0","1.0.1")<0);
        assertEquals(0,UpdateRelease.compareVersions("1.0.0","1.0.0"));
    }
    @Test public void malformedAndPreReleaseNumbersCannotInstall()throws Exception{
        for(String version:new String[]{"1.0","01.0.0","1.0.0-beta","1.0.0+build","-1.0.0","2147483648.0.0","1.0.0 "})
            fails(()->UpdateRelease.compareVersions(version,"1.0.0"));
    }
    @Test public void olderAndSameReleaseNeedNoAssets()throws Exception{
        assertNull(UpdateRelease.parse(release("0.1.9",32),"1.0.0"));
        assertNull(UpdateRelease.parse(release("1.0.0",32),"1.0.0"));
    }
    @Test public void draftsAndPrereleasesAreIgnored()throws Exception{
        assertNull(UpdateRelease.parse(release("2.0.0",32).replace("\"draft\":false","\"draft\":true"),"1.0.0"));
        assertNull(UpdateRelease.parse(release("2.0.0",32).replace("\"prerelease\":false","\"prerelease\":true"),"1.0.0"));
    }
    @Test public void validOfferUsesExistingAndroidAssetConvention()throws Exception{
        UpdateRelease offer=UpdateRelease.parse(release("1.1.0",95),"1.0.0");
        assertEquals("1.1.0",offer.version);assertEquals(95,offer.size);assertEquals("PDF_Bookmarks_v1.1.0_android.apk",offer.file);
        assertEquals(HASH,offer.checksum(HASH+"  "+offer.file+"\r\n"+HASH+"  source.zip\r\n"));
    }
    @Test public void missingDuplicateAndCrossRepositoryAssetsStop()throws Exception{
        JsonObject data=JsonParser.parseString(release("1.1.0",95)).getAsJsonObject();
        data.getAsJsonArray("assets").add(data.getAsJsonArray("assets").get(0));fails(()->UpdateRelease.parse(data.toString(),"1.0.0"));
        fails(()->UpdateRelease.parse(release("1.1.0",95).replace("SHA256SUMS.txt","sums.txt"),"1.0.0"));
        fails(()->UpdateRelease.parse(release("1.1.0",95).replace("Zerozero05","different-owner"),"1.0.0"));
    }
    @Test public void invalidStatusJsonAndSizeStopBeforeDownload()throws Exception{
        for(String text:new String[]{"{}","[]","{bad}",release("1.1.0",95)+" {}",release("1.1.0",0),release("1.1.0",UpdateRelease.MAX_APK+1),
                release("1.1.0",95).replace("\"size\":95","\"size\":\"95\""),release("1.1.0",95).replace("\"draft\":false","\"draft\":\"false\"")})
            fails(()->UpdateRelease.parse(text,"1.0.0"));
    }
    @Test public void digestAndChecksumMustAgree()throws Exception{
        JsonObject data=JsonParser.parseString(release("1.1.0",95)).getAsJsonObject();
        data.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("digest","sha256:"+HASH);
        UpdateRelease offer=UpdateRelease.parse(data.toString(),"1.0.0");
        assertEquals(HASH,offer.checksum(HASH+" *"+offer.file));
        fails(()->offer.checksum(HASH.replace('a','b')+"  "+offer.file));
        data.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("digest","md5:123");
        fails(()->UpdateRelease.parse(data.toString(),"1.0.0"));
    }
    @Test public void checksumNeedsOneExactApkEntry()throws Exception{
        UpdateRelease offer=UpdateRelease.parse(release("1.1.0",95),"1.0.0");
        fails(()->offer.checksum(HASH+"  other.apk"));fails(()->offer.checksum("wrong  "+offer.file));
        fails(()->offer.checksum(HASH+"  "+offer.file+"\n"+HASH+"  "+offer.file));
    }
    @Test public void automaticCheckIsDailyOptionalAndHandlesClockRollback(){
        assertTrue(UpdateRelease.automaticDue(true,0,100));assertFalse(UpdateRelease.automaticDue(false,0,100));
        assertFalse(UpdateRelease.automaticDue(true,100,100+UpdateRelease.DAY-1));
        assertTrue(UpdateRelease.automaticDue(true,100,100+UpdateRelease.DAY));assertTrue(UpdateRelease.automaticDue(true,1000,900));
    }
    @Test public void packageIdentityRequiresMatchingNameVersionCodeSystemAndSigners()throws Exception{
        UpdateRelease offer=UpdateRelease.cached("1.1.0",95,HASH,"");
        offer.verifyIdentity(UpdateRelease.PACKAGE,"1.1.0",13,12,26,29,HASH,HASH,HASH);
        fails(()->offer.verifyIdentity("another.app","1.1.0",13,12,26,29,HASH,HASH,HASH));
        fails(()->offer.verifyIdentity(UpdateRelease.PACKAGE,"1.0.0",13,12,26,29,HASH,HASH,HASH));
        fails(()->offer.verifyIdentity(UpdateRelease.PACKAGE,"1.1.0",12,12,26,29,HASH,HASH,HASH));
        fails(()->offer.verifyIdentity(UpdateRelease.PACKAGE,"1.1.0",13,12,30,29,HASH,HASH,HASH));
        fails(()->offer.verifyIdentity(UpdateRelease.PACKAGE,"1.1.0",13,12,26,29,HASH.replace('a','b'),HASH,HASH));
        fails(()->offer.verifyIdentity(UpdateRelease.PACKAGE,"1.1.0",13,12,26,29,HASH,HASH.replace('a','b'),HASH));
    }
    @Test public void restoringReadyMetadataStillValidatesProtocol()throws Exception{
        assertEquals("1.1.0",UpdateRelease.cached("1.1.0",95,HASH,"说明").version);
        fails(()->UpdateRelease.cached("../1",95,HASH,""));fails(()->UpdateRelease.cached("1.1.0",0,HASH,""));
        fails(()->UpdateRelease.cached("1.1.0",95,"bad",""));
    }
}
