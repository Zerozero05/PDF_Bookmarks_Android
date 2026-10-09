package cn.local.pdfbookmarks;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** 目录区域自己处理滑动，避免外层页面提前拦截手势。 */
public final class DirectoryScrollView extends ScrollView {
    private float lastY;
    public DirectoryScrollView(Context context){
        super(context);setVerticalScrollBarEnabled(true);setScrollbarFadingEnabled(false);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            lastY=event.getY();getParent().requestDisallowInterceptTouchEvent(canScrollVertically(1)||canScrollVertically(-1));
        }else if(action==MotionEvent.ACTION_MOVE){
            int direction=event.getY()<lastY?1:-1;
            getParent().requestDisallowInterceptTouchEvent(canScrollVertically(direction));lastY=event.getY();
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
        return handled;
    }
}
