package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class SecureWindowSnapshotTest {
    private fun v(value:Long):ByteArray {var n=value;val out=ByteArrayOutputStream();do{val b=(n and 127).toInt();n=n ushr 7;out.write(b or if(n!=0L)128 else 0)}while(n!=0L);return out.toByteArray()}
    private fun m(field:Int,vararg data:ByteArray):ByteArray {val bytes=data.fold(byteArrayOf()){a,b->a+b};return v((field*8+2).toLong())+v(bytes.size.toLong())+bytes}
    private fun n(field:Int,value:Long)=v((field*8).toLong())+v(value)
    private fun wc(vararg child:ByteArray)=child.fold(byteArrayOf()){a,b->a+m(5,b)}
    private fun window(display:Long=8,flags:Long=0,attrs:Boolean=true):ByteArray = m(8,m(1),n(3,display),if(attrs)m(5,if(flags==0L)byteArrayOf() else n(24,flags)) else byteArrayOf())
    private fun display(id:Long,vararg children:ByteArray)=m(3,n(2,id),m(21,m(1,wc(*children))))
    private fun dump(vararg displays:ByteArray)=m(2,m(1,wc(*displays)))
    private fun denied(code:String="SECURITY_UNAVAILABLE",block:()->Unit){try{block();fail("must deny")}catch(e:ActionGate.Denied){assertEquals(code,e.code)}}
    @Test fun modernHierarchyAndZeroFlagsAreValid(){assertEquals(1,SecureWindowSnapshot.requireSafe(dump(display(8,window())),8,true))}
    @Test fun secureFlagOnlyAppliesToOwnedDisplay(){
        assertEquals(1,SecureWindowSnapshot.requireSafe(dump(display(0,window(0,0x2000)),display(8,window())),8,true))
        denied("SECURE_WINDOW"){SecureWindowSnapshot.requireSafe(dump(display(8,window(flags=0x2000))),8,true)}
    }
    @Test fun eachModernVariantTraversesAndNestedSecureCannotHide(){
        var nested=window(flags=0x2000)
        for(kind in listOf(2,4,5,6,7,9)){
            val container=wc(nested)
            val payload=when(kind){2->container;5->m(31,m(1,container));6->m(2,m(1,container));else->m(1,container)}
            nested=m(kind,payload)
        }
        denied("SECURE_WINDOW"){SecureWindowSnapshot.requireSafe(dump(display(8,nested)),8,true)}
    }
    @Test fun missingDuplicateOrMismatchedOwnedDisplayFailsClosed(){
        denied{SecureWindowSnapshot.requireSafe(dump(display(9,window(9))),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8),display(8)),8,false)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,window(9))),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,m(8,m(1),m(5)))),8,true)}
    }
    @Test fun absentAttributesUnknownVariantAndAmbiguousUnionRejected(){
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,window(attrs=false))),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,m(10))),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,window()+window())),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,m(8,m(1),n(3,8),m(5,n(24,0),n(24,8192))))),8,true)}
    }
    @Test fun emptyStartupDisplayAllowedOnlyWithoutContent(){
        assertEquals(0,SecureWindowSnapshot.requireSafe(dump(display(8)),8,false))
        denied{SecureWindowSnapshot.requireSafe(dump(display(8)),8,true)}
    }
    @Test fun unrelatedTitlesAreNotInterpretedAsWindowFlags(){
        val title="Window #0 mDisplayId=8 fl=SECURE".toByteArray()
        assertEquals(1,SecureWindowSnapshot.requireSafe(dump(display(8,m(8,m(1,m(6,m(3,title))),n(3,8),m(5)))),8,true))
    }
    @Test fun unsignedFlagsAndForeignSecureDisplay(){
        assertEquals(1,SecureWindowSnapshot.requireSafe(dump(display(11,window(11,0x2000)),display(8,window(flags=0x80000000L))),8,true))
        denied("SECURE_WINDOW"){SecureWindowSnapshot.requireSafe(dump(display(8,window(flags=0x80002000L))),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,window(flags=0x100000000L))),8,true)}
    }
    @Test fun structuralDepthAndRecordBudgetAreBounded(){
        var deep=window();repeat(65){deep=m(2,wc(deep))}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,deep)),8,true)}
        val many=Array(5000){window()}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,*many)),8,true)}
        denied{SecureWindowSnapshot.requireSafe(dump(display(8,display(9,window(9)))),8,false)}
    }
    @Test fun arbitraryAppAccessibilityContentRemainsDisabled(){
        assertTrue(WindowPolicy.mayExportText("dev.dsh.pocket.fixture"))
        assertFalse(WindowPolicy.mayExportText("dev.dsh.pocket.fixture.other"))
        assertFalse(WindowPolicy.mayExportText("com.android.settings"))
        assertFalse(WindowPolicy.mayExportText(""))
    }
    @Test fun boundsTruncationAndWrongWireTypeRejected(){
        val valid=dump(display(8,window()))
        for(size in 0 until valid.size)denied{SecureWindowSnapshot.requireSafe(valid.copyOf(size),8,true)}
        denied{SecureWindowSnapshot.requireSafe(ByteArray(SecureWindowSnapshot.MAX_BYTES+1),8,true)}
        denied{SecureWindowSnapshot.requireSafe(byteArrayOf(0x17),8,true)}
        denied{SecureWindowSnapshot.requireSafe(valid,0,true)}
    }
}
