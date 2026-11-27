package `is`.xyz.mpv

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `is`.xyz.mpv.bridge.MpvClient
import `is`.xyz.mpv.bridge.PurePlayerSettingsBridge
import org.json.JSONArray
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 完整播放器 Activity：
 * - 集成 MPVView、PurePlayerSettingsBridge 与 SettingsActivity
 * - 支持 content:// 转 fd:// 安全读取与释放
 * - 支持 Android 8.0+ 原生画中画（PIP）、小窗播放/暂停控制与按 Home 键自动进入小窗
 * - 顶部 MX 风格功能菜单一排：手势对调、随机、循环、静音、定时关闭、A-B 重复、速度、截图、后台播放、屏幕旋转
 * - 同文件夹播放列表：上一个/下一个按同文件夹视频切换，随机时随机挑一个
 */
class PurePlayerActivity : AppCompatActivity(), MPVLib.EventObserver {

    companion object {
        private const val ACTION_PIP_TOGGLE_PAUSE = "com.example.player.ACTION_PIP_TOGGLE_PAUSE"
        private const val REQ_PIP_PLAY_PAUSE = 1001

        private const val PREF_GESTURE_SWAP = "gesture_swap"
        private const val PREF_SHUFFLE = "shuffle"
        private const val PREF_BACKGROUND_PLAY = "background_play"
        private const val PREF_ROTATION_LOCKED = "rotation_locked"
    }

    private lateinit var mpvView: MPVView
    private lateinit var playerOverlay: RelativeLayout
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnDecoderMode: TextView
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSubtitleTrack: ImageButton
    private lateinit var btnMoreMenu: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnUnlockFloating: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var gestureHud: LinearLayout
    private lateinit var tvGestureTitle: TextView
    private lateinit var tvGestureValue: TextView
    private lateinit var rvQuickActions: RecyclerView

    private lateinit var audioManager: AudioManager
    private lateinit var settingsBridge: PurePlayerSettingsBridge
    private lateinit var gestureDetector: GestureDetector
    private lateinit var prefs: SharedPreferences
    private lateinit var menuAdapter: PlayerMenuAdapter

    private var activePfd: ParcelFileDescriptor? = null
    private var isLocked = false
    private var isSeeking = false
    private var isPaused = false
    private var stickyVideoMode = "pip"
    private var totalDurationSec = 0.0
    private var currentPosSec = 0.0

    private enum class GestureMode { NONE, BRIGHTNESS, VOLUME, SEEK }
    private var gestureMode = GestureMode.NONE
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var pendingSeekSec = 0.0

    private val speedPresets = listOf(0.5, 1.0, 1.25, 1.5, 2.0)
    private var speedIndex = 1

    // ---- 顶部菜单一排的状态 ----
    private var gestureSwap = false
    private var shuffleEnabled = false
    private var loopEnabled = false
    private var isMuted = false
    private var backgroundPlayEnabled = false
    private var rotationLocked = false

    // ---- A-B 重复：0=未设置，1=A 点已设，2=A-B 循环中 ----
    private var abState = 0

    // ---- 定时关闭 ----
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    // ---- 同文件夹播放列表 ----
    private data class FolderVideo(val path: String, val contentUri: String, val name: String)
    private var folderPlaylist: List<FolderVideo> = emptyList()
    private var playlistIndex = 0
    private var playlistFolder: String? = null
    private var currentVideoPath: String? = null

    private data class TrackItem(
        val id: Int,
        val label: String,
        val selected: Boolean
    )

    private val mpvClientAdapter = object : MpvClient {
        override fun setOptionString(name: String, value: String) { MPVLib.setOptionString(name, value) }
        override fun setPropertyString(name: String, value: String) = MPVLib.setPropertyString(name, value)
        override fun setPropertyBoolean(name: String, value: Boolean) = MPVLib.setPropertyBoolean(name, value)
        override fun setPropertyInt(name: String, value: Int) = MPVLib.setPropertyInt(name, value)
        override fun setPropertyDouble(name: String, value: Double) = MPVLib.setPropertyDouble(name, value)
        override fun command(args: Array<String>) = MPVLib.command(args)
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE_PAUSE) {
                MPVLib.command(arrayOf("cycle", "pause"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pure_player)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        gestureSwap = prefs.getBoolean(PREF_GESTURE_SWAP, false)
        shuffleEnabled = prefs.getBoolean(PREF_SHUFFLE, false)
        backgroundPlayEnabled = prefs.getBoolean(PREF_BACKGROUND_PLAY, false)
        rotationLocked = prefs.getBoolean(PREF_ROTATION_LOCKED, false)
        if (rotationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        bindViews()

        stickyVideoMode = prefs.getString("sticky_video", "pip") ?: "pip"

        settingsBridge = PurePlayerSettingsBridge(
            prefs,
            mpvClientAdapter,
            object : PurePlayerSettingsBridge.UiSettingCallbacks {
                override fun onStickyModeChanged(videoMode: String, audioSticky: Boolean) {
                    stickyVideoMode = videoMode
                }
            }
        )

        mpvView.initialize(filesDir.path, cacheDir.path)
        settingsBridge.applyInitialOptions()
        observeMpvProperties()
        setupScreenshotDir()
        BackgroundPlaybackService.createNotificationChannel(this)

        setupControls()
        setupMenuRow()
        setupGestures()

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_TOGGLE_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val mediaUri: Uri? = intent?.data
        if (mediaUri != null) {
            val title = mediaUri.lastPathSegment ?: "本地视频"
            playVideo(mediaUri.toString(), title)
        } else {
            val fallbackPath = intent?.getStringExtra("video_path")
            if (!fallbackPath.isNullOrBlank()) {
                playVideo(fallbackPath, fallbackPath.substringAfterLast('/'))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settingsBridge.startSync()
    }

    override fun onStop() {
        settingsBridge.stopSync()
        super.onStop()
    }

    /**
     * 用户按 Home 键或切换任务时触发：若开启了画中画且正在播放，则自动进入小窗；
     * 若开启了后台播放，则不进小窗，音频在后台继续（由 BackgroundPlaybackService 保活）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (backgroundPlayEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            stickyVideoMode == "pip" &&
            !isPaused &&
            !isInPictureInPictureMode
        ) {
            enterPipMode()
        }
    }

    override fun onPause() {
        super.onPause()
        // 画中画或后台播放时保持继续播放，否则暂停
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !backgroundPlayEnabled) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }

    override fun onDestroy() {
        if (isFinishing && backgroundPlayEnabled) {
            stopService(Intent(this, BackgroundPlaybackService::class.java))
        }
        sleepHandler.removeCallbacksAndMessages(null)
        sleepRunnable = null
        unregisterReceiver(pipActionReceiver)
        MPVLib.removeObserver(this)
        mpvView.destroy()
        activePfd?.close()
        activePfd = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val params = buildPipParams()
        enterPictureInPictureMode(params)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val aspect = computePipAspectRatio()
        val actionIcon = Icon.createWithResource(
            this,
            if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
        )
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQ_PIP_PLAY_PAUSE,
            Intent(ACTION_PIP_TOGGLE_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = RemoteAction(
            actionIcon,
            if (isPaused) "播放" else "暂停",
            if (isPaused) "恢复播放" else "暂停播放",
            pendingIntent
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(listOf(playPauseAction))
            .build()
    }

    private fun computePipAspectRatio(): Rational {
        val rawAspect = MPVLib.getPropertyDouble("video-params/aspect") ?: (16.0 / 9.0)
        // Android PIP 强制要求宽高比在 [0.4184, 2.39] 之间
        val clamped = rawAspect.coerceIn(0.42, 2.39)
        return Rational((clamped * 100).toInt(), 100)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerOverlay.visibility = View.GONE
            btnUnlockFloating.visibility = View.GONE
            gestureHud.visibility = View.GONE
        } else if (!isLocked) {
            playerOverlay.visibility = View.VISIBLE
        }
    }

    private fun resolvePlayableUri(uri: Uri): Pair<String, ParcelFileDescriptor?> {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                return "fd://${pfd.fd}" to pfd
            }
        }
        return uri.toString() to null
    }

    /**
     * 统一播放入口：支持 content://、本地文件路径、http(s) 链接。
     * 换片时关闭旧的 ParcelFileDescriptor，重置 A-B 循环与进度，并重建同文件夹列表。
     */
    private fun playVideo(source: String, title: String) {
        activePfd?.close()
        activePfd = null
        currentVideoPath = null

        var playable = source
        when {
            source.startsWith("content://") -> {
                val uri = Uri.parse(source)
                currentVideoPath = filePathOfContentUri(uri)
                val (resolved, pfd) = resolvePlayableUri(uri)
                activePfd = pfd
                playable = resolved
            }
            source.startsWith("http://") || source.startsWith("https://") ||
                source.startsWith("fd://") -> {
                // 网络地址或已解析的 fd，直接播
            }
            else -> {
                // 本地文件路径
                currentVideoPath = source
            }
        }

        tvTitle.text = title
        // 换片重置 A-B 循环与进度
        abState = 0
        MPVLib.setPropertyString("ab-loop-a", "no")
        MPVLib.setPropertyString("ab-loop-b", "no")
        totalDurationSec = 0.0
        currentPosSec = 0.0
        mpvView.playFile(playable)
        buildFolderPlaylist()
        refreshMenu()
    }

    /** content:// Uri 在 MediaStore 里对应的文件路径（用于找同文件夹视频），拿不到返回 null */
    private fun filePathOfContentUri(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 构建当前视频所在文件夹的播放列表（按文件名排序），同一文件夹时复用不重复查 */
    private fun buildFolderPlaylist() {
        val currentPath = currentVideoPath
        if (currentPath.isNullOrBlank()) {
            folderPlaylist = emptyList()
            playlistIndex = 0
            playlistFolder = null
            return
        }
        val folder = File(currentPath).parent
        if (folder.isNullOrBlank()) {
            folderPlaylist = emptyList()
            playlistIndex = 0
            playlistFolder = null
            return
        }
        if (folder == playlistFolder && folderPlaylist.isNotEmpty()) {
            playlistIndex = folderPlaylist.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
            return
        }
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )
        val list = mutableListOf<FolderVideo>()
        try {
            contentResolver.query(
                uri, projection, null, null,
                "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataCol) ?: continue
                    if (File(data).parent != folder) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val contentUri = ContentUris.withAppendedId(uri, id).toString()
                    list.add(FolderVideo(data, contentUri, name))
                }
            }
        } catch (_: Exception) {
        }
        folderPlaylist = list
        playlistFolder = folder
        playlistIndex = list.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
    }

    private fun playAt(index: Int) {
        if (index !in folderPlaylist.indices) return
        playlistIndex = index
        val video = folderPlaylist[index]
        playVideo(video.contentUri, video.name)
    }

    private fun playNext() {
        if (folderPlaylist.size < 2) {
            Toast.makeText(this, "当前文件夹没有其他视频", Toast.LENGTH_SHORT).show()
            return
        }
        val next = if (shuffleEnabled) {
            var idx = playlistIndex
            while (idx == playlistIndex) idx = (0 until folderPlaylist.size).random()
            idx
        } else {
            (playlistIndex + 1) % folderPlaylist.size
        }
        playAt(next)
    }

    private fun playPrev() {
        if (folderPlaylist.size < 2) {
            Toast.makeText(this, "当前文件夹没有其他视频", Toast.LENGTH_SHORT).show()
            return
        }
        val prev = if (shuffleEnabled) {
            var idx = playlistIndex
            while (idx == playlistIndex) idx = (0 until folderPlaylist.size).random()
            idx
        } else {
            (playlistIndex - 1 + folderPlaylist.size) % folderPlaylist.size
        }
        playAt(prev)
    }

    private fun bindViews() {
        mpvView = findViewById(R.id.mpv_view)
        playerOverlay = findViewById(R.id.player_overlay)
        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        btnDecoderMode = findViewById(R.id.btn_decoder_mode)
        btnAudioTrack = findViewById(R.id.btn_audio_track)
        btnSubtitleTrack = findViewById(R.id.btn_subtitle_track)
        btnMoreMenu = findViewById(R.id.btn_more_menu)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspectRatio = findViewById(R.id.btn_aspect_ratio)
        btnLock = findViewById(R.id.btn_lock)
        btnUnlockFloating = findViewById(R.id.btn_unlock_floating)
        seekBar = findViewById(R.id.seekbar_progress)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalDuration = findViewById(R.id.tv_total_duration)
        loadingIndicator = findViewById(R.id.loading_indicator)
        gestureHud = findViewById(R.id.gesture_hud)
        tvGestureTitle = findViewById(R.id.tv_gesture_title)
        tvGestureValue = findViewById(R.id.tv_gesture_value)
        rvQuickActions = findViewById(R.id.rv_quick_actions)
    }

    private fun observeMpvProperties() {
        MPVLib.addObserver(this)
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    private fun setupControls() {
        btnBack.setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            MPVLib.command(arrayOf("cycle", "pause"))
        }

        btnPrev.setOnClickListener { playPrev() }
        btnNext.setOnClickListener { playNext() }

        btnDecoderMode.setOnClickListener {
            val current = MPVLib.getPropertyString("hwdec-current") ?: "no"
            val next = if (current == "no") "mediacodec-copy" else "no"
            MPVLib.setPropertyString("hwdec", next)
        }

        btnAudioTrack.setOnClickListener {
            showTrackPickerDialog(trackType = "audio", dialogTitle = "选择音轨", propName = "aid")
        }

        btnSubtitleTrack.setOnClickListener {
            showTrackPickerDialog(trackType = "sub", dialogTitle = "选择字幕", propName = "sid")
        }

        btnAspectRatio.setOnClickListener {
            showAspectRatioDialog()
        }

        btnMoreMenu.setOnClickListener {
            startActivity(Intent(this, PureSettingsActivity::class.java))
        }

        btnSpeed.setOnClickListener {
            cycleSpeed()
        }

        btnLock.setOnClickListener { setLockState(true) }
        btnUnlockFloating.setOnClickListener { setLockState(false) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && totalDurationSec > 0) {
                    val targetSec = totalDurationSec * (progress / 1000.0)
                    tvCurrentTime.text = formatTime(targetSec)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (totalDurationSec > 0 && bar != null) {
                    val targetSec = totalDurationSec * (bar.progress / 1000.0)
                    MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute"))
                }
                isSeeking = false
            }
        })
    }

    /** 倍速循环切换：0.5 / 1.0 / 1.25 / 1.5 / 2.0，底部按钮与顶部菜单共用 */
    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speedPresets.size
        val nextSpeed = speedPresets[speedIndex]
        MPVLib.setPropertyDouble("speed", nextSpeed)
        btnSpeed.text = "${nextSpeed}x"
        refreshMenu()
    }

    // ========================
    // 顶部功能菜单一排
    // 顺序（用户定）：手势对调、随机、循环、静音、定时关闭、A-B 重复、速度、截图、后台播放、屏幕旋转
    // ========================

    private fun setupMenuRow() {
        menuAdapter = PlayerMenuAdapter { item -> onMenuItemClick(item.id) }
        rvQuickActions.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvQuickActions.adapter = menuAdapter
        refreshMenu()
    }

    private fun buildMenuItems(): List<PlayerMenuItem> {
        return listOf(
            PlayerMenuItem("gesture_swap", "手势对调", R.drawable.round_gesture_24, gestureSwap),
            PlayerMenuItem("shuffle", "随机", R.drawable.ic_shuffle_24dp, shuffleEnabled),
            PlayerMenuItem("loop", "循环", R.drawable.ic_repeat_24dp, loopEnabled),
            PlayerMenuItem("mute", "静音", R.drawable.ic_volume_off, isMuted),
            PlayerMenuItem(
                "sleep", "定时关闭", R.drawable.ic_timer,
                sleepRunnable != null
            ),
            PlayerMenuItem("ab", "A-B 重复", R.drawable.ic_ab_repeat, abState != 0),
            PlayerMenuItem(
                "speed", "${speedPresets[speedIndex]}x", R.drawable.ic_speed, false
            ),
            PlayerMenuItem("screenshot", "截图", R.drawable.ic_screenshot, false),
            PlayerMenuItem(
                "background", "后台播放", R.drawable.ic_picture_in_picture_24dp,
                backgroundPlayEnabled
            ),
            PlayerMenuItem(
                "rotation", "屏幕旋转", R.drawable.ic_screen_rotate, rotationLocked
            )
        )
    }

    private fun refreshMenu() {
        if (!::menuAdapter.isInitialized) return
        menuAdapter.submitItems(buildMenuItems())
    }

    private fun onMenuItemClick(id: String) {
        when (id) {
            "gesture_swap" -> toggleGestureSwap()
            "shuffle" -> toggleShuffle()
            "loop" -> toggleLoop()
            "mute" -> toggleMute()
            "sleep" -> onSleepMenuClick()
            "ab" -> onAbMenuClick()
            "speed" -> cycleSpeed()
            "screenshot" -> takeScreenshot()
            "background" -> toggleBackgroundPlay()
            "rotation" -> toggleRotationLock()
        }
    }

    /** 手势对调：左滑亮度/右滑音量 与 左滑音量/右滑亮度 互换，状态存 SharedPreferences */
    private fun toggleGestureSwap() {
        gestureSwap = !gestureSwap
        prefs.edit().putBoolean(PREF_GESTURE_SWAP, gestureSwap).apply()
        Toast.makeText(
            this,
            if (gestureSwap) "手势已对调：左滑音量，右滑亮度" else "手势已恢复：左滑亮度，右滑音量",
            Toast.LENGTH_SHORT
        ).show()
        refreshMenu()
    }

    private fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        prefs.edit().putBoolean(PREF_SHUFFLE, shuffleEnabled).apply()
        Toast.makeText(
            this,
            if (shuffleEnabled) "随机播放已开" else "随机播放已关",
            Toast.LENGTH_SHORT
        ).show()
        refreshMenu()
    }

    /** 循环：mpv loop-file 在 no 与 inf 之间切换 */
    private fun toggleLoop() {
        loopEnabled = !loopEnabled
        MPVLib.setPropertyString("loop-file", if (loopEnabled) "inf" else "no")
        Toast.makeText(
            this,
            if (loopEnabled) "循环播放已开" else "循环播放已关",
            Toast.LENGTH_SHORT
        ).show()
        refreshMenu()
    }

    private fun toggleMute() {
        val current = MPVLib.getPropertyBoolean("mute") ?: false
        isMuted = !current
        MPVLib.setPropertyBoolean("mute", isMuted)
        refreshMenu()
    }

    // ---- 定时关闭 ----
    private fun onSleepMenuClick() {
        if (sleepRunnable != null) {
            cancelSleepTimer()
            return
        }
        val labels = arrayOf("10分钟", "15分钟", "30分钟", "45分钟", "60分钟", "90分钟")
        val minutes = intArrayOf(10, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("定时关闭")
            .setItems(labels) { dialog, which ->
                startSleepTimer(minutes[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepRunnable = Runnable {
            sleepRunnable = null
            MPVLib.setPropertyBoolean("pause", true)
            refreshMenu()
            finish()
        }
        sleepHandler.postDelayed(sleepRunnable!!, minutes * 60L * 1000L)
        Toast.makeText(this, "定时关闭：${minutes}分钟后停止播放", Toast.LENGTH_SHORT).show()
        refreshMenu()
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        Toast.makeText(this, "定时关闭已取消", Toast.LENGTH_SHORT).show()
        refreshMenu()
    }

    // ---- A-B 重复 ----
    private fun onAbMenuClick() {
        when (abState) {
            0 -> {
                MPVLib.setPropertyDouble("ab-loop-a", currentPosSec)
                abState = 1
                Toast.makeText(this, "A点已设 ${formatTime(currentPosSec)}", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                MPVLib.setPropertyDouble("ab-loop-b", currentPosSec)
                abState = 2
                Toast.makeText(this, "A-B 循环开始", Toast.LENGTH_SHORT).show()
            }
            else -> {
                MPVLib.setPropertyString("ab-loop-a", "no")
                MPVLib.setPropertyString("ab-loop-b", "no")
                abState = 0
                Toast.makeText(this, "A-B 循环已清除", Toast.LENGTH_SHORT).show()
            }
        }
        refreshMenu()
    }

    // ---- 截图 ----
    private fun setupScreenshotDir() {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PurePlayer")
        if (!dir.exists()) dir.mkdirs()
        MPVLib.setPropertyString("screenshot-directory", dir.absolutePath)
    }

    private fun takeScreenshot() {
        MPVLib.command(arrayOf("screenshot"))
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PurePlayer")
        Toast.makeText(this, "已截图，保存在：${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }

    // ---- 后台播放 ----
    private fun toggleBackgroundPlay() {
        if (!backgroundPlayEnabled) {
            BackgroundPlaybackService.createNotificationChannel(this)
            val intent = Intent(this, BackgroundPlaybackService::class.java)
            try {
                ContextCompat.startForegroundService(this, intent)
                backgroundPlayEnabled = true
                prefs.edit().putBoolean(PREF_BACKGROUND_PLAY, true).apply()
                Toast.makeText(this, "后台播放已开：离开播放器后音频继续", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "后台播放启动失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            stopService(Intent(this, BackgroundPlaybackService::class.java))
            backgroundPlayEnabled = false
            prefs.edit().putBoolean(PREF_BACKGROUND_PLAY, false).apply()
            Toast.makeText(this, "后台播放已关", Toast.LENGTH_SHORT).show()
        }
        refreshMenu()
    }

    // ---- 屏幕旋转 ----
    private fun toggleRotationLock() {
        rotationLocked = !rotationLocked
        prefs.edit().putBoolean(PREF_ROTATION_LOCKED, rotationLocked).apply()
        requestedOrientation = if (rotationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        Toast.makeText(
            this,
            if (rotationLocked) "已锁定横屏" else "屏幕旋转已恢复跟随系统",
            Toast.LENGTH_SHORT
        ).show()
        refreshMenu()
    }

    private fun showTrackPickerDialog(trackType: String, dialogTitle: String, propName: String) {
        val rawJson = MPVLib.getPropertyString("track-list") ?: "[]"
        val tracks = mutableListOf<TrackItem>()

        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("type") == trackType) {
                    val id = obj.optInt("id", -1)
                    val title = obj.optString("title").takeIf { it.isNotBlank() }
                    val lang = obj.optString("lang").takeIf { it.isNotBlank() }
                    val codec = obj.optString("codec").takeIf { it.isNotBlank() }
                    val selected = obj.optBoolean("selected", false)

                    val parts = listOfNotNull(title, lang, codec?.uppercase(Locale.US))
                    val display = if (parts.isEmpty()) "轨道 #$id" else parts.joinToString(" · ")
                    tracks.add(TrackItem(id, display, selected))
                }
            }
        } catch (_: Exception) {
        }

        val labels = mutableListOf("关闭 / 禁用")
        labels.addAll(tracks.map { it.label })

        val checkedIdx = tracks.indexOfFirst { it.selected }.let { if (it >= 0) it + 1 else 0 }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIdx) { dialog, which ->
                if (which == 0) {
                    MPVLib.setPropertyString(propName, "no")
                } else {
                    MPVLib.setPropertyInt(propName, tracks[which - 1].id)
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf(
            "适应屏幕 (默认)" to Pair("-1", 0.0),
            "铺满裁剪 (Crop)" to Pair("-1", 1.0),
            "拉伸全屏 (16:9)" to Pair("16:9", 0.0),
            "4:3" to Pair("4:3", 0.0),
            "21:9" to Pair("21:9", 0.0)
        )
        val labels = options.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setItems(labels) { dialog, which ->
                val (aspect, panscan) = options[which].second
                MPVLib.setPropertyString("video-aspect-override", aspect)
                MPVLib.setPropertyDouble("panscan", panscan)
                dialog.dismiss()
            }
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    btnUnlockFloating.visibility =
                        if (btnUnlockFloating.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    playerOverlay.visibility =
                        if (playerOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    MPVLib.command(arrayOf("cycle", "pause"))
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (gestureMode == GestureMode.NONE) {
                    gestureMode = if (abs(dx) > abs(dy)) {
                        pendingSeekSec = currentPosSec
                        GestureMode.SEEK
                    } else {
                        // 左半边默认亮度、右半边默认音量；手势对调后反过来
                        val leftIsBrightness = !gestureSwap
                        val isLeft = e1.x < mpvView.width / 2f
                        val useBrightness = if (isLeft) leftIsBrightness else !leftIsBrightness
                        if (useBrightness) {
                            val lp = window.attributes
                            startBrightness = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                            GestureMode.BRIGHTNESS
                        } else {
                            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            GestureMode.VOLUME
                        }
                    }
                }

                when (gestureMode) {
                    GestureMode.BRIGHTNESS -> {
                        val delta = -dy / mpvView.height
                        val newVal = (startBrightness + delta).coerceIn(0.01f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = newVal
                        window.attributes = lp
                        showHud("亮度", "${(newVal * 100).toInt()}%")
                    }

                    GestureMode.VOLUME -> {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta = ((-dy / mpvView.height) * maxVol).toInt()
                        val newVol = (startVolume + delta).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        showHud("音量", "${(newVol * 100 / maxVol)}%")
                    }

                    GestureMode.SEEK -> {
                        val offsetSec = (dx / mpvView.width) * 90.0
                        pendingSeekSec = (currentPosSec + offsetSec).coerceIn(0.0, totalDurationSec)
                        showHud("定位", formatTime(pendingSeekSec))
                    }

                    GestureMode.NONE -> Unit
                }
                return true
            }
        })

        mpvView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (gestureMode == GestureMode.SEEK) {
                    MPVLib.command(arrayOf("seek", pendingSeekSec.toString(), "absolute"))
                }
                gestureMode = GestureMode.NONE
                gestureHud.visibility = View.GONE
            }
            true
        }
    }

    private fun setLockState(locked: Boolean) {
        isLocked = locked
        playerOverlay.visibility = if (locked) View.GONE else View.VISIBLE
        btnUnlockFloating.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun showHud(title: String, value: String) {
        gestureHud.visibility = View.VISIBLE
        tvGestureTitle.text = title
        tvGestureValue.text = value
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Double) {
        runOnUiThread {
            when (property) {
                "duration" -> {
                    totalDurationSec = value
                    tvTotalDuration.text = formatTime(value)
                }

                "time-pos" -> {
                    currentPosSec = value
                    if (!isSeeking && totalDurationSec > 0) {
                        tvCurrentTime.text = formatTime(value)
                        seekBar.progress = ((value / totalDurationSec) * 1000).toInt().coerceIn(0, 1000)
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        runOnUiThread {
            when (property) {
                "pause" -> {
                    isPaused = value
                    btnPlayPause.setImageResource(
                        if (value) R.drawable.ic_play
                        else R.drawable.ic_pause
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                        setPictureInPictureParams(buildPipParams())
                    }
                }

                "paused-for-cache" -> {
                    loadingIndicator.visibility = if (value) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        runOnUiThread {
            if (property == "hwdec-current") {
                btnDecoderMode.text = if (value == "no" || value.isBlank()) "SW" else "HW+"
            }
        }
    }

    override fun event(eventId: Int) = Unit
}
