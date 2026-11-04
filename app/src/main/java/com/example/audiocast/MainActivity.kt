package com.example.audiocast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val reqPerm = 1
    private val reqProj = 2
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnMain: Button
    private lateinit var swMic: Switch

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "AudioCast"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Kirim audio Android ke iPhone lewat hotspot"
            textSize = 14f
        })
        tvStatus = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad, 0, 0)
        }
        root.addView(tvStatus)
        tvUrl = TextView(this).apply {
            textSize = 20f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(tvUrl)
        swMic = Switch(this).apply {
            text = "Mode mic (buat suara navigasi, ada noise suppression)"
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(swMic)
        btnMain = Button(this)
        btnMain.setOnClickListener {
            if (CastService.running) {
                stopService(Intent(this, CastService::class.java))
            } else {
                begin()
            }
            ui.postDelayed({ refresh() }, 600)
        }
        root.addView(btnMain)
        root.addView(TextView(this).apply {
            text = "Urutan: nyalain hotspot, sambungin iPhone ke hotspot, tekan Mulai, " +
                "lalu buka alamat di atas lewat Safari di iPhone."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun begin() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) proceed() else requestPermissions(missing.toTypedArray(), reqPerm)
    }

    private fun proceed() {
        if (swMic.isChecked) {
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

    private fun refresh() {
        val on = CastService.running
        btnMain.text = if (on) "Berhenti" else "Mulai"
        swMic.isEnabled = !on
        if (on) {
            tvStatus.text = "Aktif (${if (CastService.micMode) "mic" else "audio internal"}). iPhone terhubung: ${CastService.clientCount}"
            val ips = localIps()
            tvUrl.text = if (ips.isEmpty()) {
                "IP belum ketemu. Pastikan hotspot sudah nyala."
            } else {
                ips.joinToString("\n") { "http://$it:${CastService.PORT}" }
            }
        } else {
            tvStatus.text = "Belum aktif"
            tvUrl.text = "Nyalain hotspot dulu, lalu tekan Mulai."
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
