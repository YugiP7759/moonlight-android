package com.yugipc.whip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the phone microphone (Opus) and camera (H.264, VP8 fallback) to a
 * WHIP endpoint at http://host:port/path, and keeps the session alive with
 * automatic reconnects.
 *
 * Thread safe. All public methods return immediately; the work runs on an
 * internal thread and state changes are delivered on the main thread.
 */
class WhipStreamer @JvmOverloads constructor(
    private val config: WhipConfig = WhipConfig.Builder().build(),
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "yugipc-whip").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var listener: WhipStateListener? = null
    @Volatile private var state = WhipState.IDLE

    /** Bumped on every (re)connect and on stop; stale callbacks are ignored. */
    private val generation = AtomicInteger(0)
    @Volatile private var running = false

    // Everything below is touched only on the worker thread.
    private var appContext: Context? = null
    private var endpoint: String = ""
    private var host: String = ""
    private var httpPort: Int = 0
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var capturing = false
    /**
     * Wanted state, set at once by the setters (any thread) so a start() that follows them opens the
     * session with exactly these: the mic never records and the camera never opens "for a moment".
     */
    @Volatile private var micEnabled = config.micInitiallyEnabled
    @Volatile private var cameraEnabled = config.cameraInitiallyEnabled

    private var pc: PeerConnection? = null
    private var resourceUrl: String? = null
    @Volatile private var http: WhipHttp? = null
    private var reconnectDelayMs = config.reconnectInitialDelayMs
    private var pendingTask: ScheduledFuture<*>? = null
    private var disconnectTask: ScheduledFuture<*>? = null

    // ---------------------------------------------------------------- public API

    fun setStateListener(l: WhipStateListener?) {
        listener = l
    }

    fun getState(): WhipState = state

    /**
     * Start streaming to http://[host]:[port] + config.path (default "/whip").
     * Requires RECORD_AUDIO and/or CAMERA to be already granted by the app.
     * Calling start() while running restarts the session.
     */
    fun start(context: Context, host: String, port: Int) {
        val ctx = context.applicationContext
        http?.abort()
        worker.execute {
            val gen = generation.incrementAndGet()
            running = true
            teardownAll()
            startInternal(ctx, host, port, gen)
        }
    }

    /** Stop streaming, release camera/mic, DELETE the WHIP session. */
    fun stop() {
        running = false
        generation.incrementAndGet()
        http?.abort()
        worker.execute {
            running = false
            generation.incrementAndGet()
            teardownAll()
            setState(WhipState.IDLE, null, false)
        }
    }

    /**
     * Turn the microphone on/off. Off really stops recording (AudioRecord is stopped and released,
     * the system mic indicator goes away), not just a muted track.
     */
    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        worker.execute { applyMic() }
    }

    /** Turn the camera on/off (the camera device is really closed when off). */
    fun setCameraEnabled(enabled: Boolean) {
        cameraEnabled = enabled
        worker.execute { applyCamera() }
    }

    /** Toggle front/back camera. */
    fun switchCamera() {
        worker.execute {
            capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    Log.i(TAG, "camera switched, front=$isFrontCamera")
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.w(TAG, "camera switch failed: $errorDescription")
                }
            })
        }
    }

    // ------------------------------------------------------------- internals

    private fun startInternal(ctx: Context, host: String, port: Int, gen: Int) {
        val needMic = config.sendAudio
        val needCam = config.sendVideo
        val missing = buildList {
            if (needMic && !granted(ctx, Manifest.permission.RECORD_AUDIO)) add("RECORD_AUDIO")
            if (needCam && !granted(ctx, Manifest.permission.CAMERA)) add("CAMERA")
        }
        if (missing.isNotEmpty()) {
            running = false
            setState(WhipState.ERROR, "missing permission: ${missing.joinToString()}", true)
            return
        }
        if (!needMic && !needCam) {
            running = false
            setState(WhipState.ERROR, "nothing to send (sendAudio and sendVideo are false)", true)
            return
        }

        appContext = ctx
        this.host = host
        this.httpPort = port
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        endpoint = "http://$h:$port${config.path}"
        http = WhipHttp(config.bearerToken)
        reconnectDelayMs = config.reconnectInitialDelayMs

        try {
            initFactory(ctx)
            if (needMic) createAudio()
            if (needCam) createVideo(ctx)
        } catch (t: Throwable) {
            Log.e(TAG, "media init failed", t)
            teardownAll()
            running = false
            setState(WhipState.ERROR, "media init failed: ${t.message}", true)
            return
        }
        connect(gen)
    }

    private fun initFactory(ctx: Context) {
        ensureWebRtcInitialized(ctx)
        val egl = EglBase.create()
        eglBase = egl
        val audio = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        adm = audio
        val options = PeerConnectionFactory.Options().apply {
            // Default mask ignores loopback; we may need 127.0.0.1 (local proxy).
            networkIgnoreMask = 0
        }
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createAudio() {
        val f = factory ?: return
        val src = f.createAudioSource(MediaConstraints())
        audioSource = src
        audioTrack = f.createAudioTrack("yugipc-mic", src).apply { setEnabled(micEnabled) }
    }

    private fun createVideo(ctx: Context) {
        val f = factory ?: return
        val egl = eglBase ?: return
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(ctx)) Camera2Enumerator(ctx) else Camera1Enumerator(true)
        val names = enumerator.deviceNames.toList()
        if (names.isEmpty()) throw IOException("no camera found")
        val name = names.firstOrNull {
            if (config.frontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: names.first()
        val cap = enumerator.createCapturer(name, cameraEvents)
            ?: throw IOException("cannot open camera $name")
        val helper = SurfaceTextureHelper.create("yugipc-whip-cam", egl.eglBaseContext)
        val src = f.createVideoSource(false)
        cap.initialize(helper, ctx, src.capturerObserver)
        capturer = cap
        textureHelper = helper
        videoSource = src
        videoTrack = f.createVideoTrack("yugipc-cam", src)
        applyCamera()
    }

    /** Worker thread. Recording follows micEnabled on the current peer connection. */
    private fun applyMic() {
        val on = micEnabled
        audioTrack?.setEnabled(on)
        // Stops (or allows) the audio device's recording for every sending stream; while off,
        // a stream that starts sending does not start the AudioRecord either.
        pc?.let { runCatching { it.setAudioRecording(on) } }
    }

    private fun applyCamera() {
        val cap = capturer ?: return
        if (cameraEnabled && !capturing) {
            cap.startCapture(config.videoWidth, config.videoHeight, config.videoFps)
            capturing = true
        } else if (!cameraEnabled && capturing) {
            runCatching { cap.stopCapture() }
            capturing = false
        }
        videoTrack?.setEnabled(cameraEnabled)
    }

    /** One WHIP negotiation. Runs on the worker thread; blocks on signaling. */
    private fun connect(gen: Int) {
        if (!running || gen != generation.get()) return
        setState(WhipState.CONNECTING, null, false)
        try {
            val f = factory ?: throw IOException("factory gone")
            val gatheringDone = CountDownLatch(1)
            val rtc = PeerConnection.RTCConfiguration(
                config.iceServers.map { PeerConnection.IceServer.builder(it).createIceServer() }
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                keyType = PeerConnection.KeyType.ECDSA
            }
            val peer = f.createPeerConnection(rtc, PcObserver(gen, gatheringDone))
                ?: throw IOException("createPeerConnection returned null")
            pc = peer
            applyMic() // before any stream sends: the mic records only when wanted

            audioTrack?.let {
                peer.addTransceiver(
                    it,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("yugipc")
                    )
                )
            }
            videoTrack?.let { track ->
                val tr = peer.addTransceiver(
                    track,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("yugipc")
                    )
                )
                preferH264ThenVp8(f, tr)
                limitVideo(tr)
            }

            val offer = awaitCreateOffer(peer)
            awaitSetDescription(peer, offer, local = true)
            // Non-trickle WHIP: wait (briefly) for our candidates to be in the SDP.
            gatheringDone.await(2, TimeUnit.SECONDS)
            if (gen != generation.get()) return
            val offerSdp = peer.localDescription?.description ?: offer.description

            val answer = (http ?: throw IOException("stopped")).postOffer(endpoint, offerSdp)
            if (gen != generation.get()) {
                answer.resourceUrl?.let { url -> http?.delete(url) }
                return
            }
            resourceUrl = answer.resourceUrl

            var answerSdp = answer.sdp
            if (config.rewriteRemoteCandidates) {
                val ip = InetAddress.getByName(host).hostAddress ?: host
                answerSdp = SdpMunger.rewriteCandidates(
                    answerSdp, ip,
                    if (config.iceUdpPort < 0) httpPort else config.iceUdpPort,
                    config.iceTcpPort
                )
            }
            awaitSetDescription(
                peer, SessionDescription(SessionDescription.Type.ANSWER, answerSdp), local = false
            )
            // If ICE does not come up in time, try again.
            pendingTask = worker.schedule({
                if (gen == generation.get() && state != WhipState.CONNECTED) {
                    scheduleReconnect("ICE connect timeout")
                }
            }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            if (gen != generation.get()) return
            Log.w(TAG, "connect failed", t)
            scheduleReconnect(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!running) return
        val gen = generation.incrementAndGet()
        teardownPeer()
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(config.reconnectMaxDelayMs)
        setState(WhipState.ERROR, "$reason (retry in ${delay / 1000.0}s)", false)
        pendingTask = worker.schedule({ connect(gen) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun onPeerState(gen: Int, s: PeerConnection.PeerConnectionState) {
        if (gen != generation.get() || !running) return
        when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                disconnectTask?.cancel(false); disconnectTask = null
                pendingTask?.cancel(false); pendingTask = null
                reconnectDelayMs = config.reconnectInitialDelayMs
                applyMic()
                applyCamera()
                setState(WhipState.CONNECTED, null, false)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                if (disconnectTask == null) {
                    disconnectTask = worker.schedule({
                        disconnectTask = null
                        if (gen == generation.get() &&
                            pc?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED
                        ) scheduleReconnect("connection lost")
                    }, DISCONNECT_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> scheduleReconnect("connection failed")
            else -> Unit
        }
    }

    private fun preferH264ThenVp8(f: PeerConnectionFactory, tr: RtpTransceiver) {
        runCatching {
            val codecs = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val order = SdpMunger.orderVideoCodecNames(codecs.map { it.name })
            val prefs = order.map { codecs[it] }
            if (prefs.any { it.name.equals("H264", true) || it.name.equals("VP8", true) }) {
                tr.setCodecPreferences(prefs)
            }
        }.onFailure { Log.w(TAG, "setCodecPreferences failed", it) }
    }

    private fun limitVideo(tr: RtpTransceiver) {
        runCatching {
            val sender = tr.sender
            val p = sender.parameters
            p.encodings.firstOrNull()?.let {
                it.maxBitrateBps = config.videoMaxBitrateBps
                it.maxFramerate = config.videoFps
            }
            sender.setParameters(p)
        }.onFailure { Log.w(TAG, "setParameters failed", it) }
    }

    private fun teardownPeer() {
        pendingTask?.cancel(false); pendingTask = null
        disconnectTask?.cancel(false); disconnectTask = null
        resourceUrl?.let { url -> http?.delete(url) }
        resourceUrl = null
        pc?.let { runCatching { it.dispose() } }
        pc = null
    }

    private fun teardownAll() {
        teardownPeer()
        capturer?.let {
            if (capturing) runCatching { it.stopCapture() }
            runCatching { it.dispose() }
        }
        capturer = null
        capturing = false
        textureHelper?.let { runCatching { it.dispose() } }
        textureHelper = null
        videoTrack?.let { runCatching { it.dispose() } }
        videoTrack = null
        videoSource?.let { runCatching { it.dispose() } }
        videoSource = null
        audioTrack?.let { runCatching { it.dispose() } }
        audioTrack = null
        audioSource?.let { runCatching { it.dispose() } }
        audioSource = null
        factory?.let { runCatching { it.dispose() } }
        factory = null
        adm?.let { runCatching { it.release() } }
        adm = null
        eglBase?.let { runCatching { it.release() } }
        eglBase = null
        http = null
    }

    private fun setState(s: WhipState, message: String?, fatal: Boolean) {
        state = s
        if (s == WhipState.ERROR) Log.w(TAG, "state=$s $message") else Log.i(TAG, "state=$s")
        main.post { listener?.onStateChanged(s, message, fatal) }
    }

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            Log.w(TAG, "camera error: $errorDescription")
        }
        override fun onCameraDisconnected() {
            Log.w(TAG, "camera disconnected")
        }
        override fun onCameraFreezed(errorDescription: String?) {
            Log.w(TAG, "camera frozen: $errorDescription")
        }
        override fun onCameraOpening(cameraName: String?) = Unit
        override fun onFirstFrameAvailable() = Unit
        override fun onCameraClosed() = Unit
    }

    private inner class PcObserver(
        private val gen: Int,
        private val gatheringDone: CountDownLatch,
    ) : PeerConnection.Observer {
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            worker.execute { onPeerState(gen, newState) }
        }
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gatheringDone.countDown()
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ice=$s")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(c: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) = Unit
        override fun onAddStream(s: MediaStream?) = Unit
        override fun onRemoveStream(s: MediaStream?) = Unit
        override fun onDataChannel(dc: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) = Unit
    }

    companion object {
        private const val TAG = "YugiWhip"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCONNECT_GRACE_MS = 5_000L
        private const val SIGNALING_TIMEOUT_S = 5L

        private val webRtcInit = AtomicBoolean(false)

        private fun ensureWebRtcInitialized(ctx: Context) {
            if (webRtcInit.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx)
                        .createInitializationOptions()
                )
            }
        }

        private fun granted(ctx: Context, perm: String) =
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        private fun awaitCreateOffer(peer: PeerConnection): SessionDescription {
            val latch = CountDownLatch(1)
            var result: SessionDescription? = null
            var error: String? = null
            peer.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) { result = sdp; latch.countDown() }
                override fun onCreateFailure(e: String?) { error = e; latch.countDown() }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(e: String?) = Unit
            }, MediaConstraints())
            if (!latch.await(SIGNALING_TIMEOUT_S, TimeUnit.SECONDS)) throw IOException("createOffer timeout")
            return result ?: throw IOException("createOffer failed: $error")
        }

        private fun awaitSetDescription(peer: PeerConnection, sdp: SessionDescription, local: Boolean) {
            val latch = CountDownLatch(1)
            var error: String? = null
            val obs = object : SdpObserver {
                override fun onCreateSuccess(s: SessionDescription?) = Unit
                override fun onCreateFailure(e: String?) = Unit
                override fun onSetSuccess() { latch.countDown() }
                override fun onSetFailure(e: String?) { error = e ?: "unknown"; latch.countDown() }
            }
            if (local) peer.setLocalDescription(obs, sdp) else peer.setRemoteDescription(obs, sdp)
            val which = if (local) "local" else "remote"
            if (!latch.await(SIGNALING_TIMEOUT_S, TimeUnit.SECONDS)) throw IOException("set $which SDP timeout")
            error?.let { throw IOException("set $which SDP failed: $it") }
        }
    }
}
