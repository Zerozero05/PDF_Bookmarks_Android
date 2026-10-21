package cn.local.pdfbookmarks;

/** Geometry shared by the preview gestures and the adjustable two-pane layout. */
public final class PreviewGeometry {
    private PreviewGeometry() {}

    public static float splitRatio(float value) {
        finite(value);
        return clamp(value, .2f, .8f);
    }

    /** available excludes the divider. Small windows share space without negative sizes. */
    public static int firstPaneSize(int available, int minimumFirst, int minimumSecond, float ratio) {
        if (available < 0 || minimumFirst < 0 || minimumSecond < 0) throw new IllegalArgumentException("Negative pane size");
        float preferred = splitRatio(ratio);
        if (available == 0) return 0;
        long minimumTotal = (long) minimumFirst + minimumSecond;
        if (minimumTotal > available) return Math.round((float) ((double) available * minimumFirst / minimumTotal));
        return Math.max(minimumFirst, Math.min(available - minimumSecond, Math.round(available * preferred)));
    }

    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static void finite(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("Non-finite geometry");
    }

    public static final class Zoom {
        public static final float MAX_ZOOM = 6f;
        private float viewWidth, viewHeight, contentWidth, contentHeight;
        private float zoom = 1f, offsetX, offsetY;

        public void setViewport(float width, float height) {
            dimensions(width, height);
            float previousFit = fitScale();
            viewWidth = width; viewHeight = height;
            float nextFit = fitScale();
            if (previousFit > 0 && nextFit > 0) {
                offsetX *= nextFit / previousFit; offsetY *= nextFit / previousFit;
            }
            constrain();
        }

        public void setContent(float width, float height) {
            dimensions(width, height); contentWidth = width; contentHeight = height; reset();
        }

        public void reset() { zoom = 1f; offsetX = 0; offsetY = 0; }
        public boolean ready() { return viewWidth > 0 && viewHeight > 0 && contentWidth > 0 && contentHeight > 0; }
        public float zoom() { return zoom; }
        public float scale() { return fitScale() * zoom; }
        public float left() { return (viewWidth - contentWidth * scale()) / 2f + offsetX; }
        public float top() { return (viewHeight - contentHeight * scale()) / 2f + offsetY; }
        public boolean isZoomed() { return ready() && zoom > 1.001f; }
        public boolean hasHorizontalOverflow() { return ready() && contentWidth * scale() > viewWidth + .5f; }
        public boolean hasVerticalOverflow() { return ready() && contentHeight * scale() > viewHeight + .5f; }

        /** Keep the image point underneath the gesture focus, except at image boundaries. */
        public boolean zoomBy(float factor, float focusX, float focusY) {
            finite(factor); finite(focusX); finite(focusY);
            if (factor <= 0) throw new IllegalArgumentException("Non-positive scale factor");
            if (!ready()) return false;
            float next = clamp(zoom * factor, 1f, MAX_ZOOM);
            if (next == zoom) return false;
            float relative = next / zoom;
            offsetX = (focusX - viewWidth / 2f) - (focusX - viewWidth / 2f - offsetX) * relative;
            offsetY = (focusY - viewHeight / 2f) - (focusY - viewHeight / 2f - offsetY) * relative;
            zoom = next; constrain(); return true;
        }

        public boolean pan(float dx, float dy) {
            finite(dx); finite(dy);
            float beforeX = offsetX, beforeY = offsetY;
            offsetX += dx; offsetY += dy; constrain();
            return Math.abs(beforeX - offsetX) > .01f || Math.abs(beforeY - offsetY) > .01f;
        }

        public boolean canPan(float dx, float dy) {
            finite(dx); finite(dy);
            float maxX = Math.max(0, (contentWidth * scale() - viewWidth) / 2f);
            float maxY = Math.max(0, (contentHeight * scale() - viewHeight) / 2f);
            return Math.abs(clamp(offsetX + dx, -maxX, maxX) - offsetX) > .01f
                || Math.abs(clamp(offsetY + dy, -maxY, maxY) - offsetY) > .01f;
        }

        private float fitScale() { return ready() ? Math.min(viewWidth / contentWidth, viewHeight / contentHeight) : 0; }
        private void constrain() {
            float maxX = Math.max(0, (contentWidth * scale() - viewWidth) / 2f);
            float maxY = Math.max(0, (contentHeight * scale() - viewHeight) / 2f);
            offsetX = clamp(offsetX, -maxX, maxX); offsetY = clamp(offsetY, -maxY, maxY);
        }
        private static void dimensions(float width, float height) {
            finite(width); finite(height);
            if (width < 0 || height < 0) throw new IllegalArgumentException("Negative image size");
        }
    }
}
