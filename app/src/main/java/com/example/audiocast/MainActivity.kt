package com.example.audiocast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val reqPerm = 1
    private val reqProj = 2
    private val ui = Handler(Looper.getMainLooper())

    private val cBg = Color.parseColor("#0E1116")
    private val cCard = Color.parseColor("#1A1F27")
    private val cLine = Color.parseColor("#2A303A")
    private val cText = Color.parseColor("#E8EAED")
    private val cSub = Color.parseColor("#9AA0A6")
    private val cBlue = Color.parseColor("#3B82F6")
    private val cRed = Color.parseColor("#EF4444")
    private val cGreen = Color.parseColor("#22C55E")

    private var micSelected = false
    private var currentUrl = ""

    private lateinit var dot: View
    private lateinit var tvStatus: TextView
    private lateinit var btnMain: TextView
    private lateinit var tvClients: TextView
    private lateinit var segInternal: TextView
    private lateinit var segMic: TextView
    private lateinit var segRow: LinearLayout
    private lateinit var tvModeDesc: TextView
    private lateinit var addressCard: LinearLayout
    private lateinit var tvUrl: TextView
    private lateinit var hintCard: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- helpers UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(8), Color.argb(46, 255, 255, 255))
        }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(
        w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        weight: Float = 0f
    ) = LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(28))
        }
        scroll.addView(root)

        // Header
        root.addView(tv("AudioCast", 30f, cText, true))
        root.addView(tv("Kirim audio Android ke iPhone lewat hotspot", 14f, cSub), lp(top = 4))

        // Status pill
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard, 22, cLine)
            setPadding(dp(14), dp(10), dp(18), dp(10))
        }
        dot = View(this)
        pill.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        tvStatus = tv("", 14f, cText)
        pill.addView(tvStatus)
        root.addView(pill, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, top = 22))

        // Tombol utama bulat
        btnMain = tv("MULAI", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setOnClickListener { onMainClick() }
        }
        val holder = LinearLayout(this).apply { gravity = Gravity.CENTER }
        holder.addView(btnMain, LinearLayout.LayoutParams(dp(168), dp(168)))
        root.addView(holder, lp(top = 30))

        tvClients = tv("", 14f, cSub).apply { gravity = Gravity.CENTER }
        root.addView(tvClients, lp(top = 16))

        // Pemilih mode
        root.addView(tv("MODE", 12f, cSub, true).apply { letterSpacing = 0.12f }, lp(top = 28))
        segRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cCard, 16, cLine)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segInternal = tv("Audio internal", 14f, cSub, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { pickMode(false) }
        }
        segMic = tv("Mic + noise cancel", 14f, cSub, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { pickMode(true) }
        }
        segRow.addView(segInternal, lp(w = 0, weight = 1f))
        segRow.addView(segMic, lp(w = 0, weight = 1f))
        root.addView(segRow, lp(top = 8))

        tvModeDesc = tv("", 13f, cSub)
        root.addView(tvModeDesc, lp(top = 10))

        // Kartu alamat (muncul saat aktif)
        addressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 16, cLine)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            visibility = View.GONE
        }
        addressCard.addView(tv("Buka alamat ini di Safari iPhone", 13f, cSub))
        tvUrl = tv("", 20f, cBlue, true).apply { setTextIsSelectable(true) }
        addressCard.addView(tvUrl, lp(top = 8))
        val copy = tv("Salin alamat", 14f, cText, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.TRANSPARENT, 12, cLine)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { copyUrl() }
        }
        addressCard.addView(copy, lp(top = 14))
        root.addView(addressCard, lp(top = 22))

        // Kartu panduan (muncul saat belum aktif)
        hintCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 16, cLine)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        hintCard.addView(tv("Cara pakai", 14f, cText, true))
        hintCard.addView(
            tv(
                "1. Nyalain hotspot di Android\n" +
                    "2. Sambungin iPhone ke hotspot itu\n" +
                    "3. Tekan MULAI, lalu kasih izin\n" +
                    "4. Buka alamat yang muncul di Safari iPhone",
                13f, cSub
            ).apply { setLineSpacing(0f, 1.25f) },
            lp(top = 8)
        )
        root.addView(hintCard, lp(top = 22))

        setContentView(scroll)
        updateModeUi()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    // ---------- aksi ----------
    private fun onMainClick() {
        if (CastService.running) {
            stopService(Intent(this, CastService::class.java))
        } else {
            begin()
        }
        ui.postDelayed({ refresh() }, 600)
    }

    private fun pickMode(mic: Boolean) {
        if (CastService.running) return
        micSelected = mic
        updateModeUi()
    }

    private fun updateModeUi() {
        styleSeg(segInternal, !micSelected)
        styleSeg(segMic, micSelected)
        tvModeDesc.text = if (micSelected) {
            "Ngerekam lewat mic HP, dengan noise suppression. Cocok buat suara navigasi Google Maps. " +
                "Pakai headset di iPhone biar nggak feedback."
        } else {
            "Nangkep audio internal (musik, video, game). Kualitas paling bagus, " +
                "tapi suara navigasi Google Maps nggak ikut."
        }
    }

    private fun styleSeg(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) Color.WHITE else cSub)
        v.background = if (selected) rounded(cBlue, 12) else null
    }

    private fun copyUrl() {
        if (currentUrl.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
        Toast.makeText(this, "Alamat disalin", Toast.LENGTH_SHORT).show()
    }

    private fun begin() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) proceed() else requestPermissions(missing.toTypedArray(), reqPerm)
    }

    private fun proceed() {
        if (micSelected) {
            startForegroundService(Intent(this, CastService::class.java).putExtra("mic", true))
            ui.postDelayed({ refresh() }, 800)
        } else {
            askProjection()
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProj)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == reqPerm) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                proceed()
            } else {
                Toast.makeText(this, "Izin rekam audio wajib dikasih", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProj) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CastService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                ui.postDelayed({ refresh() }, 800)
            } else {
                Toast.makeText(this, "Izin capture ditolak", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- tampilan ----------
    private fun refresh() {
        val on = CastService.running
        if (on) {
            micSelected = CastService.micMode
            updateModeUi()
        }

        btnMain.text = if (on) "STOP" else "MULAI"
        btnMain.background = circle(if (on) cRed else cBlue)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) cGreen else cSub)
        }
        tvStatus.text = if (on) {
            "Aktif, mode ${if (CastService.micMode) "mic" else "audio internal"}"
        } else {
            "Belum aktif"
        }
        tvClients.text = if (on) "iPhone terhubung: ${CastService.clientCount}" else "Tekan tombol buat mulai"
        segRow.alpha = if (on) 0.5f else 1f

        addressCard.visibility = if (on) View.VISIBLE else View.GONE
        hintCard.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            val ips = localIps()
            if (ips.isEmpty()) {
                currentUrl = ""
                tvUrl.text = "IP belum ketemu. Pastikan hotspot udah nyala."
            } else {
                val urls = ips.map { "http://$it:${CastService.PORT}" }
                currentUrl = urls.first()
                tvUrl.text = urls.joinToString("\n")
            }
        }
    }

    private fun localIps(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("tun")) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) out += a.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
    }
}
