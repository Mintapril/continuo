package dev.dsh.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RpcRejectedExceptionTest {
    @Test fun explicitRejectionIsDistinguishableFromAmbiguousTransport() {
        val rejected: IOException = RpcRejectedException("UNSUPPORTED_IMAGE", "model cannot accept images")
        val transport: IOException = IOException("connection reset")
        assertTrue(rejected is RpcRejectedException)
        assertFalse(transport is RpcRejectedException)
        assertEquals("UNSUPPORTED_IMAGE", (rejected as RpcRejectedException).code)
        assertTrue(rejected.message!!.contains("model cannot accept images"))
    }
}
