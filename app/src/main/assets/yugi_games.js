// YugiPC games page helper (injected at document start into every frame).
// 1) Virtual gamepad polyfill (W3C "standard" mapping) fed by the app's on-screen controller.
// 2) RTCPeerConnection hook for FPS / ping stats.
// 3) Video fit/fill and hiding the site's own touch controller.
(function () {
  if (window.__yugi) return;
  var N = window.YugiPadNative;
  var Y = window.__yugi = {};

  // ---------------------------------------------------------------- gamepad
  var origGet = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : function () { return []; };
  function btn() { return { pressed: false, touched: false, value: 0 }; }
  var buttons = []; for (var i = 0; i < 17; i++) buttons.push(btn());
  var pad = {
    id: 'Xbox 360 Controller (XInput STANDARD GAMEPAD)',
    index: 0, connected: false, mapping: 'standard',
    axes: [0, 0, 0, 0], buttons: buttons, timestamp: 0,
    vibrationActuator: null, hapticActuators: []
  };
  var last = '';
  function fire(type) {
    try {
      var ev = new Event(type);
      Object.defineProperty(ev, 'gamepad', { value: pad });
      window.dispatchEvent(ev);
    } catch (e) {}
  }
  function freeIndex(real) {
    for (var i = 0; i < 4; i++) if (!real[i]) return i;
    return real.length;
  }
  function update(real) {
    if (!N) return;
    var s = N.state();
    if (!s || s === last) return;
    last = s;
    var p = s.split(';');
    var on = p[0] === '1';
    if (on !== pad.connected) {
      pad.connected = on;
      if (on) { pad.index = freeIndex(real); fire('gamepadconnected'); }
      else fire('gamepaddisconnected');
    }
    var b = p[1] || '';
    for (var i = 0; i < 17; i++) {
      var d = b.charAt(i) === '1';
      buttons[i].pressed = d; buttons[i].touched = d; buttons[i].value = d ? 1 : 0;
    }
    var a = (p[2] || '').split(',');
    for (var j = 0; j < 4; j++) pad.axes[j] = parseFloat(a[j]) || 0;
    pad.timestamp = performance.now();
  }
  navigator.getGamepads = function () {
    var real = Array.prototype.slice.call(origGet() || []);
    update(real);
    if (!pad.connected) return real;
    var out = real.slice();
    while (out.length < 4) out.push(null);
    out[pad.index] = pad;
    return out;
  };
  // Announce the pad once the page can listen (late listeners get a re-announce).
  function announce() { navigator.getGamepads(); if (pad.connected) fire('gamepadconnected'); }
  window.addEventListener('load', function () { announce(); setTimeout(announce, 3000); setTimeout(announce, 8000); });
  Y.announce = announce;

  // ---------------------------------------------------------------- stats
  var pcs = [];
  var Orig = window.RTCPeerConnection || window.webkitRTCPeerConnection;
  if (Orig) {
    var Hooked = function (cfg, opt) {
      var pc = new Orig(cfg, opt);
      pcs.push(pc);
      return pc;
    };
    Hooked.prototype = Orig.prototype;
    Object.setPrototypeOf(Hooked, Orig);
    window.RTCPeerConnection = Hooked;
    if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = Hooked;
  }
  var prev = null;
  Y.stats = function () {
    var pc = null;
    for (var i = pcs.length - 1; i >= 0; i--) {
      if (pcs[i].connectionState !== 'closed') { pc = pcs[i]; break; }
    }
    if (!pc) { Y.lastStats = ''; return; }
    pc.getStats(null).then(function (res) {
      var fps = null, rtt = null, w = 0, h = 0, decoded = null, ts = null;
      res.forEach(function (r) {
        if (r.type === 'inbound-rtp' && (r.kind === 'video' || r.mediaType === 'video')) {
          if (r.framesPerSecond != null) fps = r.framesPerSecond;
          decoded = r.framesDecoded; ts = r.timestamp;
          w = r.frameWidth || w; h = r.frameHeight || h;
        }
        if (r.type === 'candidate-pair' && (r.nominated || r.state === 'succeeded') && r.currentRoundTripTime != null) {
          rtt = r.currentRoundTripTime * 1000;
        }
      });
      if (fps == null && decoded != null && prev && ts > prev.ts) {
        fps = (decoded - prev.decoded) * 1000 / (ts - prev.ts);
      }
      if (decoded != null) prev = { decoded: decoded, ts: ts };
      Y.lastStats = JSON.stringify({ fps: fps == null ? null : Math.round(fps),
        ping: rtt == null ? null : Math.round(rtt), w: w, h: h });
    }).catch(function () {});
  };

  // ---------------------------------------------------------------- display
  var style = null;
  function css() {
    if (!style) {
      style = document.createElement('style');
      style.id = 'yugi-style';
      (document.head || document.documentElement).appendChild(style);
    }
    return style;
  }
  Y.fit = 'contain';
  Y.hideSiteController = true;
  Y.apply = function () {
    var rules = 'video{object-fit:' + Y.fit + ' !important;}';
    if (Y.hideSiteController) {
      rules += '#virtual_controller,#virtual_mouse,#virtual_wasd{display:none !important;}';
    }
    css().textContent = rules;
  };
  Y.setFit = function (mode) { Y.fit = mode === 'cover' ? 'cover' : 'contain'; Y.apply(); };
  Y.setHideSiteController = function (on) { Y.hideSiteController = !!on; Y.apply(); };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', Y.apply);
  } else {
    Y.apply();
  }
})();
