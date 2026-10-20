package cn.local.pdfbookmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 文件浏览的导航及请求代次；取消不提交选择，过期查询不能覆盖当前目录。 */
final class FilePickerState {
    static final class Entry {
        final String uri,name,mime;
        final boolean folder;
        Entry(String uri,String name,String mime,boolean folder){
            this.uri=uri;this.name=name;this.mime=mime;this.folder=folder;
        }
    }

    private final boolean pdf;
    private final List<Entry> path=new ArrayList<>();
    private List<Entry> entries=Collections.emptyList();
    private int generation;
    private boolean closed;

    FilePickerState(boolean pdf){this.pdf=pdf;}
    Entry location(){return path.isEmpty()?null:path.get(path.size()-1);}
    List<Entry> entries(){return entries;}
    boolean isClosed(){return closed;}
    int beginLoad(){entries=Collections.emptyList();return ++generation;}
    boolean publish(int token,List<Entry> found){
        if(closed||token!=generation)return false;
        List<Entry> visible=new ArrayList<>();
        for(Entry entry:found)if(accepts(entry))visible.add(entry);
        visible.sort(Comparator.comparing((Entry e)->!e.folder)
                .thenComparing(e->e.name,String.CASE_INSENSITIVE_ORDER).thenComparing(e->e.uri));
        entries=Collections.unmodifiableList(visible);return true;
    }
    boolean accepts(Entry entry){
        if(entry.folder)return true;
        String name=entry.name.toLowerCase(Locale.ROOT);
        return pdf?(name.endsWith(".pdf")||"application/pdf".equals(entry.mime))
                :(name.endsWith(".json")||"application/json".equals(entry.mime));
    }
    void enter(Entry entry){
        if(closed||!entry.folder||!entries.contains(entry))throw new IllegalStateException("目录已变化，请重新选择。");
        path.add(entry);beginLoad();
    }
    boolean up(){
        if(closed||path.isEmpty())return false;
        path.remove(path.size()-1);beginLoad();return true;
    }
    Entry select(Entry entry){
        if(closed||entry.folder||!entries.contains(entry))throw new IllegalStateException("文件列表已变化，请重新选择。");
        closed=true;generation++;return entry;
    }
    void cancel(){closed=true;generation++;}
}
