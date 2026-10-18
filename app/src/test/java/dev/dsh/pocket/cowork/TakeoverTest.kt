package dev.dsh.pocket.cowork
import dev.dsh.pocket.cowork.broker.Takeover
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class TakeoverTest {
    @Test fun startIsIdempotentAndDescribed(){
        val t=Takeover();assertFalse(t.active())
        val a=t.start("model","请输入验证码",0);assertEquals(a,t.start("user","other",5));assertTrue(t.active())
        val o=JSONObject();t.describe(o);assertTrue(o.getBoolean("userControl"));assertEquals("model",o.getString("interventionBy"));assertEquals("请输入验证码",o.getString("interventionReason"))
    }
    @Test fun finishRecordsOutcomeOnce(){
        val t=Takeover();t.start("user","",0);assertTrue(t.finish("handed_back"));assertFalse(t.finish("cancelled"))
        assertEquals("handed_back",t.status().getString("outcome"));assertFalse(t.status().getBoolean("active"))
        val o=JSONObject();t.describe(o);assertFalse(o.getBoolean("userControl"));assertEquals("",o.getString("interventionBy"))
    }
    @Test fun expiresAfterFifteenMinutes(){
        val t=Takeover();t.start("user","",1000);assertFalse(t.expired(1000+Takeover.MAX_MS));assertTrue(t.expired(1001+Takeover.MAX_MS))
        assertTrue(t.finish("expired"));assertFalse(t.expired(10_000_000))
    }
    @Test fun badOutcomeRejectedAndReasonCleaned(){
        val t=Takeover();t.start("user","",0);try{t.finish("done");fail()}catch(_:IllegalArgumentException){}
        assertEquals("a b",Takeover.cleanReason("a\nb"));assertEquals(120,Takeover.cleanReason("x".repeat(500)).length);assertEquals("",Takeover.cleanReason(null))
    }
}
