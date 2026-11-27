package `is`.xyz.mpv.bridge

import android.content.SharedPreferences

/**
 * 抽象 libmpv 客户端接口，可直接对接 mpv-android 的 MPVLib。
 */
interface MpvClient {
    fun setOptionString(name: String, value: String)
    fun setPropertyString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun setPropertyInt(name: String, value: Int)
    fun setPropertyDouble(name: String, value: Double)
    fun command(args: Array<String>)
}

/**
 * 将 PurePlayer 的 frag_player / frag_decoder / frag_audio / frag_subtitle
 * SharedPreferences 设置实时同步到 libmpv。
 *
 * 注意：部分 UI/系统级开关（如屏幕锁、手势缩放、画中画、耳机监听）不属于 libmpv 内核职责，
 * 已在 [applyKey] 中标出并留出回调钩子，由 Android Activity/Service 层处理。
 */
class PurePlayerSettingsBridge(
    private val prefs: SharedPreferences,
    private val mpv: MpvClient,
    private val uiCallbacks: UiSettingCallbacks? = null
) : SharedPreferences.OnSharedPreferenceChangeListener {

    interface UiSettingCallbacks {
        fun onAudioFocusPolicyChanged(enabled: Boolean) {}
        fun onPauseOnHeadsetDisconnectedChanged(enabled: Boolean) {}
        fun onStickyModeChanged(videoMode: String, audioSticky: Boolean) {}
        fun onDoubleTapBackChanged(enabled: Boolean) {}
        fun onQuickZoomChanged(enabled: Boolean) {}
    }

    /**
     * 字幕自定义（播放器内字幕自定义面板用）的 SharedPreferences 键。
     * 对应 mpv 的 sub-* 属性，播放器改动时写这些键，桥接自动同步到 libmpv。
     */
    companion object {
        const val KEY_SUB_ALIGN = "sub_custom_align" // int：0=顶部，1=中部，2=底部（默认）
        const val KEY_SUB_MARGIN_Y = "sub_custom_margin_y" // int：底部边距，默认 8
        const val KEY_SUB_BACK_ENABLED = "sub_custom_back_enabled" // boolean：显示背景，默认 false
        const val KEY_SUB_BACK_COLOR = "sub_custom_back_color" // string：背景颜色，默认 #CC000000
        const val KEY_SUB_USE_MARGINS = "sub_custom_use_margins" // boolean：贴合视频，默认 true
        const val KEY_SUB_FONT = "sub_custom_font" // string：字体名，空=默认
        const val KEY_SUB_FONT_SIZE = "sub_custom_font_size" // int：字号，默认 32
        const val KEY_SUB_SCALE = "sub_custom_scale" // int：缩放百分比，默认 100
        const val KEY_SUB_COLOR = "sub_custom_color" // string：文字颜色，默认 #FFFFFF
        const val KEY_SUB_BOLD = "sub_custom_bold" // boolean：加粗，默认 false
        const val KEY_SUB_SHADOW_ENABLED = "sub_custom_shadow_enabled" // boolean：文字背景，默认 false
        const val KEY_SUB_SHADOW_COLOR = "sub_custom_shadow_color" // string：文字背景颜色，默认 #80000000
        const val KEY_SUB_BORDER_ENABLED = "sub_custom_border_enabled" // boolean：描边，默认 true
        const val KEY_SUB_BORDER_COLOR = "sub_custom_border_color" // string：描边颜色，默认 #000000
        const val KEY_SUB_BORDER_SIZE = "sub_custom_border_size" // int：描边粗细，默认 2
    }

    /**
     * 在 mpv_initialize() 之前调用，配置初始化阶段选项。
     */
    fun applyInitialOptions() {
        mpv.setOptionString("vo", "gpu-next")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("keep-open", "yes")

        // 音量增强上限（frag_audio.xml: volume_boost）
        val boost = prefs.getBoolean("volume_boost", false)
        mpv.setOptionString("volume-max", if (boost) "200" else "100")

        // 字体目录（frag_subtitle.xml: typeface_dir）
        prefs.getString("typeface_dir", null)?.takeIf { it.isNotBlank() }?.let {
            mpv.setOptionString("sub-fonts-dir", it)
        }

        // 音频输出后端（frag_audio.xml: audio_device）
        val ao = when (prefs.getString("audio_device", "auto")) {
            "opensles" -> "opensles"
            "audiotrack" -> "audiotrack"
            else -> "audiotrack,opensles"
        }
        mpv.setOptionString("ao", ao)
    }

    /**
     * 在播放器初始化完成后调用，一次性同步全部实时属性并注册监听。
     */
    fun startSync() {
        syncAll()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun stopSync() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    fun syncAll() {
        val allKeys = listOf(
            // frag_player.xml
            "resume_last", "default_playback_speed", "fast_seek",
            "audio_focus", "double_tap_back_key", "quick_zoom",
            "sticky_video", "sticky_audio",
            // frag_decoder.xml
            "omxdecoder_local", "omxdecoder_net", "swdecoder_net",
            "correct_hw_aspect_ratio", "core_limit", "use_speedup_tricks", "default_deinterlacer",
            // frag_audio.xml
            "audio_player", "volume_boost", "audio_language", "audio_delay",
            "bluetooth_audio_delay", "prefer_audio_passthrough_mode", "pause_on_headset_disconnected",
            // frag_subtitle.xml
            "subtitle_folder", "subtitle_charset", "subtitle_language",
            "subtitle_show_hw", "subtitle_default_sync", "subtitle_hw_accel",
            // 字幕自定义面板（播放器内）
            KEY_SUB_ALIGN, KEY_SUB_MARGIN_Y, KEY_SUB_BACK_ENABLED, KEY_SUB_BACK_COLOR,
            KEY_SUB_USE_MARGINS, KEY_SUB_FONT, KEY_SUB_FONT_SIZE, KEY_SUB_SCALE,
            KEY_SUB_COLOR, KEY_SUB_BOLD, KEY_SUB_SHADOW_ENABLED, KEY_SUB_SHADOW_COLOR,
            KEY_SUB_BORDER_ENABLED, KEY_SUB_BORDER_COLOR, KEY_SUB_BORDER_SIZE
        )
        allKeys.forEach { applyKey(it) }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != null) {
            applyKey(key)
        }
    }

    fun applyKey(key: String) {
        when (key) {
            // ========================
            // 1. frag_player.xml
            // ========================
            "resume_last" -> {
                val mode = prefs.getString("resume_last", "ask") ?: "ask"
                val savePos = mode != "no"
                mpv.setPropertyBoolean("save-position-on-quit", savePos)
            }

            "default_playback_speed" -> {
                // 倍速范围 25..400 (默认 100 = 1.0x)
                val percent = prefs.getInt("default_playback_speed", 100).coerceIn(25, 400)
                mpv.setPropertyDouble("speed", percent / 100.0)
            }

            "fast_seek" -> {
                val fast = prefs.getBoolean("fast_seek", true)
                // true: 对齐关键帧快速定位; false: 精确帧定位
                mpv.setPropertyString("hr-seek", if (fast) "no" else "yes")
            }

            "audio_focus" -> {
                uiCallbacks?.onAudioFocusPolicyChanged(prefs.getBoolean("audio_focus", true))
            }

            "double_tap_back_key" -> {
                uiCallbacks?.onDoubleTapBackChanged(prefs.getBoolean("double_tap_back_key", false))
            }

            "quick_zoom" -> {
                uiCallbacks?.onQuickZoomChanged(prefs.getBoolean("quick_zoom", true))
            }

            "sticky_video", "sticky_audio" -> {
                val stickyVideo = prefs.getString("sticky_video", "pip") ?: "pip"
                val stickyAudio = prefs.getBoolean("sticky_audio", true)
                uiCallbacks?.onStickyModeChanged(stickyVideo, stickyAudio)
            }

            // ========================
            // 2. frag_decoder.xml
            // ========================
            "omxdecoder_local", "omxdecoder_net", "swdecoder_net" -> {
                // 硬解默认开启（mediacodec-copy，兼容性更好）；关闭本地硬解则回落到 mpv 内置软解
                val useHw = prefs.getBoolean("omxdecoder_local", true)
                val hwdec = if (!useHw) "no" else "mediacodec-copy"
                mpv.setPropertyString("hwdec", hwdec)
            }

            "correct_hw_aspect_ratio" -> {
                val keepAspect = prefs.getBoolean("correct_hw_aspect_ratio", true)
                mpv.setPropertyBoolean("keepaspect", keepAspect)
            }

            "core_limit" -> {
                // 0 表示自动决定线程数
                val threads = prefs.getString("core_limit", "0")?.toIntOrNull() ?: 0
                mpv.setPropertyInt("vd-lavc-threads", threads.coerceAtLeast(0))
            }

            "use_speedup_tricks" -> {
                val fast = prefs.getBoolean("use_speedup_tricks", false)
                mpv.setPropertyBoolean("vd-lavc-fast", fast)
                mpv.setPropertyString("vd-lavc-skiploopfilter", if (fast) "nonref" else "default")
            }

            "default_deinterlacer" -> {
                val mode = prefs.getString("default_deinterlacer", "0")?.toIntOrNull() ?: 0
                mpv.setPropertyBoolean("deinterlace", mode != 0)
            }

            // ========================
            // 3. frag_audio.xml
            // ========================
            "audio_player" -> {
                val audioOnly = prefs.getBoolean("audio_player", false)
                mpv.setPropertyString("vid", if (audioOnly) "no" else "auto")
            }

            "volume_boost" -> {
                val boost = prefs.getBoolean("volume_boost", false)
                mpv.setPropertyInt("volume-max", if (boost) 200 else 100)
            }

            "audio_language" -> {
                val lang = prefs.getString("audio_language", "")?.trim().orEmpty()
                if (lang.isNotEmpty()) {
                    mpv.setPropertyString("alang", lang)
                }
            }

            "audio_delay", "bluetooth_audio_delay" -> {
                val baseDelay = prefs.getString("audio_delay", "0")?.toDoubleOrNull() ?: 0.0
                val btDelay = prefs.getString("bluetooth_audio_delay", "0")?.toDoubleOrNull() ?: 0.0
                // 实际工程中可结合 AudioDeviceInfo 判断蓝牙是否激活，再叠加 btDelay
                mpv.setPropertyDouble("audio-delay", baseDelay + btDelay)
            }

            "prefer_audio_passthrough_mode" -> {
                val passthrough = prefs.getBoolean("prefer_audio_passthrough_mode", true)
                mpv.setPropertyString(
                    "audio-spdif",
                    if (passthrough) "ac3,eac3,dts,dts-hd,truehd" else ""
                )
            }

            "pause_on_headset_disconnected" -> {
                uiCallbacks?.onPauseOnHeadsetDisconnectedChanged(
                    prefs.getBoolean("pause_on_headset_disconnected", true)
                )
            }

            // ========================
            // 4. frag_subtitle.xml
            // ========================
            "subtitle_folder" -> {
                val dir = prefs.getString("subtitle_folder", "")?.trim().orEmpty()
                if (dir.isNotEmpty()) {
                    mpv.setPropertyString("sub-file-paths", dir)
                }
            }

            "subtitle_charset" -> {
                val charset = prefs.getString("subtitle_charset", "")?.trim().orEmpty()
                mpv.setPropertyString("sub-codepage", if (charset.isEmpty()) "auto" else charset)
            }

            "subtitle_language" -> {
                val lang = prefs.getString("subtitle_language", "")?.trim().orEmpty()
                if (lang.isNotEmpty()) {
                    mpv.setPropertyString("slang", lang)
                }
            }

            "subtitle_show_hw" -> {
                // 控制硬解下是否显示内嵌字幕，对应 mpv 的 sub-visibility
                val visible = prefs.getBoolean("subtitle_show_hw", true)
                mpv.setPropertyBoolean("sub-visibility", visible)
            }

            "subtitle_default_sync" -> {
                val syncSec = prefs.getString("subtitle_default_sync", "0")?.toDoubleOrNull() ?: 0.0
                mpv.setPropertyDouble("sub-delay", syncSec)
            }

            "subtitle_hw_accel" -> {
                val blend = prefs.getBoolean("subtitle_hw_accel", true)
                mpv.setPropertyString("blend-subtitles", if (blend) "yes" else "no")
            }

            // ========================
            // 5. 字幕自定义面板（播放器内）
            // ========================
            KEY_SUB_ALIGN -> {
                // 0=顶部，1=中部，2=底部
                val align = prefs.getInt(KEY_SUB_ALIGN, 2).coerceIn(0, 2)
                mpv.setPropertyInt("sub-align-y", align)
            }

            KEY_SUB_MARGIN_Y -> {
                val margin = prefs.getInt(KEY_SUB_MARGIN_Y, 8).coerceIn(0, 200)
                mpv.setPropertyInt("sub-margin-y", margin)
            }

            KEY_SUB_BACK_ENABLED, KEY_SUB_BACK_COLOR -> {
                val enabled = prefs.getBoolean(KEY_SUB_BACK_ENABLED, false)
                val color = prefs.getString(KEY_SUB_BACK_COLOR, "#CC000000") ?: "#CC000000"
                mpv.setPropertyString("sub-back-color", if (enabled) color else "#00000000")
            }

            KEY_SUB_USE_MARGINS -> {
                mpv.setPropertyBoolean(
                    "sub-use-margins",
                    prefs.getBoolean(KEY_SUB_USE_MARGINS, true)
                )
            }

            KEY_SUB_FONT -> {
                val font = prefs.getString(KEY_SUB_FONT, "")?.trim().orEmpty()
                if (font.isNotEmpty()) {
                    mpv.setPropertyString("sub-font", font)
                }
            }

            KEY_SUB_FONT_SIZE -> {
                val size = prefs.getInt(KEY_SUB_FONT_SIZE, 32).coerceIn(12, 96)
                mpv.setPropertyDouble("sub-font-size", size.toDouble())
            }

            KEY_SUB_SCALE -> {
                val scale = prefs.getInt(KEY_SUB_SCALE, 100).coerceIn(50, 200)
                mpv.setPropertyDouble("sub-scale", scale / 100.0)
            }

            KEY_SUB_COLOR -> {
                val color = prefs.getString(KEY_SUB_COLOR, "#FFFFFF") ?: "#FFFFFF"
                mpv.setPropertyString("sub-color", color)
            }

            KEY_SUB_BOLD -> {
                mpv.setPropertyBoolean("sub-bold", prefs.getBoolean(KEY_SUB_BOLD, false))
            }

            KEY_SUB_SHADOW_ENABLED, KEY_SUB_SHADOW_COLOR -> {
                // 文字背景：用阴影颜色在文字后形成一层底色
                val enabled = prefs.getBoolean(KEY_SUB_SHADOW_ENABLED, false)
                val color = prefs.getString(KEY_SUB_SHADOW_COLOR, "#80000000") ?: "#80000000"
                mpv.setPropertyString("sub-shadow-color", if (enabled) color else "#00000000")
            }

            KEY_SUB_BORDER_ENABLED, KEY_SUB_BORDER_COLOR, KEY_SUB_BORDER_SIZE -> {
                val enabled = prefs.getBoolean(KEY_SUB_BORDER_ENABLED, true)
                val color = prefs.getString(KEY_SUB_BORDER_COLOR, "#000000") ?: "#000000"
                val size = prefs.getInt(KEY_SUB_BORDER_SIZE, 2).coerceIn(0, 8)
                mpv.setPropertyString("sub-border-color", color)
                mpv.setPropertyDouble("sub-border-size", if (enabled) size.toDouble() else 0.0)
            }
        }
    }
}
