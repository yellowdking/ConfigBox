package com.configbox.app.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class NodeNetworkToolsTest {

    @Test
    fun detectsIpv4Ipv6AndDomainAccurately() {
        assertTrue(NodeNetworkTools.isIpAddress("1.1.1.1"))
        assertTrue(NodeNetworkTools.isIpAddress("198.51.100.25"))
        assertTrue(NodeNetworkTools.isIpAddress("2001:db8::1"))
        assertTrue(NodeNetworkTools.isIpAddress("[2001:db8::1]"))
        assertTrue(NodeNetworkTools.isIpAddress("::1"))

        assertFalse(NodeNetworkTools.isIpAddress("hk01.example.com"))
        assertFalse(NodeNetworkTools.isIpAddress("999.1.1.1"))
        assertFalse(NodeNetworkTools.isIpAddress("foo:bar:baz"))
        assertFalse(NodeNetworkTools.isIpAddress(""))
    }

    @Test
    fun measuresTcpLatencyAgainstLocalSocketAndResolvesLoopback() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        try {
            val port = server.localPort
            val res = NodeNetworkTools.testTcpLatency("127.0.0.1", port, timeoutMs = 2000)
            assertNotNull(res.latencyMs)
            assertTrue(res.latencyMs!! >= 1L)
            assertEquals("127.0.0.1", res.resolvedIp)
            assertNull(res.error)

            val ips = NodeNetworkTools.resolveDomainIps("127.0.0.1")
            assertEquals(listOf("127.0.0.1"), ips)
        } finally {
            server.close()
        }

        val bad = NodeNetworkTools.testTcpLatency("", 0)
        assertNull(bad.latencyMs)
        assertNotNull(bad.error)
    }
}
