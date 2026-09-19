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
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
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
    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .build()
        BarcodeScanning.getClient(options)
    }
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
    private var languageMs = true
    private var interstitialAd: InterstitialAd? = null
    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoading = false
    private var appOpenShowing = false
    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false
    private var freeScanCredits = 0
    private var firstLaunch = true
    private var backgroundAt = 0L
    private var scanningStarted = false
    private val history = mutableListOf<HistoryItem>()
    data class HistoryItem(val value: String, val meta: String)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            permissionButton.visibility = View.GONE
            preview.post { startCamera() }
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
        freeScanCredits = prefs.getInt("free_scan_credits", 0)
        languageMs = prefs.getBoolean("language_ms", true)
        applyLanguage()
        setupAds()

        findViewById<Button>(R.id.startScanButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.flashButton).setOnClickListener { toggleFlash() }
        findViewById<Button>(R.id.flashHeaderButton).setOnClickListener { toggleLanguage() }
        findViewById<Button>(R.id.zoomOneButton).setOnClickListener { setCameraZoom(1f) }
        findViewById<Button>(R.id.zoomTwoButton).setOnClickListener { setCameraZoom(2f) }
        findViewById<Button>(R.id.saveHistoryButton).setOnClickListener { Toast.makeText(this, t("Keputusan telah disimpan ke sejarah.", "Result saved to history."), Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickImage.launch("image/*") }
        // Hamburger menu is visual-only for now.
        findViewById<Button>(R.id.resultBackButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.resultScanAgainButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.bottomScanButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.bottomHistoryButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.bottomSettingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.bottomMoreButton).setOnClickListener { showMoreMenu() }
        scanAgainButton.setOnClickListener { startCamera() }
        // Reference design uses a launch screen, then enters the scanner automatically.
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed && findViewById<View>(R.id.welcomeScreen).visibility == View.VISIBLE) beginScanning()
        }, 1400L)

        permissionButton.setOnClickListener {
            if (!prefs.getBoolean("asked_camera", false) || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                prefs.edit().putBoolean("asked_camera", true).apply()
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }

    }

    private fun toggleLanguage() {
        languageMs = !languageMs
        prefs.edit().putBoolean("language_ms", languageMs).apply()
        applyLanguage()
        Toast.makeText(this, if (languageMs) "Bahasa Melayu" else "English", Toast.LENGTH_SHORT).show()
    }

    private fun t(ms: String, en: String): String = if (languageMs) ms else en

    private fun applyLanguage() {
        findViewById<Button>(R.id.flashHeaderButton).text = if (languageMs) "BM" else "EN"
        findViewById<Button>(R.id.startScanButton).text = if (languageMs) "Mula Scan" else "Start Scan"
        findViewById<TextView>(R.id.cameraMessage).text = if (languageMs) "Halakan QR / barcode ke ruang ini" else "Point a QR / barcode into this area"
        findViewById<Button>(R.id.bottomScanButton).text = if (languageMs) "Scan" else "Scan"
        findViewById<Button>(R.id.bottomHistoryButton).text = if (languageMs) "Sejarah" else "History"
        findViewById<Button>(R.id.bottomSettingsButton).text = if (languageMs) "Tetapan" else "Settings"
        findViewById<Button>(R.id.bottomMoreButton).text = if (languageMs) "Lainnya" else "More"
        findViewById<TextView>(R.id.resultTitle).text = if (languageMs) "Hasil Scan" else "Scan Result"
        findViewById<TextView>(R.id.resultInfoTitle).text = if (languageMs) "Maklumat" else "Information"
        findViewById<Button>(R.id.openButton).text = if (languageMs) "Buka Link" else "Open Link"
        findViewById<Button>(R.id.copyButton).text = if (languageMs) "Salin Link" else "Copy Link"
        findViewById<Button>(R.id.shareButton).text = if (languageMs) "Kongsi" else "Share"
        findViewById<Button>(R.id.saveHistoryButton).text = if (languageMs) "Simpan ke Sejarah" else "Save to History"
        findViewById<Button>(R.id.resultScanAgainButton).text = if (languageMs) "Scan Lagi" else "Scan Again"
        findViewById<TextView>(R.id.welcomeSubtitle).text = "Scan Anything, Anytime"
        findViewById<TextView>(R.id.welcomeTagline).text = if (languageMs) "Imbas Dunia Dengan Lebih Bijak" else "Scan a Smarter World"
    }

    private fun beginScanning() {
        if (isFinishing || isDestroyed) return
        scanningStarted = true
        findViewById<View>(R.id.welcomeScreen).visibility = View.GONE
        findViewById<View>(R.id.resultScreen).visibility = View.GONE
        findViewById<View>(R.id.mainContent).visibility = View.VISIBLE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionButton.visibility = View.GONE
            preview.post { startCamera() }
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
                    runCatching {
                        findViewById<AdView>(R.id.bannerAd).loadAd(AdRequest.Builder().build())
                        findViewById<AdView>(R.id.resultBannerAd).loadAd(AdRequest.Builder().build())
                    }
                    loadInterstitial()
                    loadAppOpenAd()
                    loadNativeAd()
                    loadRewardedAd()
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
        if (!::preview.isInitialized) return
        locked = false
        scanAgainButton.visibility = View.GONE
        cameraMessage.text = "Letak QR atau barcode dalam bingkai"
        statusText.text = "Kamera aktif — sedang mengimbas"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@addListener
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
                                if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                                    showScanResult(value, format)
                                } else {
                                    locked = false
                                }
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
        if (isFinishing || isDestroyed) return

        findViewById<View>(R.id.mainContent).visibility = View.GONE
        findViewById<View>(R.id.welcomeScreen).visibility = View.GONE
        findViewById<View>(R.id.resultScreen).visibility = View.VISIBLE
        findViewById<View>(R.id.nativeAdContainer).visibility = View.VISIBLE

        val resultText = findViewById<TextView>(R.id.resultText)
        val resultType = findViewById<TextView>(R.id.resultType)
        val open = findViewById<Button>(R.id.openButton)

        resultText.text = value
        resultType.text = "SCAN RESULT  •  $format"
        open.isEnabled = isWebUrl(value)
        open.alpha = if (open.isEnabled) 1f else .45f

        open.setOnClickListener {
            if (isWebUrl(value)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                    .onFailure { Toast.makeText(this, t("Link tidak dapat dibuka.", "Link could not be opened."), Toast.LENGTH_SHORT).show() }
            }
        }

        findViewById<Button>(R.id.copyButton).setOnClickListener {
            runCatching {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("QR result", value))
                Toast.makeText(this, t("Keputusan disalin.", "Result copied."), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.shareButton).setOnClickListener {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, value)
                }, t("Kongsi keputusan scan", "Share scan result")))
            }
        }

        runCatching { findViewById<View>(R.id.nativeAdContainer).visibility = View.VISIBLE }
        runCatching { loadNativeAd() }

        if (autoOpenEnabled && isWebUrl(value))
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && findViewById<View>(R.id.resultScreen).visibility == View.VISIBLE) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                }
            }, 350)
        }

        runCatching {
            findViewById<AdView>(R.id.resultBannerAd).loadAd(AdRequest.Builder().build())
        }
        maybeShowInterstitial()
    }

    private fun maybeShowInterstitial() {
        if (freeScanCredits > 0) {
            freeScanCredits--
            prefs.edit().putInt("free_scan_credits", freeScanCredits).apply()
            return
        }
        if (scanCount % 5 != 0 || scanCount == 0 || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val ad = interstitialAd ?: return
        interstitialAd = null
        runCatching { ad.show(this) }.onFailure { loadInterstitial() }
    }

    private fun loadRewardedAd() {
        if (rewardedLoading || rewardedAd != null) return
        rewardedLoading = true
        runCatching {
            RewardedAd.load(
                this,
                "ca-app-pub-3940256099942544/5224354917",
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedLoading = false
                        rewardedAd = ad
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        rewardedLoading = false
                        rewardedAd = null
                    }
                }
            )
        }.onFailure {
            rewardedLoading = false
            rewardedAd = null
        }
    }

    private fun showRewardedAd() {
        val ad = rewardedAd
        if (ad == null) {
            Toast.makeText(this, t("Iklan ganjaran belum tersedia.", "Rewarded ad is not ready yet."), Toast.LENGTH_SHORT).show()
            loadRewardedAd()
            return
        }
        rewardedAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadRewardedAd()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadRewardedAd()
            }
        }
        runCatching {
            ad.show(this) { _: RewardItem ->
                freeScanCredits += 10
                prefs.edit().putInt("free_scan_credits", freeScanCredits).apply()
                Toast.makeText(
                    this,
                    t("Ganjaran diterima: 10 scan tanpa interstitial.", "Reward received: 10 scans without interstitial."),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onFailure {
            loadRewardedAd()
        }
    }

    private fun setCameraZoom(level: Float) {
        camera?.cameraControl?.setZoomRatio(level)
    }

    private fun toggleFlash() {
        val c = camera ?: return Toast.makeText(this, "Mula kamera dahulu.", Toast.LENGTH_SHORT).show()
        if (!c.cameraInfo.hasFlashUnit()) return Toast.makeText(this, "Telefon ini tiada flashlight kamera.", Toast.LENGTH_SHORT).show()
        torchOn = !torchOn
        runCatching { c.cameraControl.enableTorch(torchOn) }
        findViewById<Button>(R.id.flashButton).text = if (torchOn) "Flash ON" else "Flash"
    }

    private fun showMoreMenu() {
        val choices = arrayOf(t("Galeri", "Gallery"), t("Flash", "Flash"), t("Kamera depan / belakang", "Front / rear camera"), t("Tonton iklan & dapatkan 10 scan bebas interstitial", "Watch ad & get 10 scans without interstitial"), t("Scan Lagi", "Scan Again"))
        AlertDialog.Builder(this)
            .setTitle(t("Lainnya", "More"))
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> pickImage.launch("image/*")
                    1 -> toggleFlash()
                    2 -> {
                        val cameraChoices = arrayOf(t("Kamera belakang", "Rear camera"), t("Kamera depan", "Front camera"))
                        AlertDialog.Builder(this)
                            .setTitle(t("Pilih Kamera", "Choose Camera"))
                            .setSingleChoiceItems(cameraChoices, if (useFrontCamera) 1 else 0) { dialog, selected ->
                                useFrontCamera = selected == 1
                                saveSettings()
                                if (scanningStarted && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    startCamera()
                                }
                                dialog.dismiss()
                            }
                            .setNegativeButton(t("Batal", "Cancel"), null)
                            .show()
                    }
                    3 -> showRewardedAd()
                    4 -> startCamera()
                }
            }
            .setNegativeButton(t("Tutup", "Close"), null)
            .show()
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            AlertDialog.Builder(this).setTitle(t("Sejarah Scan", "Scan History")).setMessage(t("Belum ada QR atau barcode yang diimbas.", "No QR or barcode has been scanned yet.")).setPositiveButton("OK", null).show()
            return
        }
        val text = history.joinToString("\n\n") { it.value + "\n" + it.meta }
        AlertDialog.Builder(this).setTitle(t("Sejarah Scan", "Scan History")).setMessage(text).setPositiveButton("Tutup", null)
            .setNeutralButton(t("Padam Semua", "Clear All")) { _, _ -> history.clear(); saveHistoryToPrefs() }.show()
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

        addSwitch(t("Bunyi selepas scan", "Sound after scan"), soundEnabled) { soundEnabled = it }
        addSwitch(t("Getaran selepas scan", "Vibration after scan"), vibrationEnabled) { vibrationEnabled = it }
        addSwitch(t("Buka link automatik", "Open links automatically"), autoOpenEnabled) { autoOpenEnabled = it }
        addSwitch(t("Scan semula automatik", "Auto rescan"), autoScanEnabled) { autoScanEnabled = it }
        addSwitch(t("Simpan sejarah scan", "Save scan history"), saveHistoryEnabled) { saveHistoryEnabled = it }

        val cameraButton = Button(this).apply {
            text = if (useFrontCamera) t("Kamera: Depan", "Camera: Front") else t("Kamera: Belakang", "Camera: Rear")
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            isAllCaps = false
            minHeight = 0
            setPadding(18, 0, 18, 0)
            setOnClickListener {
                val choices = arrayOf("Kamera belakang", "Kamera depan")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(t("Pilih Kamera", "Choose Camera"))
                    .setSingleChoiceItems(choices, if (useFrontCamera) 1 else 0) { dialog, which ->
                        useFrontCamera = which == 1
                        saveSettings()
                        text = if (useFrontCamera) t("Kamera: Depan", "Camera: Front") else t("Kamera: Belakang", "Camera: Rear")
                        if (scanningStarted && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            startCamera()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(t("Batal", "Cancel"), null)
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
            text = t("Padam Semua Sejarah", "Clear All History")
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            isAllCaps = false
            minHeight = 0
            setPadding(18, 0, 18, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(t("Padam Sejarah", "Clear History"))
                    .setMessage(t("Padam semua rekod scan yang disimpan?", "Clear all saved scan records?"))
                    .setNegativeButton(t("Batal", "Cancel"), null)
                    .setPositiveButton(t("Padam", "Clear")) { _, _ ->
                        history.clear()
                        saveHistoryToPrefs()
                        Toast.makeText(this@MainActivity, "Sejarah scan telah dipadam.", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        }
        box.addView(historyButton)

        AlertDialog.Builder(this)
            .setTitle(t("Tetapan Scanner", "Scanner Settings"))
            .setView(box)
            .setPositiveButton(t("Selesai", "Done"), null)
            .setNeutralButton(t("Privasi & Tentang", "Privacy & About")) { _, _ -> showPrivacyPolicy() }
            .show()
    }

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this).setTitle(t("Privasi & Polisi", "Privacy & Policy"))
            .setMessage("QR Scanner menggunakan kamera hanya untuk fungsi scan. Sejarah scan disimpan secara tempatan. Aplikasi menggunakan Google AdMob untuk iklan. Iklan ujian digunakan semasa pembangunan.")
            .setNegativeButton(t("Tutup", "Close"), null)
            .setPositiveButton(t("Buka Dasar Privasi", "Open Privacy Policy")) { _, _ ->
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
        if (::preview.isInitialized &&
            findViewById<View>(R.id.resultScreen).visibility != View.VISIBLE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            scanningStarted && provider == null) {
            preview.post { startCamera() }
        }
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