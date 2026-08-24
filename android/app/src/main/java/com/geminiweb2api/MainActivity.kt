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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    /** While resumed, refresh status + logs once per second. */
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLogs()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var errorText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var hostSpinner: Spinner
    private lateinit var portEdit: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var testMsgEdit: EditText
    private lateinit var testBtn: Button
    private lateinit var testResultText: TextView
    private lateinit var cookieEdit: EditText
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        errorText = findViewById(R.id.errorText)
        toggleBtn = findViewById(R.id.toggleBtn)
        hostSpinner = findViewById(R.id.hostSpinner)
        portEdit = findViewById(R.id.portEdit)
        modelSpinner = findViewById(R.id.modelSpinner)
        testMsgEdit = findViewById(R.id.testMsgEdit)
        testBtn = findViewById(R.id.testBtn)
        testResultText = findViewById(R.id.testResultText)
        cookieEdit = findViewById(R.id.cookieEdit)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        modelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.model_ids)
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        toggleBtn.setOnClickListener {
            if (MainService.running) {
                startService(Intent(this, MainService::class.java).setAction(MainService.ACTION_STOP))
            } else {
                requestNotificationPermissionIfNeeded()
                startForegroundService(
                    Intent(this, MainService::class.java).setAction(MainService.ACTION_START)
                )
            }
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

        findViewById<Button>(R.id.saveSettingsBtn).setOnClickListener { saveSettings() }

        findViewById<Button>(R.id.saveCookieBtn).setOnClickListener {
            if (writeUiFile(File(filesDir, "cookie.txt"), cookieEdit.text.toString())) {
                // Cookies are re-read per request with mtime caching; no restart needed.
                Toast.makeText(this, R.string.saved_cookie_live, Toast.LENGTH_SHORT).show()
            }
        }

        testBtn.setOnClickListener { runSelfTest() }

        findViewById<Button>(R.id.clearLogBtn).setOnClickListener {
            MainService.clearLogs()
            MainService.logs = ""
            refreshLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        cookieEdit.setText(readUiFile(File(filesDir, "cookie.txt")))
        refreshStatus()
        refreshLogs()
        handler.post(uiTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiTicker)
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("SetTextOnTextI18n")
    private fun refreshStatus() {
        toggleBtn.setText(if (MainService.running) R.string.stop_server else R.string.start_server)
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

    private fun refreshLogs() {
        val logs = MainService.logs
        logText.text = if (logs.isBlank()) getString(R.string.log_empty) else logs
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ─── Settings form (config.json stays an implementation detail) ───────────

    private fun loadSettings() {
        val cfg = readConfig()
        hostSpinner.setSelection(if (cfg.optString("host", "0.0.0.0") == "127.0.0.1") 1 else 0)
        portEdit.setText(cfg.optInt("port", 8081).toString())
        val models = resources.getStringArray(R.array.model_ids)
        modelSpinner.setSelection(models.indexOf(cfg.optString("default_model", "gemini-3.6-flash")).coerceAtLeast(0))
    }

    private fun saveSettings() {
        val port = portEdit.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            return
        }
        val host = if (hostSpinner.selectedItemPosition == 1) "127.0.0.1" else "0.0.0.0"
        val model = modelSpinner.selectedItem as? String ?: "gemini-3.6-flash"

        val file = File(filesDir, "config.json")
        val cfg = readConfig()
        cfg.put("host", host)
        cfg.put("port", port)
        cfg.put("default_model", model)
        if (writeUiFile(file, cfg.toString(2))) {
            if (MainService.running) {
                // Config is only read at server start: restart to apply it.
                Toast.makeText(this, R.string.saved_restarting, Toast.LENGTH_SHORT).show()
                startService(Intent(this, MainService::class.java).setAction(MainService.ACTION_STOP))
                handler.postDelayed({
                    startForegroundService(
                        Intent(this, MainService::class.java).setAction(MainService.ACTION_START)
                    )
                }, 700)
            } else {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readConfig(): JSONObject = try {
        val file = File(filesDir, "config.json")
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    // ─── One-click self test against the on-device server ────────────────────

    private fun runSelfTest() {
        if (!MainService.running || MainService.port == 0) {
            Toast.makeText(this, R.string.test_no_service, Toast.LENGTH_SHORT).show()
            return
        }
        val message = testMsgEdit.text.toString().ifBlank { getString(R.string.test_default_msg) }
        testBtn.isEnabled = false
        testResultText.text = getString(R.string.test_running)
        val startedAt = System.currentTimeMillis()
        Thread {
            val (ok, text) = doTestRequest(message)
            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            handler.post {
                testBtn.isEnabled = true
                val body = if (ok) getString(R.string.test_reply, text)
                else getString(R.string.test_failed, text)
                testResultText.text =
                    "${getString(R.string.test_sent, message)}\n\n$body\n${getString(R.string.test_elapsed, seconds)}"
            }
        }.start()
    }

    /** POSTs one chat message to the local server; returns (ok, reply-or-error). */
    private fun doTestRequest(message: String): Pair<Boolean, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://127.0.0.1:${MainService.port}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", message))
            ).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
            if (code in 200..299) {
                val content = json?.getJSONArray("choices")?.getJSONObject(0)
                    ?.getJSONObject("message")?.optString("content").orEmpty()
                true to content.ifBlank { getString(R.string.test_empty_reply) }
            } else {
                val err = json?.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { body.take(300) }
                false to "HTTP $code: $err"
            }
        } catch (e: Exception) {
            false to (e.message ?: e.toString())
        } finally {
            conn?.disconnect()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
