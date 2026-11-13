package com.yugipc.whip

/** Pure string helpers for SDP. No Android dependencies, unit tested. */
internal object SdpMunger {

    /**
     * Removes every `a=candidate` / `a=end-of-candidates` line from [sdp] and
     * adds, at the end of each media section, a UDP host candidate
     * [ip]:[udpPort] (if udpPort > 0) and a passive ICE-TCP host candidate
     * [ip]:[tcpPort] (if tcpPort > 0), followed by `a=end-of-candidates`.
     * [ip] must be an IP literal (resolve hostnames before calling).
     */
    fun rewriteCandidates(sdp: String, ip: String, udpPort: Int, tcpPort: Int): String {
        val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }
        val extra = buildList {
            if (udpPort > 0) add("a=candidate:1 1 udp 2130706431 $ip $udpPort typ host")
            if (tcpPort > 0) add("a=candidate:2 1 tcp 1518280447 $ip $tcpPort typ host tcptype passive")
            if (isNotEmpty()) add("a=end-of-candidates")
        }
        val out = ArrayList<String>(lines.size + 8)
        var inMedia = false
        for (line in lines) {
            if (line.startsWith("a=candidate:") || line == "a=end-of-candidates") continue
            if (line.startsWith("m=")) {
                if (inMedia) out.addAll(extra)
                inMedia = true
            }
            out.add(line)
        }
        if (inMedia) out.addAll(extra)
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    /** Video codec order we want: H264 first, VP8 second, then helper codecs. */
    fun orderVideoCodecNames(names: List<String>): List<Int> {
        fun rank(n: String) = when (n.uppercase()) {
            "H264" -> 0
            "VP8" -> 1
            "RTX", "RED", "ULPFEC", "FLEXFEC-03" -> 2
            else -> -1 // dropped (VP9, AV1, H265...)
        }
        return names.indices.filter { rank(names[it]) >= 0 }.sortedBy { rank(names[it]) }
    }
}
