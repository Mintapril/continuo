package dev.dsh.pocket.cowork.broker;

import java.util.Arrays;

/** Pure layout and pixels of a zoomed phone_screenshot (no Android types, so it runs on the JVM).
 *
 * The region [left, top, right, bottom) of the screen (right/bottom exclusive, like UI bounds) is enlarged scale times
 * with nearest-neighbour pixels, so edges stay sharp, and framed by a white band of MARGIN px on every side. The band
 * carries rulers on all four sides: a tick at every multiple of minor() screen px and a longer tick labelled with its
 * screen coordinate at every multiple of major(). A tick marks where its screen pixel starts, so the model reads a
 * position from the labels and uses it directly for taps and gestures; exactly, screen x = left + (imageX - MARGIN) /
 * scale. The labels use a built-in 5x7 digit font: the broker needs no system typeface. */
public final class ScreenZoom {
    /** Shared with the plugin (ZOOM in cowork-plugin/src/broker.mjs). An image side, rulers included, is at most MAX_IMAGE. */
    public static final int MAX_SCALE=8,MIN_SIDE=8,MAX_IMAGE=1536,MARGIN=60;
    /** Tick spacings in screen px. */
    private static final int[] STEPS={1,2,5,10,20,25,50,100,200,500};
    private static final int INK=0xFF000000,PAPER=0xFFFFFFFF,MAJOR_TICK=8,MINOR_TICK=4,GAP=3,DOT=2,ADVANCE=6*DOT,TEXT_HEIGHT=7*DOT;
    /** Digits 0-9 in 5x7 dots, one row per value, bit 4 = left column. */
    private static final int[][] DIGITS={
        {14,17,19,21,25,17,14},{4,12,4,4,4,4,14},{14,17,1,2,4,8,31},{31,2,4,2,1,17,14},{2,6,10,18,31,2,2},
        {31,16,30,1,1,17,14},{6,8,16,30,17,17,14},{31,1,2,4,8,8,8},{14,17,17,14,17,17,14},{14,17,17,15,1,2,12}};
    public final int left,top,right,bottom,scale;

    /** Refuses (ARGUMENTS_INVALID) a region outside the width x height screen or under MIN_SIDE a side, a scale outside
     *  1..MAX_SCALE, and an image over MAX_IMAGE a side. */
    public ScreenZoom(int left,int top,int right,int bottom,int scale,int width,int height){
        if(left<0||top<0||right>width||bottom>height||right-left<MIN_SIDE||bottom-top<MIN_SIDE||scale<1||scale>MAX_SCALE)
            ActionGate.fail("ARGUMENTS_INVALID","Zoom region or scale invalid");
        this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.scale=scale;
        if(imageWidth()>MAX_IMAGE||imageHeight()>MAX_IMAGE)ActionGate.fail("ARGUMENTS_INVALID","Zoomed image too large");
    }
    public int imageWidth(){return (right-left)*scale+2*MARGIN;}
    public int imageHeight(){return (bottom-top)*scale+2*MARGIN;}
    /** Screen px between labelled ticks: the smallest step at least 48 image px apart (room for a 4-digit label). */
    public int major(){for(int s:STEPS)if(s*scale>=48)return s;return STEPS[STEPS.length-1];}
    /** Screen px between ticks: the smallest step that divides major() and is at least 8 image px apart. */
    public int minor(){int m=major();for(int s:STEPS)if(m%s==0&&s*scale>=8)return s;return m;}

    /** The zoomed image: {@code region} holds the region's screen pixels row by row (ARGB, (right-left) x (bottom-top));
     *  the result is imageWidth() x imageHeight() ARGB pixels. Ticks, labels and the frame stay in the band: every
     *  content pixel is a screen pixel. */
    public int[] render(int[] region){
        int w=right-left,h=bottom-top,iw=imageWidth();
        if(region.length!=w*h)throw new IllegalArgumentException("Region pixels do not match the region");
        int[] out=new int[iw*imageHeight()];Arrays.fill(out,PAPER);
        for(int y=0;y<h*scale;y++){int src=y/scale*w,dst=(MARGIN+y)*iw+MARGIN;for(int x=0;x<w*scale;x++)out[dst+x]=region[src+x/scale];}
        // A frame just outside the content shows where it ends, even when the content is white.
        int fl=MARGIN-1,ft=MARGIN-1,fr=MARGIN+w*scale,fb=MARGIN+h*scale;
        fill(out,iw,fl,ft,fr+1,ft+1);fill(out,iw,fl,fb,fr+1,fb+1);fill(out,iw,fl,ft,fl+1,fb+1);fill(out,iw,fr,ft,fr+1,fb+1);
        int major=major(),minor=minor();
        for(int x=first(left,minor);x<=right;x+=minor){
            int ix=MARGIN+(x-left)*scale,len=x%major==0?MAJOR_TICK:MINOR_TICK;
            fill(out,iw,ix,ft-len,ix+1,ft);fill(out,iw,ix,fb+1,ix+1,fb+1+len);
            if(x%major!=0)continue;
            String label=Integer.toString(x);int lx=ix-width(label)/2;
            text(out,iw,label,lx,ft-MAJOR_TICK-GAP-TEXT_HEIGHT);text(out,iw,label,lx,fb+1+MAJOR_TICK+GAP);
        }
        for(int y=first(top,minor);y<=bottom;y+=minor){
            int iy=MARGIN+(y-top)*scale,len=y%major==0?MAJOR_TICK:MINOR_TICK;
            fill(out,iw,fl-len,iy,fl,iy+1);fill(out,iw,fr+1,iy,fr+1+len,iy+1);
            if(y%major!=0)continue;
            String label=Integer.toString(y);int ly=iy-TEXT_HEIGHT/2;
            text(out,iw,label,fl-MAJOR_TICK-GAP-width(label),ly);text(out,iw,label,fr+1+MAJOR_TICK+GAP,ly);
        }
        return out;
    }
    /** Smallest multiple of step at or after from (from >= 0). */
    private static int first(int from,int step){return (from+step-1)/step*step;}
    private static int width(String digits){return digits.length()*ADVANCE-DOT;}
    private static void text(int[] out,int iw,String digits,int x,int y){
        for(int c=0;c<digits.length();c++){
            int[] glyph=DIGITS[digits.charAt(c)-'0'];
            for(int r=0;r<7;r++)for(int k=0;k<5;k++)if((glyph[r]>>(4-k)&1)!=0)
                fill(out,iw,x+c*ADVANCE+k*DOT,y+r*DOT,x+c*ADVANCE+(k+1)*DOT,y+(r+1)*DOT);
        }
    }
    /** Inks [x0, x1) x [y0, y1), clipped to the image. */
    private static void fill(int[] out,int iw,int x0,int y0,int x1,int y1){
        int ih=out.length/iw;
        for(int y=Math.max(0,y0);y<Math.min(ih,y1);y++)for(int x=Math.max(0,x0);x<Math.min(iw,x1);x++)out[y*iw+x]=INK;
    }
}
