package cn.local.pdfbookmarks;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import static org.junit.Assert.*;

public class WorkMetricsTest {
    @Test public void observesBytesWithoutChangingStreamOrLeakingToNextOperation()throws Exception{
        WorkMetrics first=new WorkMetrics("preview");WorkMetrics.attach(first);
        byte[] bytes=new byte[]{1,2,3,4};
        try(InputStream input=WorkMetrics.providerRead(new ByteArrayInputStream(bytes))){
            assertEquals(1,input.read());byte[] remainder=new byte[3];assertEquals(3,input.read(remainder));assertArrayEquals(new byte[]{2,3,4},remainder);assertEquals(-1,input.read());
        }finally{WorkMetrics.detach();}
        assertEquals(1,first.providerReads);assertEquals(4,first.providerBytes);
        InputStream plain=new ByteArrayInputStream(bytes);assertSame(plain,WorkMetrics.providerRead(plain));
        WorkMetrics second=new WorkMetrics("save");WorkMetrics.attach(second);try{WorkMetrics.directoryQuery();}finally{WorkMetrics.detach();}
        assertEquals(0,second.providerReads);assertEquals(1,second.directoryQueries);assertEquals(0,first.directoryQueries);
    }
    @Test public void observerPreservesReadFailureAndClose()throws Exception{
        boolean[] closed={false};InputStream failed=new InputStream(){
            @Override public int read()throws IOException{throw new IOException("source unavailable");}
            @Override public void close(){closed[0]=true;}
        };
        WorkMetrics metrics=new WorkMetrics("read");WorkMetrics.attach(metrics);
        try(InputStream input=WorkMetrics.providerRead(failed)){
            try{input.read();fail();}catch(IOException expected){assertEquals("source unavailable",expected.getMessage());}
        }finally{WorkMetrics.detach();}
        assertTrue(closed[0]);assertEquals(0,metrics.providerBytes);
    }
}
