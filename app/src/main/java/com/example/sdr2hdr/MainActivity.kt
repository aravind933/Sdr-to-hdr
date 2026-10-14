package com.example.sdr2hdr

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private var videoUri: Uri? = null
    private var lut: CubeLut? = null

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var convertBtn: Button
    private lateinit var strengthBar: SeekBar
    private lateinit var expandBar: SeekBar
    @Volatile private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad, 0, 0) }

        val pickVideo = Button(this).apply { text = "1. Pick SDR video" }
        val pickLut = Button(this).apply { text = "2. Pick .cube LUT (optional)" }
        strengthBar = SeekBar(this).apply { max = 100; progress = 100 }
        expandBar = SeekBar(this).apply { max = 100; progress = 25 }
        convertBtn = Button(this).apply { text = "3. Convert to HLG HDR"; isEnabled = false }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        status = TextView(this).apply { text = "Pick a video to begin."; setPadding(0, pad, 0, 0) }

        root.addView(pickVideo)
        root.addView(pickLut)
        root.addView(label("LUT strength"))
        root.addView(strengthBar)
        root.addView(label("Highlight expansion"))
        root.addView(expandBar)
        root.addView(convertBtn)
        root.addView(progress)
        root.addView(status)
        setContentView(root)

        pickVideo.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }, REQ_VIDEO)
        }
        pickLut.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, REQ_LUT)
        }
        convertBtn.setOnClickListener { startConversion() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_VIDEO -> {
                videoUri = uri
                convertBtn.isEnabled = true
                status.text = "Video selected."
            }
            REQ_LUT -> try {
                lut = contentResolver.openInputStream(uri)!!.use { CubeParser.parse(it) }
                status.text = "LUT loaded (${lut!!.size}^3)."
            } catch (e: Exception) {
                lut = null
                status.text = "LUT error: ${e.message}"
            }
        }
    }

    private fun startConversion() {
        val input = videoUri ?: return
        convertBtn.isEnabled = false
        cancelled = false
        progress.progress = 0
        status.text = "Converting..."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val options = SdrToHdrTranscoder.Options(
            lut = lut,
            lutStrength = strengthBar.progress / 100f,
            expand = expandBar.progress / 100f
        )
        val tmp = File(cacheDir, "out_hlg_${System.currentTimeMillis()}.mp4")

        Thread {
            val result = runCatching {
                SdrToHdrTranscoder(this).transcode(
                    input, tmp.absolutePath, options,
                    onProgress = { p -> runOnUiThread { progress.progress = (p * 100).toInt() } },
                    isCancelled = { cancelled }
                )
                saveToMovies(tmp)
            }
            tmp.delete()
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                convertBtn.isEnabled = true
                status.text = result.fold(
                    onSuccess = { "Done. Saved to Movies/SDR2HDR." },
                    onFailure = { "Failed: ${it.message}" }
                )
            }
        }.start()
    }

    private fun saveToMovies(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HDR_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SDR2HDR")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create output file")
        contentResolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private companion object {
        const val REQ_VIDEO = 1
        const val REQ_LUT = 2
    }
}
