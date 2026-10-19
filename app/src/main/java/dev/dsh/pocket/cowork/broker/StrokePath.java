package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure geometry and timing of one finger of a phone_gesture stroke (no Android types, so it runs on the JVM).
 *
 * A finger follows a polyline of screen points: the plugin flattens the model's SVG path into it
 * (cowork-plugin/src/gesture-path.mjs). It moves at constant speed along its length. Each frame sends one MOVE whose
 * historical samples are every vertex passed since the previous frame, at the time the finger reached it, so a drawing
 * app receives the exact shape however fast the stroke is, instead of one chord per frame. Between vertices a sample is
 * added every SAMPLE_MS of movement time, so a straight stretch (a pinch, a long line) also moves smoothly when frames
 * are far apart. The fingers of a two-finger stroke (pinch, rotate) move together: each covers its own path in the same
 * time, and a sample of the MOVE places every finger at that moment ({@link #frame}). */
public final class StrokePath {
    /** Wire limits, shared by ToolContract and the plugin. The total keeps a call inside the plugin's 25 s request. */
    public static final int MAX_STROKES=32,MAX_FINGERS=2,MAX_POINTS=3000,MIN_DURATION_MS=20,MAX_DURATION_MS=10000,MAX_HOLD_MS=3000,GAP_MS=60,MAX_TOTAL_MS=15000;
    /** Wall-clock budget of one call's strokes. The checks between strokes take time MAX_TOTAL_MS does not count; a stroke
     *  that would end after this is not started, so the reply (with settle and observation) beats the 25 s request. */
    public static final long WALL_MS=19000;
    /** Movement time between two touch samples besides the vertices (125 Hz). */
    public static final int SAMPLE_MS=8;
    /** Most time between two full ownership checks (display, foreground UID) while a stroke runs. Measured on the phone a
     *  full check takes about 90 ms, so checking every frame left about 10 frames per second; takeover and cancellation
     *  are still checked every frame, and every event stays UID-targeted by the input dispatcher. */
    public static final int CHECK_MS=100;
    /** Whether a stroke makes the full ownership check now ({@code now} and {@code checked} on the uptime clock): when
     *  CHECK_MS have passed since the last one ended at {@code checked}. 0 forces it, and so does a time ahead of
     *  {@code now} (another clock), so a wrong timestamp can only add a check, never skip one. */
    public static boolean checkDue(long now,long checked){return checked<=0||checked>now||now-checked>=CHECK_MS;}
    /** The last events of a stroke take up to this long after the lift a stroke plans: the last MOVE is injected with
     *  WAIT_FOR_FINISH, and an app takes a batched MOVE at its next frame (60 Hz). */
    public static final int LIFT_SLACK_MS=16;
    /** While the fingers are down, the check that covers the lift starts at most this long before it, and not later
     *  than LIFT_LATE_MS before it. A check takes about 90 ms, so it ends before the last MOVE; Android takes a pointer
     *  that has not moved for 40 ms as stopped (VelocityTracker ASSUME_POINTER_STOPPED_TIME), so a check between the
     *  last MOVE and the UP would turn a fling into a stop. Nearer the lift a check would end after it: then only the
     *  CHECK_MS rule applies, which checks only when the guarantee needs it. */
    public static final int LIFT_LEAD_MS=150,LIFT_LATE_MS=70;
    /** {@link #checkDue(long,long)}, and also when the last check would be CHECK_MS old at the stroke's lift ({@code lift},
     *  uptime, LIFT_SLACK_MS added for the last events) and now is the time to cover it: before the DOWN
     *  ({@code down} false) when a check now covers the whole stroke, so a short stroke runs after one check; while the
     *  fingers are down when the lift is LIFT_LATE_MS to LIFT_LEAD_MS away. A lift that is now ({@code down} true) leaves
     *  only the CHECK_MS rule. */
    public static boolean checkDue(long now,long checked,long lift,boolean down){
        long left=lift-now;
        return checkDue(now,checked)||lift+LIFT_SLACK_MS-checked>=CHECK_MS&&(down?left>=LIFT_LATE_MS&&left<LIFT_LEAD_MS:left+LIFT_SLACK_MS<CHECK_MS);
    }
    /** One touch sample: {@code fraction} of the movement time at which the finger is at (x, y). */
    public record Sample(double fraction,float x,float y){}
    private final float[] xs,ys;
    /** Distance along the line from the first point to each point. */
    private final double[] at;

    /** {@code flat} is x0,y0,x1,y1,...; repeated points are dropped (they add no movement). */
    public StrokePath(int[] flat){
        if(flat.length<2||flat.length%2!=0)throw new IllegalArgumentException("points");
        int n=0;float[] x=new float[flat.length/2],y=new float[flat.length/2];
        for(int i=0;i<flat.length;i+=2){
            if(n>0&&x[n-1]==flat[i]&&y[n-1]==flat[i+1])continue;
            x[n]=flat[i];y[n]=flat[i+1];n++;
        }
        xs=Arrays.copyOf(x,n);ys=Arrays.copyOf(y,n);at=new double[n];
        for(int i=1;i<n;i++)at[i]=at[i-1]+Math.hypot(xs[i]-xs[i-1],ys[i]-ys[i-1]);
    }
    public double length(){return at[at.length-1];}
    public float firstX(){return xs[0];}
    public float firstY(){return ys[0];}
    public float lastX(){return xs[xs.length-1];}
    public float lastY(){return ys[ys.length-1];}

    /** Samples after fraction {@code from} up to {@code to} (0 <= from <= to <= 1): every vertex in between at its own
     *  fraction, then the point reached at {@code to} unless that vertex was just added. Empty for a single point. */
    public List<Sample> between(double from,double to){
        ArrayList<Sample> out=new ArrayList<>();
        double length=length();
        if(length<=0||to<=from)return out;
        double last=from;
        for(int i=1;i<xs.length;i++){
            double f=at[i]/length;
            if(f<=from)continue;
            if(f>to)break;
            out.add(new Sample(f,xs[i],ys[i]));last=f;
        }
        if(to>last)out.add(point(to));
        return out;
    }
    /** The finger position at {@code fraction} of the movement. */
    public Sample point(double fraction){
        if(xs.length==1)return new Sample(fraction,xs[0],ys[0]);
        double s=fraction*length();
        int i=Arrays.binarySearch(at,s);if(i<0)i=-i-1;i=Math.max(1,Math.min(at.length-1,i));
        double span=at[i]-at[i-1],t=span<=0?1:Math.max(0,Math.min(1,(s-at[i-1])/span));
        return new Sample(fraction,(float)(xs[i-1]+(xs[i]-xs[i-1])*t),(float)(ys[i-1]+(ys[i]-ys[i-1])*t));
    }
    /** Sample fractions of one frame for fingers that move together (0 <= from <= to <= 1): every vertex any finger
     *  passes after {@code from} and every multiple of {@code step} (SAMPLE_MS as a fraction of the movement time; the
     *  grid is shared by all frames), in order, ending with {@code to}. Empty when no finger moves. */
    public static double[] frame(StrokePath[] fingers,double from,double to,double step){
        TreeSet<Double> all=new TreeSet<>();
        for(StrokePath finger:fingers)for(Sample s:finger.between(from,to))all.add(s.fraction());
        if(all.isEmpty())return new double[0];
        for(long k=(long)Math.floor(from/step)+1;k*step<to;k++)if(k*step>from)all.add(k*step);
        double[] out=new double[all.size()];int i=0;
        for(double f:all)out[i++]=f;
        return out;
    }
}
