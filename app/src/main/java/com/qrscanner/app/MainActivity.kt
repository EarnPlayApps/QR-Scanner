package com.qrscanner.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var permissionButton: Button
    private lateinit var cameraMessage: TextView
    private lateinit var statusText: TextView
    private lateinit var scanAgainButton: Button
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var locked = false
    private var torchOn = false
    private val history = mutableListOf<HistoryItem>()
    private val prefs by lazy { getSharedPreferences("qr_scanner", MODE_PRIVATE) }
    private val scanner by lazy { BarcodeScanning.getClient() }
    private var soundEnabled = true
    private var vibrationEnabled = true
    private var autoOpenEnabled = false

    data class HistoryItem(val value: String, val meta: String)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionButton.visibility = View.GONE
            startCamera()
        } else {
            permissionButton.visibility = View.VISIBLE
            statusText.text = "Kebenaran kamera diperlukan untuk scan melalui kamera."
            Toast.makeText(this, "Kebenaran kamera tidak diberikan.", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scanImage(uri)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        permissionButton = findViewById(R.id.permissionButton)
        cameraMessage = findViewById(R.id.cameraMessage)
        statusText = findViewById(R.id.statusText)
        scanAgainButton = findViewById(R.id.scanAgainButton)

        loadSettings()
        loadHistory()

        findViewById<Button>(R.id.flashButton).setOnClickListener { toggleFlash() }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        scanAgainButton.setOnClickListener { scanAgain() }

        permissionButton.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                !prefs.getBoolean("asked_camera", false)) {
                prefs.edit().putBoolean("asked_camera", true).apply()
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionButton.visibility = View.GONE
            startCamera()
        } else {
            prefs.edit().putBoolean("asked_camera", true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        locked = false
        scanAgainButton.visibility = View.GONE
        cameraMessage.text = "Letak QR atau barcode dalam bingkai"
        statusText.text = "Kamera aktif — sedang mengimbas"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy ->
                    if (locked || proxy.image == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(proxy.image!!, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                            if (first != null && !locked) {
                                locked = true
                                val value = first.rawValue!!
                                val format = formatName(first.format)
                                saveHistory(value, format)
                                runOnUiThread { handleFoundResult(value, format) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }

                p.unbindAll()
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (_: Exception) {
                permissionButton.visibility = View.VISIBLE
                statusText.text = "Kamera gagal dimulakan."
                Toast.makeText(this, "Tidak dapat memulakan kamera.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFoundResult(value: String, format: String) {
        if (soundEnabled) {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                release()
            }
        }

        if (vibrationEnabled) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }

        statusText.text = "Scan berjaya: $format"
        cameraMessage.text = "QR/barcode ditemui"
        showResult(value, format)

        if (autoOpenEnabled && isWebUrl(value)) {
            window.decorView.postDelayed({
                if (!isFinishing) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                }
            }, 250)
        }
    }

    private fun scanAgain() {
        locked = false
        startCamera()
    }

    private fun toggleFlash() {
        val c = camera
        if (c == null) {
            Toast.makeText(this, "Mula kamera dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!c.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Telefon ini tiada flashlight kamera.", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
        findViewById<Button>(R.id.flashButton).text = if (torchOn) "Flash ON" else "Flash"
    }

    private fun scanImage(uri: Uri) {
        statusText.text = "Mengimbas gambar..."
        scanner.process(InputImage.fromFilePath(this, uri))
            .addOnSuccessListener { codes ->
                val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (first == null) {
                    statusText.text = "Tiada QR/barcode ditemui."
                    Toast.makeText(this, "Tiada QR/barcode ditemui dalam gambar.", Toast.LENGTH_SHORT).show()
                } else {
                    val value = first.rawValue!!
                    val format = formatName(first.format)
                    saveHistory(value, format)
                    handleFoundResult(value, format)
                }
            }
            .addOnFailureListener {
                statusText.text = "Gagal mengimbas gambar."
                Toast.makeText(this, "Gambar tidak dapat diimbas.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showResult(value: String, format: String) {
        val view = layoutInflater.inflate(R.layout.dialog_result, null)
        view.findViewById<TextView>(R.id.resultText).text = value
        view.findViewById<TextView>(R.id.resultType).text = "SCAN RESULT • $format"

        val openButton = view.findViewById<Button>(R.id.openButton)
        openButton.isEnabled = isWebUrl(value)
        openButton.alpha = if (openButton.isEnabled) 1f else .45f

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener {
                locked = false
                scanAgainButton.visibility = View.VISIBLE
            }
            .create()

        view.findViewById<Button>(R.id.copyButton).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("QR result", value))
            Toast.makeText(this, "Keputusan disalin.", Toast.LENGTH_SHORT).show()
        }

        openButton.setOnClickListener {
            if (isWebUrl(value)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            }
        }

        view.findViewById<Button>(R.id.shareButton).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }, "Kongsi keputusan scan"))
        }

        dialog.show()
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Sejarah Scan")
                .setMessage("Belum ada QR atau barcode yang diimbas.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 4, 18, 0)
        }

        val search = EditText(this).apply {
            hint = "Cari QR / barcode..."
            singleLine = true
        }
        container.addView(search, LinearLayout.LayoutParams(-1, -2))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)
        }
        container.addView(list, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sejarah Scan")
            .setView(container)
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Padam Semua") { _, _ ->
                history.clear()
                saveHistoryToPrefs()
                Toast.makeText(this, "Sejarah telah dipadam.", Toast.LENGTH_SHORT).show()
            }
            .create()

        fun render(query: String = "") {
            list.removeAllViews()
            history.filter {
                query.isBlank() || it.value.contains(query, true) || it.meta.contains(query, true)
            }.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(14, 12, 14, 12)
                    setBackgroundColor(Color.rgb(246, 248, 252))
                    setOnClickListener {
                        val format = item.meta.substringAfter(" • ", "BARCODE")
                        showResult(item.value, format)
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Padam scan?")
                            .setMessage(item.value)
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Padam") { _, _ ->
                                history.removeAll { h -> h.value == item.value }
                                saveHistoryToPrefs()
                                render(search.text.toString())
                            }
                            .show()
                        true
                    }
                }

                val value = TextView(this).apply {
                    text = item.value
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                }

                val meta = TextView(this).apply {
                    text = item.meta
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                    setPadding(0, 5, 0, 0)
                }

                row.addView(value)
                row.addView(meta)
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = 8
                })
            }
        }

        search.addTextChangedListener(SimpleTextWatcher { render(it) })
        render()
        dialog.show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Bunyi selepas scan",
            "Getaran selepas scan",
            "Buka link automatik"
        )
        val checked = booleanArrayOf(soundEnabled, vibrationEnabled, autoOpenEnabled)

        AlertDialog.Builder(this)
            .setTitle("Tetapan Scanner")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> soundEnabled = isChecked
                    1 -> vibrationEnabled = isChecked
                    2 -> autoOpenEnabled = isChecked
                }
                saveSettings()
            }
            .setPositiveButton("Selesai", null)
            .show()
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean("sound", soundEnabled)
            .putBoolean("vibration", vibrationEnabled)
            .putBoolean("auto_open", autoOpenEnabled)
            .apply()
    }

    private fun loadSettings() {
        soundEnabled = prefs.getBoolean("sound", true)
        vibrationEnabled = prefs.getBoolean("vibration", true)
        autoOpenEnabled = prefs.getBoolean("auto_open", false)
    }

    private fun saveHistory(value: String, format: String) {
        val time = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        history.removeAll { it.value == value }
        history.add(0, HistoryItem(value, "$time • $format"))
        while (history.size > 50) history.removeLast()
        saveHistoryToPrefs()
    }

    private fun saveHistoryToPrefs() {
        prefs.edit().putString(
            "history",
            history.joinToString("\n|||") { item -> item.value + "|||" + item.meta }
        ).apply()
    }

    private fun loadHistory() {
        val data = prefs.getString("history", "") ?: return
        if (data.isBlank()) return
        data.split("\n|||").forEach {
            val p = it.split("|||", limit = 2)
            if (p.size == 2) history.add(HistoryItem(p[0], p[1]))
        }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        else -> "BARCODE"
    }

    private fun isWebUrl(value: String): Boolean {
        return runCatching {
            val u = Uri.parse(value)
            u.scheme == "http" || u.scheme == "https"
        }.getOrDefault(false)
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            provider == null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        camera?.cameraControl?.enableTorch(false)
        torchOn = false
    }

    override fun onDestroy() {
        provider?.unbindAll()
        scanner.close()
        executor.shutdown()
        super.onDestroy()
    }

    class SimpleTextWatcher(private val callback: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            callback(s?.toString() ?: "")
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}
