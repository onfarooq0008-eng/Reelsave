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
        const val DEFAULT_SERVER = "https://modern-rebekkah-usman62627-a7dc2c5c.apps.mistral.run/"
    }

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var pickerList: LinearLayout

    private val prefs by lazy { getSharedPreferences("reelsave", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
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
        val info = TextView(this).apply {
            text = "TikTok, Instagram and X/Twitter work with no setup.\n\nFor Facebook, YouTube, Pinterest and others, add ONE optional provider:\n• a free FastSaver key (api.fastsaver.io), or\n• a cobalt instance URL."
        }
        val etYt = EditText(this).apply { hint = "yt-dlp server URL"; setText(prefs.getString("ytdlp_url", DEFAULT_SERVER)) }
        val etFs = EditText(this).apply { hint = "FastSaver API key (optional)"; setText(prefs.getString("apikey", "")) }
        val etCu = EditText(this).apply { hint = "Cobalt instance URL (optional)"; setText(prefs.getString("cobalt_url", "")) }
        val etCk = EditText(this).apply { hint = "Cobalt API key (optional)"; setText(prefs.getString("cobalt_key", "")) }
        box.addView(info); box.addView(etYt); box.addView(etFs); box.addView(etCu); box.addView(etCk)
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("ytdlp_url", etYt.text.toString().trim())
                    .putString("apikey", etFs.text.toString().trim())
                    .putString("cobalt_url", etCu.text.toString().trim())
                    .putString("cobalt_key", etCk.text.toString().trim())
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
        val ytUrl = prefs.getString("ytdlp_url", DEFAULT_SERVER) ?: ""
        val key = prefs.getString("apikey", "") ?: ""
        val cUrl = prefs.getString("cobalt_url", "") ?: ""
        val cKey = prefs.getString("cobalt_key", "") ?: ""
        if (!ensureStorage()) return

        pickerList.removeAllViews()
        tvStatus.text = "Fetching…"
        progress.visibility = View.VISIBLE
        btnDownload.isEnabled = false

        thread {
            val result = Resolver.resolve(ytUrl, key, cUrl, cKey, link)
            runOnUiThread {
                progress.visibility = View.GONE
                btnDownload.isEnabled = true
                when (result) {
                    is ApiResult.Failure -> tvStatus.text = result.message
                    is ApiResult.Single ->
                        enqueue(result.item.url, "reelsave_${System.currentTimeMillis()}.${result.item.ext}")
                    is ApiResult.Picker -> {
                        tvStatus.text = "Multiple items found. Tap to download:"
                        result.items.forEachIndexed { idx, item ->
                            val b = Button(this).apply {
                                text = (if (item.ext == "jpg") "Photo " else "Video ") + (idx + 1)
                                setOnClickListener {
                                    enqueue(item.url, "reelsave_${System.currentTimeMillis()}_${idx + 1}.${item.ext}")
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
