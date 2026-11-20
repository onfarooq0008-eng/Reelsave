package com.reelsave.app

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_INSTANCE = "https://rude-lissy-usman62627-31f64484.apps.mistral.run/"
    }

    private lateinit var etUrl: EditText
    private lateinit var cbAudio: CheckBox
    private lateinit var btnDownload: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var pickerList: LinearLayout

    private val prefs by lazy { getSharedPreferences("reelsave", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        cbAudio = findViewById(R.id.cbAudio)
        btnDownload = findViewById(R.id.btnDownload)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        pickerList = findViewById(R.id.pickerList)

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!t.isNullOrBlank()) etUrl.setText(t.trim())
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            etUrl.setText(""); tvStatus.text = ""; pickerList.removeAllViews()
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        btnDownload.setOnClickListener { startFetch() }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) {
                val m = Regex("https?://\\S+").find(t)
                etUrl.setText(m?.value ?: t.trim())
            }
        }
    }

    private fun showSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val etInstance = EditText(this).apply {
            hint = "Instance URL"
            setText(prefs.getString("instance", DEFAULT_INSTANCE))
        }
        val etKey = EditText(this).apply {
            hint = "API key (optional)"
            setText(prefs.getString("apikey", ""))
        }
        box.addView(etInstance); box.addView(etKey)
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("instance", etInstance.text.toString().trim().ifBlank { DEFAULT_INSTANCE })
                    .putString("apikey", etKey.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startFetch() {
        val link = etUrl.text.toString().trim()
        if (!link.startsWith("http")) {
            tvStatus.text = "Please enter a valid link."
            return
        }
        if (!ensureStorage()) return

        pickerList.removeAllViews()
        tvStatus.text = "Fetching…"
        progress.visibility = View.VISIBLE
        btnDownload.isEnabled = false

        val base = prefs.getString("instance", DEFAULT_INSTANCE) ?: DEFAULT_INSTANCE
        val key = prefs.getString("apikey", "") ?: ""
        val audio = cbAudio.isChecked

        thread {
            val result = CobaltApi.request(base, key, link, audio)
            runOnUiThread {
                progress.visibility = View.GONE
                btnDownload.isEnabled = true
                when (result) {
                    is ApiResult.Failure -> tvStatus.text = result.message
                    is ApiResult.Single -> {
                        val name = result.filename ?: ("reelsave_" + System.currentTimeMillis() + if (audio) ".mp3" else ".mp4")
                        enqueue(result.url, name)
                    }
                    is ApiResult.Picker -> {
                        tvStatus.text = "Multiple items found. Tap to download:"
                        result.items.forEachIndexed { idx, item ->
                            val ext = when (item.type) { "photo" -> "jpg"; "gif" -> "gif"; else -> "mp4" }
                            val b = Button(this).apply {
                                text = "${item.type.replaceFirstChar { it.uppercase() }} ${idx + 1}"
                                setOnClickListener {
                                    enqueue(item.url, "reelsave_${System.currentTimeMillis()}_${idx + 1}.$ext")
                                }
                            }
                            pickerList.addView(b)
                        }
                    }
                }
            }
        }
    }

    private fun enqueue(url: String, filename: String) {
        try {
            val safe = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(safe)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ReelSave/$safe")
                .addRequestHeader("User-Agent", "ReelSave/1.0")
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            tvStatus.text = "Download started: $safe\nSaved in Downloads/ReelSave"
        } catch (e: Exception) {
            tvStatus.text = "Download failed: ${e.message}"
        }
    }

    private fun ensureStorage(): Boolean {
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            tvStatus.text = "Grant storage permission, then tap Download again."
            return false
        }
        return true
    }
}
