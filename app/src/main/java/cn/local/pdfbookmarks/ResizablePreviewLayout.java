package cn.local.pdfbookmarks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.Consumer;

/** Native split preview with a 48dp drag target; the Activity persists the preferred ratio. */
public final class ResizablePreviewLayout extends ViewGroup {
    private final Divider divider;
    private View firstPane, secondPane;
    private boolean wide;
    private float splitRatio = .5f;
    private int minimumFirstDp = -1, minimumSecondDp = -1;
    private Consumer<Float> ratioListener;

    public ResizablePreviewLayout(Context context) {
        super(context);
        divider = new Divider(context);
    }

    public void setPanes(View directory, View page) {
        if (directory == null || page == null || directory == page) throw new IllegalArgumentException("Two distinct preview panes required");
        if ((directory.getParent() != null && directory.getParent() != this)
                || (page.getParent() != null && page.getParent() != this)) throw new IllegalArgumentException("Preview pane already belongs to another layout");
        removeAllViews(); firstPane = directory; secondPane = page;
        addView(firstPane); addView(divider); addView(secondPane); requestLayout();
    }

    public void setWide(boolean value) {
        if (wide != value) { wide = value; requestLayout(); divider.invalidate(); }
    }
    public boolean isWide() { return wide; }
    public float getSplitRatio() { return splitRatio; }
    public void setSplitRatio(float ratio) { splitRatio = PreviewGeometry.splitRatio(ratio); requestLayout(); }
    public void setOnRatioChangedListener(Consumer<Float> listener) { ratioListener = listener; }
    public void setDividerColor(int color) { divider.line.setColor(color); divider.invalidate(); }
    public void setPaneMinimums(int firstDp, int secondDp) {
        if (firstDp < 0 || secondDp < 0) throw new IllegalArgumentException("Negative pane minimum");
        minimumFirstDp = firstDp; minimumSecondDp = secondDp; requestLayout();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int dividerSize() { return dp(48); }
    private int usableAxis() {
        int total = wide ? getWidth() - getPaddingLeft() - getPaddingRight() : getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(0, total - Math.min(dividerSize(), total));
    }
    private int firstSize(int available) {
        return PreviewGeometry.firstPaneSize(available,
            dp(minimumFirstDp < 0 ? (wide ? 220 : 180) : minimumFirstDp),
            dp(minimumSecondDp < 0 ? 240 : minimumSecondDp), splitRatio);
    }
    private void commitRatio(float ratio) {
        setSplitRatio(ratio);
        if (ratioListener != null) ratioListener.accept(splitRatio);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int desiredWidth = Math.max(getSuggestedMinimumWidth(), dp(wide ? 720 : 320));
        int desiredHeight = Math.max(getSuggestedMinimumHeight(), dp(wide ? 440 : 640));
        int measuredWidth = resolveSize(desiredWidth, widthSpec), measuredHeight = resolveSize(desiredHeight, heightSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
        if (firstPane == null || secondPane == null) return;
        int width = Math.max(0, measuredWidth - getPaddingLeft() - getPaddingRight());
        int height = Math.max(0, measuredHeight - getPaddingTop() - getPaddingBottom());
        int handle = Math.min(dividerSize(), wide ? width : height);
        int available = Math.max(0, (wide ? width : height) - handle), first = firstSize(available), second = available - first;
        firstPane.measure(exact(wide ? first : width), exact(wide ? height : first));
        divider.measure(exact(wide ? handle : width), exact(wide ? height : handle));
        secondPane.measure(exact(wide ? second : width), exact(wide ? height : second));
    }
    private static int exact(int dimension) { return MeasureSpec.makeMeasureSpec(Math.max(0, dimension), MeasureSpec.EXACTLY); }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT); }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (firstPane == null || secondPane == null) return;
        int x = getPaddingLeft(), y = getPaddingTop();
        firstPane.layout(x, y, x + firstPane.getMeasuredWidth(), y + firstPane.getMeasuredHeight());
        if (wide) x += firstPane.getMeasuredWidth(); else y += firstPane.getMeasuredHeight();
        divider.layout(x, y, x + divider.getMeasuredWidth(), y + divider.getMeasuredHeight());
        if (wide) x += divider.getMeasuredWidth(); else y += divider.getMeasuredHeight();
        secondPane.layout(x, y, x + secondPane.getMeasuredWidth(), y + secondPane.getMeasuredHeight());
    }

    private final class Divider extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float startCoordinate, startRatio;
        private int startFirstSize;
        private boolean dragged;

        Divider(Context context) {
            super(context); setFocusable(true); setClickable(true);
            setContentDescription(context.getString(R.string.preview_resize_description));
            line.setColor(0xff4f7e78); line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            line.setStrokeWidth(dp(isFocused() || isPressed() ? 5 : 3));
            float half = dp(22);
            if (wide) canvas.drawLine(cx, cy - half, cx, cy + half, line);
            else canvas.drawLine(cx - half, cy, cx + half, cy, line);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled() || !ResizablePreviewLayout.this.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startCoordinate = wide ? event.getRawX() : event.getRawY();
                    startRatio = splitRatio; startFirstSize = firstSize(usableAxis()); dragged = false;
                    setPressed(true); requestFocus(); getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE:
                    float delta = (wide ? event.getRawX() : event.getRawY()) - startCoordinate;
                    if (usableAxis() > 0) {
                        if (Math.abs(delta) > dp(3)) dragged = true;
                        setSplitRatio((startFirstSize + delta) / usableAxis());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    setPressed(false); getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragged) { if (ratioListener != null) ratioListener.accept(splitRatio); }
                    else performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false); setSplitRatio(startRatio);
                    getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        }
        @Override public boolean performClick() { return super.performClick(); }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            float step = event.isShiftPressed() ? .1f : .05f;
            if ((wide && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) || (!wide && keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                commitRatio(splitRatio - step); return true;
            }
            if ((wide && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) || (!wide && keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                commitRatio(splitRatio + step); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_NUMPAD_0 || keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
                commitRatio(.5f); return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 20, 80, splitRatio * 100));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.preview_first_larger_action, getContext().getString(R.string.preview_resize_first)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.preview_second_larger_action, getContext().getString(R.string.preview_resize_second)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.preview_split_reset_action, getContext().getString(R.string.preview_resize_reset)));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (isEnabled() && ResizablePreviewLayout.this.isEnabled()) {
                if (action == R.id.preview_first_larger_action) { commitRatio(splitRatio + .05f); return true; }
                if (action == R.id.preview_second_larger_action) { commitRatio(splitRatio - .05f); return true; }
                if (action == R.id.preview_split_reset_action) { commitRatio(.5f); return true; }
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null
                        && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                    float percent = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                    if (!Float.isNaN(percent) && !Float.isInfinite(percent)) { commitRatio(percent / 100f); return true; }
                }
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }
}
