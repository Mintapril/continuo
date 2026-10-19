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
    @Test fun batchIsElementInputsWithAnOptionalFinalKey(){
        valid("phone.batch","""{"observationId":"one","steps":[{"tool":"type_text","elementId":"n1","text":"张三"},{"tool":"tap","elementId":"n2"},{"tool":"key","key":"ENTER"}]}""")
        valid("phone.batch","""{"observationId":"one","steps":[{"tool":"tap","elementId":"n1"},{"tool":"long_press","elementId":"n2"},{"tool":"key","key":"BACK"}]}""")
        for(steps in listOf(
            """[{"tool":"tap","elementId":"n1"}]""",                                                          // one step is a single call
            (1..6).joinToString(",","[","]"){"""{"tool":"tap","elementId":"n$it"}"""},                           // more than five
            """[{"tool":"key","key":"ENTER"},{"tool":"tap","elementId":"n1"}]""",                              // key only last
            """[{"tool":"tap","elementId":"n1"},{"tool":"key","key":"HOME"}]""",                               // only ENTER/BACK
            """[{"tool":"tap","elementId":"n1"},{"tool":"tap","elementId":"n1"}]""",                           // each target once
            """[{"tool":"tap","x":1,"y":2},{"tool":"tap","elementId":"n1"}]""",                                // no coordinates
            """[{"tool":"tap","elementId":"n1"},{"tool":"swipe","elementId":"n2"}]""",
            """[{"tool":"type_text","elementId":"n1"},{"tool":"tap","elementId":"n2"}]""",                     // text required
            """[{"tool":"tap","elementId":"n1","text":"x"},{"tool":"tap","elementId":"n2"}]""",                // no extras
            """[{"tool":"type_text","text":"x"},{"tool":"tap","elementId":"n2"}]""",                           // elementId required
            """[{"tool":"tap","elementId":"edit"},{"tool":"tap","elementId":"n2"}]""",
            """[{"tool":"type_text","elementId":"n1","text":"${"字".repeat(4000)}"},{"tool":"type_text","elementId":"n2","text":"${"字".repeat(4000)}"},{"tool":"type_text","elementId":"n3","text":"x"}]""",
            """{"tool":"tap"}""","[1,2]"))
            denied("phone.batch","""{"observationId":"one","steps":$steps}""")
        denied("phone.batch","""{"steps":[{"tool":"tap","elementId":"n1"},{"tool":"tap","elementId":"n2"}]}""")
    }
    private fun stroke(points:String,duration:Int=200,start:Int=0,end:Int=0)=fingers("[$points]",duration,start,end)
    private fun fingers(fingers:String,duration:Int=200,start:Int=0,end:Int=0)="""{"fingers":[$fingers],"durationMs":$duration,"holdStartMs":$start,"holdEndMs":$end}"""
    private fun gesture(vararg strokes:String)="""{"observationId":"one","strokes":[${strokes.joinToString(",")}]}"""
    @Test fun gestureWireForm(){
        valid("phone.gesture",gesture(stroke("0,0,719,1999,360,800")))
        valid("phone.gesture",gesture(stroke("30,40",20),stroke("10,10,20,20",10000,3000,1500)))
        valid("phone.gesture",gesture(*Array(32){stroke("1,1,2,2",360)}))
        valid("phone.gesture",gesture(stroke((0 until 3000).joinToString(","){"${it%700},${it%1900}"},1000)))
        // Two fingers: a pinch, and one finger held still while the other turns around it.
        valid("phone.gesture",gesture(fingers("[300,800,150,650],[420,900,570,1050]",400)))
        valid("phone.gesture",gesture(fingers("[360,800],[560,800,500,941,360,1000]",600,100,100)))
        valid("phone.gesture",gesture(fingers((0 until 2).joinToString(","){"[${(0 until 1500).joinToString(","){k->"${k%700},${k%1900}"}}]"},1000)))
    }
    @Test fun gestureFingers(){
        denied("phone.gesture",gesture(fingers("",200)))
        denied("phone.gesture",gesture(fingers("[1,1],[2,2],[3,3]")))
        denied("phone.gesture",gesture(fingers("[1,1],[]")));denied("phone.gesture",gesture(fingers("[1,1],[2]")));denied("phone.gesture",gesture(fingers("[1,1],[720,0]")))
        denied("phone.gesture",gesture(fingers("[1,1],5")));denied("phone.gesture",gesture(fingers("[[1,1]]")))
        // The point limit counts every finger.
        denied("phone.gesture",gesture(fingers((0 until 2).joinToString(","){"[${(0 until 1501).joinToString(","){k->"${k%700},${k%1900}"}}]"},1000)))
        denied("phone.gesture","""{"observationId":"one","strokes":[{"points":[1,1],"durationMs":200,"holdStartMs":0,"holdEndMs":0}]}""")
    }
    @Test fun gestureLimitsAndShape(){
        denied("phone.gesture","""{"observationId":"one","strokes":[]}""")
        denied("phone.gesture",gesture(*Array(33){stroke("1,1",20)}))
        denied("phone.gesture",gesture(stroke((0 until 3001).joinToString(","){"${it%700},${it%1900}"},1000)))
        denied("phone.gesture",gesture(stroke("1,1,2")));denied("phone.gesture",gesture(stroke("")))
        denied("phone.gesture",gesture(stroke("720,0")));denied("phone.gesture",gesture(stroke("0,2000")));denied("phone.gesture",gesture(stroke("-1,0")))
        denied("phone.gesture",gesture(stroke("1.5,2")));denied("phone.gesture",gesture(stroke("\"1\",2")))
        denied("phone.gesture",gesture(stroke("1,1",19)));denied("phone.gesture",gesture(stroke("1,1",10001)))
        denied("phone.gesture",gesture(stroke("1,1",200,3001)));denied("phone.gesture",gesture(stroke("1,1",200,0,-1)))
        // 2 x (10000 + 3000 + 3000) ms is far over the 15 s a call may hold the screen.
        denied("phone.gesture",gesture(stroke("1,1",10000,3000),stroke("1,1",10000,0,3000)))
        denied("phone.gesture",gesture(stroke("1,1",7480),stroke("1,1",7481))) // 60 ms lift between them: 15021 ms
        valid("phone.gesture",gesture(stroke("1,1",7470),stroke("1,1",7470)))
        denied("phone.gesture","""{"observationId":"one","strokes":[{"points":[1,1],"durationMs":200,"holdStartMs":0}]}""")
        denied("phone.gesture","""{"observationId":"one","strokes":[{"points":[1,1],"durationMs":200,"holdStartMs":0,"holdEndMs":0,"path":"M1 1"}]}""")
        denied("phone.gesture","""{"strokes":[${stroke("1,1")}]}""")
        denied("phone.gesture","""{"observationId":"one","strokes":[${stroke("1,1")}],"viewBox":[0,0,1,1]}""")
    }
    @Test fun screenshotZoomWireForm(){
        // The plugin always sends region and scale together; ScreenZoom checks them against the screen.
        valid("phone.screenshot","""{"region":[100,200,220,320],"scale":4}""")
        valid("phone.screenshot","""{"region":[0,0,720,1600],"scale":1}""")
        denied("phone.screenshot","""{"region":[100,200,220,320]}""");denied("phone.screenshot","""{"scale":2}""")
        denied("phone.screenshot","""{"region":[100,200,220],"scale":2}""");denied("phone.screenshot","""{"region":[100,200,220,320,1],"scale":2}""")
        denied("phone.screenshot","""{"region":[100,200.5,220,320],"scale":2}""");denied("phone.screenshot","""{"region":[-1,200,220,320],"scale":2}""")
        denied("phone.screenshot","""{"region":"100,200,220,320","scale":2}""");denied("phone.screenshot","""{"region":[100,200,220,"320"],"scale":2}""")
        denied("phone.screenshot","""{"region":[100,200,220,320],"scale":0}""");denied("phone.screenshot","""{"region":[100,200,220,320],"scale":9}""")
        denied("phone.screenshot","""{"region":[100,200,220,320],"scale":2,"grid":true}""")
    }
    @Test fun interventionReasonIsBounded(){
        valid("phone.request_intervention","""{"reason":"请输入验证码"}""");valid("phone.intervention_status","{}")
        denied("phone.request_intervention","""{"reason":""}""");denied("phone.request_intervention","""{"reason":"${"x".repeat(121)}"}""");denied("phone.intervention_status","""{"x":1}""")
    }
}
