package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.BatchPolicy
import org.junit.Assert.*
import org.junit.Test

class BatchPolicyTest {
    private fun tap(text:String="",desc:String="",rid:String="",checkable:Boolean=false,editable:Boolean=false)=BatchPolicy.tapAllowed(text,desc,rid,checkable,editable)

    @Test fun ordinaryTargetsMayBeBatched(){
        for(label in listOf("下一步","搜索","男","记住我","Next","Search","Settings","Postcode","Orders"))assertTrue(label,tap(text=label))
        assertTrue(tap(desc="返回"))
    }
    @Test fun highImpactLabelsAreLeftToASingleStep(){
        for(label in listOf("发送","回复","发布动态","提交","立即付款","支付","去结算","转账","购买","提交订单","删除","清空购物车","卸载","注销账号","确认","确定","同意并继续","授权登录","允许",
            "Send","Reply","Post","Publish","Submit","Pay now","Checkout","Transfer","Buy","Purchase","Place order","Delete","Remove","Uninstall","Confirm","OK","Agree","Accept","Authorize","Authorise","Allow","Grant"))
            assertFalse(label,tap(text=label))
        assertFalse(tap(desc="发送"))
        assertFalse("view id of an icon-only button",tap(rid="btn_send",checkable=true))
        assertFalse("a toggle that accepts terms",tap(text="我已阅读并同意用户协议",checkable=true))
    }
    @Test fun unlabelledTargetsOnlyWhenTheyAreFieldsOrToggles(){
        assertFalse(tap(rid="iv_more"))
        assertTrue(tap(rid="switch_wifi",checkable=true))
        assertTrue(tap(editable=true))
        // A field's text is user content, not a command: tapping only focuses it.
        assertTrue(tap(text="请删除这条",rid="et_post_title",editable=true))
    }
    @Test fun enterOnlyAfterTypingIntoASearchField(){
        for((text,desc,rid) in listOf(Triple("搜索","",""),Triple("","查找联系人",""),Triple("笔记本","","et_search"),Triple("Search","","")))
            assertTrue(text+desc+rid,BatchPolicy.enterAllowed(text,desc,rid))
        for((text,desc,rid) in listOf(Triple("发消息","","et_input"),Triple("","",""),Triple("用户名","","username")))
            assertFalse(text+desc+rid,BatchPolicy.enterAllowed(text,desc,rid))
    }
}
