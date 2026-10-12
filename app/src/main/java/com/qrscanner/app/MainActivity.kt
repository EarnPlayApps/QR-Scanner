package com.qrscanner.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
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
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
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
    private val scannerDelegate = lazy {
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
    private val scanner by scannerDelegate
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
    private var nativeAdLoaded = false
    private var scannerBannerLoaded = false
    private var resultBannerLoaded = false
    private var freeScanCredits = 0
    private var firstLaunch = true
    private var backgroundAt = 0L
    private var appOpenReturnCount = 0
    private var lastAppOpenShownAt = 0L
    private var scansSinceInterstitial = 0
    private var adsInitializationStarted = false
    private var scanningStarted = false
    private var currentResultValue = ""
    private var currentResultFormat = ""
    private val history = mutableListOf<HistoryItem>()
    data class HistoryItem(val value: String, val meta: String)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            permissionButton.visibility = View.GONE
            preview.post { startCamera() }
        } else {
            permissionButton.visibility = View.VISIBLE
            statusText.text = t("Kebenaran kamera diperlukan untuk scan melalui kamera.", "Camera permission is required to scan.")
            Toast.makeText(this, t("Benarkan akses kamera untuk mula mengimbas.", "Allow camera access to start scanning."), Toast.LENGTH_LONG).show()
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
        scansSinceInterstitial = prefs.getInt("scans_since_interstitial", 0)
        languageMs = prefs.getBoolean("language_ms", true)
        applyLanguage()
        // Let the first screen render before initializing consent/ads SDKs.
        // An SDK initialization problem must never close the scanner on launch.
        window.decorView.post {
            if (!isFinishing && !isDestroyed) runCatching { setupAds() }
        }

        findViewById<Button>(R.id.startScanButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.settingsButton).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { showQuickMenu() }
        }
        findViewById<Button>(R.id.flashButton).setOnClickListener { toggleFlash() }
        findViewById<Button>(R.id.flashHeaderButton).setOnClickListener { toggleLanguage() }
        findViewById<Button>(R.id.zoomOneButton).setOnClickListener { setCameraZoom(1f) }
        findViewById<Button>(R.id.zoomTwoButton).setOnClickListener { setCameraZoom(2f) }
        findViewById<Button>(R.id.saveHistoryButton).setOnClickListener {
            if (currentResultValue.isBlank()) {
                Toast.makeText(this, t("Tiada keputusan untuk disimpan.", "No result to save."), Toast.LENGTH_SHORT).show()
            } else {
                saveHistory(currentResultValue, currentResultFormat)
                Toast.makeText(this, t("Keputusan telah disimpan ke sejarah.", "Result saved to history."), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.resultBackButton).setOnClickListener { maybeShowInterstitial(); beginScanning() }
        findViewById<Button>(R.id.resultScanAgainButton).setOnClickListener { maybeShowInterstitial(); beginScanning() }
        findViewById<Button>(R.id.bottomScanButton).setOnClickListener { beginScanning() }
        findViewById<Button>(R.id.bottomHistoryButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.bottomSettingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.bottomMoreButton).setOnClickListener { showMoreMenu() }
        scanAgainButton.setOnClickListener { startCamera() }
        configureBannerAds()
        permissionButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                permissionButton.visibility = View.GONE
                startCamera()
            } else if (!prefs.getBoolean("asked_camera", false) || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
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
        findViewById<Button>(R.id.copyButton).text = if (languageMs) "Salin Kandungan" else "Copy Content"
        findViewById<Button>(R.id.shareButton).text = if (languageMs) "Kongsi" else "Share"
        findViewById<Button>(R.id.saveHistoryButton).text = if (languageMs) "Simpan ke Sejarah" else "Save to History"
        findViewById<Button>(R.id.resultScanAgainButton).text = if (languageMs) "Scan Lagi" else "Scan Again"
        findViewById<TextView>(R.id.welcomeSubtitle).text = if (languageMs) "Pengimbas QR & Barcode Pantas" else "Fast QR & Barcode Scanner"
        findViewById<Button>(R.id.startScanButton).apply {
            text = if (languageMs) "Mula Scan" else "Start Scan"
            isAllCaps = false
        }
        findViewById<TextView>(R.id.welcomeTagline).text = if (languageMs) "Imbas Dunia Dengan Lebih Bijak" else "Scan a Smarter World"
    }

    private fun beginScanning() {
        if (isFinishing || isDestroyed) return
        scanningStarted = true
        findViewById<View>(R.id.welcomeScreen).visibility = View.GONE
        findViewById<View>(R.id.resultScreen).visibility = View.GONE
        findViewById<View>(R.id.mainContent).visibility = View.VISIBLE
        findViewById<AdView>(R.id.bannerAd).visibility = if (scannerBannerLoaded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.nativeAdContainer).visibility = View.GONE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionButton.visibility = View.GONE
            preview.post { startCamera() }
        } else {
            prefs.edit().putBoolean("asked_camera", true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupAds() {
        val consentInformation = UserMessagingPlatform.getConsentInformation(this)
        val consentParameters = ConsentRequestParameters.Builder().build()

        // Refresh consent status and display Google's consent form when required
        // before requesting any ads.
        consentInformation.requestConsentInfoUpdate(
            this,
            consentParameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { _ ->
                    initializeAdsIfAllowed(consentInformation.canRequestAds())
                }
            },
            {
                // A previously valid consent choice may still permit ad requests
                // if the consent-information refresh cannot complete.
                initializeAdsIfAllowed(consentInformation.canRequestAds())
            }
        )

        // The SDK permits requests immediately when a valid choice is already stored.
        if (consentInformation.canRequestAds()) {
            initializeAdsIfAllowed(true)
        }
    }

    private fun initializeAdsIfAllowed(canRequestAds: Boolean) {
        if (!canRequestAds || adsInitializationStarted || isFinishing || isDestroyed) return
        adsInitializationStarted = true
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

    private fun configureBannerAds() {
        val scannerBanner = findViewById<AdView>(R.id.bannerAd)
        val resultBanner = findViewById<AdView>(R.id.resultBannerAd)
        scannerBanner.adListener = object : AdListener() {
            override fun onAdLoaded() {
                scannerBannerLoaded = true
                scannerBanner.visibility = if (findViewById<View>(R.id.mainContent).visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                scannerBannerLoaded = false
                scannerBanner.visibility = View.GONE
                android.util.Log.w("QRScannerAds", "Scanner banner failed: ${error.code} ${error.message}")
            }
        }
        resultBanner.adListener = object : AdListener() {
            override fun onAdLoaded() {
                resultBannerLoaded = true
                resultBanner.visibility = if (findViewById<View>(R.id.resultScreen).visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                resultBannerLoaded = false
                resultBanner.visibility = View.GONE
                android.util.Log.w("QRScannerAds", "Result banner failed: ${error.code} ${error.message}")
            }
        }
    }

    private fun loadNativeAd() {
        runCatching {
            val adView = findViewById<NativeAdView>(R.id.nativeAdView)
            val loader = AdLoader.Builder(this, "ca-app-pub-9940728659432865/2572993259")
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
                    nativeAdLoaded = true
                    // Only reserve space for a native ad after a real ad has loaded.
                    val resultScreen = findViewById<View>(R.id.resultScreen)
                    findViewById<View>(R.id.nativeAdContainer).visibility =
                        if (resultScreen.visibility == View.VISIBLE) View.VISIBLE else View.GONE
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
            InterstitialAd.load(this, "ca-app-pub-9940728659432865/6512238262", AdRequest.Builder().build(),
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
            AppOpenAd.load(this, "ca-app-pub-9940728659432865/9951869956", AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) { appOpenLoading = false; appOpenAd = ad }
                    override fun onAdFailedToLoad(e: LoadAdError) { appOpenLoading = false; appOpenAd = null }
                })
        }.onFailure { appOpenLoading = false }
    }

    private fun showAppOpenIfReady() {
        val now = System.currentTimeMillis()
        if (firstLaunch || appOpenShowing || isFinishing || isDestroyed) return
        if (appOpenReturnCount < 3) return
        if (now - lastAppOpenShownAt < 900000L) return
        if (backgroundAt <= 0L || now - backgroundAt < 60000L) return
        val ad = appOpenAd ?: return
        appOpenShowing = true
        lastAppOpenShownAt = now
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
        cameraMessage.text = t("Letak QR atau barcode dalam bingkai", "Place a QR code or barcode inside the frame")
        statusText.text = t("Kamera aktif — sedang mengimbas", "Camera active — scanning")
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
                statusText.text = t("Kamera gagal dimulakan.", "Unable to start camera.")
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
            scansSinceInterstitial++
            prefs.edit()
                .putInt("scan_count", scanCount)
                .putInt("scans_since_interstitial", scansSinceInterstitial)
                .apply()
            feedback()
            statusText.text = "Scan berjaya: $format"
            cameraMessage.text = t("QR/barcode ditemui", "QR/barcode found")
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
            statusText.text = t("Gambar tidak dapat dibuka.", "Unable to open image.")
            Toast.makeText(this, t("Gambar tidak dapat dibuka.", "Unable to open image."), Toast.LENGTH_SHORT).show()
            resumeCameraAfterGalleryFailure()
            return
        }
        scanner.process(input)
            .addOnSuccessListener { codes ->
                val first = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (first == null) {
                    statusText.text = t("Tiada QR/barcode ditemui.", "No QR/barcode found.")
                    Toast.makeText(this, t("Tiada QR/barcode ditemui dalam gambar.", "No QR/barcode found in image."), Toast.LENGTH_SHORT).show()
                    resumeCameraAfterGalleryFailure()
                } else {
                    runOnUiThread { showScanResult(first.rawValue.orEmpty(), formatName(first.format)) }
                }
            }
            .addOnFailureListener {
                statusText.text = t("Gagal mengimbas gambar.", "Failed to scan image.")
                Toast.makeText(this, t("Gambar tidak dapat diimbas.", "Unable to scan image."), Toast.LENGTH_SHORT).show()
                resumeCameraAfterGalleryFailure()
            }
    }

    private fun resumeCameraAfterGalleryFailure() {
        if (!autoScanEnabled || !scanningStarted || isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        preview.post { if (!isFinishing && !isDestroyed) startCamera() }
    }

    private fun showResult(value: String, format: String) {
        if (isFinishing || isDestroyed) return

        findViewById<View>(R.id.mainContent).visibility = View.GONE
        findViewById<View>(R.id.welcomeScreen).visibility = View.GONE
        findViewById<View>(R.id.resultScreen).visibility = View.VISIBLE
        findViewById<View>(R.id.nativeAdContainer).visibility = if (nativeAdLoaded) View.VISIBLE else View.GONE
        findViewById<AdView>(R.id.resultBannerAd).visibility = if (resultBannerLoaded) View.VISIBLE else View.GONE
        findViewById<AdView>(R.id.bannerAd).visibility = View.GONE

        currentResultValue = value
        currentResultFormat = format
        val resultText = findViewById<TextView>(R.id.resultText)
        val resultType = findViewById<TextView>(R.id.resultType)
        val resultInfo = findViewById<TextView>(R.id.resultInfo)
        val open = findViewById<Button>(R.id.openButton)

        val contentType = when {
            isWebUrl(value) -> t("Pautan web", "Web link")
            value.startsWith("mailto:", ignoreCase = true) || value.contains(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)) -> t("E-mel", "Email")
            value.startsWith("tel:", ignoreCase = true) || value.matches(Regex("^\\+?[0-9() .-]{7,}$")) -> t("Nombor telefon", "Phone number")
            value.startsWith("WIFI:", ignoreCase = true) -> t("Maklumat Wi-Fi", "Wi-Fi information")
            value.startsWith("geo:", ignoreCase = true) -> t("Lokasi", "Location")
            else -> t("Teks / data", "Text / data")
        }
        val scannedAt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
        resultText.text = value
        resultType.text = t("HASIL SCAN  •  $format", "SCAN RESULT  •  $format")
        resultInfo.text = t("Jenis: $format\nKandungan: $contentType\nMasa: $scannedAt", "Format: $format\nContent: $contentType\nTime: $scannedAt")
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

        if (autoOpenEnabled && isWebUrl(value))
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && findViewById<View>(R.id.resultScreen).visibility == View.VISIBLE) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                }
            }, 350)

    }

    private fun maybeShowInterstitial() {
        if (scansSinceInterstitial < 5 || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (System.currentTimeMillis() - prefs.getLong("last_interstitial_at", 0L) < 300000L) return

        if (freeScanCredits > 0) {
            freeScanCredits--
            scansSinceInterstitial = 0
            prefs.edit()
                .putInt("free_scan_credits", freeScanCredits)
                .putInt("scans_since_interstitial", scansSinceInterstitial)
                .apply()
            return
        }

        val ad = interstitialAd ?: return
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { loadInterstitial() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { loadInterstitial() }
        }
        runCatching {
            ad.show(this)
            scansSinceInterstitial = 0
            prefs.edit().putInt("scans_since_interstitial", scansSinceInterstitial).putLong("last_interstitial_at", System.currentTimeMillis()).apply()
        }.onFailure {
            interstitialAd = null
            loadInterstitial()
        }
    }

    private fun loadRewardedAd() {
        if (rewardedLoading || rewardedAd != null) return
        rewardedLoading = true
        runCatching {
            RewardedAd.load(
                this,
                "ca-app-pub-9940728659432865/7753257039",
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
                freeScanCredits += 1
                prefs.edit().putInt("free_scan_credits", freeScanCredits).apply()
                Toast.makeText(
                    this,
                    t("Ganjaran diterima: +1 free scan. Baki: $freeScanCredits", "Reward received: +1 free scan. Balance: $freeScanCredits"),
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
        val c = camera ?: return Toast.makeText(this, t("Mula kamera dahulu.", "Start the camera first."), Toast.LENGTH_SHORT).show()
        if (!c.cameraInfo.hasFlashUnit()) return Toast.makeText(this, t("Telefon ini tiada flashlight kamera.", "This phone has no camera flash."), Toast.LENGTH_SHORT).show()
        torchOn = !torchOn
        runCatching { c.cameraControl.enableTorch(torchOn) }
        findViewById<Button>(R.id.flashButton).text = if (torchOn) "Flash ON" else "Flash"
    }

    private fun showQuickMenu() {
        val choices = arrayOf(
            t("Mula Scan", "Start Scanning"),
            t("Pilih gambar dari galeri", "Choose image from Gallery"),
            t("Sejarah scan", "Scan History"),
            t("Tetapan", "Settings"),
            t("Tonton iklan untuk +1 scan percuma", "Watch ad for +1 free scan"),
            t("Privasi & Tentang", "Privacy & About")
        )
        AlertDialog.Builder(this)
            .setTitle(t("Menu QR Scanner", "QR Scanner Menu"))
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> beginScanning()
                    1 -> pickImage.launch("image/*")
                    2 -> showHistory()
                    3 -> showSettings()
                    4 -> showRewardedAd()
                    5 -> showPrivacyPolicy()
                }
            }
            .setNegativeButton(t("Tutup", "Close"), null)
            .show()
    }

    private fun showMoreMenu() {
        val choices = arrayOf(t("Galeri", "Gallery"), t("Flash", "Flash"), t("Kamera depan / belakang", "Front / rear camera"), t("Tonton iklan & dapatkan +1 free scan", "Watch ad & get +1 free scan"), t("Scan Lagi", "Scan Again"))
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

        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(t("Tetapan Scanner", "Scanner Settings"))
            .setView(settingsScroll)
            .setPositiveButton(t("Selesai", "Done"), null)
            .setNeutralButton(t("Privasi & Tentang", "Privacy & About")) { _, _ -> showPrivacyPolicy() }
            .show()
    }

    private fun showPrivacyPolicy() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("Privasi & Polisi", "Privacy & Policy"))
            .setMessage(t(
                "QR Scanner menggunakan kamera hanya untuk scan. Sejarah scan disimpan pada peranti. Google AdMob memaparkan iklan dan Google/rakan pengiklan mungkin memproses data peranti serta interaksi iklan mengikut tetapan dan polisi mereka. Jangan imbas atau kongsi maklumat sulit.",
                "QR Scanner uses the camera only for scanning. Scan history is stored on your device. Google AdMob displays ads, and Google/advertising partners may process device and ad-interaction data according to their settings and policies. Avoid scanning or sharing sensitive information."
            ))
            .setNegativeButton(t("Tutup", "Close"), null)
            .setPositiveButton(t("Buka Dasar Privasi", "Open Privacy Policy")) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EarnPlayApps/QR-Scanner/blob/main/PRIVACY_POLICY.md"))) }
            }
        val consentInformation = UserMessagingPlatform.getConsentInformation(this)
        if (consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) {
            dialog.setNeutralButton(t("Pilihan privasi iklan", "Ad privacy choices")) { _, _ ->
                UserMessagingPlatform.showPrivacyOptionsForm(this) { _ ->
                    if (consentInformation.canRequestAds()) {
                        // Ad requests remain controlled by the UMP consent status.
                    }
                }
            }
        }
        dialog.show()
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
        val returningFromBackground = !firstLaunch && backgroundAt > 0L
        if (returningFromBackground) {
            appOpenReturnCount++
            showAppOpenIfReady()
        }
        firstLaunch = false
        if (::preview.isInitialized &&
            findViewById<View>(R.id.resultScreen).visibility != View.VISIBLE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            scanningStarted && autoScanEnabled && provider == null) {
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
        if (scannerDelegate.isInitialized()) runCatching { scanner.close() }
        executor.shutdown()
        super.onDestroy()
    }
}
