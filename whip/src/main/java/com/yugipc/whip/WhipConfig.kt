package com.yugipc.whip

/**
 * Optional settings. Every field has a default, so `WhipConfig()` (Kotlin) or
 * `new WhipConfig.Builder().build()` (Java) is the normal case.
 */
class WhipConfig private constructor(b: Builder) {
    /** HTTP path of the WHIP endpoint. MediaMTX uses "/<stream>/whip". */
    val path: String = b.path

    /** Optional "Authorization: Bearer" token. */
    val bearerToken: String? = b.bearerToken

    val sendAudio: Boolean = b.sendAudio
    val sendVideo: Boolean = b.sendVideo
    val micInitiallyEnabled: Boolean = b.micInitiallyEnabled
    val cameraInitiallyEnabled: Boolean = b.cameraInitiallyEnabled
    val frontCamera: Boolean = b.frontCamera

    val videoWidth: Int = b.videoWidth
    val videoHeight: Int = b.videoHeight
    val videoFps: Int = b.videoFps
    val videoMaxBitrateBps: Int = b.videoMaxBitrateBps

    /**
     * When true (default) every ICE candidate in the server's answer is
     * replaced by candidates that point at the WHIP host itself
     * (the same host passed to start(), e.g. 127.0.0.1 behind a local proxy):
     * one UDP candidate on [iceUdpPort] and, if [iceTcpPort] > 0, one
     * passive ICE-TCP candidate on [iceTcpPort].
     * When false the server's candidates are used unchanged.
     */
    val rewriteRemoteCandidates: Boolean = b.rewriteRemoteCandidates
    val iceUdpPort: Int = b.iceUdpPort
    val iceTcpPort: Int = b.iceTcpPort

    /** Optional STUN/TURN URLs. Empty by default (not needed through the proxy). */
    val iceServers: List<String> = b.iceServers.toList()

    /** Reconnect backoff: first delay, doubled up to the max. */
    val reconnectInitialDelayMs: Long = b.reconnectInitialDelayMs
    val reconnectMaxDelayMs: Long = b.reconnectMaxDelayMs

    fun toBuilder(): Builder = Builder(this)

    class Builder() {
        internal var path = "/whip"
        internal var bearerToken: String? = null
        internal var sendAudio = true
        internal var sendVideo = true
        internal var micInitiallyEnabled = true
        internal var cameraInitiallyEnabled = true
        internal var frontCamera = true
        internal var videoWidth = 1280
        internal var videoHeight = 720
        internal var videoFps = 30
        internal var videoMaxBitrateBps = 2_500_000
        internal var rewriteRemoteCandidates = true
        internal var iceUdpPort = 8189
        internal var iceTcpPort = 8189
        internal var iceServers = mutableListOf<String>()
        internal var reconnectInitialDelayMs = 1_000L
        internal var reconnectMaxDelayMs = 30_000L

        internal constructor(c: WhipConfig) : this() {
            path = c.path; bearerToken = c.bearerToken
            sendAudio = c.sendAudio; sendVideo = c.sendVideo
            micInitiallyEnabled = c.micInitiallyEnabled
            cameraInitiallyEnabled = c.cameraInitiallyEnabled
            frontCamera = c.frontCamera
            videoWidth = c.videoWidth; videoHeight = c.videoHeight; videoFps = c.videoFps
            videoMaxBitrateBps = c.videoMaxBitrateBps
            rewriteRemoteCandidates = c.rewriteRemoteCandidates
            iceUdpPort = c.iceUdpPort; iceTcpPort = c.iceTcpPort
            iceServers = c.iceServers.toMutableList()
            reconnectInitialDelayMs = c.reconnectInitialDelayMs
            reconnectMaxDelayMs = c.reconnectMaxDelayMs
        }

        fun path(v: String) = apply { path = if (v.startsWith("/")) v else "/$v" }
        fun bearerToken(v: String?) = apply { bearerToken = v }
        fun sendAudio(v: Boolean) = apply { sendAudio = v }
        fun sendVideo(v: Boolean) = apply { sendVideo = v }
        fun micInitiallyEnabled(v: Boolean) = apply { micInitiallyEnabled = v }
        fun cameraInitiallyEnabled(v: Boolean) = apply { cameraInitiallyEnabled = v }
        fun frontCamera(v: Boolean) = apply { frontCamera = v }
        fun videoSize(width: Int, height: Int) = apply { videoWidth = width; videoHeight = height }
        fun videoFps(v: Int) = apply { videoFps = v }
        fun videoMaxBitrateBps(v: Int) = apply { videoMaxBitrateBps = v }
        fun rewriteRemoteCandidates(v: Boolean) = apply { rewriteRemoteCandidates = v }
        fun iceUdpPort(v: Int) = apply { iceUdpPort = v }
        /** 0 disables the ICE-TCP candidate. */
        fun iceTcpPort(v: Int) = apply { iceTcpPort = v }
        fun addIceServer(url: String) = apply { iceServers.add(url) }
        fun reconnectDelays(initialMs: Long, maxMs: Long) = apply {
            reconnectInitialDelayMs = initialMs; reconnectMaxDelayMs = maxMs
        }

        fun build() = WhipConfig(this)
    }
}
