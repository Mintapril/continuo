package dev.dsh.pocket.cowork.broker;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure rules of phone_batch (no Android types, so they run on the JVM). A batch runs several element inputs planned
 *  from one observation; these rules keep the steps that must be taken alone, after looking at the screen, out of it.
 *  They are a backstop: the tool description already tells the model to do high-impact steps on their own. */
public final class BatchPolicy {
    /** Whole batch, measured from its start: a stopped batch still returns its screen well inside the plugin's 25 s
     *  transport timeout, after which the model could no longer tell which steps ran. */
    public static final long TIME_LIMIT_MS=15_000L;
    private static final String[] CHINESE={"发送","回复","发布","提交","付款","支付","结算","转账","购买","下单","删除","清空","卸载","注销","确认","确定","同意","授权","允许"};
    private static final Pattern ENGLISH=Pattern.compile("(?<![a-z])(send|reply|post|publish|submit|pay|payment|checkout|transfer|buy|purchase|order|delete|remove|uninstall|confirm|ok|agree|accept|authori[sz]e|allow|grant)(?![a-z])");
    private static final Pattern SEARCH=Pattern.compile("搜索|查找|search");
    private BatchPolicy(){}
    private static String lower(String text,String desc,String rid){return (text+"\n"+desc+"\n"+rid).toLowerCase(Locale.ROOT);}
    /** Sending, paying, deleting, confirming, granting, buying or publishing, judged by the label and view id. */
    static boolean highImpact(String text,String desc,String rid){
        String all=lower(text,desc,rid);
        for(String word:CHINESE)if(all.contains(word))return true;
        return ENGLISH.matcher(all).find();
    }
    /** A tap or long press a batch may run: a text field (tapping only focuses it; its text is user content, not a
     *  command), or a labelled element or toggle without a high-impact word ("同意用户协议" stays out). Any other
     *  unlabelled element (an icon-only send button) cannot be judged and is left to a single step. */
    public static boolean tapAllowed(String text,String desc,String rid,boolean checkable,boolean editable){
        if(editable)return true;
        if(highImpact(text,desc,rid))return false;
        return !text.isEmpty()||!desc.isEmpty()||checkable;
    }
    /** ENTER submits whatever field has focus, and in a chat box that is sending: it may end a batch only after typing
     *  into a field that reads as a search box (its observed label or view id). */
    public static boolean enterAllowed(String text,String desc,String rid){return SEARCH.matcher(lower(text,desc,rid)).find();}
}
