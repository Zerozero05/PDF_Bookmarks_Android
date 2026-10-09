package cn.local.pdfbookmarks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** 只验证本地取消与流交接逻辑；不代替平板文件提供者的取消验收。 */
public class ReadCancellationTest {
    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(){super(new byte[]{1});}
        @Override public void close()throws IOException {closed=true;super.close();}
    }
    @Test public void cancelClosesActiveStream()throws Exception {
        MainActivity.ReadJob job=new MainActivity.ReadJob();TrackedInput input=new TrackedInput();job.attach(input);
        job.cancelled=true;job.closeInput();assertTrue(input.closed);
        try{job.check();fail("cancelled read continued");}catch(InterruptedIOException expected){}
    }
    @Test public void streamReturnedAfterCancellationIsClosed()throws Exception {
        MainActivity.ReadJob job=new MainActivity.ReadJob();job.cancelled=true;job.closeInput();TrackedInput input=new TrackedInput();
        try{job.attach(input);fail("late stream accepted");}catch(InterruptedIOException expected){}
        assertTrue(input.closed);
    }
    @Test public void cancellationDoesNotCloseNewJobsStream()throws Exception {
        MainActivity.ReadJob oldJob=new MainActivity.ReadJob(),newJob=new MainActivity.ReadJob();
        TrackedInput oldInput=new TrackedInput(),newInput=new TrackedInput();oldJob.attach(oldInput);newJob.attach(newInput);
        oldJob.cancelled=true;oldJob.closeInput();assertTrue(oldInput.closed);assertFalse(newInput.closed);newJob.check();newInput.close();
    }
    @Test public void closingReadUnblocksWaitingWorker()throws Exception {
        MainActivity.ReadJob job=new MainActivity.ReadJob();CountDownLatch started=new CountDownLatch(1),closed=new CountDownLatch(1),finished=new CountDownLatch(1);
        java.io.InputStream input=new java.io.InputStream(){public int read()throws IOException{started.countDown();try{if(!closed.await(2,TimeUnit.SECONDS))throw new IOException("read stayed blocked");}catch(InterruptedException e){throw new IOException(e);}return -1;}public void close(){closed.countDown();}};
        job.attach(input);Thread thread=new Thread(()->{try{input.read();}catch(IOException ignored){}finally{finished.countDown();}});thread.start();
        assertTrue(started.await(1,TimeUnit.SECONDS));job.cancelled=true;job.closeInput();assertTrue(finished.await(1,TimeUnit.SECONDS));thread.join();
    }
    @Test public void detachedClosedStreamIsNotReclosed()throws Exception {
        MainActivity.ReadJob job=new MainActivity.ReadJob();TrackedInput input=new TrackedInput();job.attach(input);job.detach();job.cancelled=true;job.closeInput();assertFalse(input.closed);input.close();
    }
}
