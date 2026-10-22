package com.hapi.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class HapiService : AccessibilityService() {

    companion object {
        @Volatile var instance: HapiService? = null
    }

    private class N(val text: String, val id: String, val r: Rect)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private lateinit var statusTv: TextView
    private lateinit var toggleBtn: Button

    @Volatile private var running = false
    @Volatile private var clicks = 0
    private var startMs = 0L
    private var worker: Thread? = null

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 1000)
        }
    }

    // ───────────── lifecycle ─────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        ui.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        panel = null
        instance = null
        super.onDestroy()
    }

    // ───────────── overlay ─────────────
    private fun buildOverlay() {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0F0B1E"))
                cornerRadius = 16 * dp
            }
        }
        statusTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "Hapi Auto Bot"
        }
        toggleBtn = Button(this).apply {
            setTextColor(Color.WHITE)
            setOnClickListener { if (running) stopBot() else startBot() }
        }
        box.addView(statusTv)
        box.addView(toggleBtn)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        // drag the panel by its text
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        statusTv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }

        wm.addView(box, lp)
        panel = box
        refreshUi()
    }

    private fun refreshUi() {
        if (panel == null) return
        if (running) {
            val s = (System.currentTimeMillis() - startMs) / 1000
            statusTv.text = String.format("▶ %02d:%02d:%02d  |  Go: %d", s / 3600, s % 3600 / 60, s % 60, clicks)
            toggleBtn.text = "STOP"
            toggleBtn.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            statusTv.text = "■ Stopped  |  Go: $clicks"
            toggleBtn.text = "START"
            toggleBtn.setBackgroundColor(Color.parseColor("#16A34A"))
        }
    }

    // ───────────── start / stop ─────────────
    private fun startBot() {
        val pkg = Cfg.pkg(this)
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "$pkg is not installed", Toast.LENGTH_LONG).show()
            return
        }
        clicks = 0
        startMs = System.currentTimeMillis()
        running = true
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(launch) } catch (_: Exception) {
            Toast.makeText(this, "Could not open the app — open it manually", Toast.LENGTH_LONG).show()
        }
        refreshUi()
        worker = Thread { loop(pkg) }.also { it.isDaemon = true; it.start() }
    }

    private fun stopBot() {
        running = false
        worker?.interrupt()
        worker = null
        refreshUi()
    }

    // ───────────── scan loop ─────────────
    private fun loop(pkg: String) {
        while (running) {
            try {
                val root = rootInActiveWindow
                if (root == null || root.packageName?.toString() != pkg) {
                    Thread.sleep(300)   // target app not in front — do nothing
                    continue
                }
                val nodes = ArrayList<N>()
                collect(root, nodes, 0)

                val go = findGo(nodes)
                if (go != null) {
                    tap(go.first, go.second)
                    clicks++
                    Thread.sleep(100)
                    continue
                }
                val popup = findPopup(nodes, pkg)
                if (popup != null) {
                    tap(popup.r.centerX(), popup.r.centerY())
                    Thread.sleep(300)
                    continue
                }
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<N>, depth: Int) {
        if (n == null || depth > 60 || !n.isVisibleToUser) return
        val t = n.text?.toString()?.trim().orEmpty()
        val id = n.viewIdResourceName ?: ""
        if (t.isNotEmpty() || id.isNotEmpty()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(N(t, id, r))
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    /** Same rule as 3Hapi.py: a "Go" button on the same row as "Room ID ...". */
    private fun findGo(nodes: List<N>): Pair<Int, Int>? {
        val gos = nodes.filter { it.text == "Go" }
        val rooms = nodes.filter { it.text.lowercase().startsWith("room id") }
        if (gos.isEmpty() || rooms.isEmpty()) return null
        for (g in gos) {
            val gy = g.r.centerY()
            val tol = max(30, g.r.height() / 2)
            for (r in rooms) {
                if (abs(gy - r.r.centerY()) <= tol) return Pair(g.r.centerX(), g.r.centerY())
            }
        }
        return null
    }

    /** OK button, close buttons, and Cancel on "upgrade" dialogs (what the PC watcher did). */
    private fun findPopup(nodes: List<N>, pkg: String): N? {
        nodes.firstOrNull { it.text == "Ok" || it.text == "OK" || it.text == "ok" }?.let { return it }
        nodes.firstOrNull { it.id == "$pkg:id/close_btn" || it.id == "$pkg:id/close" }?.let { return it }
        if (nodes.any { it.text.lowercase().contains("upgrade") }) {
            nodes.firstOrNull { it.text == "Cancel" || it.text == "CANCLE" || it.text == "cancle" }
                ?.let { return it }
        }
        return null
    }

    private fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        val latch = CountDownLatch(1)
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (ok) latch.await(600, TimeUnit.MILLISECONDS)
    }
}
