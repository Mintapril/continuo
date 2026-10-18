package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.ProfileWindowPolicy
import org.junit.Assert.*
import org.junit.Test

class ProfileWindowPolicyTest {
    private fun w(token:Any?=Any(),display:Int=7,uid:Int=1012345,flags:Int=0)=ProfileWindowPolicy.Window(token,display,uid,flags)
    private fun s(vararg windows:ProfileWindowPolicy.Window)=ProfileWindowPolicy.Snapshot(1,123,windows.toList(),setOf(0,7))
    private fun denied(action:()->Unit){try{action();fail("must deny")}catch(e:ActionGate.Denied){assertEquals("APP_DENIED",e.code)}}
    @Test fun profileOwnedHostWindowsAdmitted(){val a=w();val b=w(uid=1012346);assertEquals(listOf(a,b),ProfileWindowPolicy.requireProfileDisplay(s(a,b),7,10))}
    @Test fun foreignMainHandlesAreNotTreatedAsTarget(){val a=w();assertEquals(listOf(a),ProfileWindowPolicy.requireProfileDisplay(s(w(display=0,uid=12345),a),7,10))}
    @Test fun personalAndSystemAndOtherProfileUidDenied(){for(uid in listOf(0,1000,12345,1112345,-1))denied{ProfileWindowPolicy.requireProfileDisplay(s(w(),w(uid=uid)),7,10)}}
    @Test fun invisibleOrOverlayFlagsGrantNoExemption(){for(flags in listOf(1,2,256,Int.MAX_VALUE))denied{ProfileWindowPolicy.requireProfileDisplay(s(w(uid=1000,flags=flags)),7,10)}}
    @Test fun noTokenAndDuplicateTokenFailClosed(){val token=Any();denied{ProfileWindowPolicy.requireProfileDisplay(s(w(token=null)),7,10)};denied{ProfileWindowPolicy.requireProfileDisplay(s(w(token),w(token)),7,10)}}
    @Test fun missingDisplayEmptyAndDefaultDisplayRejected(){denied{ProfileWindowPolicy.requireProfileDisplay(s(),7,10)};denied{ProfileWindowPolicy.requireProfileDisplay(s(w()),8,10)};denied{ProfileWindowPolicy.requireProfileDisplay(s(w(display=0)),0,10)}}
    @Test fun exactHostTokenJoinCannotUseAnotherToken(){val a=w();assertSame(a,ProfileWindowPolicy.joinHostWindow(listOf(a),a.windowToken()));denied{ProfileWindowPolicy.joinHostWindow(listOf(a),Any())};denied{ProfileWindowPolicy.joinHostWindow(listOf(a),null)}}
    @Test fun ambiguousJoinRejected(){val token=Any();denied{ProfileWindowPolicy.joinHostWindow(listOf(w(token),w(token)),token)}}
    @Test fun inputRoutingSeparatesNonInputSurfaceFromCapturePermission(){val app=w();val surface=w(token=null,uid=1000,flags=5);assertEquals(listOf(app),ProfileWindowPolicy.requireInputTargets(s(app,surface),7,10));denied{ProfileWindowPolicy.requireProfileDisplay(s(app,surface),7,10)}}
    @Test fun noInputFlagWithoutNullWindowDoesNotExemptForeignUid(){denied{ProfileWindowPolicy.requireInputTargets(s(w(),w(uid=1000,flags=5)),7,10)}}
    @Test fun nullWindowWithoutNoInputChannelCannotBeIgnored(){for(flags in listOf(0,2,4,8,256))denied{ProfileWindowPolicy.requireInputTargets(s(w(),w(token=null,uid=1000,flags=flags)),7,10)}}
    @Test fun nonInputSurfacesAloneNeverAdmitInput(){denied{ProfileWindowPolicy.requireInputTargets(s(w(token=null,uid=1000,flags=5)),7,10)}}
    @Test fun snapshotDefensivelyCopies(){val windows=mutableListOf(w());val displays=mutableSetOf(7);val snap=ProfileWindowPolicy.Snapshot(1,123,windows,displays);windows.clear();displays.clear();assertEquals(1,ProfileWindowPolicy.requireProfileDisplay(snap,7,10).size)}
}
