package com.yugipc.whip

/** Connection state reported to [WhipStateListener]. */
enum class WhipState {
    /** Not started, or stopped with [WhipStreamer.stop]. */
    IDLE,

    /** Negotiating (first attempt or automatic reconnect). */
    CONNECTING,

    /** Media is flowing to the server. */
    CONNECTED,

    /**
     * Something failed. If `fatal` is false the streamer retries by itself
     * (the next state will be CONNECTING). If `fatal` is true it gave up
     * (for example a missing permission) and the app must call start() again.
     */
    ERROR,
}

/** Called on the Android main thread. */
fun interface WhipStateListener {
    fun onStateChanged(state: WhipState, message: String?, fatal: Boolean)
}
