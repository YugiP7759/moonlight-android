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
    if (!style || !style.isConnected) {
      style = document.createElement('style');
      style.id = 'yugi-style';
      (document.head || document.documentElement).appendChild(style);
    }
    return style;
  }
  Y.cfg = { fit: 'cover', hideSiteController: true, showSiteMenu: false, force169: true };
  function isStreamPage() { return !!document.getElementById('remotevideo') || /\/static\/streaming\//.test(location.pathname); }
  Y.apply = function () {
    var c = Y.cfg, rules = '';
    if (isStreamPage()) {
      // Edge to edge: page and every player container fill the viewport; the video fills it per mode
      rules += 'html,body{width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
        'margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;}';
      rules += 'video#remotevideo,#remotevideo{position:fixed!important;left:0!important;top:0!important;right:auto!important;bottom:auto!important;' +
        'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;' +
        'margin:0!important;transform:none!important;object-fit:' + c.fit + '!important;object-position:50% 50%!important;background:#000!important;}';
      if (c.hideSiteController) {
        rules += '#virtual_controller,#virtual_mouse,#virtual_wasd{display:none!important;}';
      }
      if (!c.showSiteMenu) {
        rules += '#menu{display:none!important;}';
      }
    }
    css().textContent = rules;
    // The player may live in a shadow root or a same-origin iframe
    try {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        var w = frames[i].contentWindow;
        if (w && w.__yugi && w.__yugi.configure) w.__yugi.configure(c);
      }
    } catch (e) {}
    var v = Y.video();
    if (v && v.getRootNode && v.getRootNode() !== document && v.getRootNode().host) {
      var sr = v.getRootNode();
      var st = sr.getElementById ? sr.getElementById('yugi-style') : null;
      if (!st) { st = document.createElement('style'); st.id = 'yugi-style'; sr.appendChild(st); }
      st.textContent = 'video{width:100%!important;height:100%!important;object-fit:' + c.fit + '!important;}';
    }
  };
  Y.configure = function (c) {
    for (var k in c) Y.cfg[k] = c[k];
    Y.apply();
  };
  Y.setFit = function (mode) { Y.configure({ fit: mode }); };
  Y.video = function () {
    var v = document.getElementById('remotevideo') || document.querySelector('video');
    if (v) return v;
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      if (all[i].shadowRoot) { var sv = all[i].shadowRoot.querySelector('video'); if (sv) return sv; }
    }
    return null;
  };
  // Re-apply when the site re-renders the player
  var mo = new MutationObserver(function () { if (isStreamPage() && !document.getElementById('yugi-style')) Y.apply(); });
  function startObserver() { try { mo.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {} }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { Y.apply(); startObserver(); });
  } else {
    Y.apply(); startObserver();
  }

  // ---------------------------------------------------------------- 16:9 stream
  // Boosteroid sizes the stream from the screen (5:4 on the Fold's inner screen). Ask for 1920x1080.
  function patchResolution() {
    try {
      /* global SessionHandler */
      if (typeof SessionHandler !== 'undefined' && SessionHandler.getWindowResolution && !SessionHandler.__yugiPatched) {
        var orig = SessionHandler.getWindowResolution;
        SessionHandler.getWindowResolution = async function () {
          if (Y.cfg.force169) return { width: 1920, height: 1080 };
          return orig.apply(this, arguments);
        };
        SessionHandler.__yugiPatched = true;
        return true;
      }
    } catch (e) {}
    return false;
  }
  var tries = 0;
  var rt = setInterval(function () { if (patchResolution() || ++tries > 200) clearInterval(rt); }, 25);

  // ---------------------------------------------------------------- snapshot
  Y.snapshot = function () {
    var v = Y.video();
    if (!v || !v.videoWidth) return '';
    try {
      var s = Math.min(1, 1920 / Math.max(v.videoWidth, v.videoHeight));
      var cv = document.createElement('canvas');
      cv.width = Math.round(v.videoWidth * s); cv.height = Math.round(v.videoHeight * s);
      cv.getContext('2d').drawImage(v, 0, 0, cv.width, cv.height);
      return cv.toDataURL('image/jpeg', 0.85);
    } catch (e) { return ''; }
  };
})();
