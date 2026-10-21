package cn.local.pdfbookmarks;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewGeometryTest {
    private PreviewGeometry.Zoom portrait() {
        PreviewGeometry.Zoom image = new PreviewGeometry.Zoom();
        image.setViewport(400, 600); image.setContent(1000, 1500); return image;
    }

    @Test public void pageStartsEntirelyVisibleAndCentered() {
        PreviewGeometry.Zoom image = portrait();
        assertEquals(.4f, image.scale(), .0001f); assertEquals(0, image.left(), .0001f); assertEquals(0, image.top(), .0001f);
        assertFalse(image.pan(30, -20)); assertFalse(image.isZoomed());
    }
    @Test public void landscapeImageRetainsLetterboxWithoutPanningAtFit() {
        PreviewGeometry.Zoom image = new PreviewGeometry.Zoom(); image.setViewport(400, 600); image.setContent(1000, 500);
        assertEquals(.4f, image.scale(), .0001f); assertEquals(200, image.top(), .0001f);
        assertFalse(image.canPan(100, 100)); assertFalse(image.pan(100, 100));
    }
    @Test public void pinchKeepsTheSameDocumentPointUnderFingerAwayFromBoundary() {
        PreviewGeometry.Zoom image = portrait();
        float focusX = 100, focusY = 240, pageX = (focusX - image.left()) / image.scale(), pageY = (focusY - image.top()) / image.scale();
        image.zoomBy(2, focusX, focusY);
        assertEquals(pageX, (focusX - image.left()) / image.scale(), .001f);
        assertEquals(pageY, (focusY - image.top()) / image.scale(), .001f);
    }
    @Test public void zoomAndPanningNeverRevealSpaceBeyondLargerImage() {
        PreviewGeometry.Zoom image = portrait(); image.zoomBy(3, 200, 300);
        image.pan(100000, 100000); assertEquals(0, image.left(), .001f); assertEquals(0, image.top(), .001f);
        assertFalse(image.canPan(20, 20)); assertTrue(image.canPan(-20, -20));
        image.pan(-100000, -100000); assertEquals(-800, image.left(), .001f); assertEquals(-1200, image.top(), .001f);
        assertFalse(image.canPan(-20, -20)); assertTrue(image.canPan(20, 20));
    }
    @Test public void zoomIsBoundedAndResetAlwaysShowsWholePage() {
        PreviewGeometry.Zoom image = portrait(); image.zoomBy(100, 200, 300); assertEquals(6, image.zoom(), 0);
        image.pan(70, -70); image.reset(); assertEquals(1, image.zoom(), 0); assertEquals(0, image.left(), .001f); assertEquals(0, image.top(), .001f);
        assertFalse(image.zoomBy(.1f, 200, 300));
    }
    @Test public void changingThePageResetsZoomButChangingWindowKeepsIt() {
        PreviewGeometry.Zoom image = portrait(); image.zoomBy(2, 200, 300); image.pan(80, -30);
        image.setViewport(600, 400); assertEquals(2, image.zoom(), 0); assertTrue(image.left() <= .001f || !image.hasHorizontalOverflow());
        image.setContent(1200, 800); assertEquals(1, image.zoom(), 0); assertEquals(0, image.left(), .001f);
    }
    @Test public void resizedWindowKeepsTheSameDocumentCenterWhenItFitsConstraints() {
        PreviewGeometry.Zoom image = portrait(); image.zoomBy(3, 200, 300); image.pan(80, -30);
        float pageX = (200 - image.left()) / image.scale(), pageY = (300 - image.top()) / image.scale();
        image.setViewport(600, 900);
        assertEquals(pageX, (300 - image.left()) / image.scale(), .001f);
        assertEquals(pageY, (450 - image.top()) / image.scale(), .001f);
    }
    @Test public void noDrawableOrZeroViewportCannotProduceZoomOrNaN() {
        PreviewGeometry.Zoom image = new PreviewGeometry.Zoom();
        assertFalse(image.zoomBy(2, 0, 0)); assertEquals(0, image.scale(), 0);
        image.setContent(1000, 1500); assertFalse(image.zoomBy(2, 0, 0));
        image.setViewport(400, 600); assertTrue(image.ready());
        image.setContent(0, 0); assertFalse(image.ready()); assertFalse(Float.isNaN(image.left()));
    }
    @Test public void badExternalGeometryIsRejected() {
        PreviewGeometry.Zoom image = portrait();
        assertThrows(IllegalArgumentException.class, () -> image.setViewport(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> image.zoomBy(Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> image.zoomBy(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> image.pan(Float.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> PreviewGeometry.splitRatio(Float.NaN));
    }
    @Test public void dividerKeepsMinimumReadablePanesAtPreferredExtremes() {
        assertEquals(220, PreviewGeometry.firstPaneSize(652, 220, 240, .2f));
        assertEquals(412, PreviewGeometry.firstPaneSize(652, 220, 240, .8f));
        assertEquals(326, PreviewGeometry.firstPaneSize(652, 220, 240, .5f));
    }
    @Test public void tinyWindowSharesSpaceWithoutNegativeOrOversizedPane() {
        int first = PreviewGeometry.firstPaneSize(100, 180, 240, .5f);
        assertEquals(43, first); assertEquals(57, 100 - first);
        assertEquals(0, PreviewGeometry.firstPaneSize(0, 180, 240, .5f));
        assertThrows(IllegalArgumentException.class, () -> PreviewGeometry.firstPaneSize(-1, 0, 0, .5f));
    }
    @Test public void ratioSurvivesViewportConstraintsAndRestoresWhenExpanded() {
        float preferred = .7f;
        assertEquals(412, PreviewGeometry.firstPaneSize(652, 220, 240, preferred));
        assertEquals(700, PreviewGeometry.firstPaneSize(1000, 220, 240, preferred));
        assertEquals(.2f, PreviewGeometry.splitRatio(-1), 0); assertEquals(.8f, PreviewGeometry.splitRatio(2), 0);
    }
}
