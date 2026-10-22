package cn.local.pdfbookmarks;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** A horizontal reveal gesture; vertical movement remains owned by the file list. */
final class SwipeGrantRow extends FrameLayout {
    private final int slop;
    private View content;
    private View actions;
    private float downX,downY,start;
    private int revealWidth;
    private boolean dragging,opened,enabled=true;
    private Runnable revealed;

    SwipeGrantRow(Context context){super(context);slop=ViewConfiguration.get(context).getScaledTouchSlop();}
    void configure(View content,View actions,int revealWidth,Runnable revealed){
        this.content=content;this.actions=actions;this.revealWidth=revealWidth;this.revealed=revealed;
        actions.setVisibility(INVISIBLE);
    }
    void allowSwipe(boolean allowed){enabled=allowed;if(!allowed)close(false);}
    boolean isOpen(){return content!=null&&(opened||content.getTranslationX()<0);}
    void close(boolean animate){settle(0,animate);}

    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(!enabled||content==null)return super.onInterceptTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            content.animate().cancel();downX=event.getX();downY=event.getY();start=content.getTranslationX();dragging=false;
        }else if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.25f&&(dx<0||start<0)){
                dragging=true;actions.setVisibility(VISIBLE);getParent().requestDisallowInterceptTouchEvent(true);return true;
            }
        }
        return false;
    }
    // This container consumes horizontal drags only; it has no click action. The content handles
    // navigation, and its focusable overflow menu plus selection controls expose every grant action
    // to keyboard and accessibility users. Ending a drag must never perform the content's click.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!dragging||content==null)return super.onTouchEvent(event);
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_MOVE){
            content.setTranslationX(Math.max(-revealWidth,Math.min(0,start+event.getX()-downX)));return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            boolean open=action!=MotionEvent.ACTION_CANCEL&&content.getTranslationX()<-revealWidth*.35f;
            settle(open?-revealWidth:0,true);dragging=false;getParent().requestDisallowInterceptTouchEvent(false);
            if(open&&revealed!=null)revealed.run();return true;
        }
        return true;
    }
    private void settle(float translation,boolean animate){
        if(content==null)return;content.animate().cancel();
        opened=translation<0;if(actions!=null)actions.setVisibility(opened?VISIBLE:INVISIBLE);
        if(animate)content.animate().translationX(translation).setDuration(160).start();
        else content.setTranslationX(translation);
    }
}
