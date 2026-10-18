package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrokerSafetyTest {
    private val agent="a".repeat(48)
    private val controller="c".repeat(48)
    private val nonce="1".repeat(64)
    private fun denied(action:()->Unit){try{action();fail("must reject")}catch(_:SecurityException){}}
    private fun header(auth:BrokerAuth,role:String="agent",method:String="POST",path:String="/v1/tool",body:ByteArray=byteArrayOf()):String{
        val digest=BrokerAuth.digest(method,path,body)
        val value=auth.challenge(JSONObject().put("role",role).put("clientNonce",nonce).put("digest",digest))
        val fields="$role\n$nonce\n${value.getString("serverNonce")}\n$digest"
        val secret=if(role=="agent")agent else controller
        assertEquals(BrokerAuth.mac(secret,"server\n$fields"),value.getString("proof"))
        return "Cowork-HMAC $role:$nonce:${value.getString("serverNonce")}:${BrokerAuth.mac(secret,"client\n$fields")}"
    }
    @Test fun requestBoundAuthIsSingleUseAndResponseIsSigned(){
        val auth=BrokerAuth(agent,controller){10L};val body="中文".toByteArray()
        val header=header(auth,body=body)
        val accepted=auth.authenticate(header,"POST","/v1/tool",body)
        assertFalse(accepted.controller());assertFalse(accepted.toString().contains(agent))
        assertNotEquals(accepted.responseProof(200,body),accepted.responseProof(409,body))
        denied{auth.authenticate(header,"POST","/v1/tool",body)}
    }
    @Test fun tamperingBurnsChallenge(){
        val auth=BrokerAuth(agent,controller){10L};val header=header(auth)
        denied{auth.authenticate(header,"POST","/v1/control",byteArrayOf())}
        denied{auth.authenticate(header,"POST","/v1/tool",byteArrayOf())}
    }
    @Test fun roleCannotBeSubstituted(){
        val auth=BrokerAuth(agent,controller){10L};val header=header(auth)
        denied{auth.authenticate(header.replace(" agent:"," controller:"),"POST","/v1/tool",byteArrayOf())}
    }
    @Test fun deadlineBoundaryAndClockRegression(){
        var now=100L;val auth=BrokerAuth(agent,controller){now}
        val first=header(auth);now=30100;assertNotNull(auth.authenticate(first,"POST","/v1/tool",byteArrayOf()))
        val expired=header(auth);now+=30001;denied{auth.authenticate(expired,"POST","/v1/tool",byteArrayOf())}
        val backward=header(auth);now--;denied{auth.authenticate(backward,"POST","/v1/tool",byteArrayOf())}
    }
    @Test fun challengeRetentionBoundAndStrictShape(){
        val auth=BrokerAuth(agent,controller){10L};val first=header(auth)
        repeat(128){header(auth)};denied{auth.authenticate(first,"POST","/v1/tool",byteArrayOf())}
        denied{auth.challenge(JSONObject().put("role","agent").put("clientNonce",nonce).put("digest",nonce).put("extra",true))}
        denied{auth.challenge(JSONObject().put("role","other").put("clientNonce",nonce).put("digest",nonce))}
    }
    @Test fun cancellationCannotCrossExecutions(){
        val state=ExecutionCancellation();state.begin("old");state.request("old");assertTrue(state.cancelled())
        state.begin("new");state.request("old");assertFalse(state.cancelled());state.request("new");assertTrue(state.cancelled())
        state.clear("old");assertTrue(state.cancelled());state.clear("new");assertFalse(state.cancelled())
        state.end();state.request("new");assertFalse(state.cancelled())
    }
    @Test fun displayCapabilityIsNotWindowContentAndIsPinned(){
        fun identity(flags:Int=18562,unique:String="virtual:owned",id:Int=60)=OwnedDisplayIdentity.require(true,id,id,5,flags,unique,0,"android")
        assertTrue(identity().endsWith("|18562")) // ColorOS reports FLAG_SECURE without it being requested.
        assertNotEquals(identity(),identity(flags=18560))
        assertNotEquals(identity(),identity(unique="virtual:replacement"))
        assertNotEquals(identity(),identity(id=61))
        for(args in listOf(Triple(false,60,5),Triple(true,0,5),Triple(true,60,1))){
            try{OwnedDisplayIdentity.require(args.first,args.second,args.second,args.third,0,"owned",0,"android");fail("must reject")}catch(_:IllegalStateException){}
        }
        try{OwnedDisplayIdentity.require(true,60,61,5,18562,"owned",0,"android");fail("must reject")}catch(_:IllegalStateException){}
        try{OwnedDisplayIdentity.require(true,60,60,5,18562,"",0,"android");fail("must reject")}catch(_:IllegalStateException){}
    }
    @Test fun unknownOrUnapprovedWindowFailsClosed(){
        val gate=ActionGate(listOf("dev.dsh.pocket.fixture"))
        WindowPolicy.requireAllowed(gate,listOf("dev.dsh.pocket.fixture"),true)
        for(packages in listOf(emptyList(),listOf(""),listOf("dev.dsh.pocket.fixture","com.android.settings"))){
            try{WindowPolicy.requireAllowed(gate,packages,true);fail("must reject")}catch(_:ActionGate.Denied){}
        }
        WindowPolicy.requireAllowed(gate,emptyList(),false)
    }
}
