package org.medilens.ui;
import android.app.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
public final class Ui {
    private Ui(){}
    public static final int INK=0xFF090B10, MINT=0xFF7DD3FC, MUTED=0xFFB9C0CA, CARD=0xE61B1F27, BLUE=0xFF0A84FF;
    public static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    public static GradientDrawable bg(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    public static GradientDrawable outlined(int color,float radius,int stroke){GradientDrawable d=bg(color,radius);d.setStroke(dpHolder(stroke),0x33FFFFFF);return d;}
    private static int dpHolder(int v){return v;}
    public static TextView text(Context c,String value,int size,int color,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setLineSpacing(dp(c,3),1);return t;}
    public static Button button(Context c,String title,boolean primary,Runnable run){Button b=new Button(c);b.setText(title);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?Color.WHITE:Color.WHITE);GradientDrawable g=bg(primary?BLUE:CARD,dp(c,16));if(!primary)g.setStroke(dp(c,1),0x22FFFFFF);b.setBackground(g);b.setMinimumHeight(dp(c,50));b.setPadding(dp(c,14),0,dp(c,14),0);b.setOnClickListener(v->run.run());return b;}
    public static TextView iconButton(Context c,String glyph,Runnable run){TextView v=text(c,glyph,22,Color.WHITE,false);v.setGravity(Gravity.CENTER);GradientDrawable g=bg(0xAA20242D,dp(c,22));g.setStroke(dp(c,1),0x28FFFFFF);v.setBackground(g);v.setOnClickListener(x->run.run());v.setClickable(true);v.setFocusable(true);return v;}
    public static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    public static void add(LinearLayout l,View v,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(l.getContext(),margin);l.addView(v,p);}
    public static final class Sheet {
        public final Dialog dialog;public final LinearLayout body;
        public Sheet(Activity a,String title,String caption){
            dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root=column(a);root.setPadding(dp(a,22),dp(a,18),dp(a,22),dp(a,14));GradientDrawable shell=bg(0xFF15181F,dp(a,30));shell.setStroke(dp(a,1),0x33FFFFFF);root.setBackground(shell);
            View handle=new View(a);handle.setBackground(bg(0x66FFFFFF,dp(a,3)));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(a,42),dp(a,5));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(a,18);root.addView(handle,hp);
            add(root,text(a,title,26,Color.WHITE,true),7);if(!caption.isEmpty())add(root,text(a,caption,14,MUTED,false),18);
            ScrollView scroll=new ScrollView(a);scroll.setClipToPadding(false);body=column(a);body.setPadding(0,0,0,dp(a,8));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));add(root,button(a,"Done",false,dialog::dismiss),0);
            dialog.setContentView(root);Window win=dialog.getWindow();if(win!=null){win.setBackgroundDrawableResource(android.R.color.transparent);win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams lp=win.getAttributes();lp.dimAmount=.48f;win.setAttributes(lp);win.setGravity(Gravity.BOTTOM);}
        }
        public void show(){dialog.show();Window w=dialog.getWindow();if(w!=null){w.setLayout(-1,(int)(body.getResources().getDisplayMetrics().heightPixels*.86f));w.setGravity(Gravity.BOTTOM);}}
    }
}
