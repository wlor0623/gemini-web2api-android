package com.geminiweb2api

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var errorText: TextView
    private lateinit var configEdit: EditText
    private lateinit var cookieEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        errorText = findViewById(R.id.errorText)
        configEdit = findViewById(R.id.configEdit)
        cookieEdit = findViewById(R.id.cookieEdit)

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            requestNotificationPermissionIfNeeded()
            startForegroundService(Intent(this, MainService::class.java).setAction(MainService.ACTION_START))
            pollStatus(8)
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            startService(Intent(this, MainService::class.java).setAction(MainService.ACTION_STOP))
            handler.postDelayed({ refreshStatus() }, 400)
        }

        findViewById<Button>(R.id.batteryBtn).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.battery_opt_failed, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.saveConfigBtn).setOnClickListener {
            val ok = writeUiFile(File(filesDir, "config.json"), configEdit.text.toString())
            if (ok && MainService.running) {
                // Config is only read at server start: restart to apply it.
                Toast.makeText(this, R.string.saved_restarting, Toast.LENGTH_SHORT).show()
                startService(Intent(this, MainService::class.java).setAction(MainService.ACTION_STOP))
                handler.postDelayed({
                    startForegroundService(
                        Intent(this, MainService::class.java).setAction(MainService.ACTION_START)
                    )
                    pollStatus(8)
                }, 700)
            } else if (ok) {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.saveCookieBtn).setOnClickListener {
            if (writeUiFile(File(filesDir, "cookie.txt"), cookieEdit.text.toString())) {
                // Cookies are re-read per request with mtime caching; no restart needed.
                Toast.makeText(this, R.string.saved_cookie_live, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadEditors()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        if (MainService.running) {
            statusText.text = getString(R.string.status_running)
            addressText.text = MainService.baseUrl
            addressText.visibility = View.VISIBLE
        } else {
            statusText.text =
                if (MainService.lastError != null) getString(R.string.status_error)
                else getString(R.string.status_stopped)
            addressText.text = ""
            addressText.visibility = View.GONE
        }
        val err = MainService.lastError
        if (err != null && !MainService.running) {
            errorText.text = err
            errorText.visibility = View.VISIBLE
        } else {
            errorText.visibility = View.GONE
        }
    }

    /** The Python VM needs a few seconds on first start; poll until it is up. */
    private fun pollStatus(remaining: Int) {
        refreshStatus()
        if (remaining > 0 && !MainService.running) {
            handler.postDelayed({ pollStatus(remaining - 1) }, 600)
        }
    }

    private fun loadEditors() {
        configEdit.setText(readUiFile(File(filesDir, "config.json")))
        cookieEdit.setText(readUiFile(File(filesDir, "cookie.txt")))
    }

    private fun readUiFile(file: File): String =
        try {
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }

    private fun writeUiFile(file: File, content: String): Boolean = try {
        file.writeText(content)
        true
    } catch (e: Exception) {
        Toast.makeText(this, getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
        false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
