package cn.local.pdfbookmarks;

import org.junit.Test;
import static org.junit.Assert.*;

public class UiAppearanceTest {
    @Test public void systemThemeTracksOnlySystemMode(){
        assertFalse(UiAppearance.of(UiAppearance.SYSTEM,0,0,false).dark);
        assertTrue(UiAppearance.of(UiAppearance.SYSTEM,0,0,true).dark);
        assertFalse(UiAppearance.of(UiAppearance.LIGHT,0,0,true).dark);
        assertTrue(UiAppearance.of(UiAppearance.DARK,0,0,false).dark);
    }
    @Test public void unknownPersistedOptionsResolveToSafeAppearanceDefaults(){
        UiAppearance settings=UiAppearance.of(99,-2,9,true);
        assertEquals(UiAppearance.SYSTEM,settings.mode);
        assertEquals(UiAppearance.STANDARD,settings.textSize);
        assertEquals(UiAppearance.COMFORTABLE,settings.density);
        assertTrue(settings.dark);
    }
    @Test public void largerTextDoesNotDependOnDensity(){
        UiAppearance standard=UiAppearance.of(0,0,0,false),large=UiAppearance.of(0,1,1,false),largest=UiAppearance.of(0,2,0,false);
        assertEquals(15f,standard.textSp(15),.001f);
        assertEquals(16.8f,large.textSp(15),.001f);
        assertEquals(18.6f,largest.textSp(15),.001f);
        assertEquals(large.textSp(15),UiAppearance.of(0,1,0,false).textSp(15),.001f);
    }
    @Test public void compactSpacingKeepsTouchTargetsAndZeroPadding(){
        UiAppearance comfortable=UiAppearance.of(0,0,0,false),compact=UiAppearance.of(0,0,1,false);
        assertEquals(20,comfortable.spacingDp(20));assertEquals(16,compact.spacingDp(20));
        assertEquals(0,compact.spacingDp(0));assertEquals(1,compact.spacingDp(1));
        assertEquals(48,comfortable.touchDp());assertEquals(48,compact.touchDp());
    }
    @Test public void bothPalettesDistinguishBodySecondaryAndSelectedText(){
        for(boolean dark:new boolean[]{false,true}){
            UiAppearance appearance=UiAppearance.of(0,0,0,dark);
            assertNotEquals(appearance.page,appearance.text);
            assertNotEquals(appearance.surface,appearance.text);
            assertNotEquals(appearance.onAccent,appearance.accent);
            assertNotEquals(appearance.selectedRow,appearance.text);
            assertNotEquals(appearance.disabledBackground,appearance.disabledText);
            assertTrue(contrast(appearance.text,appearance.surface)>7);
            assertTrue(contrast(appearance.onAccent,appearance.accent)>4.5);
            assertTrue(contrast(appearance.secondaryText,appearance.surface)>4.5);
        }
    }
    private static double contrast(int a,int b){
        double aa=luminance(a),bb=luminance(b);return (Math.max(aa,bb)+.05)/(Math.min(aa,bb)+.05);
    }
    private static double luminance(int color){
        return .2126*linear((color>>16)&255)+.7152*linear((color>>8)&255)+.0722*linear(color&255);
    }
    private static double linear(int value){double s=value/255d;return s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4);}
}
