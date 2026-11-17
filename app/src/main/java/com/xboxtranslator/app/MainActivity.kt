package com.xboxtranslator.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.xboxtranslator.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding

    private val uiVisibilityHandler = Handler(Looper.getMainLooper())
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ocrScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    enum class VoiceGenderMode {
        AUTO_AI,
        FEMALE,
        MALE
    }

    enum class TtsQueueMode {
        QUEUE_ALL,
        FLUSH_IMMEDIATE,
        SMART_QUEUE
    }

    data class SubtitleHistoryItem(
        val english: String,
        val thai: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var tts: TextToSpeech? = null
    private var isTtsEnabled = false
    private var voiceGenderMode = VoiceGenderMode.AUTO_AI
    private var ttsQueueMode = TtsQueueMode.SMART_QUEUE
    private val activeUtterances = Collections.synchronizedSet(mutableSetOf<String>())
    private var isSubtitleEnabled = true
    private var isAudioMuted = false
    private var isTextOnlyMode = false
    private var isCapturing = false
    private var isTranslating = false
    private var pendingOcrEnglish = ""
    private var lastTranslatedEnglish = ""
    private var lastTranslatedThai = ""
    private var lastSpokenThai = ""
    private var lastSpokenTimeMs = 0L

    private lateinit var cropPrefs: SharedPreferences
    private var isFilterShortTextEnabled = true
    private var speechSpeedRate = 1.25f

    private val subtitleHistory = mutableListOf<SubtitleHistoryItem>()
    private var maxHistoryLines = 3
    private var currentSubTextColor = Color.parseColor("#00FF88")
    private var currentSubFontSize = 26
    private var currentSubPosition = "CENTER"
    private var currentSubBgOpacity = 80

    // Gemini API settings
    private var geminiApiKey = ""
    private var isGeminiTranslateEnabled = true
    private var isGeminiTtsEnabled = true
    private var geminiMediaPlayer: MediaPlayer? = null
    private val geminiAudioQueue = java.util.ArrayDeque<java.io.File>()

    // On-demand Note/Tutorial Crop Box & Top Quick Drawer
    private var isEditingNoteCrop = false
    private var isScanningNote = false
    private var lastNoteThai = ""
    private var lastNoteEnglish = ""

    private val hideNoteCardRunnable = Runnable {
        if (::binding.isInitialized && !isDestroyed && !isFinishing) {
            hideNoteTranslationCard()
        }
    }

    private val hideTopDrawerRunnable = Runnable {
        if (::binding.isInitialized && !isDestroyed && !isFinishing) {
            closeTopQuickDrawer()
        }
    }

    private var betterXcloudScript: String? = null

    private val hideMenuButtonRunnable = Runnable {
        if (::binding.isInitialized && !binding.menuPanelContainer.isVisible) {
            binding.btnMenuToggle.animate().alpha(0.35f).setDuration(300).start()
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isSubtitleEnabled || isTtsEnabled) {
                captureAndTranslateFrame()
            }
            uiVisibilityHandler.postDelayed(this, 1500)
        }
    }

    private lateinit var audioManager: AudioManager
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var audioModeListener: Any? = null

    companion object {
        private const val REQ_BLUETOOTH_CONNECT = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        volumeControlStream = AudioManager.STREAM_MUSIC

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAudioRouting()
        tts = TextToSpeech(this, this)

        cropPrefs = getSharedPreferences("xbox_translator_crop", Context.MODE_PRIVATE)
        isFilterShortTextEnabled = cropPrefs.getBoolean("filter_short_ui", true)
        binding.filterShortTextSwitch.isChecked = isFilterShortTextEnabled

        setupXboxWebView()
        prefetchBetterXcloudScript()
        setupMenuControls()
        setupSubtitleStylingControls()
        setupVoiceControls()
        setupModeControls()
        setupCropEditor()

        uiVisibilityHandler.postDelayed(captureRunnable, 2000)
        resetMenuButtonTimer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupXboxWebView() {
        val webView = binding.xboxWebView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Standard Chrome Mobile/Desktop compatible UA for Xbox Cloud & Remote Play
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.isVisible = false
                if (url != null && url.contains("xbox.com")) {
                    injectBetterXcloud(view)
                    applyWebViewMuteState()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        webView.loadUrl("https://www.xbox.com/play/consoles")
    }

    private fun prefetchBetterXcloudScript() {
        val cacheFile = File(filesDir, "better-xcloud.min.js")
        if (cacheFile.exists() && cacheFile.length() > 1024) {
            betterXcloudScript = cacheFile.readText()
        }
        ocrScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://cdn.jsdelivr.net/gh/redphx/better-xcloud@latest/dist/better-xcloud.min.js")
                val conn = url.openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val script = conn.getInputStream().bufferedReader().use { it.readText() }
                if (script.length > 1024) {
                    cacheFile.writeText(script)
                    betterXcloudScript = script
                }
            } catch (_: Exception) {
                // Keep cached copy if offline or CDN unreachable
            }
        }
    }

    private fun injectBetterXcloud(webView: WebView?) {
        val script = betterXcloudScript ?: return
        val wrapped = """
            (function() {
                if (window.__bxInjected) return;
                window.__bxInjected = true;
                try {
                    $script
                } catch (e) { console.error('Better xCloud inject error:', e); }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(wrapped, null)
    }

    private fun applyWebViewMuteState() {
        val muted = isAudioMuted
        val js = """
            (function() {
                window.__xboxTranslatorMuted = $muted;
                document.querySelectorAll('video, audio').forEach(function(el) {
                    el.muted = $muted;
                });
                if (!window.__xboxMuteObserver) {
                    window.__xboxMuteObserver = new MutationObserver(function() {
                        document.querySelectorAll('video, audio').forEach(function(el) {
                            el.muted = !!window.__xboxTranslatorMuted;
                        });
                    });
                    window.__xboxMuteObserver.observe(document.documentElement, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
        binding.xboxWebView.evaluateJavascript(js, null)
    }

    private fun setupMenuControls() {
        binding.btnMenuToggle.setOnClickListener {
            if (!binding.menuPanelContainer.isVisible) {
                openSettingsSheet()
            } else {
                closeSettingsSheet()
            }
        }

        binding.btnCloseMenu.setOnClickListener {
            closeSettingsSheet()
        }

        binding.menuPanelContainer.setOnClickListener {
            closeSettingsSheet()
        }

        setupLiquidGlassMotion()

        binding.btnMenuBack.setOnClickListener {
            showMenuPage("MAIN")
        }
        binding.btnOpenSubText.setOnClickListener {
            showMenuPage("TEXT")
        }
        binding.btnOpenSubVoice.setOnClickListener {
            showMenuPage("VOICE")
        }
        binding.btnOpenSubMode.setOnClickListener {
            showMenuPage("MODE")
        }
        binding.btnOpenSubApi.setOnClickListener {
            showMenuPage("API")
        }

        setupGeminiApiControls()
        setupTopQuickDrawer()
        setupNoteTranslationFeature()

        // Subtitle overlay switch (persisted)
        isSubtitleEnabled = cropPrefs.getBoolean("sub_overlay_enabled", true)
        binding.subOverlaySwitch.isChecked = isSubtitleEnabled
        binding.subOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            isSubtitleEnabled = isChecked
            cropPrefs.edit().putBoolean("sub_overlay_enabled", isChecked).apply()
            if (!isChecked) {
                binding.subtitleOverlayContainer.isVisible = false
                if (isTextOnlyMode) toggleTextOnlyMode(false)
            } else if (subtitleHistory.isNotEmpty() && !isTextOnlyMode) {
                binding.subtitleOverlayContainer.isVisible = true
            }
            updateQuickDrawerUI()
        }
    }

    private fun setupVoiceControls() {
        // TTS voice switch (persisted)
        isTtsEnabled = cropPrefs.getBoolean("tts_voice_enabled", false)
        binding.ttsVoiceSwitch.isChecked = isTtsEnabled
        binding.voiceGenderContainer.isVisible = isTtsEnabled
        binding.speechSpeedContainer.isVisible = isTtsEnabled
        binding.ttsQueueModeContainer.isVisible = isTtsEnabled
        binding.ttsVoiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            isTtsEnabled = isChecked
            cropPrefs.edit().putBoolean("tts_voice_enabled", isChecked).apply()
            binding.voiceGenderContainer.isVisible = isChecked
            binding.speechSpeedContainer.isVisible = isChecked
            binding.ttsQueueModeContainer.isVisible = isChecked
            if (!isChecked) {
                activeUtterances.clear()
                tts?.stop()
                stopGeminiAudioPlayback()
            }
            updateQuickDrawerUI()
        }

        val savedVoiceMode = cropPrefs.getString("voice_gender_mode", VoiceGenderMode.AUTO_AI.name)
        voiceGenderMode = try { VoiceGenderMode.valueOf(savedVoiceMode ?: VoiceGenderMode.AUTO_AI.name) } catch (_: Exception) { VoiceGenderMode.AUTO_AI }
        when (voiceGenderMode) {
            VoiceGenderMode.AUTO_AI -> binding.voiceGenderToggleGroup.check(R.id.btnVoiceAuto)
            VoiceGenderMode.FEMALE -> binding.voiceGenderToggleGroup.check(R.id.btnVoiceFemale)
            VoiceGenderMode.MALE -> binding.voiceGenderToggleGroup.check(R.id.btnVoiceMale)
        }

        binding.voiceGenderToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                voiceGenderMode = when (checkedId) {
                    R.id.btnVoiceFemale -> VoiceGenderMode.FEMALE
                    R.id.btnVoiceMale -> VoiceGenderMode.MALE
                    else -> VoiceGenderMode.AUTO_AI
                }
                cropPrefs.edit().putString("voice_gender_mode", voiceGenderMode.name).apply()
            }
        }

        speechSpeedRate = cropPrefs.getFloat("speech_speed_rate", 1.25f)
        when {
            speechSpeedRate <= 1.05f -> binding.speechSpeedToggleGroup.check(R.id.btnSpeedNormal)
            speechSpeedRate <= 1.35f -> binding.speechSpeedToggleGroup.check(R.id.btnSpeedFast)
            else -> binding.speechSpeedToggleGroup.check(R.id.btnSpeedVeryFast)
        }

        binding.speechSpeedToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                speechSpeedRate = when (checkedId) {
                    R.id.btnSpeedNormal -> 1.0f
                    R.id.btnSpeedFast -> 1.25f
                    else -> 1.50f
                }
                cropPrefs.edit().putFloat("speech_speed_rate", speechSpeedRate).apply()
            }
        }

        val savedQueueMode = cropPrefs.getString("tts_queue_mode", TtsQueueMode.SMART_QUEUE.name)
        ttsQueueMode = try { TtsQueueMode.valueOf(savedQueueMode ?: TtsQueueMode.SMART_QUEUE.name) } catch (_: Exception) { TtsQueueMode.SMART_QUEUE }
        when (ttsQueueMode) {
            TtsQueueMode.QUEUE_ALL -> binding.ttsQueueModeToggleGroup.check(R.id.btnQueueAdd)
            TtsQueueMode.FLUSH_IMMEDIATE -> binding.ttsQueueModeToggleGroup.check(R.id.btnQueueFlush)
            TtsQueueMode.SMART_QUEUE -> binding.ttsQueueModeToggleGroup.check(R.id.btnQueueSmart)
        }

        binding.ttsQueueModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                ttsQueueMode = when (checkedId) {
                    R.id.btnQueueAdd -> TtsQueueMode.QUEUE_ALL
                    R.id.btnQueueFlush -> TtsQueueMode.FLUSH_IMMEDIATE
                    else -> TtsQueueMode.SMART_QUEUE
                }
                cropPrefs.edit().putString("tts_queue_mode", ttsQueueMode.name).apply()
            }
        }

        binding.btnMuteAudio.setOnClickListener {
            isAudioMuted = !isAudioMuted
            applyWebViewMuteState()
            if (isAudioMuted) {
                binding.btnMuteAudio.text = "เปิดเสียงเกม (กำลังปิดเสียงเกมอยู่)"
                binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_off)
                Toast.makeText(this, "ปิดเสียงเกมแล้ว (ยังคงพากย์เสียงไทยตามปกติ)", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnMuteAudio.text = "ปิดเสียงเกมจากมือถือ"
                binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_up)
                Toast.makeText(this, "เปิดเสียงเกมปกติแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupModeControls() {
        binding.btnAdjustCropBox.setOnClickListener {
            binding.menuPanelContainer.isVisible = false
            isEditingNoteCrop = false
            openCropEditor()
        }

        binding.btnAdjustNoteCropBox.setOnClickListener {
            binding.menuPanelContainer.isVisible = false
            isEditingNoteCrop = true
            openCropEditor()
        }

        binding.filterShortTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            isFilterShortTextEnabled = isChecked
            cropPrefs.edit().putBoolean("filter_short_ui", isChecked).apply()
        }

        binding.btnTextOnlyMode.setOnClickListener {
            toggleTextOnlyMode(!isTextOnlyMode)
            binding.menuPanelContainer.isVisible = false
            resetMenuButtonTimer()
        }

        binding.pureFullscreenLayout.setOnClickListener {
            toggleTextOnlyMode(false)
        }

        binding.btnReloadXbox.setOnClickListener {
            binding.menuPanelContainer.isVisible = false
            resetMenuButtonTimer()
            binding.xboxWebView.loadUrl("https://www.xbox.com/play/consoles")
            Toast.makeText(this, "กำลังรีโหลดหน้า Xbox...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.language = Locale("th", "TH")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) activeUtterances.remove(utteranceId)
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null) activeUtterances.remove(utteranceId)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId != null) activeUtterances.remove(utteranceId)
                }
            })
        }
    }

    private fun captureAndTranslateFrame() {
        if (isCapturing || isScanningNote || isDestroyed || isFinishing) return
        val webView = binding.xboxWebView
        if (webView.width <= 0 || webView.height <= 0) return

        val cropLeftNorm = cropPrefs.getFloat("crop_left", 0.18f).coerceIn(0f, 0.85f)
        val cropTopNorm = cropPrefs.getFloat("crop_top", 0.72f).coerceIn(0f, 0.90f)
        val cropWidthNorm = cropPrefs.getFloat("crop_width", 0.64f).coerceIn(0.10f, 1f - cropLeftNorm)
        val cropHeightNorm = cropPrefs.getFloat("crop_height", 0.22f).coerceIn(0.06f, 1f - cropTopNorm)

        isCapturing = true

        // Capture directly from the underlying HTML5 <video> element so Android overlays
        // (such as Pure Text Only black screen or subtitle box) never block or pollute OCR.
        val js = """
            (function() {
                try {
                    var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                    var video = null;
                    var bestArea = 0;
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.videoWidth > 0 && v.videoHeight > 0 && !v.paused && !v.ended && v.readyState >= 2) {
                            var area = v.videoWidth * v.videoHeight;
                            if (area > bestArea) { bestArea = area; video = v; }
                        }
                    }
                    if (!video) return '';

                    var rect = video.getBoundingClientRect();
                    var winW = window.innerWidth || document.documentElement.clientWidth;
                    var winH = window.innerHeight || document.documentElement.clientHeight;
                    if (!rect.width || !rect.height || !winW || !winH) return '';

                    var elemRatio = rect.width / rect.height;
                    var vidRatio = video.videoWidth / video.videoHeight;
                    var renderW = rect.width, renderH = rect.height, renderX = rect.left, renderY = rect.top;
                    if (vidRatio > elemRatio) {
                        renderH = rect.width / vidRatio;
                        renderY = rect.top + (rect.height - renderH) / 2;
                    } else if (vidRatio < elemRatio) {
                        renderW = rect.height * vidRatio;
                        renderX = rect.left + (rect.width - renderW) / 2;
                    }

                    var boxX = $cropLeftNorm * winW;
                    var boxY = $cropTopNorm * winH;
                    var boxW = $cropWidthNorm * winW;
                    var boxH = $cropHeightNorm * winH;

                    var relX = (boxX - renderX) / renderW;
                    var relY = (boxY - renderY) / renderH;
                    var relW = boxW / renderW;
                    var relH = boxH / renderH;

                    var sx = Math.max(0, Math.min(video.videoWidth - 1, Math.floor(relX * video.videoWidth)));
                    var sy = Math.max(0, Math.min(video.videoHeight - 1, Math.floor(relY * video.videoHeight)));
                    var sw = Math.max(20, Math.min(video.videoWidth - sx, Math.floor(relW * video.videoWidth)));
                    var sh = Math.max(16, Math.min(video.videoHeight - sy, Math.floor(relH * video.videoHeight)));

                    var scale = Math.min(2, Math.max(1, Math.floor(1400 / sw)));
                    var canvas = document.createElement('canvas');
                    canvas.width = sw * scale;
                    canvas.height = sh * scale;
                    var ctx = canvas.getContext('2d');
                    ctx.drawImage(video, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
                    return canvas.toDataURL('image/jpeg', 0.88);
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { rawResult ->
            val cleaned = rawResult?.trim()?.removeSurrounding("\"") ?: ""
            val base64Idx = cleaned.indexOf("base64,")
            if (base64Idx >= 0) {
                try {
                    val b64 = cleaned.substring(base64Idx + 7)
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        processCroppedBitmap(bitmap)
                        return@evaluateJavascript
                    }
                } catch (_: Exception) {
                    // Fall through to PixelCopy fallback if allowed
                }
            }

            // Fallback to PixelCopy only when no opaque overlay (black screen / menu) is covering the WebView
            if (binding.pureFullscreenLayout.isVisible || binding.menuPanelContainer.isVisible) {
                isCapturing = false
                return@evaluateJavascript
            }
            captureViaPixelCopy(webView, cropLeftNorm, cropTopNorm, cropWidthNorm, cropHeightNorm)
        }
    }

    private fun captureViaPixelCopy(
        webView: WebView,
        cropLeftNorm: Float,
        cropTopNorm: Float,
        cropWidthNorm: Float,
        cropHeightNorm: Float
    ) {
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + webView.width, location[1] + webView.height)
        if (rect.width() <= 0 || rect.height() <= 0) {
            isCapturing = false
            return
        }

        val fullBitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, rect, fullBitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    try {
                        val cropX = (fullBitmap.width * cropLeftNorm).toInt().coerceIn(0, fullBitmap.width - 1)
                        val cropY = (fullBitmap.height * cropTopNorm).toInt().coerceIn(0, fullBitmap.height - 1)
                        val cropW = (fullBitmap.width * cropWidthNorm).toInt().coerceIn(40, fullBitmap.width - cropX)
                        val cropH = (fullBitmap.height * cropHeightNorm).toInt().coerceIn(30, fullBitmap.height - cropY)

                        val subBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                        val scaledSub = Bitmap.createScaledBitmap(subBitmap, cropW * 2, cropH * 2, true)
                        fullBitmap.recycle()
                        subBitmap.recycle()
                        processCroppedBitmap(scaledSub)
                    } catch (_: Exception) {
                        isCapturing = false
                        fullBitmap.recycle()
                    }
                } else {
                    isCapturing = false
                    fullBitmap.recycle()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            isCapturing = false
            fullBitmap.recycle()
        }
    }

    private fun processCroppedBitmap(bitmap: Bitmap) {
        isCapturing = false
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text.trim().replace("\n", " ")
                val fixedText = fixOcrText(rawText)
                val text = fixedText.replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty() && isDialogueSubtitle(text)) {
                    val isDuplicateOfPending = isTranslating && isSimilarText(text, pendingOcrEnglish)
                    val isDuplicateOfLast = isSimilarText(text, lastTranslatedEnglish)
                    val isDuplicateOfRecent = subtitleHistory.take(2).any { isSimilarText(text, it.english) }

                    if (!isTranslating && !isDuplicateOfPending && !isDuplicateOfLast && !isDuplicateOfRecent) {
                        isTranslating = true
                        pendingOcrEnglish = text
                        ocrScope.launch {
                            try {
                                val thai = translateToThai(text)
                                val isDuplicateThai = isSimilarThai(thai, lastTranslatedThai) ||
                                    subtitleHistory.take(2).any { isSimilarThai(thai, it.thai) }
                                if (!isDuplicateThai) {
                                    lastTranslatedEnglish = text
                                    lastTranslatedThai = thai
                                    subtitleHistory.add(0, SubtitleHistoryItem(text, thai))
                                    while (subtitleHistory.size > maxHistoryLines) {
                                        subtitleHistory.removeAt(subtitleHistory.size - 1)
                                    }
                                    renderSubtitles()
                                    if (isTtsEnabled) {
                                        speakTranslation(text, thai)
                                    }
                                }
                            } finally {
                                isTranslating = false
                            }
                        }
                    }
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }
    }

    private fun setupSubtitleStylingControls() {
        currentSubTextColor = try {
            Color.parseColor(cropPrefs.getString("sub_text_color", "#00FF88") ?: "#00FF88")
        } catch (_: Exception) {
            Color.parseColor("#00FF88")
        }
        currentSubFontSize = cropPrefs.getInt("sub_font_size_sp", 26).coerceIn(16, 42)
        currentSubPosition = cropPrefs.getString("sub_position", "CENTER") ?: "CENTER"
        currentSubBgOpacity = cropPrefs.getInt("sub_bg_opacity", 80).coerceIn(0, 100)
        maxHistoryLines = cropPrefs.getInt("sub_history_limit", 3).coerceIn(1, 3)

        if (currentSubPosition == "BOTTOM") {
            binding.positionToggleGroup.check(R.id.btnPosBottom)
        } else {
            binding.positionToggleGroup.check(R.id.btnPosCenter)
        }
        binding.positionToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSubPosition = if (checkedId == R.id.btnPosBottom) "BOTTOM" else "CENTER"
                cropPrefs.edit().putString("sub_position", currentSubPosition).apply()
                applySubtitleStyling()
            }
        }

        when (cropPrefs.getString("sub_text_color", "#00FF88")) {
            "#FFFFFF" -> binding.colorToggleGroup.check(R.id.btnColorWhite)
            "#00E5FF" -> binding.colorToggleGroup.check(R.id.btnColorCyan)
            "#FFD700" -> binding.colorToggleGroup.check(R.id.btnColorYellow)
            "#FF9100" -> binding.colorToggleGroup.check(R.id.btnColorOrange)
            "#FF4081" -> binding.colorToggleGroup.check(R.id.btnColorPink)
            else -> binding.colorToggleGroup.check(R.id.btnColorGreen)
        }
        binding.colorToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val hexColor = when (checkedId) {
                    R.id.btnColorWhite -> "#FFFFFF"
                    R.id.btnColorCyan -> "#00E5FF"
                    R.id.btnColorYellow -> "#FFD700"
                    R.id.btnColorOrange -> "#FF9100"
                    R.id.btnColorPink -> "#FF4081"
                    else -> "#00FF88"
                }
                currentSubTextColor = Color.parseColor(hexColor)
                cropPrefs.edit().putString("sub_text_color", hexColor).apply()
                applySubtitleStyling()
            }
        }

        syncFontSizeToggleUI()
        binding.fontSizeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSubFontSize = when (checkedId) {
                    R.id.btnSizeSmall -> 18
                    R.id.btnSizeMedium -> 22
                    R.id.btnSizeXLarge -> 34
                    R.id.btnSizeXXLarge -> 40
                    else -> 26
                }
                cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
                syncFontSizeToggleUI()
                applySubtitleStyling()
            }
        }
        binding.btnFontDecrease.setOnClickListener {
            currentSubFontSize = (currentSubFontSize - 2).coerceIn(16, 42)
            cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
            syncFontSizeToggleUI()
            applySubtitleStyling()
        }
        binding.btnFontIncrease.setOnClickListener {
            currentSubFontSize = (currentSubFontSize + 2).coerceIn(16, 42)
            cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
            syncFontSizeToggleUI()
            applySubtitleStyling()
        }

        when (currentSubBgOpacity) {
            0 -> binding.bgOpacityToggleGroup.check(R.id.btnOpacityTransparent)
            40 -> binding.bgOpacityToggleGroup.check(R.id.btnOpacityGlass)
            100 -> binding.bgOpacityToggleGroup.check(R.id.btnOpacitySolid)
            else -> binding.bgOpacityToggleGroup.check(R.id.btnOpacityDark)
        }
        binding.bgOpacityToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSubBgOpacity = when (checkedId) {
                    R.id.btnOpacityTransparent -> 0
                    R.id.btnOpacityGlass -> 40
                    R.id.btnOpacitySolid -> 100
                    else -> 80
                }
                cropPrefs.edit().putInt("sub_bg_opacity", currentSubBgOpacity).apply()
                applySubtitleStyling()
            }
        }

        when (maxHistoryLines) {
            1 -> binding.historyLinesToggleGroup.check(R.id.btnHistory1)
            2 -> binding.historyLinesToggleGroup.check(R.id.btnHistory2)
            else -> binding.historyLinesToggleGroup.check(R.id.btnHistory3)
        }
        binding.historyLinesToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                maxHistoryLines = when (checkedId) {
                    R.id.btnHistory1 -> 1
                    R.id.btnHistory2 -> 2
                    else -> 3
                }
                cropPrefs.edit().putInt("sub_history_limit", maxHistoryLines).apply()
                while (subtitleHistory.size > maxHistoryLines) {
                    subtitleHistory.removeAt(subtitleHistory.size - 1)
                }
                renderSubtitles()
            }
        }

        applySubtitleStyling()
    }

    private fun syncFontSizeToggleUI() {
        binding.tvCurrentFontSize.text = "${currentSubFontSize}sp"
        when (currentSubFontSize) {
            18 -> binding.fontSizeToggleGroup.check(R.id.btnSizeSmall)
            22 -> binding.fontSizeToggleGroup.check(R.id.btnSizeMedium)
            26 -> binding.fontSizeToggleGroup.check(R.id.btnSizeLarge)
            34 -> binding.fontSizeToggleGroup.check(R.id.btnSizeXLarge)
            40 -> binding.fontSizeToggleGroup.check(R.id.btnSizeXXLarge)
            else -> binding.fontSizeToggleGroup.clearChecked()
        }
    }

    private fun applySubtitleStyling() {
        val lp = binding.subtitleOverlayContainer.layoutParams as android.widget.FrameLayout.LayoutParams
        if (currentSubPosition == "BOTTOM") {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dpToPx(24)
            lp.topMargin = 0
        } else {
            lp.gravity = Gravity.CENTER
            lp.bottomMargin = 0
            lp.topMargin = 0
        }
        binding.subtitleOverlayContainer.layoutParams = lp

        val alphaInt = (currentSubBgOpacity * 255 / 100).coerceIn(0, 255)
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.argb(alphaInt, 6, 8, 16))
            cornerRadius = dpToPx(12).toFloat()
            if (currentSubBgOpacity > 0) {
                setStroke(dpToPx(1), Color.argb(60, 255, 255, 255))
            }
        }
        binding.subtitleOverlayContainer.background = bgDrawable

        binding.translatedSubText.setTextColor(currentSubTextColor)
        binding.translatedSubText.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSubFontSize.toFloat())
        binding.translatedSubText1.setTextColor(currentSubTextColor)
        binding.translatedSubText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.85f).coerceAtLeast(14f))
        binding.translatedSubText2.setTextColor(currentSubTextColor)
        binding.translatedSubText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.78f).coerceAtLeast(13f))

        val fullSize = (currentSubFontSize + 4).toFloat()
        binding.pureFullscreenText.setTextColor(currentSubTextColor)
        binding.pureFullscreenText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fullSize)
        binding.pureFullscreenText1.setTextColor(currentSubTextColor)
        binding.pureFullscreenText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fullSize * 0.85f).coerceAtLeast(16f))
        binding.pureFullscreenText2.setTextColor(currentSubTextColor)
        binding.pureFullscreenText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fullSize * 0.72f).coerceAtLeast(14f))

        val previewBg = GradientDrawable().apply {
            setColor(Color.argb(alphaInt, 6, 8, 16))
            cornerRadius = dpToPx(8).toFloat()
            setStroke(dpToPx(1), Color.argb(60, 255, 255, 255))
        }
        binding.previewSubContainer.background = previewBg
        binding.previewSubNew.setTextColor(currentSubTextColor)
        binding.previewSubNew.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.7f).coerceIn(13f, 22f))
        binding.previewSubOld.setTextColor(currentSubTextColor)
        binding.previewSubOld.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.58f).coerceIn(11f, 18f))

        renderSubtitles()
    }

    private fun showMenuPage(page: String) {
        binding.menuMainPage.isVisible = (page == "MAIN")
        binding.menuSubTextPage.isVisible = (page == "TEXT")
        binding.menuSubVoicePage.isVisible = (page == "VOICE")
        binding.menuSubModePage.isVisible = (page == "MODE")
        binding.menuSubApiPage.isVisible = (page == "API")
        binding.btnMenuBack.isVisible = (page != "MAIN")
        binding.tvMenuTitle.text = when (page) {
            "TEXT" -> "ปรับแต่งตัวหนังสือ"
            "VOICE" -> "การออกเสียงพากย์"
            "MODE" -> "โหมดและการแสดงผล"
            "API" -> "ตั้งค่า Gemini API"
            else -> "ตั้งค่า XB Translator"
        }
    }

    private fun setupGeminiApiControls() {
        geminiApiKey = cropPrefs.getString("gemini_api_key", "")?.trim() ?: ""
        isGeminiTranslateEnabled = cropPrefs.getBoolean("gemini_translate_enabled", true)
        isGeminiTtsEnabled = cropPrefs.getBoolean("gemini_tts_enabled", true)

        binding.etGeminiApiKey.setText(geminiApiKey)
        binding.useGeminiTranslateSwitch.isChecked = isGeminiTranslateEnabled
        binding.useGeminiTtsSwitch.isChecked = isGeminiTtsEnabled
        updateGeminiApiStatusUI()

        binding.btnSaveGeminiApiKey.setOnClickListener {
            val key = binding.etGeminiApiKey.text?.toString()?.trim() ?: ""
            geminiApiKey = key
            cropPrefs.edit().putString("gemini_api_key", key).apply()
            updateGeminiApiStatusUI()
            if (key.isNotEmpty()) {
                Toast.makeText(this, "บันทึก Gemini API Key เรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "ยังไม่ได้กรอก API Key (ใช้ระบบฟรีเดิม)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearGeminiApiKey.setOnClickListener {
            geminiApiKey = ""
            binding.etGeminiApiKey.setText("")
            cropPrefs.edit().remove("gemini_api_key").apply()
            updateGeminiApiStatusUI()
            Toast.makeText(this, "ล้าง API Key แล้ว (กลับไปใช้ระบบฟรีเดิม)", Toast.LENGTH_SHORT).show()
        }

        binding.useGeminiTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            isGeminiTranslateEnabled = isChecked
            cropPrefs.edit().putBoolean("gemini_translate_enabled", isChecked).apply()
        }

        binding.useGeminiTtsSwitch.setOnCheckedChangeListener { _, isChecked ->
            isGeminiTtsEnabled = isChecked
            cropPrefs.edit().putBoolean("gemini_tts_enabled", isChecked).apply()
            if (!isChecked) {
                stopGeminiAudioPlayback()
            }
        }
    }

    private fun updateGeminiApiStatusUI() {
        if (geminiApiKey.isNotEmpty()) {
            binding.tvGeminiApiStatus.text = "สถานะ: เชื่อมต่อ Gemini API แล้ว (พร้อมใช้งาน)"
            binding.tvGeminiApiStatus.setTextColor(Color.parseColor("#00E676"))
        } else {
            binding.tvGeminiApiStatus.text = "สถานะ: ใช้ระบบแปลฟรี + TTS ในเครื่อง"
            binding.tvGeminiApiStatus.setTextColor(Color.parseColor("#AAAAAA"))
        }
    }

    private fun renderSubtitles() {
        if (subtitleHistory.isEmpty()) {
            binding.subtitleOverlayContainer.isVisible = false
            return
        }

        val item0 = subtitleHistory.getOrNull(0)
        if (item0 != null) {
            binding.originalSubText.text = item0.english
            binding.translatedSubText.text = item0.thai
            binding.layoutSubItem0.isVisible = true
            binding.layoutSubItem0.alpha = 1.0f
            binding.pureFullscreenText.text = item0.thai
            binding.pureFullscreenText.alpha = 1.0f
            binding.pureFullscreenText.isVisible = true
        } else {
            binding.layoutSubItem0.isVisible = false
        }

        val item1 = subtitleHistory.getOrNull(1)
        if (item1 != null && maxHistoryLines >= 2) {
            binding.translatedSubText1.text = item1.thai
            binding.translatedSubText1.alpha = 0.65f
            binding.translatedSubText1.isVisible = true
            binding.pureFullscreenText1.text = item1.thai
            binding.pureFullscreenText1.alpha = 0.55f
            binding.pureFullscreenText1.isVisible = true
        } else {
            binding.translatedSubText1.isVisible = false
            binding.pureFullscreenText1.isVisible = false
        }

        val item2 = subtitleHistory.getOrNull(2)
        if (item2 != null && maxHistoryLines >= 3) {
            binding.translatedSubText2.text = item2.thai
            binding.translatedSubText2.alpha = 0.35f
            binding.translatedSubText2.isVisible = true
            binding.pureFullscreenText2.text = item2.thai
            binding.pureFullscreenText2.alpha = 0.25f
            binding.pureFullscreenText2.isVisible = true
        } else {
            binding.translatedSubText2.isVisible = false
            binding.pureFullscreenText2.isVisible = false
        }

        if (isSubtitleEnabled && !isTextOnlyMode) {
            binding.subtitleOverlayContainer.isVisible = true
            binding.subtitleOverlayContainer.alpha = 1.0f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCropEditor() {
        var boxX = 0f
        var boxY = 0f
        var boxW = 0f
        var boxH = 0f

        var touchStartX = 0f
        var touchStartY = 0f
        var startBoxX = 0f
        var startBoxY = 0f

        binding.cropBoxFrame.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    startBoxX = v.x
                    startBoxY = v.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parentW = binding.cropEditorOverlay.width
                    val parentH = binding.cropEditorOverlay.height
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    val newX = (startBoxX + dx).coerceIn(0f, (parentW - v.width).toFloat().coerceAtLeast(0f))
                    val newY = (startBoxY + dy).coerceIn(0f, (parentH - v.height).toFloat().coerceAtLeast(0f))
                    v.x = newX
                    v.y = newY
                    true
                }
                else -> false
            }
        }

        binding.cropResizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    boxX = binding.cropBoxFrame.x
                    boxY = binding.cropBoxFrame.y
                    boxW = binding.cropBoxFrame.width.toFloat()
                    boxH = binding.cropBoxFrame.height.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parentW = binding.cropEditorOverlay.width
                    val parentH = binding.cropEditorOverlay.height
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    val newW = (boxW + dx).coerceIn(dpToPx(120).toFloat(), parentW - boxX)
                    val newH = (boxH + dy).coerceIn(dpToPx(50).toFloat(), parentH - boxY)
                    val params = binding.cropBoxFrame.layoutParams
                    params.width = newW.toInt()
                    params.height = newH.toInt()
                    binding.cropBoxFrame.layoutParams = params
                    true
                }
                else -> false
            }
        }

        binding.btnSaveCropBox.setOnClickListener {
            saveCropBox()
        }

        binding.btnResetCropBox.setOnClickListener {
            val prefix = if (isEditingNoteCrop) "note_crop_" else "crop_"
            cropPrefs.edit()
                .putFloat("${prefix}left", if (isEditingNoteCrop) 0.20f else 0.18f)
                .putFloat("${prefix}top", if (isEditingNoteCrop) 0.15f else 0.72f)
                .putFloat("${prefix}width", if (isEditingNoteCrop) 0.60f else 0.64f)
                .putFloat("${prefix}height", if (isEditingNoteCrop) 0.65f else 0.22f)
                .apply()
            openCropEditor()
        }
    }

    private fun openCropEditor() {
        if (isEditingNoteCrop) {
            binding.cropEditorTitle.text = "ลากกรอบสีฟ้าไปวางตรงพื้นที่เอกสาร/คำแนะนำ"
            binding.cropBoxGuideText.text = "กรอบแปลเฉพาะจุด (ลากเพื่อย้าย)"
            binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E5FF"))
        } else {
            binding.cropEditorTitle.text = "ลากกรอบสีเขียวไปวางตรงพื้นที่ซับไตเติ้ลเกม"
            binding.cropBoxGuideText.text = "กรอบอ่านซับ (ลากเพื่อย้าย)"
            binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E676"))
        }
        binding.cropEditorOverlay.isVisible = true
        binding.cropEditorOverlay.post {
            val parentW = binding.cropEditorOverlay.width
            val parentH = binding.cropEditorOverlay.height
            if (parentW <= 0 || parentH <= 0) return@post

            val prefix = if (isEditingNoteCrop) "note_crop_" else "crop_"
            val leftNorm = cropPrefs.getFloat("${prefix}left", if (isEditingNoteCrop) 0.20f else 0.18f)
            val topNorm = cropPrefs.getFloat("${prefix}top", if (isEditingNoteCrop) 0.15f else 0.72f)
            val widthNorm = cropPrefs.getFloat("${prefix}width", if (isEditingNoteCrop) 0.60f else 0.64f)
            val heightNorm = cropPrefs.getFloat("${prefix}height", if (isEditingNoteCrop) 0.65f else 0.22f)

            val params = binding.cropBoxFrame.layoutParams
            params.width = (parentW * widthNorm).toInt().coerceIn(dpToPx(120), parentW)
            params.height = (parentH * heightNorm).toInt().coerceIn(dpToPx(50), parentH)
            binding.cropBoxFrame.layoutParams = params

            binding.cropBoxFrame.x = (parentW * leftNorm).coerceIn(0f, (parentW - params.width).toFloat())
            binding.cropBoxFrame.y = (parentH * topNorm).coerceIn(0f, (parentH - params.height).toFloat())
        }
    }

    private fun saveCropBox() {
        val parentW = binding.cropEditorOverlay.width.toFloat()
        val parentH = binding.cropEditorOverlay.height.toFloat()
        if (parentW <= 0 || parentH <= 0) {
            binding.cropEditorOverlay.isVisible = false
            return
        }

        val leftNorm = (binding.cropBoxFrame.x / parentW).coerceIn(0f, 0.85f)
        val topNorm = (binding.cropBoxFrame.y / parentH).coerceIn(0f, 0.90f)
        val widthNorm = (binding.cropBoxFrame.width / parentW).coerceIn(0.10f, 1f - leftNorm)
        val heightNorm = (binding.cropBoxFrame.height / parentH).coerceIn(0.06f, 1f - topNorm)

        val prefix = if (isEditingNoteCrop) "note_crop_" else "crop_"
        cropPrefs.edit()
            .putFloat("${prefix}left", leftNorm)
            .putFloat("${prefix}top", topNorm)
            .putFloat("${prefix}width", widthNorm)
            .putFloat("${prefix}height", heightNorm)
            .apply()

        binding.cropEditorOverlay.isVisible = false
        resetMenuButtonTimer()
        val msg = if (isEditingNoteCrop) "บันทึกกรอบแปลเฉพาะจุด (เอกสาร/คำแนะนำ) แล้ว" else "บันทึกกรอบพื้นที่อ่านซับเรียบร้อยแล้ว"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopQuickDrawer() {
        var startY = 0f
        binding.topDrawerHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - startY
                    if (dy > dpToPx(12) || kotlin.math.abs(dy) < dpToPx(12)) {
                        openTopQuickDrawer()
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnQuickTts.setOnClickListener {
            binding.ttsVoiceSwitch.isChecked = !binding.ttsVoiceSwitch.isChecked
            updateQuickDrawerUI()
            resetTopDrawerTimer()
        }

        binding.btnQuickMuteGame.setOnClickListener {
            binding.btnMuteAudio.performClick()
            updateQuickDrawerUI()
            resetTopDrawerTimer()
        }

        binding.btnQuickSub.setOnClickListener {
            binding.subOverlaySwitch.isChecked = !binding.subOverlaySwitch.isChecked
            updateQuickDrawerUI()
            resetTopDrawerTimer()
        }
        updateQuickDrawerUI()
    }

    private fun openTopQuickDrawer() {
        updateQuickDrawerUI()
        binding.topDrawerHandle.isVisible = false
        binding.topQuickDrawerPanel.isVisible = true
        binding.topQuickDrawerPanel.translationY = -dpToPx(60).toFloat()
        binding.topQuickDrawerPanel.alpha = 0f
        binding.topQuickDrawerPanel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(180)
            .start()
        resetTopDrawerTimer()
    }

    private fun closeTopQuickDrawer() {
        uiVisibilityHandler.removeCallbacks(hideTopDrawerRunnable)
        if (!binding.topQuickDrawerPanel.isVisible) return
        binding.topQuickDrawerPanel.animate()
            .translationY(-dpToPx(60).toFloat())
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                binding.topQuickDrawerPanel.isVisible = false
                binding.topDrawerHandle.isVisible = true
            }
            .start()
    }

    private fun resetTopDrawerTimer() {
        uiVisibilityHandler.removeCallbacks(hideTopDrawerRunnable)
        uiVisibilityHandler.postDelayed(hideTopDrawerRunnable, 4000)
    }

    private fun updateQuickDrawerUI() {
        if (!::binding.isInitialized) return
        binding.btnQuickTts.text = if (isTtsEnabled) "พากย์: เปิด" else "พากย์: ปิด"
        binding.btnQuickTts.setTextColor(Color.parseColor(if (isTtsEnabled) "#00FF88" else "#FFFFFF"))
        binding.btnQuickTts.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isTtsEnabled) "#00FF88" else "#66FFFFFF"))

        binding.btnQuickMuteGame.text = if (isAudioMuted) "เสียงเกม: ปิด" else "เสียงเกม: เปิด"
        binding.btnQuickMuteGame.setTextColor(Color.parseColor(if (isAudioMuted) "#FF6B6B" else "#FFFFFF"))
        binding.btnQuickMuteGame.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isAudioMuted) "#FF6B6B" else "#66FFFFFF"))

        binding.btnQuickSub.text = if (isSubtitleEnabled) "ซับ: เปิด" else "ซับ: ปิด"
        binding.btnQuickSub.setTextColor(Color.parseColor(if (isSubtitleEnabled) "#00FF88" else "#FFFFFF"))
        binding.btnQuickSub.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isSubtitleEnabled) "#00FF88" else "#66FFFFFF"))
    }

    private fun setupNoteTranslationFeature() {
        binding.btnScanNoteFloat.setOnClickListener {
            captureAndTranslateNote()
        }
        binding.btnCloseNote.setOnClickListener {
            hideNoteTranslationCard()
        }
        binding.btnSpeakNote.setOnClickListener {
            if (lastNoteThai.isNotEmpty()) {
                val isFemale = when (voiceGenderMode) {
                    VoiceGenderMode.AUTO_AI -> isFemaleCharacter(lastNoteEnglish, lastNoteThai)
                    VoiceGenderMode.FEMALE -> true
                    VoiceGenderMode.MALE -> false
                }
                val now = System.currentTimeMillis()
                if (geminiApiKey.isNotEmpty() && isGeminiTtsEnabled) {
                    ocrScope.launch {
                        val wavFile = withContext(Dispatchers.IO) {
                            synthesizeGeminiTts(lastNoteThai, isFemale, now)
                        }
                        if (wavFile != null && !isDestroyed && !isFinishing) {
                            enqueueGeminiAudio(wavFile)
                        } else if (!isDestroyed && !isFinishing) {
                            speakWithLocalTts(lastNoteThai, isFemale, now)
                        }
                    }
                } else {
                    speakWithLocalTts(lastNoteThai, isFemale, now)
                }
            }
            uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
            uiVisibilityHandler.postDelayed(hideNoteCardRunnable, 20000)
        }
    }

    private fun captureAndTranslateNote() {
        val webView = binding.xboxWebView
        if (webView.width <= 0 || webView.height <= 0) {
            Toast.makeText(this, "ยังไม่พบภาพสตรีมสำหรับสแกน", Toast.LENGTH_SHORT).show()
            return
        }
        if (isScanningNote) return

        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + webView.width, location[1] + webView.height)
        if (rect.width() <= 0 || rect.height() <= 0) return

        isScanningNote = true
        binding.btnScanNoteFloat.text = "กำลังแปล..."
        val fullBitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)

        try {
            PixelCopy.request(window, rect, fullBitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    try {
                        val cropLeftNorm = cropPrefs.getFloat("note_crop_left", 0.20f).coerceIn(0f, 0.85f)
                        val cropTopNorm = cropPrefs.getFloat("note_crop_top", 0.15f).coerceIn(0f, 0.90f)
                        val cropWidthNorm = cropPrefs.getFloat("note_crop_width", 0.60f).coerceIn(0.10f, 1f - cropLeftNorm)
                        val cropHeightNorm = cropPrefs.getFloat("note_crop_height", 0.65f).coerceIn(0.06f, 1f - cropTopNorm)

                        val cropX = (fullBitmap.width * cropLeftNorm).toInt().coerceIn(0, fullBitmap.width - 1)
                        val cropY = (fullBitmap.height * cropTopNorm).toInt().coerceIn(0, fullBitmap.height - 1)
                        val cropW = (fullBitmap.width * cropWidthNorm).toInt().coerceIn(40, fullBitmap.width - cropX)
                        val cropH = (fullBitmap.height * cropHeightNorm).toInt().coerceIn(30, fullBitmap.height - cropY)

                        val subBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                        val image = InputImage.fromBitmap(subBitmap, 0)
                        textRecognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val blocks = visionText.textBlocks
                                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                                    .mapNotNull { block ->
                                        val t = block.text.trim().replace(Regex("\\s+"), " ")
                                        if (t.length >= 2) t else null
                                    }
                                val rawNote = blocks.joinToString("\n")
                                if (rawNote.isBlank()) {
                                    isScanningNote = false
                                    binding.btnScanNoteFloat.text = "แปลเฉพาะจุด"
                                    Toast.makeText(this, "ไม่พบข้อความในกรอบเฉพาะจุด", Toast.LENGTH_SHORT).show()
                                } else {
                                    ocrScope.launch {
                                        try {
                                            val thai = translateToThai(rawNote)
                                            lastNoteEnglish = rawNote
                                            lastNoteThai = thai
                                            showNoteTranslationCard(rawNote, thai)
                                        } finally {
                                            isScanningNote = false
                                            binding.btnScanNoteFloat.text = "แปลเฉพาะจุด"
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener {
                                isScanningNote = false
                                binding.btnScanNoteFloat.text = "แปลเฉพาะจุด"
                            }
                            .addOnCompleteListener {
                                fullBitmap.recycle()
                                subBitmap.recycle()
                            }
                    } catch (_: Exception) {
                        isScanningNote = false
                        binding.btnScanNoteFloat.text = "แปลเฉพาะจุด"
                        fullBitmap.recycle()
                    }
                } else {
                    isScanningNote = false
                    binding.btnScanNoteFloat.text = "แปลเฉพาะจุด"
                    fullBitmap.recycle()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            isScanningNote = false
            binding.btnScanNoteFloat.text = "แปลเฉพาะจุด"
            fullBitmap.recycle()
        }
    }

    private fun showNoteTranslationCard(english: String, thai: String) {
        uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
        binding.tvNoteThaiText.text = thai
        binding.tvNoteOriginalText.text = english
        binding.noteTranslationCard.isVisible = true

        val lp = binding.pureFullscreenInner.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM
        binding.pureFullscreenInner.layoutParams = lp

        uiVisibilityHandler.postDelayed(hideNoteCardRunnable, 20000)
    }

    private fun hideNoteTranslationCard() {
        uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
        binding.noteTranslationCard.isVisible = false
        val lp = binding.pureFullscreenInner.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.CENTER
        binding.pureFullscreenInner.layoutParams = lp
    }

    private fun openSettingsSheet() {
        showMenuPage("MAIN")
        binding.btnMenuToggle.alpha = 1.0f
        uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)

        binding.menuPanelContainer.isVisible = true
        binding.menuPanelContainer.alpha = 0f
        binding.menuPanelContainer.animate().alpha(1f).setDuration(180).start()

        binding.menuSheetContainer.post {
            val startY = binding.menuSheetContainer.height.toFloat().coerceAtLeast(dpToPx(260).toFloat())
            binding.menuSheetContainer.translationY = startY
            binding.menuContentWrapper.translationY = startY * 0.14f

            SpringAnimation(binding.menuSheetContainer, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring = SpringForce(0f).apply {
                    stiffness = 420f
                    dampingRatio = 0.78f
                }
                start()
            }
            SpringAnimation(binding.menuContentWrapper, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring = SpringForce(0f).apply {
                    stiffness = 350f
                    dampingRatio = 0.58f
                }
                start()
            }
        }
    }

    private fun closeSettingsSheet() {
        if (!binding.menuPanelContainer.isVisible) return
        val targetY = binding.menuSheetContainer.height.toFloat().coerceAtLeast(dpToPx(260).toFloat())
        binding.menuPanelContainer.animate().alpha(0f).setDuration(180).start()
        binding.menuSheetContainer.animate()
            .translationY(targetY)
            .setDuration(200)
            .withEndAction {
                binding.menuPanelContainer.isVisible = false
                binding.menuPanelContainer.alpha = 1f
                binding.menuSheetContainer.translationY = 0f
                binding.menuContentWrapper.translationY = 0f
                resetMenuButtonTimer()
            }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLiquidGlassMotion() {
        var startRawY = 0f
        var velocityTracker: VelocityTracker? = null

        binding.menuDragArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dy = event.rawY - startRawY
                    val clampedY = if (dy < 0f) dy * 0.18f else dy
                    binding.menuSheetContainer.translationY = clampedY
                    binding.menuContentWrapper.translationY = clampedY * 0.12f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velY = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val dy = binding.menuSheetContainer.translationY
                    if (dy > dpToPx(110) || velY > 1200f) {
                        closeSettingsSheet()
                    } else {
                        SpringAnimation(binding.menuSheetContainer, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                            spring = SpringForce(0f).apply {
                                stiffness = 420f
                                dampingRatio = 0.72f
                            }
                            start()
                        }
                        SpringAnimation(binding.menuContentWrapper, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                            spring = SpringForce(0f).apply {
                                stiffness = 350f
                                dampingRatio = 0.52f
                            }
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        attachLiquidGlassPressPhysics(binding.menuSheetContainer)
        attachLiquidGlassPressPhysics(binding.topQuickDrawerPanel)
        attachLiquidGlassPressPhysics(binding.btnScanNoteFloat)
        attachLiquidGlassPressPhysics(binding.btnSpeakNote)
        attachLiquidGlassPressPhysics(binding.btnCloseNote)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachLiquidGlassPressPhysics(view: View) {
        if (view is com.google.android.material.button.MaterialButton || view is android.widget.ImageButton) {
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(75).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        SpringAnimation(v, DynamicAnimation.SCALE_X, 1f).apply {
                            spring = SpringForce(1f).apply {
                                stiffness = 520f
                                dampingRatio = 0.55f
                            }
                            start()
                        }
                        SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f).apply {
                            spring = SpringForce(1f).apply {
                                stiffness = 520f
                                dampingRatio = 0.55f
                            }
                            start()
                        }
                    }
                }
                false
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                attachLiquidGlassPressPhysics(view.getChildAt(i))
            }
        }
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val a = s1.lowercase()
        val b = s2.lowercase()
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            costs[0] = i
            var nw = i - 1
            for (j in 1..b.length) {
                val cj = minOf(1 + minOf(costs[j], costs[j - 1]), if (a[i - 1] == b[j - 1]) nw else nw + 1)
                nw = costs[j]
                costs[j] = cj
            }
        }
        return costs[b.length]
    }

    private fun isSimilarText(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val cleanA = a.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        val cleanB = b.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false
        if (cleanA == cleanB) return true
        val maxLen = maxOf(cleanA.length, cleanB.length)
        val dist = levenshteinDistance(cleanA, cleanB)
        return (1.0 - dist.toDouble() / maxLen) >= 0.72
    }

    private fun isSimilarThai(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val cleanA = a.replace(Regex("[\\s.,!?\"'()\\-…]"), "")
        val cleanB = b.replace(Regex("[\\s.,!?\"'()\\-…]"), "")
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false
        if (cleanA == cleanB) return true
        val maxLen = maxOf(cleanA.length, cleanB.length)
        val dist = levenshteinDistance(cleanA, cleanB)
        return (1.0 - dist.toDouble() / maxLen) >= 0.80
    }

    private fun isDialogueSubtitle(text: String): Boolean {
        if (!isFilterShortTextEnabled) return text.length >= 3 && text.any { it.isLetter() }

        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 && text.length < 8) return false

        val lower = text.lowercase()
        val promptWords = setOf(
            "talk", "skip", "attack", "dodge", "interact", "examine", "open", "back", "next", "continue", "menu", "cancel",
            "lb", "rb", "lt", "rt", "ls", "rs", "r1", "r2", "l1", "l2"
        )
        if (words.size == 1 && promptWords.contains(lower)) return false
        if (words.size == 2 && words.any { it.lowercase() in listOf("lb", "rb", "lt", "rt", "r1", "r2", "l1", "l2") }) return false

        return text.any { it.isLetter() }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private suspend fun translateToThai(englishText: String): String = withContext(Dispatchers.IO) {
        if (geminiApiKey.isNotEmpty() && isGeminiTranslateEnabled) {
            try {
                val geminiRes = translateWithGemini(englishText)
                if (!geminiRes.isNullOrBlank()) {
                    return@withContext geminiRes
                }
            } catch (_: Exception) {}
        }
        try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=th&dt=t&q=" + URLEncoder.encode(englishText, "UTF-8")
            val conn = URL(url).openConnection()
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val res = conn.getInputStream().bufferedReader().use { it.readText() }
            val json = JSONArray(res)
            val sentences = json.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                sb.append(sentences.getJSONArray(i).getString(0))
            }
            val result = sb.toString().trim()
            if (result.isNotEmpty()) result else englishText
        } catch (_: Exception) {
            englishText
        }
    }

    private fun translateWithGemini(englishText: String): String? {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" +
            URLEncoder.encode(geminiApiKey, "UTF-8")
        val conn = (URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 6000
            doOutput = true
        }

        val prompt = "Translate this video game subtitle from English to natural, punchy spoken Thai (movie/game subtitle style). Accurately translate slang, idioms, and profanity/swear words matching the character's raw emotion without censoring or making it overly polite. Return ONLY the Thai translation:\n$englishText"
        val safetyCategories = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )
        val safetyArray = JSONArray().apply {
            for (cat in safetyCategories) {
                put(org.json.JSONObject().apply {
                    put("category", cat)
                    put("threshold", "BLOCK_NONE")
                })
            }
        }
        val body = org.json.JSONObject().apply {
            put("contents", JSONArray().put(
                org.json.JSONObject().put("parts", JSONArray().put(
                    org.json.JSONObject().put("text", prompt)
                ))
            ))
            put("safetySettings", safetyArray)
            put("generationConfig", org.json.JSONObject().apply {
                put("temperature", 0.35)
                put("maxOutputTokens", 200)
            })
        }

        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val resText = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = org.json.JSONObject(resText)
        val candidates = json.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null
        return parts.getJSONObject(0).optString("text", "").trim().ifEmpty { null }
    }

    private fun isFemaleCharacter(englishText: String, thaiText: String): Boolean {
        val engLower = englishText.lowercase()
        val thaiLower = thaiText.lowercase()

        var femaleScore = 0
        var maleScore = 0

        val femaleWords = listOf(
            "she", "her", "hers", "lady", "milady", "girl", "woman", "women",
            "sister", "mother", "queen", "princess", "madam", "miss", "mrs",
            "daughter", "wife", "aunt", "witch", "goddess", "priestess", "empress"
        )
        for (w in femaleWords) {
            if (Regex("\\b$w\\b").containsMatchIn(engLower)) femaleScore += 2
        }
        if (thaiLower.contains("ค่ะ") || thaiLower.contains("คะ") || thaiLower.contains("ดิฉัน") || thaiLower.contains("เจ้าแม่") || thaiLower.contains("นาง")) {
            femaleScore += 3
        }

        val maleWords = listOf(
            "he", "him", "his", "man", "men", "boy", "sir", "lord", "milord",
            "brother", "father", "king", "prince", "mr", "son", "husband", "uncle",
            "knight", "warrior", "chief", "master", "soldier", "dude", "bro", "guy"
        )
        for (w in maleWords) {
            if (Regex("\\b$w\\b").containsMatchIn(engLower)) maleScore += 2
        }
        if (thaiLower.contains("ครับ") || thaiLower.contains("ผม") || thaiLower.contains("กระผม") || thaiLower.contains("ท่านชาย") || thaiLower.contains("ข้า")) {
            maleScore += 3
        }

        val namePrefixesFemale = listOf("ellie", "lara", "chloe", "nadine", "aloy", "tifa", "aerith", "jill", "claire", "ada", "yennefer", "ciri", "triss", "freya", "selene", "lucia")
        for (name in namePrefixesFemale) {
            if (engLower.startsWith(name) || engLower.contains("$name:")) femaleScore += 5
        }

        val namePrefixesMale = listOf("joel", "kratos", "nathan", "drake", "leon", "chris", "geralt", "arthur", "john", "dante", "vergil", "cloud", "barret", "jason", "master chief")
        for (name in namePrefixesMale) {
            if (engLower.startsWith(name) || engLower.contains("$name:")) maleScore += 5
        }

        return femaleScore > maleScore
    }

    private fun fixOcrText(raw: String): String {
        var s = raw
        s = s.replace("vv", "w").replace("VV", "W")
        s = s.replace("rn", "m")
        s = s.replace("thc", "the").replace("Thc", "The").replace("THC", "THE")
        s = s.replace("hcre", "here").replace("Hcre", "Here")
        s = s.replace("whcre", "where").replace("Whcre", "Where")
        s = s.replace("thcre", "there").replace("Thcre", "There")
        s = s.replace("thcm", "them").replace("Thcm", "Them")
        s = s.replace("thcy", "they").replace("Thcy", "They")
        s = s.replace("thcse", "these").replace("thcso", "these")
        s = s.replace("somc", "some").replace("Somc", "Some")
        s = s.replace("comc", "come").replace("Comc", "Come")
        s = s.replace("havc", "have").replace("Havc", "Have")
        s = s.replace("makc", "make").replace("Makc", "Make")
        s = s.replace("takc", "take").replace("Takc", "Take")
        s = s.replace("givc", "give").replace("Givc", "Give")
        s = s.replace("likc", "like").replace("Likc", "Like")
        s = s.replace("timc", "time").replace("Timc", "Time")
        s = s.replace("knowlc", "knowle")
        s = s.replace("whcn", "when").replace("Whcn", "When")
        s = s.replace("thcn", "then").replace("Thcn", "Then")
        s = s.replace("wcre", "were").replace("Wcre", "Were")
        s = s.replace("arce", "are").replace("arc ", "are ")
        s = s.replace("becn", "been").replace("scce", "see")
        s = s.replace("nccd", "need").replace("Nccd", "Need")
        s = s.replace("fcel", "feel").replace("kccp", "keep")
        s = s.replace("bcfore", "before").replace("bctter", "better")
        s = s.replace("evcry", "every").replace("ncver", "never")
        s = s.replace("ovcr", "over").replace("undcr", "under")
        s = s.replace("aftcr", "after").replace("watcr", "water")
        s = s.replace("peoplc", "people").replace("littlc", "little")
        s = s.replace("bclieve", "believe").replace("frcend", "friend")
        s = s.replace("shc ", "she ").replace("Shc ", "She ")
        s = s.replace("hc ", "he ").replace("Hc ", "He ")
        s = s.replace("wc ", "we ").replace("Wc ", "We ")
        s = s.replace("mc ", "me ").replace("bc ", "be ")
        s = s.replace("0f", "of").replace("0n", "on").replace("t0", "to")
        s = s.replace("|", "I").replace("!", "l").replace("1", "l")
        s = s.replace(Regex("(?<=\\w)\\.(?=\\w)"), "")
        return s.trim()
    }

    private fun toggleTextOnlyMode(enable: Boolean) {
        isTextOnlyMode = enable
        if (enable) {
            binding.pureFullscreenLayout.isVisible = true
            binding.subtitleOverlayContainer.isVisible = false
            binding.btnTextOnlyMode.text = "กลับสู่หน้าจอเกมปกติ"
            if (subtitleHistory.isNotEmpty()) {
                renderSubtitles()
            } else {
                binding.pureFullscreenText.text = "XB Translator\n(รอรับข้อความซับไตเติ้ลจากเกม...)"
                binding.pureFullscreenText.isVisible = true
                binding.pureFullscreenText1.isVisible = false
                binding.pureFullscreenText2.isVisible = false
            }
        } else {
            binding.pureFullscreenLayout.isVisible = false
            binding.btnTextOnlyMode.text = "โหมดโชว์เฉพาะตัวหนังสือ (จอดำ)"
            if (isSubtitleEnabled && subtitleHistory.isNotEmpty()) {
                binding.subtitleOverlayContainer.isVisible = true
            }
        }
    }

    private fun resetMenuButtonTimer() {
        uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
        binding.btnMenuToggle.alpha = 1.0f
        uiVisibilityHandler.postDelayed(hideMenuButtonRunnable, 4000)
    }

    private fun speakTranslation(englishText: String, thaiText: String) {
        val now = System.currentTimeMillis()
        if (isSimilarThai(thaiText, lastSpokenThai) && (now - lastSpokenTimeMs) < 8000) {
            return
        }
        lastSpokenThai = thaiText
        lastSpokenTimeMs = now

        val isFemale = when (voiceGenderMode) {
            VoiceGenderMode.AUTO_AI -> isFemaleCharacter(englishText, thaiText)
            VoiceGenderMode.FEMALE -> true
            VoiceGenderMode.MALE -> false
        }

        if (geminiApiKey.isNotEmpty() && isGeminiTtsEnabled) {
            ocrScope.launch {
                val wavFile = withContext(Dispatchers.IO) {
                    synthesizeGeminiTts(thaiText, isFemale, now)
                }
                if (wavFile != null && isTtsEnabled && !isDestroyed && !isFinishing) {
                    enqueueGeminiAudio(wavFile)
                } else if (isTtsEnabled && !isDestroyed && !isFinishing) {
                    speakWithLocalTts(thaiText, isFemale, now)
                }
            }
            return
        }

        speakWithLocalTts(thaiText, isFemale, now)
    }

    private fun synthesizeGeminiTts(thaiText: String, isFemale: Boolean, timestamp: Long): java.io.File? {
        return try {
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-goog-api-key", geminiApiKey)
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 6000
                readTimeout = 10000
                doOutput = true
            }

            val voiceName = if (isFemale) "Kore" else "Charon"
            val body = org.json.JSONObject().apply {
                put("model", "gemini-3.8-flash-lite-tts")
                put("input", JSONArray().put(
                    org.json.JSONObject().apply {
                        put("type", "user_input")
                        put("content", JSONArray().put(
                            org.json.JSONObject().apply {
                                put("type", "text")
                                put("text", thaiText)
                            }
                        ))
                    }
                ))
                put("response_format", org.json.JSONObject().put("type", "audio"))
                put("generation_config", org.json.JSONObject().put(
                    "speech_config", JSONArray().put(
                        org.json.JSONObject().put("voice", voiceName)
                    )
                ))
            }

            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val resText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = org.json.JSONObject(resText)
            val steps = json.optJSONArray("steps") ?: return null
            var base64Audio: String? = null
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                if (step.optString("type") == "model_output") {
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val item = content.getJSONObject(j)
                        if (item.optString("type") == "audio") {
                            base64Audio = item.optString("data", "")
                        }
                    }
                }
            }
            if (base64Audio.isNullOrEmpty()) return null

            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val outFile = java.io.File(cacheDir, "gemini_tts_${timestamp}.wav")
            outFile.writeBytes(audioBytes)
            outFile
        } catch (_: Exception) {
            null
        }
    }

    private fun enqueueGeminiAudio(wavFile: java.io.File) {
        when (ttsQueueMode) {
            TtsQueueMode.QUEUE_ALL -> {
                geminiAudioQueue.addLast(wavFile)
                if (geminiMediaPlayer == null) {
                    playNextGeminiAudio()
                }
            }
            TtsQueueMode.FLUSH_IMMEDIATE -> {
                stopGeminiAudioPlayback()
                geminiAudioQueue.addLast(wavFile)
                playNextGeminiAudio()
            }
            TtsQueueMode.SMART_QUEUE -> {
                if (geminiMediaPlayer == null && geminiAudioQueue.isEmpty()) {
                    geminiAudioQueue.addLast(wavFile)
                    playNextGeminiAudio()
                } else if (geminiAudioQueue.size <= 1) {
                    geminiAudioQueue.addLast(wavFile)
                } else {
                    stopGeminiAudioPlayback()
                    geminiAudioQueue.addLast(wavFile)
                    playNextGeminiAudio()
                }
            }
        }
    }

    private fun playNextGeminiAudio() {
        if (!isTtsEnabled || isDestroyed || isFinishing) {
            stopGeminiAudioPlayback()
            return
        }
        val nextFile = geminiAudioQueue.pollFirst() ?: return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(nextFile.absolutePath)
            mp.prepare()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mp.playbackParams = PlaybackParams().setSpeed(speechSpeedRate)
                } catch (_: Exception) {}
            }
            mp.setOnCompletionListener {
                it.release()
                if (geminiMediaPlayer == it) geminiMediaPlayer = null
                nextFile.delete()
                playNextGeminiAudio()
            }
            mp.setOnErrorListener { player, _, _ ->
                player.release()
                if (geminiMediaPlayer == player) geminiMediaPlayer = null
                nextFile.delete()
                playNextGeminiAudio()
                true
            }
            geminiMediaPlayer = mp
            mp.start()
        } catch (_: Exception) {
            nextFile.delete()
            geminiMediaPlayer = null
            playNextGeminiAudio()
        }
    }

    private fun stopGeminiAudioPlayback() {
        try {
            geminiMediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            geminiMediaPlayer?.release()
        } catch (_: Exception) {}
        geminiMediaPlayer = null
        while (geminiAudioQueue.isNotEmpty()) {
            geminiAudioQueue.pollFirst()?.delete()
        }
    }

    private fun speakWithLocalTts(thaiText: String, isFemale: Boolean, now: Long) {
        val ttsEngine = tts ?: return

        try {
            val voices = ttsEngine.voices
            if (voices != null) {
                val thVoices = voices.filter { it.locale.language.equals("th", ignoreCase = true) }
                if (thVoices.isNotEmpty()) {
                    val targetVoice = if (isFemale) {
                        thVoices.firstOrNull { v ->
                            val name = v.name.lowercase()
                            (name.contains("female") || name.contains("woman") || name.contains("thb") || name.contains("thc") || name.contains("cfn") || name.contains("-b-") || name.contains("-c-"))
                        } ?: if (thVoices.size > 1) thVoices[1] else thVoices.first()
                    } else {
                        thVoices.firstOrNull { v ->
                            val name = v.name.lowercase()
                            (!name.contains("female") && (name.contains("male") || name.contains("man"))) || name.contains("tha") || name.contains("thd") || name.contains("cfa") || name.contains("-a-") || name.contains("-d-")
                        } ?: thVoices.first()
                    }
                    if (targetVoice != null) {
                        ttsEngine.voice = targetVoice
                    }
                }
            }
        } catch (_: Exception) {}

        if (isFemale) {
            ttsEngine.setPitch(1.15f)
        } else {
            ttsEngine.setPitch(0.75f)
        }
        ttsEngine.setSpeechRate(speechSpeedRate)

        val utteranceId = "sub_voice_${now}"
        val queueMode = when (ttsQueueMode) {
            TtsQueueMode.QUEUE_ALL -> TextToSpeech.QUEUE_ADD
            TtsQueueMode.FLUSH_IMMEDIATE -> {
                activeUtterances.clear()
                TextToSpeech.QUEUE_FLUSH
            }
            TtsQueueMode.SMART_QUEUE -> {
                if (!ttsEngine.isSpeaking || activeUtterances.size <= 1) {
                    TextToSpeech.QUEUE_ADD
                } else {
                    activeUtterances.clear()
                    TextToSpeech.QUEUE_FLUSH
                }
            }
        }
        activeUtterances.add(utteranceId)
        ttsEngine.speak(thaiText, queueMode, null, utteranceId)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.menuPanelContainer.isVisible) {
            binding.menuPanelContainer.isVisible = false
            resetMenuButtonTimer()
            return
        }
        if (binding.cropEditorOverlay.isVisible) {
            binding.cropEditorOverlay.isVisible = false
            resetMenuButtonTimer()
            return
        }
        if (binding.xboxWebView.canGoBack()) {
            binding.xboxWebView.goBack()
            return
        }
        super.onBackPressed()
    }

    private fun setupAudioRouting() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQ_BLUETOOTH_CONNECT
                )
            }

            val modeListener = AudioManager.OnModeChangedListener {
                applyBluetoothAudioRouting()
            }
            audioManager.addOnModeChangedListener(mainExecutor, modeListener)
            audioModeListener = modeListener
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                applyBluetoothAudioRouting()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                applyBluetoothAudioRouting()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, uiVisibilityHandler)
        audioDeviceCallback = callback

        applyBluetoothAudioRouting()
    }

    private fun applyBluetoothAudioRouting() {
        if (!::audioManager.isInitialized) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = audioManager.availableCommunicationDevices
                val preferredDevice = commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_HEARING_AID
                } ?: commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }

                if (preferredDevice != null) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    audioManager.setCommunicationDevice(preferredDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasBluetooth = outputs.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                val hasWired = outputs.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (hasBluetooth || hasWired) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    if (hasBluetooth && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                    }
                }
            }
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT not yet granted by user
        } catch (_: Exception) {
            // Ignore transient routing errors
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLUETOOTH_CONNECT &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            applyBluetoothAudioRouting()
        }
    }

    override fun onResume() {
        super.onResume()
        applyBluetoothAudioRouting()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioManager.isInitialized) {
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (audioModeListener as? AudioManager.OnModeChangedListener)?.let {
                    audioManager.removeOnModeChangedListener(it)
                }
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }
        uiVisibilityHandler.removeCallbacks(captureRunnable)
        uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
        uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
        uiVisibilityHandler.removeCallbacks(hideTopDrawerRunnable)
        activeUtterances.clear()
        stopGeminiAudioPlayback()
        ocrScope.cancel()
        tts?.stop()
        tts?.shutdown()
        binding.xboxWebView.destroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }
}
