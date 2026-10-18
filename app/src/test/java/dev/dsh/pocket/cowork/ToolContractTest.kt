package dev.dsh.pocket.cowork
import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.ToolContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ToolContractTest {
    private fun valid(tool:String,json:String) = ToolContract.validate(tool,JSONObject(json))
    private fun denied(tool:String,json:String){try{valid(tool,json);fail("Expected rejection")}catch(_:ActionGate.Denied){}}
    @Test fun eachReadAndLifecycleTool(){for(t in listOf("status","apps","screenshot","ui_tree","pause","stop","request_intervention"))valid("phone.$t","{}");valid("phone.start","""{"packageName":"dev.dsh.pocket.fixture"}""")}
    @Test fun tapAndLongPress(){valid("phone.tap","""{"observationId":"one","x":0,"y":1279}""");valid("phone.long_press","""{"observationId":"one","x":719,"y":1,"durationMs":2000}""")}
    @Test fun swipeAndScroll(){valid("phone.swipe","""{"observationId":"one","x1":0,"y1":0,"x2":719,"y2":1279,"durationMs":100}""");valid("phone.scroll","""{"observationId":"one","x":200,"y":300,"deltaY":-500}""")}
    @Test fun textAndKey(){valid("phone.type_text","""{"observationId":"one","text":"中文测试"}""");valid("phone.key","""{"observationId":"one","key":"BACK"}""");valid("phone.key","""{"observationId":"one","key":"ENTER"}""")}
    @Test fun launchAndWait(){valid("phone.launch_app","""{"observationId":"one","packageName":"dev.dsh.pocket.fixture"}""");valid("phone.wait","""{"timeoutMs":2000}""")}
    @Test fun noCoercionOrExtras(){denied("phone.tap","""{"observationId":"one","x":"2","y":3}""");denied("phone.status","""{"shell":"id"}""");denied("phone.tap","""{"observationId":"one","x":2.5,"y":3}""")}
    @Test fun virtualKeysAndNoUris(){
        // HOME and RECENTS are the display's own virtual navigation keys and need no observation.
        valid("phone.key","""{"key":"HOME"}""");valid("phone.key","""{"key":"RECENTS"}""");valid("phone.key","""{"observationId":"one","key":"HOME"}""")
        denied("phone.key","""{"key":"MENU"}""");denied("phone.key","""{"observationId":"one"}""");denied("phone.launch_app","""{"observationId":"one","packageName":"intent://shell"}""")
    }
    @Test fun boundsAndObservationRequired(){denied("phone.tap","""{"observationId":"one","x":720,"y":0}""");denied("phone.tap","""{"x":20,"y":30}""");denied("phone.wait","""{"timeoutMs":10001}""")}
    @Test fun elementTargetsAreExclusiveWithCoordinates(){
        valid("phone.tap","""{"observationId":"one","elementId":"n12"}""");valid("phone.long_press","""{"observationId":"one","elementId":"n0","durationMs":800}""")
        denied("phone.tap","""{"observationId":"one","elementId":"n12","x":1,"y":2}""");denied("phone.tap","""{"observationId":"one","elementId":"12"}""")
        denied("phone.tap","""{"observationId":"one","elementId":"n12345"}""");denied("phone.tap","""{"observationId":"one"}""");denied("phone.tap","""{"observationId":"one","x":1}""")
    }
    @Test fun interventionReasonIsBounded(){
        valid("phone.request_intervention","""{"reason":"请输入验证码"}""");valid("phone.intervention_status","{}")
        denied("phone.request_intervention","""{"reason":""}""");denied("phone.request_intervention","""{"reason":"${"x".repeat(121)}"}""");denied("phone.intervention_status","""{"x":1}""")
    }
}
