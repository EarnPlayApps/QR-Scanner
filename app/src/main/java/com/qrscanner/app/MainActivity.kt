package com.qrscanner.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var permissionButton: Button
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var locked = false
    private val history = mutableListOf<Pair<String,String>>()
    private val prefs by lazy { getSharedPreferences("qr_scanner", MODE_PRIVATE) }
    private val scanner by lazy { BarcodeScanning.getClient() }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionButton.visibility = android.view.View.GONE
            startCamera()
        } else {
            permissionButton.visibility = android.view.View.VISIBLE
            Toast.makeText(this, "Camera permission was not granted.", Toast.LENGTH_LONG).show()
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
        loadHistory()

        findViewById<Button>(R.id.flashButton).setOnClickListener {
            val c = camera ?: return@setOnClickListener
            if (!c.cameraInfo.hasFlashUnit()) {
                Toast.makeText(this, "This phone has no flashlight.", Toast.LENGTH_SHORT).show()
            } else {
                c.cameraControl.enableTorch(c.cameraInfo.torchState.value != 1)
            }
        }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
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
            permissionButton.visibility = android.view.View.GONE
            startCamera()
        } else {
            prefs.edit().putBoolean("asked_camera", true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
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
                    if (locked || proxy.image == null) { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(proxy.image!!, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                            if (first != null && !locked) {
                                locked = true
                                val value = first.rawValue!!
                                val format = first.format.toString()
                                saveHistory(value, format)
                                runOnUiThread { showResult(value, format) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                p.unbindAll()
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (_: Exception) {
                permissionButton.visibility = android.view.View.VISIBLE
                Toast.makeText(this, "Could not start the camera.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scanImage(uri: Uri) {
        try {
            scanner.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { codes ->
                    val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    if (first == null) Toast.makeText(this, "No QR/barcode found.", Toast.LENGTH_SHORT).show()
                    else {
                        val value = first.rawValue!!
                        saveHistory(value, first.format.toString())
                        showResult(value, first.format.toString())
                    }
                }
                .addOnFailureListener { Toast.makeText(this, "Could not scan this image.", Toast.LENGTH_SHORT).show() }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open the selected image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showResult(value: String, format: String) {
        val view = layoutInflater.inflate(R.layout.dialog_result, null)
        view.findViewById<TextView>(R.id.resultText).text = value
        view.findViewById<TextView>(R.id.resultType).text = "SCAN RESULT • $format"
        val dialog = AlertDialog.Builder(this).setView(view).setOnDismissListener { locked = false }.create()

        view.findViewById<Button>(R.id.copyButton).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("QR result", value))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.openButton).setOnClickListener {
            val u = runCatching { Uri.parse(value) }.getOrNull()
            if (u != null && (u.scheme == "http" || u.scheme == "https")) startActivity(Intent(Intent.ACTION_VIEW, u))
            else Toast.makeText(this, "This result is not a web link.", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.shareButton).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value)
            }, "Share scan result"))
        }
        dialog.show()
    }

    private fun saveHistory(value: String, format: String) {
        val time = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        history.removeAll { it.first == value }
        history.add(0, value to "$time • $format")
        while (history.size > 50) history.removeLast()
        prefs.edit().putString("history", history.joinToString("\n|||") { "${it.first}|||${it.second}" }).apply()
    }

    private fun loadHistory() {
        val data = prefs.getString("history", "") ?: return
        if (data.isBlank()) return
        data.split("\n|||").forEach {
            val p = it.split("|||", limit = 2)
            if (p.size == 2) history.add(p[0] to p[1])
        }
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            AlertDialog.Builder(this).setTitle("History").setMessage("No scanned QR codes yet.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = history.map { "${it.second}\n${it.first}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Scan History").setItems(labels) { _, which ->
            showResult(history[which].first, "History")
        }.setNegativeButton("Close", null).setNeutralButton("Clear") { _, _ ->
            history.clear(); prefs.edit().remove("history").apply()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }.show()
    }

    override fun onDestroy() {
        provider?.unbindAll()
        scanner.close()
        executor.shutdown()
        super.onDestroy()
    }
}
