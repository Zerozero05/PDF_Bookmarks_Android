package cn.local.pdfbookmarks;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class FilePickerStateTest {
    private static FilePickerState.Entry folder(String id){return new FilePickerState.Entry(id,id,"vnd.android.document/directory",true);}
    private static FilePickerState.Entry file(String name){return new FilePickerState.Entry("file:"+name,name,"application/octet-stream",false);}

    @Test public void cancellingWithoutSelectionRejectsLateQuery(){
        FilePickerState state=new FilePickerState(true);int token=state.beginLoad();state.cancel();
        assertFalse(state.publish(token,List.of(file("book.pdf"))));assertTrue(state.entries().isEmpty());assertTrue(state.isClosed());
    }
    @Test public void cancelledEmptyOrFailedQueryCanAlwaysBeClosed(){
        FilePickerState state=new FilePickerState(false);assertTrue(state.publish(state.beginLoad(),List.of()));
        state.cancel();assertFalse(state.up());assertFalse(state.publish(state.beginLoad(),List.of(file("toc.json"))));
    }
    @Test public void folderNavigationReturnsToGrantedRootsWithoutInferringParentIds(){
        FilePickerState state=new FilePickerState(true);FilePickerState.Entry root=folder("opaque-root"),child=folder("opaque-child");
        state.publish(state.beginLoad(),List.of(root));state.enter(root);
        state.publish(state.beginLoad(),List.of(child));state.enter(child);assertSame(child,state.location());
        assertTrue(state.up());assertSame(root,state.location());assertTrue(state.up());assertNull(state.location());assertFalse(state.up());
    }
    @Test public void previousFolderQueryCannotReplaceNewLocation(){
        FilePickerState state=new FilePickerState(true);FilePickerState.Entry root=folder("root");
        state.publish(state.beginLoad(),List.of(root));state.enter(root);int old=state.beginLoad();
        state.up();int current=state.beginLoad();assertTrue(state.publish(current,List.of(root)));
        assertFalse(state.publish(old,List.of(file("wrong.pdf"))));assertSame(root,state.entries().get(0));
    }
    @Test public void hiddenEnjoyPdfAndUppercaseExtensionRemainVisibleAndFoldersComeFirst(){
        FilePickerState state=new FilePickerState(true);FilePickerState.Entry root=folder("z-folder"),hidden=file(".note_123.pdf"),upper=file("BOOK.PDF");
        state.publish(state.beginLoad(),List.of(file("toc.json"),upper,hidden,root,file("photo.jpg")));
        assertEquals(List.of(root,hidden,upper),state.entries());
    }
    @Test public void jsonPickerRetainsNativeAndDesktopJsonButRejectsOtherFiles(){
        FilePickerState state=new FilePickerState(false);FilePickerState.Entry nativeJson=file("jasminum-outline.json"),desktop=file("book.toc.JSON");
        state.publish(state.beginLoad(),List.of(nativeJson,desktop,file("book.pdf"),file("notes.txt")));
        assertEquals(List.of(desktop,nativeJson),state.entries());
    }
    @Test public void selectingAVisibleFileCommitsOnceAndRejectsLaterResults(){
        FilePickerState state=new FilePickerState(true);FilePickerState.Entry entry=file("book.pdf");int token=state.beginLoad();
        state.publish(token,List.of(entry));assertSame(entry,state.select(entry));
        assertFalse(state.publish(token,List.of(file("other.pdf"))));assertThrows(IllegalStateException.class,()->state.select(entry));
    }
    @Test public void folderOrStaleFileCannotBeCommittedAsAnInput(){
        FilePickerState state=new FilePickerState(true);FilePickerState.Entry directory=folder("root");
        state.publish(state.beginLoad(),List.of(directory));assertThrows(IllegalStateException.class,()->state.select(directory));
        assertThrows(IllegalStateException.class,()->state.select(file("unlisted.pdf")));assertFalse(state.isClosed());
    }
}
