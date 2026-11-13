package com.yugipc.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpMungerTest {

    private val answer = listOf(
        "v=0",
        "o=- 1 1 IN IP4 0.0.0.0",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "c=IN IP4 0.0.0.0",
        "a=mid:0",
        "a=candidate:111 1 udp 2130706431 192.168.1.5 8189 typ host",
        "a=candidate:222 1 udp 2130706431 100.104.166.48 8189 typ host",
        "a=end-of-candidates",
        "a=recvonly",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "c=IN IP4 0.0.0.0",
        "a=mid:1",
        "a=candidate:333 1 udp 2130706431 10.0.0.2 8189 typ host",
        "a=recvonly",
    ).joinToString("\r\n", postfix = "\r\n")

    @Test
    fun replacesAllCandidatesWithSingleHost() {
        val out = SdpMunger.rewriteCandidates(answer, "127.0.0.1", 8189, 8189)
        assertFalse(out.contains("192.168.1.5"))
        assertFalse(out.contains("100.104.166.48"))
        assertFalse(out.contains("10.0.0.2"))
        val udp = out.lines().filter { it.startsWith("a=candidate:1 1 udp") }
        val tcp = out.lines().filter { it.contains("tcptype passive") }
        assertEquals(2, udp.size) // one per m-section
        assertEquals(2, tcp.size)
        assertTrue(udp.all { it.endsWith("127.0.0.1 8189 typ host") })
        assertTrue(out.endsWith("\r\n"))
    }

    @Test
    fun candidatesStayInsideTheirMediaSection() {
        val out = SdpMunger.rewriteCandidates(answer, "127.0.0.1", 9000, 0).lines()
        val video = out.indexOfFirst { it.startsWith("m=video") }
        val firstCand = out.indexOfFirst { it.startsWith("a=candidate") }
        assertTrue(firstCand in 0 until video)
        assertFalse(out.any { it.contains(" tcp ") })
        assertEquals(2, out.count { it == "a=end-of-candidates" })
    }

    @Test
    fun noPortsMeansCandidatesRemoved() {
        val out = SdpMunger.rewriteCandidates(answer, "127.0.0.1", 0, 0)
        assertFalse(out.contains("a=candidate"))
        assertFalse(out.contains("a=end-of-candidates"))
    }

    @Test
    fun codecOrderH264ThenVp8() {
        val names = listOf("VP8", "rtx", "VP9", "H264", "AV1", "H264", "red", "ulpfec")
        val order = SdpMunger.orderVideoCodecNames(names).map { names[it] }
        assertEquals(listOf("H264", "H264", "VP8", "rtx", "red", "ulpfec"), order)
    }
}
