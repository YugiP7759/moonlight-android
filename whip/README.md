# whip: phone mic + camera to the PC over WebRTC (WHIP)

Android library module (Kotlin, AAR) for YugiPC. It sends the phone microphone
(Opus) and camera (H.264, VP8 fallback, 1280x720 @ 30 fps, front camera by
default) to a WHIP endpoint, and reconnects by itself when the link drops.

- Package: `com.yugipc.whip`
- libwebrtc: `io.getstream:stream-webrtc-android:1.3.10` (exposed as `api`)
- minSdk 21, compileSdk 36, Java 11 bytecode
- CI: `.github/workflows/whip.yml` builds `whip-release.aar` (artifact `yugipc-whip-aar`)

## Integration

`settings.gradle`: `include ':whip'`
root `build.gradle` buildscript: `classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21'`
`app/build.gradle`: `implementation project(':whip')`

The app has to request `RECORD_AUDIO` and `CAMERA` at runtime before calling
`start()`. The module only checks them. The module manifest declares the
permissions, with camera and microphone features marked `required=false`.
WHIP is plain HTTP, so cleartext must be allowed. The Moonlight
`network_security_config.xml` already allows it.

## API

```java
WhipStreamer whip = new WhipStreamer();                 // defaults
// or: new WhipStreamer(new WhipConfig.Builder().path("/yugipc/whip").build());

whip.setStateListener((state, message, fatal) -> {
    // main thread. state: IDLE | CONNECTING | CONNECTED | ERROR
    // ERROR + fatal=false: retrying by itself (next state is CONNECTING)
    // ERROR + fatal=true: gave up (missing permission, no camera...). Call start() again.
});

whip.start(context, "127.0.0.1", 8890);   // POST offer to http://127.0.0.1:8890/whip
whip.setMicEnabled(false);                // mute (sends silence, track stays)
whip.setCameraEnabled(false);             // closes the camera device
whip.switchCamera();                      // front <-> back
whip.stop();                              // DELETE session, release mic/camera
WhipState s = whip.getState();
```

All methods are thread safe and return immediately. The work runs on one
internal thread. Calling `start()` while it is running restarts the session.

### WhipConfig.Builder (all optional)

| method | default | meaning |
|---|---|---|
| `path(String)` | `/whip` | WHIP path. MediaMTX: `/<stream>/whip` |
| `bearerToken(String)` | none | `Authorization: Bearer ...` |
| `sendAudio(bool)` / `sendVideo(bool)` | true / true | which tracks to send (and which permissions are required) |
| `micInitiallyEnabled` / `cameraInitiallyEnabled` | true / true | initial mute state |
| `frontCamera(bool)` | true | start with front camera |
| `videoSize(w,h)`, `videoFps(n)` | 1280x720, 30 | capture format |
| `videoMaxBitrateBps(n)` | 2,500,000 | encoder cap |
| `rewriteRemoteCandidates(bool)` | true | replace server ICE candidates (see below) |
| `iceUdpPort(n)` | -1 | fixed UDP media port on the server. `-1` = same as the HTTP port (8890) |
| `iceTcpPort(n)` | 0 | ICE-TCP port. `0` = no TCP candidate (yugipc-bridge has no ICE-TCP) |
| `addIceServer(url)` | none | STUN/TURN, not needed through the proxy |
| `reconnectDelays(initialMs, maxMs)` | 1000, 30000 | exponential backoff |

## How it negotiates

1. Sendonly audio and video transceivers. Video codec preference: H264, then VP8,
   then rtx/red/ulpfec. VP9/AV1 are dropped.
2. Non-trickle WHIP. It waits up to 2 s for ICE gathering, then sends `POST`
   with `Content-Type: application/sdp`. It accepts `200`/`201` with an SDP answer and
   stores `Location` for the `DELETE` sent on stop and on reconnect (the bridge also
   replaces the old session on a new POST, so a lost DELETE is harmless).
3. Single address for ICE. With `rewriteRemoteCandidates=true` (default), every
   `a=candidate` in the answer is dropped and replaced by:
   - `udp <host-ip> <iceUdpPort> typ host`
   - `tcp <host-ip> <iceTcpPort> typ host tcptype passive` (if `iceTcpPort > 0`)

   `<host-ip>` is the host given to `start()` (resolved to an IP), e.g. `127.0.0.1`.
   The phone only ever sends media to that one address. The server then learns
   the phone's address from the incoming STUN checks (peer-reflexive), so it does
   not need to reach the phone's own candidates.
4. Loopback is not ignored (`networkIgnoreMask = 0`), so 127.0.0.1 works as a
   remote address. ICE-TCP is enabled.
5. Reconnect: when the PeerConnection is `FAILED`, stays `DISCONNECTED` for 5 s,
   does not reach `CONNECTED` within 15 s, or the HTTP call fails, the module
   drops the PeerConnection, DELETEs the old session, waits (1 s, 2 s, 4 s ... up to 30 s)
   and POSTs a new offer. Camera and mic stay open between attempts. The backoff
   resets after `CONNECTED`.

## What the server needs

Verified against the live receiver, `YugiP7759/yugipc-bridge` at
`100.104.166.48:8890`. On 2026-11-13 a `POST /whip` returned `201`, an SDP
answer with H264 (pt 96, `nack pli`, `ccm fir`) and VP8, a single host candidate
`100.104.166.48 8890 udp`, and `Location: /whip/<id>`. The matching `DELETE` returned `200`.
No server change is needed.

The bridge uses **one port number for everything**:

| port | proto | purpose |
|---|---|---|
| **8890** | TCP | WHIP HTTP (`POST /whip`, `DELETE /whip/<id>`) |
| **8890** | **UDP** | WebRTC media + ICE (the one fixed UDP port the proxy must expose) |

With the defaults the module replaces the server candidate with
`<host given to start()> <same port> udp`. So:
- **Phone on the Tailscale VPN:** `start(ctx, "100.104.166.48", 8890)`. The
  rewritten candidate is identical to the server's.
- **Local Tailscale proxy:** `start(ctx, "127.0.0.1", 8890)`. The proxy must
  forward `127.0.0.1:8890/tcp` to `100.104.166.48:8890/tcp` **and**
  `127.0.0.1:8890/udp` to `100.104.166.48:8890/udp`. UDP is forwarded as a 1:1 session
  per local socket, and replies go back to the same socket. The server learns the
  phone's address from the STUN checks (peer-reflexive), so nothing else is needed.

General requirements, for any other receiver:
- WHIP `POST` with `application/sdp` returns `201` + SDP answer + `Location`. No trickle.
- All media on **one fixed UDP port** (UDP mux). Accept peer-reflexive candidates.
- Opus, H.264 packetization-mode=1 and/or VP8, and PLI/FIR (the libwebrtc encoder honors them).
- Optional ICE-TCP: enable it with `iceTcpPort(n)` if the server listens for passive ICE-TCP.

### MediaMTX example (`mediamtx.yml`)

```yaml
webrtc: yes
webrtcAddress: :8890            # MediaMTX default is :8889
webrtcLocalUDPAddress: :8890    # fixed UDP port (or set iceUdpPort)
webrtcLocalTCPAddress: :8891    # optional ICE-TCP (then iceTcpPort(8891))
webrtcIPsFromInterfaces: no
webrtcAdditionalHosts: [127.0.0.1]
paths:
  yugipc:
    source: publisher
```
The MediaMTX WHIP URL is `/<path>/whip`. Use
`new WhipConfig.Builder().path("/yugipc/whip")`, or have the receiver serve `/whip`.

### pion example (Go)

```go
mux, _ := ice.NewMultiUDPMuxFromPort(8890)       // single UDP port
se := webrtc.SettingEngine{}
se.SetICEUDPMux(mux)
tcpL, _ := net.ListenTCP("tcp", &net.TCPAddr{Port: 8891})
se.SetICETCPMux(webrtc.NewICETCPMux(nil, tcpL, 8)) // optional ICE-TCP
se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4, webrtc.NetworkTypeTCP4})
api := webrtc.NewAPI(webrtc.WithSettingEngine(se), webrtc.WithMediaEngine(m))
// POST /whip: SetRemoteDescription(offer) -> CreateAnswer -> SetLocalDescription
// -> <-GatheringCompletePromise -> 201 + answer SDP + Location: /whip/<id>
```

## Known limits

- The HTTP contract was checked against the live bridge. A full media session
  from a real phone has not been tested yet.
- 127.0.0.1 as an ICE remote depends on libwebrtc gathering on the loopback
  adapter (`networkIgnoreMask = 0`). If a device refuses it, use ICE-TCP or
  point `start()` at the PC's Tailscale IP directly with `rewriteRemoteCandidates(false)`.
- The audio uses VOICE_COMMUNICATION with hardware AEC/NS. On some phones this
  routes game audio differently while the mic is open.
