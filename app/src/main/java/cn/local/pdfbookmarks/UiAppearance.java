package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.ContextThemeWrapper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

/** Appearance preferences are independent of file tasks, recovery and update settings. */
public final class UiAppearance {
    public static final int SYSTEM=0,LIGHT=1,DARK=2;
    public static final int STANDARD=0,LARGE=1,EXTRA_LARGE=2;
    public static final int COMFORTABLE=0,COMPACT=1;
    private static final String PREFERENCES="ui_appearance";

    public final int mode,textSize,density;
    public final boolean dark;
    public final int page,surface,surfaceVariant,text,secondaryText,accent,onAccent,outline;
    public final int statusBg,statusText,error,errorBg,disabledBackground,disabledText,selectedRow;

    private UiAppearance(int mode,int textSize,int density,boolean systemDark){
        this.mode=valid(mode,SYSTEM,DARK)?mode:SYSTEM;
        this.textSize=valid(textSize,STANDARD,EXTRA_LARGE)?textSize:STANDARD;
        this.density=valid(density,COMFORTABLE,COMPACT)?density:COMFORTABLE;
        dark=this.mode==DARK||(this.mode==SYSTEM&&systemDark);
        page=dark?0xff10191b:0xfff4f7f8;
        surface=dark?0xff19262a:Color.WHITE;
        surfaceVariant=dark?0xff203135:0xfff6f9f9;
        text=dark?0xffe7eff0:0xff1d3439;
        secondaryText=dark?0xffb0c2c6:0xff52676c;
        accent=dark?0xff7bd6c8:0xff147a70;
        onAccent=dark?0xff00382f:Color.WHITE;
        outline=dark?0xff4b636a:0xffccdeda;
        statusBg=dark?0xff173d37:0xffe8f2ef;
        statusText=dark?0xffb0e8dc:0xff214d44;
        error=dark?0xffffb4ab:0xff9c2e2e;
        errorBg=dark?0xff4b1714:0xffffedea;
        disabledBackground=dark?0xff2a373b:0xffe4ece9;
        disabledText=dark?0xffa9babc:0xff52676c;
        selectedRow=dark?0xff284c46:0xffd8eee8;
    }

    /** Pure settings resolution, also usable by host regression tests. */
    public static UiAppearance of(int mode,int textSize,int density,boolean systemDark){
        return new UiAppearance(mode,textSize,density,systemDark);
    }

    public static UiAppearance load(Context context){
        SharedPreferences preferences=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        boolean night=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        return of(preferences.getInt("theme",SYSTEM),preferences.getInt("text_size",STANDARD),
                preferences.getInt("density",COMFORTABLE),night);
    }

    public static void save(Context context,int mode,int textSize,int density){
        if(!valid(mode,SYSTEM,DARK)||!valid(textSize,STANDARD,EXTRA_LARGE)||!valid(density,COMFORTABLE,COMPACT))
            throw new IllegalArgumentException("Unknown appearance option");
        context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit()
                .putInt("theme",mode).putInt("text_size",textSize).putInt("density",density).apply();
    }

    /** Call before Activity.super.onCreate, without changing global app configuration. */
    public static UiAppearance applyBeforeCreate(Activity activity){
        UiAppearance appearance=load(activity);appearance.applyTheme(activity);return appearance;
    }

    /** Runtime changes do not recreate the Activity or touch active file-task state. */
    public void applyTheme(Activity activity){
        activity.setTheme(themeResId());
    }

    public int themeResId(){return dark?R.style.AppTheme_UiDark:R.style.AppTheme_UiLight;}

    public Context dialogContext(Activity activity){return new ContextThemeWrapper(activity,themeResId());}

    public float textSp(float baseSp){
        return baseSp*(textSize==LARGE?1.12f:textSize==EXTRA_LARGE?1.24f:1f);
    }

    public int spacingDp(int baseDp){
        if(baseDp<=0)return baseDp;
        return Math.max(1,Math.round(baseDp*(density==COMPACT?.8f:1f)));
    }

    public int touchDp(){return 48;}

    public void applySystemBars(Activity activity){
        Window window=activity.getWindow();
        if(Build.VERSION.SDK_INT<35){window.setStatusBarColor(page);window.setNavigationBarColor(page);}
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=window.getInsetsController();
            if(controller!=null){
                int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark?0:mask,mask);
            }
        }else{
            View decor=window.getDecorView();
            int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(dark?decor.getSystemUiVisibility()&~mask:decor.getSystemUiVisibility()|mask);
        }
    }

    public void styleText(TextView view,float baseSp){
        view.setTag(R.id.appearance_base_text_size,baseSp);view.setTextSize(textSp(baseSp));view.setTextColor(text);
        view.setHighlightColor(selectedRow);
    }

    public void styleInput(EditText input){
        styleText(input,15);input.setTextColor(enabledTextColors());input.setHintTextColor(secondaryText);input.setLinkTextColor(accent);
        input.setBackgroundTintList(controlColors());input.setMinimumHeight(px(input.getContext(),touchDp()));
    }

    public void styleChoice(CompoundButton choice){
        styleText(choice,15);choice.setTextColor(enabledTextColors());choice.setButtonTintList(controlColors());
        choice.setMinimumHeight(px(choice.getContext(),touchDp()));
    }

    public void styleSpinner(Spinner spinner){
        spinner.setBackgroundTintList(controlColors());spinner.setPopupBackgroundDrawable(new ColorDrawable(surface));
        spinner.setMinimumHeight(px(spinner.getContext(),touchDp()));
    }

    public ArrayAdapter<String> themedAdapter(Context context,String[] labels){
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int position,View recycled,ViewGroup parent){
                TextView row=(TextView)super.getView(position,recycled,parent);
                styleText(row,15);row.setBackgroundColor(Color.TRANSPARENT);return row;
            }
            @Override public View getDropDownView(int position,View recycled,ViewGroup parent){
                TextView row=(TextView)super.getDropDownView(position,recycled,parent);
                styleText(row,15);row.setBackgroundColor(surface);row.setMinimumHeight(px(context,touchDp()));return row;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return adapter;
    }

    /** Keep native list selection/click handling while styling each bound item. */
    public ArrayAdapter<CharSequence> dialogListAdapter(Context context,CharSequence[] labels){
        return new ArrayAdapter<CharSequence>(context,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View recycled,ViewGroup parent){
                TextView row=(TextView)super.getView(position,recycled,parent);
                styleText(row,16);row.setTextColor(enabledTextColors());
                row.setMinimumHeight(px(context,touchDp()));return row;
            }
        };
    }

    public void styleButton(Button button,boolean primary){
        Context context=button.getContext();
        GradientDrawable enabled=shape(context,primary?accent:surface,10);
        if(!primary)enabled.setStroke(px(context,1),outline);
        GradientDrawable focused=shape(context,primary?accent:surface,10);
        focused.setStroke(px(context,2),primary?onAccent:accent);
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},shape(context,disabledBackground,10));
        states.addState(new int[]{android.R.attr.state_focused},focused);
        states.addState(new int[]{},enabled);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x29000000),states,null));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{disabledText,primary?onAccent:accent}));
        button.setTextSize(textSp(15));button.setAllCaps(false);button.setMinHeight(px(context,touchDp()));
        button.setPadding(px(context,spacingDp(12)),px(context,spacingDp(10)),px(context,spacingDp(12)),px(context,spacingDp(10)));
        button.setStateListAnimator(null);
    }

    /** Native dialog controls created after applyTheme inherit the matching native theme. */
    public void styleDialog(AlertDialog dialog){
        Window window=dialog.getWindow();if(window!=null)styleDialogText(window.getDecorView());
        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null)styleText(message,15);
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button button=dialog.getButton(which);
            if(button!=null){styleText(button,14);button.setTextColor(accent);}
        }
        if(dialog.getListView()!=null)dialog.getListView().setBackgroundColor(surface);
    }

    private void styleDialogText(View view){
        if(view instanceof ListView||view instanceof Button||view instanceof EditText||view instanceof CompoundButton)return;
        if(view instanceof TextView){
            TextView textView=(TextView)view;Object base=textView.getTag(R.id.appearance_base_text_size);
            boolean nativeText=Boolean.TRUE.equals(view.getTag(R.id.appearance_dialog_native_text));
            if(base instanceof Number){
                if(nativeText)styleText(textView,((Number)base).floatValue());
                else textView.setTextSize(textSp(((Number)base).floatValue()));
            }else{
                float initialSp=textView.getTextSize()/view.getResources().getDisplayMetrics().scaledDensity;
                styleText(textView,initialSp);view.setTag(R.id.appearance_dialog_native_text,true);
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)styleDialogText(group.getChildAt(i));}
    }

    private ColorStateList controlColors(){
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{android.R.attr.state_focused},new int[]{}},
                new int[]{disabledText,accent,accent,secondaryText});
    }
    private ColorStateList enabledTextColors(){
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{disabledText,text});
    }
    private static boolean valid(int value,int first,int last){return value>=first&&value<=last;}
    private static int px(Context context,int dp){return Math.round(dp*context.getResources().getDisplayMetrics().density);}
    private static GradientDrawable shape(Context context,int color,int radius){
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(px(context,radius));return drawable;
    }
}
