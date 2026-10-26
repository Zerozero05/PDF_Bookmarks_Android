package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

/** Opt-in integration tests run only under the separately installed, empty test host. */
final class Workflow142Runner {
    static void run(Instrumentation instrumentation,Bundle arguments){
        Bundle result=new Bundle();Activity current=null;Throwable failure=null;
        try{
            Context context=instrumentation.getTargetContext();
            boolean readOnly=arguments.containsKey("readOnly142")||arguments.containsKey("profile142");
            if(!(readOnly?"cn.local.pdfbookmarks":"cn.local.pdfbookmarks.testhost").equals(context.getPackageName()))throw new AssertionError("Unexpected target package for this test scope");
            if(!readOnly)context.getSharedPreferences(AppUpdates.PREFS,Context.MODE_PRIVATE).edit().putBoolean("automatic",false).commit();
            PDFBoxResourceLoader.init(context);
            if(arguments.containsKey("performance142")){
                int size=Integer.parseInt(arguments.getString("performance142"));
                result.putString("performance142",Performance142Smoke.verify(context,size));
            }else{
                current=instrumentation.startActivitySync(new Intent(Intent.ACTION_MAIN).setClass(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                instrumentation.waitForIdleSync();
                if(arguments.containsKey("profile142"))result.putString("profile142",ReadOnly142Smoke.profile(instrumentation,current));
                else if(readOnly)result.putString("readOnly142",ReadOnly142Smoke.verify(instrumentation,current,android.net.Uri.parse(arguments.getString("readOnly142"))));
                else if(arguments.containsKey("restart142"))result.putString("restart142",Share142Smoke.restartPhase(instrumentation,current,arguments.getString("restart142")));
                else if(arguments.containsKey("pipeline142"))result.putString("pipeline142",Share142Smoke.pipeline(instrumentation,current));
                else{
                    result.putString("verifiedPdf",VerifiedPdfSmoke.verify(context));
                    result.putString("sharing142",Share142Smoke.verify(instrumentation,current));
                }
            }
        }catch(Throwable error){failure=error;result.putString("failure",Log.getStackTraceString(error));}
        finally{
            if(current!=null){Activity opened=current;try{instrumentation.runOnMainSync(opened::finish);instrumentation.waitForIdleSync();}catch(Throwable error){if(failure==null)failure=error;}}
        }
        result.putString("workflow142",failure==null?"PASS":"FAIL");
        result.putString("scope",arguments.containsKey("readOnly142")||arguments.containsKey("profile142")?"Production package read-only checks; no PDF/catalog write, copy, backup or deletion":"Separate test host and synthetic source only; real notes and production preferences are not accessed");
        result.putString("stream",failure==null?"\nPASS: isolated v1.4.2 checks.\n":"\nFAIL: "+Log.getStackTraceString(failure));
        instrumentation.finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
