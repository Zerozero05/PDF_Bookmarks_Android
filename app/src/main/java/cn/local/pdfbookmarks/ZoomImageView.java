package cn.local.pdfbookmarks;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import java.util.function.Consumer;

/** A bitmap preview. The Activity retains ownership of every supplied bitmap. */
public final class ZoomImageView extends ImageView {
    private final PreviewGeometry.Zoom geometry = new PreviewGeometry.Zoom();
    private final Matrix imageTransform = new Matrix();
    private final ScaleGestureDetector scales;
    private final GestureDetector gestures;
    private Consumer<Float> zoomListener;
    private float lastX, lastY;
    private boolean scaling;

    public ZoomImageView(Context context) { this(context, null); }
    public ZoomImageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setScaleType(ScaleType.MATRIX);
        setFocusable(true); setClickable(true);
        setContentDescription(context.getString(R.string.preview_image_description));
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaling = true; disallowParent(true); return geometry.ready();
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (geometry.zoomBy(detector.getScaleFactor(), detector.getFocusX() - getPaddingLeft(), detector.getFocusY() - getPaddingTop())) applyTransform();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) { scaling = false; }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (!geometry.ready()) return false;
                if (geometry.zoom() > 1.05f) resetZoom();
                else { geometry.zoomBy(2.5f, event.getX() - getPaddingLeft(), event.getY() - getPaddingTop()); applyTransform(); }
                return true;
            }
        });
        updateContent();
    }

    public float getZoom() { return geometry.zoom(); }
    public void setZoomListener(Consumer<Float> listener) { zoomListener = listener; }
    public void resetZoom() { geometry.reset(); applyTransform(); }
    public void zoomIn() { zoomAroundCenter(1.3f); }
    public void zoomOut() { zoomAroundCenter(1f / 1.3f); }

    private void zoomAroundCenter(float factor) {
        if (geometry.zoomBy(factor, (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f,
                (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f)) applyTransform();
    }

    @Override public void setImageBitmap(Bitmap bitmap) { super.setImageBitmap(bitmap); updateContent(); }
    @Override public void setImageDrawable(Drawable drawable) { super.setImageDrawable(drawable); updateContent(); }
    private void updateContent() {
        // ImageView may call overridden setters before this subclass is initialized.
        if (geometry == null) return;
        Drawable image = getDrawable();
        geometry.setContent(image == null ? 0 : Math.max(0, image.getIntrinsicWidth()),
            image == null ? 0 : Math.max(0, image.getIntrinsicHeight()));
        geometry.setViewport(Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()),
            Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom()));
        applyTransform();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        geometry.setViewport(Math.max(0, width - getPaddingLeft() - getPaddingRight()),
            Math.max(0, height - getPaddingTop() - getPaddingBottom()));
        applyTransform();
    }

    private void applyTransform() {
        imageTransform.reset();
        if (geometry.ready()) {
            imageTransform.setScale(geometry.scale(), geometry.scale());
            imageTransform.postTranslate(geometry.left(), geometry.top());
        }
        setImageMatrix(imageTransform); invalidate();
        if (zoomListener != null) zoomListener.accept(geometry.zoom());
    }

    private void disallowParent(boolean disallow) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow); }

    @Override protected void onDetachedFromWindow() {
        disallowParent(false); scaling = false; super.onDetachedFromWindow();
    }

    // Single taps call performClick exactly once in onSingleTapConfirmed, after double-tap disambiguation.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || !geometry.ready()) return super.onTouchEvent(event);
        scales.onTouchEvent(event); gestures.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX(); lastY = event.getY();
                disallowParent(geometry.isZoomed()); break;
            case MotionEvent.ACTION_POINTER_DOWN:
                disallowParent(true); break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX, dy = event.getY() - lastY;
                if (!scaling && event.getPointerCount() == 1 && geometry.isZoomed()) {
                    // Hand an edge swipe back to the surrounding vertical page.
                    boolean verticalSwipe = Math.abs(dy) >= Math.abs(dx);
                    boolean canConsume = geometry.canPan(verticalSwipe ? 0 : dx, verticalSwipe ? dy : 0);
                    disallowParent(canConsume);
                    if (canConsume && geometry.pan(dx, dy)) applyTransform();
                }
                lastX = event.getX(); lastY = event.getY(); break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() == 2) {
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    lastX = event.getX(remaining); lastY = event.getY(remaining);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                scaling = false; disallowParent(false); break;
            default: break;
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled() || !geometry.ready()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_EQUALS: case KeyEvent.KEYCODE_NUMPAD_ADD: zoomIn(); return true;
            case KeyEvent.KEYCODE_MINUS: case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: zoomOut(); return true;
            case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_NUMPAD_0: case KeyEvent.KEYCODE_MOVE_HOME: resetZoom(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: return panFromKey(48, 0) || super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return panFromKey(-48, 0) || super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_UP: return panFromKey(0, 48) || super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_DOWN: return panFromKey(0, -48) || super.onKeyDown(keyCode, event);
            default: return super.onKeyDown(keyCode, event);
        }
    }
    private boolean panFromKey(float x, float y) {
        float density = getResources().getDisplayMetrics().density;
        if (!geometry.pan(x * density, y * density)) return false;
        applyTransform(); return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!geometry.ready()) return;
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.preview_zoom_in_action, getContext().getString(R.string.preview_zoom_in)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.preview_zoom_out_action, getContext().getString(R.string.preview_zoom_out)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.preview_zoom_fit_action, getContext().getString(R.string.preview_zoom_fit)));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (isEnabled() && geometry.ready()) {
            if (action == R.id.preview_zoom_in_action) { zoomIn(); return true; }
            if (action == R.id.preview_zoom_out_action) { zoomOut(); return true; }
            if (action == R.id.preview_zoom_fit_action) { resetZoom(); return true; }
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
