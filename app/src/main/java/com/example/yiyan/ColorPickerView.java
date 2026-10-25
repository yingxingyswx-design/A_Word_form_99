package com.example.yiyan;
import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.view.*;
public class ColorPickerView extends View{
 private Paint p=new Paint(); private float hue=270f; private int color=0xff6750a4; private OnColorChanged listener;
 public interface OnColorChanged{void onColorChanged(int c);}
 public ColorPickerView(Context c,android.util.AttributeSet a){super(c,a);p.setAntiAlias(true);}
 public void setOnColorChangedListener(OnColorChanged l){listener=l;}
 protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();p.setShader(new LinearGradient(0,0,w,0,new int[]{Color.WHITE,Color.HSVToColor(new float[]{hue,1,1})},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(new LinearGradient(0,0,0,h,0x00ffffff,0xff000000,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(new LinearGradient(0,0,w,0,new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED},null,Shader.TileMode.CLAMP));c.drawRect(0,h-18,w,h,p);p.setShader(null);}
 public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_DOWN&&e.getAction()!=MotionEvent.ACTION_MOVE)return true;float w=getWidth(),h=getHeight();if(e.getY()>h-24){hue=Math.max(0,Math.min(360,(e.getX()/w)*360));invalidate();return true;}float sat=Math.max(0,Math.min(1,e.getX()/w));float val=Math.max(0,Math.min(1,1-e.getY()/Math.max(1,h-24)));color=Color.HSVToColor(new float[]{hue,sat,val});if(listener!=null)listener.onColorChanged(color);return true;}
}
