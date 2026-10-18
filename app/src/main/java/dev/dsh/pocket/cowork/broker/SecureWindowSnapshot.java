package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Bounded, schema-directed decoder for AOSP Android 16 WMS --proto. No window-title parsing. */
public final class SecureWindowSnapshot {
    public static final int MAX_BYTES=4*1024*1024;
    private final byte[] data;
    private final int owned;
    private int visits,records,displays,windows;
    private SecureWindowSnapshot(byte[] data,int owned){this.data=data;this.owned=owned;}
    public static int requireSafe(byte[] data,int display,boolean requireContent){
        if(data==null||data.length==0||data.length>MAX_BYTES||display<=0)unknown();
        SecureWindowSnapshot decoder=new SecureWindowSnapshot(data,display);
        decoder.message(0,data.length,0,0,-1);
        if(decoder.displays!=1||(requireContent&&decoder.windows==0))unknown();
        return decoder.windows;
    }
    private static void unknown(){ActionGate.fail("SECURITY_UNAVAILABLE","Cannot verify owned-display window protection");}
    private record Field(int number,int wire,long value,int start,int end){}
    private List<Field> fields(int start,int end){
        List<Field> out=new ArrayList<>();int[] at={start};
        while(at[0]<end){
            if(++visits>100000)unknown();long tag=varint(at,end);int wire=(int)(tag&7),number=(int)(tag>>>3);
            if(number<=0||tag>>>3>0x1fffffffL)unknown();long value=0;int from=at[0],to;
            if(wire==0){value=varint(at,end);from=at[0];to=from;}
            else if(wire==2){long length=varint(at,end);if(length<0||length>end-at[0])unknown();from=at[0];to=from+(int)length;at[0]=to;}
            else if(wire==1||wire==5){int length=wire==1?8:4;if(end-at[0]<length)unknown();to=at[0]+length;at[0]=to;}
            else {unknown();return out;}
            out.add(new Field(number,wire,value,from,to));
        }
        return out;
    }
    private long varint(int[] at,int end){
        long value=0;for(int i=0;i<10;i++){if(at[0]>=end)unknown();int b=data[at[0]++]&255;
            if(i==9&&(b&0xfe)!=0)unknown();value|=(long)(b&127)<<(7*i);if((b&128)==0)return value;}
        unknown();return 0;
    }
    private Field one(List<Field> fs,int n,boolean required,int wire){
        Field found=null;for(Field f:fs)if(f.number==n){if(found!=null||f.wire!=wire)unknown();found=f;}
        if(found==null&&required)unknown();return found;
    }
    private long scalar(List<Field> fs,int n,long fallback){Field f=one(fs,n,false,0);return f==null?fallback:f.value;}
    private void child(Field f,int type,int depth,int display){message(f.start,f.end,type,depth+1,display);}
    // Types: dump=0, root=1, container=2, child union=3, display=4,
    // display area=5, task=6, activity=7, token=8, window=9, task fragment=10.
    private void message(int start,int end,int type,int depth,int display){
        if(depth>64||++records>8192)unknown();List<Field> fs=fields(start,end);
        switch(type){
            case 0:child(one(fs,2,true,2),1,depth,display);break;
            case 1:child(one(fs,1,true,2),2,depth,display);break;
            case 2:for(Field f:fs)if(f.number==5){if(f.wire!=2)unknown();child(f,3,depth,display);}break;
            case 3:{
                if(fs.size()!=1)unknown();Field f=fs.get(0);if(f.wire!=2)unknown();
                int next=switch(f.number){case 2->2;case 3->4;case 4->5;case 5->6;case 6->7;case 7->8;case 8->9;case 9->10;default->-1;};
                if(next<0)unknown();child(f,next,depth,display);break;
            }
            case 4:{if(display>=0)unknown();long id=scalar(fs,2,0);if(id<0||id>Integer.MAX_VALUE)unknown();
                if(id==owned){displays++;child(one(fs,21,true,2),5,depth,(int)id);}break;}
            case 5:case 8:case 10:child(one(fs,1,true,2),2,depth,display);break;
            case 6:child(one(fs,31,true,2),10,depth,display);break;
            case 7:child(one(fs,2,true,2),8,depth,display);break;
            case 9:{
                long id=scalar(fs,3,0);if(display<0||id!=display)unknown();
                Field attrs=one(fs,5,true,2);List<Field> values=fields(attrs.start,attrs.end);
                long flags=scalar(values,24,0);if(flags<0||flags>0xffffffffL)unknown();
                if(id==owned){windows++;if((flags&0x2000)!=0)ActionGate.fail("SECURE_WINDOW","Protected window requires user takeover");}
                child(one(fs,1,true,2),2,depth,display);break;
            }
            default:unknown();
        }
    }
}
