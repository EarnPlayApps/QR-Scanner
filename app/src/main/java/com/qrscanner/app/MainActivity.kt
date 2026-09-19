package com.qrscanner.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.*
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var permissionButton: Button
    private lateinit var cameraMessage: TextView
    private lateinit var statusText: TextView
    private lateinit var scanAgainButton: Button
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner by lazy { BarcodeScanning.getClient() }
    private val prefs by lazy { getSharedPreferences("qr_scanner", MODE_PRIVATE) }
    private var locked = false
    private var torchOn = false
    private var soundEnabled = true
    private var vibrationEnabled = true
    private var autoOpenEnabled = false
    private var autoScanEnabled = true
    private var saveHistoryEnabled = true
    private var useFrontCamera = false
    private var scanCount = 0
    private var interstitialAd: InterstitialAd? = null
    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoading = false
    private var appOpenShowing = false
    private var firstLaunch = true
    private var backgroundAt = 0L
    private var scanningStarted = false
    private val history = mutableListOf<HistoryItem>()
    data class HistoryItem(val value: String, val meta: String)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            permissionButton.visibility = View.GONE
            startCamera()
        } else {
            permissionButton.visibility = View.VISIBLE
            statusText.text = "Kebenaran kamera diperlukan untuk scan melalui kamera."
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scanImage(it) }
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
        scanCount = prefs.getInt("scan_count", 0)
        setupAds()

        findViewById<Button>(R.id.startScanButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.flashButton).setOnClickListener { toggleFlash() }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        scanAgainButton.setOnClickListener { startCamera() }
        permissionButton.setOnClickListener {
            if (!prefs.getBoolean("asked_camera", false) || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                prefs.edit().putBoolean("asked_camera", true).apply()
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }

    }

    private fun beginScanning() {
        if (isFinishing || isDestroyed) return
        scanningStarted = true
        findViewById<View>(R.id.welcomeScreen).visibility = View.GONE
        findViewById<View>(R.id.mainContent).visibility = View.VISIBLE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionButton.visibility = View.GONE
            startCamera()
        } else {
            prefs.edit().putBoolean("asked_camera", true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupAds() {
        runCatching {
            MobileAds.initialize(this) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    runCatching { findViewById<AdView>(R.id.bannerAd).loadAd(AdRequest.Builder().build()) }
                    loadInterstitial()
                    loadAppOpenAd()
                    loadNativeAd()
                }
            }
        }
    }

    private fun loadNativeAd() {
        runCatching {
            val adView = findViewById<NativeAdView>(R.id.nativeAdView)
            val loader = AdLoader.Builder(this, "ca-app-pub-3940256099942544/2247696110")
                .forNativeAd { ad: NativeAd ->
                    if (isFinishing || isDestroyed) {
                        ad.destroy()
                        return@forNativeAd
                    }
                    adView.mediaView = adView.findViewById(R.id.nativeAdMedia)
                    adView.headlineView = adView.findViewById(R.id.nativeAdHeadline)
                    adView.setIconView(adView.findViewById(R.id.nativeAdIcon))
                    adView.setAdvertiserView(adView.findViewById(R.id.nativeAdAdvertiser))
                    adView.setBodyView(adView.findViewById(R.id.nativeAdBody))
                    adView.setCallToActionView(adView.findViewById(R.id.nativeAdCallToAction))

                    (adView.headlineView as TextView).text = ad.headline
                    val icon = adView.findViewById<ImageView>(R.id.nativeAdIcon)
                    if (ad.icon?.drawable != null) { icon.setImageDrawable(ad.icon?.drawable); icon.visibility = View.VISIBLE } else icon.visibility = View.GONE
                    val advertiser = adView.findViewById<TextView>(R.id.nativeAdAdvertiser)
                    if (ad.advertiser.isNullOrBlank()) { advertiser.visibility = View.GONE } else { advertiser.visibility = View.VISIBLE; advertiser.text = ad.advertiser }

                    val body = adView.findViewById<TextView>(R.id.nativeAdBody)
                    if (ad.body.isNullOrBlank()) {
                        body.visibility = View.GONE
                    } else {
                        body.visibility = View.VISIBLE
                        body.text = ad.body
                    }

                    val cta = adView.findViewById<Button>(R.id.nativeAdCallToAction)
                    if (ad.callToAction.isNullOrBlank()) {
                        cta.visibility = View.GONE
                    } else {
                        cta.visibility = View.VISIBLE
                        cta.text = ad.callToAction
                    }

                    adView.setNativeAd(ad)
                }
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                        .build()
                )
                .build()
            loader.loadAd(AdRequest.Builder().build())
        }
    }

    private fun loadInterstitial() {
        runCatching {
            InterstitialAd.load(this, "ca-app-pub-3940256099942544/1033173712", AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) { interstitialAd = ad }
                    override fun onAdFailedToLoad(e: LoadAdError) { interstitialAd = null }
                })
        }
    }

    private fun loadAppOpenAd() {
        if (appOpenLoading || appOpenAd != null) return
        appOpenLoading = true
        runCatching {
            AppOpenAd.load(this, "ca-app-pub-3940256099942544/9257395921", AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) { appOpenLoading = false; appOpenAd = ad }
                    override fun onAdFailedToLoad(e: LoadAdError) { appOpenLoading = false; appOpenAd = null }
                })
        }.onFailure { appOpenLoading = false }
    }

    private fun showAppOpenIfReady() {
        if (firstLaunch || appOpenShowing || isFinishing || isDestroyed) return
        val ad = appOpenAd ?: return
        appOpenShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { appOpenAd = null; appOpenShowing = false; loadAppOpenAd() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { appOpenAd = null; appOpenShowing = false; loadAppOpenAd() }
        }
        runCatching { ad.show(this) }.onFailure { appOpenAd = null; appOpenShowing = false; loadAppOpenAd() }
    }

    private fun startCamera() {
        if (isFinishing || isDestroyed) return
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
                    if (locked || isFinishing || isDestroyed) { runCatching { proxy.close() }; return@setAnalyzer }
                    val image = proxy.image
                    if (image == null) { proxy.close(); return@setAnalyzer }
                    val input = runCatching { InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees) }.getOrNull()
                    if (input == null) { proxy.close(); return@setAnalyzer }
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() } ?: return@addOnSuccessListener
                            if (locked || isFinishing || isDestroyed) return@addOnSuccessListener
                            locked = true
                            val value = first.rawValue.orEmpty()
                            val format = formatName(first.format)
                            if (isFinishing || isDestroyed) {
                                locked = false
                                return@addOnSuccessListener
                            }
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) showScanResult(value, format)
                                else locked = false
                            }
                        }
                        .addOnCompleteListener { runCatching { proxy.close() } }
                }

                p.unbindAll()
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                camera = p.bindToLifecycle(this, selector, previewUseCase, analysis)
            } catch (e: Exception) {
                permissionButton.visibility = View.VISIBLE
                statusText.text = "Kamera gagal dimulakan."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showScanResult(value: String, format: String) {
        if (isFinishing || isDestroyed) return
        runCatching {
            provider?.unbindAll()
            provider = null
            camera = null
            if (saveHistoryEnabled) saveHistory(value, format)
            scanCount++
            prefs.edit().putInt("scan_count", scanCount).apply()
            feedback()
            statusText.text = "Scan berjaya: $format"
            cameraMessage.text = "QR/barcode ditemui"
            showResult(value, format)
        }.onFailure {
            locked = false
            statusText.text = "Keputusan scan tidak dapat dipaparkan."
            Toast.makeText(this, "Keputusan scan tidak dapat dipaparkan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun feedback() {
        if (soundEnabled) runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            window.decorView.postDelayed({ runCatching { tone.release() } }, 220)
        }
        if (vibrationEnabled) runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = manager.defaultVibrator
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(140L, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        vibrator.vibrate(VibrationEffect.createOneShot(140L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        vibrator.vibrate(140L)
                    }
                }
            }
        }
    }

    private fun scanImage(uri: Uri) {
        if (isFinishing || isDestroyed) return
        runCatching { provider?.unbindAll(); provider = null; camera = null }.onFailure { }
        statusText.text = "Mengimbas gambar..."
        val input = runCatching { InputImage.fromFilePath(this, uri) }.getOrNull()
        if (input == null) {
            statusText.text = "Gambar tidak dapat dibuka."
            Toast.makeText(this, "Gambar tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
            return
        }
        scanner.process(input)
            .addOnSuccessListener { codes ->
                val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (first == null) {
                    statusText.text = "Tiada QR/barcode ditemui."
                    Toast.makeText(this, "Tiada QR/barcode ditemui dalam gambar.", Toast.LENGTH_SHORT).show()
                } else {
                    runOnUiThread { showScanResult(first.rawValue.orEmpty(), formatName(first.format)) }
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
        val open = view.findViewById<Button>(R.id.openButton)
        open.isEnabled = isWebUrl(value)
        open.alpha = if (open.isEnabled) 1f else .45f
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.setOnDismissListener {
            locked = false
            scanAgainButton.visibility = View.GONE
            statusText.text = "Kamera sedang disediakan..."
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    if (autoScanEnabled) startCamera()
                }
            }, 80)
            maybeShowInterstitial()
        }

        view.findViewById<Button>(R.id.copyButton).setOnClickListener {
            runCatching {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("QR result", value))
                Toast.makeText(this, "Keputusan disalin.", Toast.LENGTH_SHORT).show()
            }
        }
        open.setOnClickListener {
            if (isWebUrl(value)) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
        }
        view.findViewById<Button>(R.id.shareButton).setOnClickListener {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, value)
                }, "Kongsi keputusan scan"))
            }
        }
        dialog.show()

        if (autoOpenEnabled && isWebUrl(value)) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
            }, 350)
        }
    }

    private fun maybeShowInterstitial() {
        if (scanCount % 5 != 0 || scanCount == 0 || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val ad = interstitialAd ?: return
        interstitialAd = null
        runCatching { ad.show(this) }.onFailure { loadInterstitial() }
    }

    private fun toggleFlash() {
        val c = camera ?: return Toast.makeText(this, "Mula kamera dahulu.", Toast.LENGTH_SHORT).show()
        if (!c.cameraInfo.hasFlashUnit()) return Toast.makeText(this, "Telefon ini tiada flashlight kamera.", Toast.LENGTH_SHORT).show()
        torchOn = !torchOn
        runCatching { c.cameraControl.enableTorch(torchOn) }
        findViewById<Button>(R.id.flashButton).text = if (torchOn) "Flash ON" else "Flash"
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Sejarah Scan").setMessage("Belum ada QR atau barcode yang diimbas.").setPositiveButton("OK", null).show()
            return
        }
        val text = history.joinToString("\n\n") { it.value + "\n" + it.meta }
        AlertDialog.Builder(this).setTitle("Sejarah Scan").setMessage(text).setPositiveButton("Tutup", null)
            .setNeutralButton("Padam Semua") { _, _ -> history.clear(); saveHistoryToPrefs() }.show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 4)
        }

        fun addSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            box.addView(Switch(this).apply {
                text = title
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value); saveSettings() }
                setPadding(0, 10, 0, 10)
            })
        }

        addSwitch("Bunyi selepas scan", soundEnabled) { soundEnabled = it }
        addSwitch("Getaran selepas scan", vibrationEnabled) { vibrationEnabled = it }
        addSwitch("Buka link automatik", autoOpenEnabled) { autoOpenEnabled = it }
        addSwitch("Scan semula automatik", autoScanEnabled) { autoScanEnabled = it }
        addSwitch("Simpan sejarah scan", saveHistoryEnabled) { saveHistoryEnabled = it }

        val cameraButton = Button(this).apply {
            text = if (useFrontCamera) "Kamera: Depan" else "Kamera: Belakang"
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            isAllCaps = false
            minHeight = 0
            setPadding(18, 0, 18, 0)
            setOnClickListener {
                val choices = arrayOf("Kamera belakang", "Kamera depan")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pilih Kamera")
                    .setSingleChoiceItems(choices, if (useFrontCamera) 1 else 0) { dialog, which ->
                        useFrontCamera = which == 1
                        saveSettings()
                        text = if (useFrontCamera) "Kamera: Depan" else "Kamera: Belakang"
                        if (scanningStarted && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            startCamera()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
        val cameraSpace = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                10
            )
        }
        box.addView(cameraButton)
        box.addView(cameraSpace)

        val historyButton = Button(this).apply {
            text = "Padam Semua Sejarah"
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            isAllCaps = false
            minHeight = 0
            setPadding(18, 0, 18, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Padam Sejarah")
                    .setMessage("Padam semua rekod scan yang disimpan?")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Padam") { _, _ ->
                        history.clear()
                        saveHistoryToPrefs()
                        Toast.makeText(this@MainActivity, "Sejarah scan telah dipadam.", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        }
        box.addView(historyButton)

        AlertDialog.Builder(this)
            .setTitle("Tetapan Scanner")
            .setView(box)
            .setPositiveButton("Selesai", null)
            .setNeutralButton("Privasi & Tentang") { _, _ -> showPrivacyPolicy() }
            .show()
    }

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this).setTitle("Privasi & Polisi")
            .setMessage("QR Scanner menggunakan kamera hanya untuk fungsi scan. Sejarah scan disimpan secara tempatan. Aplikasi menggunakan Google AdMob untuk iklan. Iklan ujian digunakan semasa pembangunan.")
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Buka Dasar Privasi") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EarnPlayApps/QR-Scanner/blob/main/PRIVACY_POLICY.md"))) }
            }.show()
    }

    private fun saveSettings() {
        prefs.edit().putBoolean("sound", soundEnabled).putBoolean("vibration", vibrationEnabled).putBoolean("auto_open", autoOpenEnabled).putBoolean("auto_scan", autoScanEnabled).putBoolean("save_history", saveHistoryEnabled).putBoolean("front_camera", useFrontCamera).apply()
    }
    private fun loadSettings() {
        soundEnabled = prefs.getBoolean("sound", true)
        vibrationEnabled = prefs.getBoolean("vibration", true)
        autoOpenEnabled = prefs.getBoolean("auto_open", false)
        autoScanEnabled = prefs.getBoolean("auto_scan", true)
        saveHistoryEnabled = prefs.getBoolean("save_history", true)
        useFrontCamera = prefs.getBoolean("front_camera", false)
    }
    private fun saveHistory(value: String, format: String) {
        val time = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        history.removeAll { it.value == value }
        history.add(0, HistoryItem(value, "$time • $format"))
        while (history.size > 50) history.removeLast()
        saveHistoryToPrefs()
    }
    private fun saveHistoryToPrefs() {
        prefs.edit().putString("history", history.joinToString("\n|||") { it.value + "|||" + it.meta }).apply()
    }
    private fun loadHistory() {
        val data = prefs.getString("history", "") ?: return
        if (data.isBlank()) return
        data.split("\n|||").forEach {
            val p = it.split("|||", limit = 2)
            if (p.size == 2) history.add(HistoryItem(p[0], p[1]))
        }
    }
    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_AZTEC -> "AZTEC"; Barcode.FORMAT_CODE_128 -> "CODE_128"; Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"; Barcode.FORMAT_CODABAR -> "CODABAR"; Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_EAN_13 -> "EAN_13"; Barcode.FORMAT_EAN_8 -> "EAN_8"; Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_PDF417 -> "PDF417"; Barcode.FORMAT_QR_CODE -> "QR_CODE"; Barcode.FORMAT_UPC_A -> "UPC_A"; Barcode.FORMAT_UPC_E -> "UPC_E"
        else -> "BARCODE"
    }
    private fun isWebUrl(value: String) = runCatching { Uri.parse(value).scheme in setOf("http", "https") }.getOrDefault(false)

    override fun onResume() {
        super.onResume()
        if (!firstLaunch && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > 60000) showAppOpenIfReady()
        firstLaunch = false
        if (::preview.isInitialized && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && scanningStarted && provider == null) startCamera()
    }
    override fun onPause() {
        super.onPause()
        backgroundAt = System.currentTimeMillis()
        runCatching { provider?.unbindAll() }
        provider = null
        camera = null
        locked = false
        torchOn = false
    }
    override fun onDestroy() {
        runCatching { provider?.unbindAll() }
        scanner.close()
        executor.shutdown()
        super.onDestroy()
    }
}