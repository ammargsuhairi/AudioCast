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
            orientation =
