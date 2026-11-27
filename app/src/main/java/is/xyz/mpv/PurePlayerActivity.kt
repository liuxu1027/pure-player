package `is`.xyz.mpv

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaScannerConnection
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        // 首页“上次播放标记 / 悬浮继续播放 / 播放历史”用的记录
        const val PREF_LAST_PLAYED_PATH = "last_played_path"
        const val PREF_HISTORY_PATHS = "play_history_paths"

        // 控制层无操作多久后自动隐藏（毫秒）
        private const val CONTROLS_DISPLAY_TIMEOUT = 4000L
    }

    private lateinit var mpvView: MPVView
    private lateinit var playerRoot: FrameLayout
    private lateinit var playerOverlay: RelativeLayout
    private lateinit var topToolbar: LinearLayout
    private lateinit var bottomController: LinearLayout
    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnDecoderMode: TextView
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSubtitleTrack: ImageButton
    private lateinit var btnMoreMenu: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnBigPlay: ImageButton
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

    // ---- 字幕覆盖层（同步 / 速度悬浮条） ----
    private lateinit var subSyncBar: LinearLayout
    private lateinit var etSyncValue: EditText
    private lateinit var subSpeedBar: LinearLayout
    private lateinit var etSubSpeedValue: EditText

    private lateinit var audioManager: AudioManager
    private lateinit var settingsBridge: PurePlayerSettingsBridge
    private lateinit var gestureDetector: GestureDetector
    private lateinit var prefs: SharedPreferences
    private lateinit var menuAdapter: PlayerMenuAdapter

    // 外挂字幕选择器：挑一个字幕文件，拷到缓存后 sub-add
    private val subPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) addExternalSubtitle(uri)
    }

    // 字幕颜色选项（名 -> mpv 颜色值 #RRGGBB / #RRGGBBAA）
    private val subTextColors = listOf(
        "白色" to "#FFFFFF",
        "黄色" to "#FFFF00",
        "绿色" to "#00FF00",
        "青色" to "#00FFFF",
        "蓝色" to "#3c8cf0",
        "紫色" to "#FF00FF",
        "红色" to "#FF0000",
        "橙色" to "#FFA500",
        "黑色" to "#000000"
    )
    private val subBgColors = listOf(
        "半透明黑" to "#CC000000",
        "黑色" to "#FF000000",
        "白色" to "#FFFFFFFF",
        "半透明白" to "#CCFFFFFF",
        "红色" to "#CCFF0000",
        "蓝色" to "#CC0000FF"
    )
    private val subFonts = listOf(
        "默认" to "",
        "无衬线" to "sans-serif",
        "衬线" to "serif",
        "等宽" to "monospace"
    )

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
    // 循环三态：0=关，1=列表循环，2=单只循环（与 MPVView.getRepeat() 的返回值一致）
    private var loopMode = 0
    private var isMuted = false
    private var backgroundPlayEnabled = false
    private var rotationLocked = false

    // ---- 控制层自动隐藏：无操作几秒后连同顶部菜单一排一起隐藏，点一下再出现 ----
    private val controlsHideHandler = Handler(Looper.getMainLooper())
    private val controlsHideRunnable = Runnable { hideControls() }
    private var hasVideo = false

    // ---- 列表循环：手动切歌也会让旧文件走一次 END_FILE，用该标志忽略这一次 ----
    @Volatile
    private var ignoreNextEndFile = false

    // ---- A-B 重复：0=未设置，1=A 点已设，2=A-B 循环中 ----
    private var abState = 0

    // ---- 定时关闭 ----
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    // ---- 截图：mpv 先写到私有目录（中转），落盘后再搬到公开相册 ----
    private val screenshotHandler = Handler(Looper.getMainLooper())
    private var screenshotPollRunnable: Runnable? = null

    // ---- 同文件夹播放列表 ----
    private data class FolderVideo(val path: String, val contentUri: String, val name: String)
    private var folderPlaylist: List<FolderVideo> = emptyList()
    private var playlistIndex = 0
    private var playlistFolder: String? = null
    private var currentVideoPath: String? = null
    // 多选播放时由调用方直接给一列视频（不按文件夹重查）
    private var isCustomPlaylist = false

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
        setupEdgeToEdge()

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
        setupSubtitleBars()
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
            // 多选播放：调用方给一列 content:// 地址 + 起始下标
            val uris = intent?.getStringArrayListExtra("video_uris")
            if (!uris.isNullOrEmpty()) {
                val startIndex = intent.getIntExtra("video_index", 0)
                    .coerceIn(0, uris.size - 1)
                setupCustomPlaylist(uris, startIndex)
            } else {
                val fallbackPath = intent?.getStringExtra("video_path")
                if (!fallbackPath.isNullOrBlank()) {
                    playVideo(fallbackPath, fallbackPath.substringAfterLast('/'))
                }
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

    override fun onResume() {
        super.onResume()
        if (playerOverlay.visibility == View.VISIBLE) scheduleControlsHide()
    }

    override fun onPause() {
        super.onPause()
        controlsHideHandler.removeCallbacks(controlsHideRunnable)
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
        controlsHideHandler.removeCallbacksAndMessages(null)
        screenshotPollRunnable?.let { screenshotHandler.removeCallbacks(it) }
        screenshotPollRunnable = null
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
            controlsHideHandler.removeCallbacks(controlsHideRunnable)
            playerOverlay.visibility = View.GONE
            btnUnlockFloating.visibility = View.GONE
            gestureHud.visibility = View.GONE
        } else if (!isLocked) {
            showControls()
        }
        updateBigPlayVisibility()
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

        // 记录上次播放（首页“上次播放标记 / 悬浮按钮 / 播放历史”用）
        currentVideoPath?.takeIf { it.startsWith("/") }?.let { saveLastPlayed(it) }

        tvTitle.text = title
        // 换片重置 A-B 循环与进度
        abState = 0
        MPVLib.setPropertyString("ab-loop-a", "no")
        MPVLib.setPropertyString("ab-loop-b", "no")
        totalDurationSec = 0.0
        currentPosSec = 0.0
        // 手动切歌会让旧文件走一次 END_FILE，先立标志，event() 里忽略那一次；
        // 新文件开始加载（START_FILE）时清掉，之后自然播完的 END_FILE 才能正常触发列表循环
        ignoreNextEndFile = true
        hasVideo = true
        mpvView.playFile(playable)
        buildFolderPlaylist()
        refreshMenu()
        scheduleControlsHide()
    }

    /** content:// Uri 在 MediaStore 里对应的文件路径与显示名，拿不到返回 null */
    private fun videoInfoOfContentUri(uri: Uri): Pair<String?, String?> {
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val p = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    val n = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    p to n
                } else {
                    null to null
                }
            } ?: (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    /** content:// Uri 在 MediaStore 里对应的文件路径（用于找同文件夹视频），拿不到返回 null */
    private fun filePathOfContentUri(uri: Uri): String? = videoInfoOfContentUri(uri).first

    /** 记录上次播放：最新一条 + 最近 20 条历史（去重，最新的在前） */
    private fun saveLastPlayed(path: String) {
        try {
            prefs.edit().putString(PREF_LAST_PLAYED_PATH, path).apply()
            val raw = prefs.getString(PREF_HISTORY_PATHS, null)
            val arr = if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i)
                if (p.isNotBlank() && p != path) list.add(p)
            }
            list.add(0, path)
            val out = JSONArray()
            list.take(20).forEach { out.put(it) }
            prefs.edit().putString(PREF_HISTORY_PATHS, out.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 多选播放：调用方直接给一列视频地址，按给定顺序播。
     * 之后的上一个/下一个、播完自动进下一个都走这列，不按文件夹重查。
     */
    private fun setupCustomPlaylist(uris: List<String>, startIndex: Int) {
        isCustomPlaylist = true
        playlistFolder = null
        folderPlaylist = uris.map { uriString ->
            if (uriString.startsWith("content://")) {
                val uri = Uri.parse(uriString)
                val (path, name) = videoInfoOfContentUri(uri)
                FolderVideo(
                    path ?: uriString,
                    uriString,
                    name ?: uriString.substringAfterLast('/')
                )
            } else {
                FolderVideo(uriString, uriString, uriString.substringAfterLast('/'))
            }
        }
        playlistIndex = startIndex.coerceIn(folderPlaylist.indices)
        val first = folderPlaylist[playlistIndex]
        playVideo(first.contentUri, first.name)
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
        // 自定义播放列表（多选播放）：不按文件夹重查，只更新当前下标
        if (isCustomPlaylist) {
            playlistIndex = folderPlaylist.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
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
        playerRoot = findViewById(R.id.player_root)
        playerOverlay = findViewById(R.id.player_overlay)
        topToolbar = findViewById(R.id.top_toolbar)
        bottomController = findViewById(R.id.bottom_controller)
        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        btnDecoderMode = findViewById(R.id.btn_decoder_mode)
        btnAudioTrack = findViewById(R.id.btn_audio_track)
        btnSubtitleTrack = findViewById(R.id.btn_subtitle_track)
        btnMoreMenu = findViewById(R.id.btn_more_menu)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnBigPlay = findViewById(R.id.btn_big_play)
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
        subSyncBar = findViewById(R.id.sub_sync_bar)
        etSyncValue = findViewById(R.id.et_sync_value)
        subSpeedBar = findViewById(R.id.sub_speed_bar)
        etSubSpeedValue = findViewById(R.id.et_sub_speed_value)
    }

    /**
     * 全面屏（不改 styles.xml，全部用代码做）：
     * 内容延伸到状态栏/刘海/导航栏后面，视频本身占满全屏；
     * 顶部工具栏顶边 = 状态栏与刘海高度的较大值，底部控制栏底边 = 导航栏高度，
     * 刘海屏用 shortEdges 让内容可以伸进刘海两侧。
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // player_overlay 在布局里写了 fitsSystemWindows=true；这里关掉，
        // 改由下面的手动边距一次性处理，避免系统内边距和手动边距叠加
        playerOverlay.fitsSystemWindows = false
        insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.setOnApplyWindowInsetsListener(playerRoot) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val top = statusBar.top.coerceAtLeast(cutout.top)
            (topToolbar.layoutParams as ViewGroup.MarginLayoutParams).topMargin = top
            (bottomController.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                navBar.bottom
            topToolbar.requestLayout()
            bottomController.requestLayout()
            insets
        }
    }

    /**
     * 显示控制层（含顶部菜单一排 rv_quick_actions，它在 player_overlay 里面，
     * 跟着控制层一起显示/隐藏），同时显示系统栏，并重新开始自动隐藏计时。
     */
    private fun showControls() {
        if (isLocked) return
        playerOverlay.visibility = View.VISIBLE
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        updateBigPlayVisibility()
        scheduleControlsHide()
    }

    /** 隐藏控制层（含顶部菜单一排），同时隐藏系统栏（从屏幕边缘下滑可临时唤出） */
    private fun hideControls() {
        controlsHideHandler.removeCallbacks(controlsHideRunnable)
        playerOverlay.visibility = View.GONE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        updateBigPlayVisibility()
    }

    /**
     * 无操作几秒后自动隐藏控制层。暂停、锁定、未加载视频时不自动隐藏，
     * 这样暂停时中央大播放按钮和底部控制栏一直可用。
     */
    private fun scheduleControlsHide() {
        controlsHideHandler.removeCallbacks(controlsHideRunnable)
        if (hasVideo && !isPaused && !isLocked) {
            controlsHideHandler.postDelayed(controlsHideRunnable, CONTROLS_DISPLAY_TIMEOUT)
        }
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

        // 中央大播放按钮：只在暂停时出现，点一下继续播放
        btnBigPlay.setOnClickListener {
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
            showSubtitleMenu()
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
                controlsHideHandler.removeCallbacks(controlsHideRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (totalDurationSec > 0 && bar != null) {
                    val targetSec = totalDurationSec * (bar.progress / 1000.0)
                    MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute"))
                }
                isSeeking = false
                scheduleControlsHide()
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
            // 循环三态：关 = 普通循环图标（未激活态），列表循环 = 循环图标（激活态），
            // 单只循环 = 单曲循环图标（激活态）
            PlayerMenuItem(
                "loop", "循环",
                if (loopMode == 2) R.drawable.ic_repeat_one_24dp else R.drawable.ic_repeat_24dp,
                loopMode != 0
            ),
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
        // 点了顶部菜单也算一次操作，重新开始自动隐藏计时
        scheduleControlsHide()
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

    /**
     * 循环三态：关 → 列表循环 → 单只循环 → 关（复用 MPVView.cycleRepeat()/getRepeat()）。
     * 列表循环不靠 mpv loop-playlist（mpv 内部列表永远只有当前一个文件），
     * 靠 MPV_EVENT_END_FILE 回调里调 playNext() 切同文件夹下一个；
     * 单只循环靠 mpv loop-file=inf。
     */
    private fun toggleLoop() {
        mpvView.cycleRepeat()
        loopMode = mpvView.getRepeat()
        Toast.makeText(
            this,
            when (loopMode) {
                1 -> "循环：列表循环"
                2 -> "循环：单只循环"
                else -> "循环已关"
            },
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
        // 只有真的在跑的定时器才提示：startSleepTimer() 里先取消再设置，
        // 没有这句判断会先弹出“已取消”再弹出“已设置”，出现两条 Toast
        val hadTimer = sleepRunnable != null
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        if (hadTimer) {
            Toast.makeText(this, "定时关闭已取消", Toast.LENGTH_SHORT).show()
        }
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

    // ---- 截图：私有目录只做中转，落盘后搬到公开相册 ----
    private fun screenshotStagingDir(): File {
        return File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PurePlayer").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun setupScreenshotDir() {
        // mpv 截图先写到私有目录（中转）；takeScreenshot() 等文件落盘后
        // 再搬到公开相册 Pictures/PurePlayer 并删掉私有副本
        MPVLib.setPropertyString("screenshot-directory", screenshotStagingDir().absolutePath)
    }

    private fun takeScreenshot() {
        val dir = screenshotStagingDir()
        val existing = dir.listFiles()?.map { it.name }.orEmpty().toSet()
        MPVLib.command(arrayOf("screenshot"))
        // mpv 截图是异步写盘：轮询等新文件出现（最多约 5 秒），再搬到公开相册
        screenshotPollRunnable?.let { screenshotHandler.removeCallbacks(it) }
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                attempts++
                val newest = dir.listFiles()
                    ?.filter { it.isFile && it.name !in existing }
                    ?.maxByOrNull { it.lastModified() }
                if (newest != null) {
                    publishScreenshot(newest)
                } else if (attempts < 16) {
                    screenshotHandler.postDelayed(this, 300L)
                } else {
                    Toast.makeText(this@PurePlayerActivity, "截图超时，请重试", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        screenshotPollRunnable = poll
        screenshotHandler.postDelayed(poll, 300L)
    }

    /** 把中转目录里的截图搬到公开相册 Pictures/PurePlayer，成功后删掉私有副本 */
    private fun publishScreenshot(file: File) {
        val mime = if (file.name.endsWith(".jpg", ignoreCase = true) ||
            file.name.endsWith(".jpeg", ignoreCase = true)
        ) "image/jpeg" else "image/png"
        val publicPath = "Pictures/PurePlayer/${file.name}"
        var ok = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PurePlayer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                    contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        },
                        null, null
                    )
                    ok = true
                }
            } else {
                // Android 9 及以下没有 RELATIVE_PATH：直接写公开目录再通知媒体库扫描
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PurePlayer"
                ).apply { if (!exists()) mkdirs() }
                val dest = File(publicDir, file.name)
                file.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    this, arrayOf(dest.absolutePath), arrayOf(mime), null
                )
                ok = true
            }
        } catch (_: Exception) {
            ok = false
        }
        if (ok) {
            file.delete()
            Toast.makeText(this, "已保存到相册：$publicPath", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "截图保存失败", Toast.LENGTH_SHORT).show()
        }
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

    /** 从 mpv track-list 里取指定类型的轨道（音频/字幕共用） */
    private fun fetchTracks(trackType: String): List<TrackItem> {
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
        return tracks
    }

    private fun showTrackPickerDialog(trackType: String, dialogTitle: String, propName: String) {
        val tracks = fetchTracks(trackType)

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

    // ========================
    // 字幕系统（MX 风格）
    // 菜单：选轨道 / 打开外挂字幕 / 设置（同步、速度、自定义）
    // ========================

    private fun showSubtitleMenu() {
        val view = layoutInflater.inflate(R.layout.dialog_subtitle_menu, null)
        val containerTracks = view.findViewById<LinearLayout>(R.id.container_tracks)
        val tvSyncValue = view.findViewById<TextView>(R.id.tv_sync_value)
        val tvSpeedValue = view.findViewById<TextView>(R.id.tv_speed_value)

        val curDelay = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
        tvSyncValue.text = formatDelayText(curDelay)
        val curSpeed = MPVLib.getPropertyDouble("sub-speed") ?: 1.0
        tvSpeedValue.text = "${(curSpeed * 100).toInt()}%"

        val tracks = fetchTracks("sub")
        val noneSelected = tracks.none { it.selected }

        lateinit var dialog: AlertDialog
        fun addTrack(label: String, selected: Boolean, onPick: () -> Unit) {
            addSubTrackRow(containerTracks, label, selected) {
                onPick()
                dialog.dismiss()
            }
        }
        addTrack("关闭 / 禁用", noneSelected) {
            MPVLib.setPropertyString("sid", "no")
        }
        for (track in tracks) {
            addTrack(track.label, track.selected) {
                MPVLib.setPropertyInt("sid", track.id)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()

        view.findViewById<View>(R.id.row_open_sub).setOnClickListener {
            dialog.dismiss()
            openExternalSubtitle()
        }
        view.findViewById<View>(R.id.row_sub_sync).setOnClickListener {
            dialog.dismiss()
            showSubSyncBar()
        }
        view.findViewById<View>(R.id.row_sub_speed).setOnClickListener {
            dialog.dismiss()
            showSubSpeedBar()
        }
        view.findViewById<View>(R.id.row_sub_custom).setOnClickListener {
            dialog.dismiss()
            showSubCustomDialog()
        }
        dialog.show()
    }

    private fun addSubTrackRow(
        container: LinearLayout,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val row = TextView(this).apply {
            text = (if (selected) "●  " else "○  ") + label
            setTextColor(if (selected) android.graphics.Color.parseColor("#3c8cf0") else android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(8, 0, 8, 0)
            height = (48 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
        }
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    private fun formatDelayText(seconds: Double): String {
        if (seconds == 0.0) return "0秒"
        val sign = if (seconds > 0) "+" else ""
        val v = if (seconds == seconds.toInt().toDouble()) {
            seconds.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", seconds)
        }
        return "$sign${v}秒"
    }

    // ---- 打开外挂字幕 ----
    private fun openExternalSubtitle() {
        subPickerLauncher.launch("*/*")
    }

    private fun addExternalSubtitle(uri: Uri) {
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "subtitle.srt"
            val dir = File(cacheDir, "subtitles").apply { mkdirs() }
            val dest = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            MPVLib.command(arrayOf("sub-add", dest.absolutePath))
            Toast.makeText(this, "已加载外挂字幕：$name", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "加载外挂字幕失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 字幕同步 / 速度悬浮条（字幕覆盖层内） ----
    private fun setupSubtitleBars() {
        findViewById<View>(R.id.btn_sync_minus).setOnClickListener {
            val cur = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
            applySubSync(cur - 0.1)
        }
        findViewById<View>(R.id.btn_sync_plus).setOnClickListener {
            val cur = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
            applySubSync(cur + 0.1)
        }
        findViewById<View>(R.id.btn_sync_close).setOnClickListener {
            applySubSyncFromEdit()
            subSyncBar.visibility = View.GONE
        }
        etSyncValue.setOnEditorActionListener { _, _, _ ->
            applySubSyncFromEdit()
            false
        }
        etSyncValue.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySubSyncFromEdit()
        }

        findViewById<View>(R.id.btn_sub_speed_minus).setOnClickListener {
            val cur = MPVLib.getPropertyDouble("sub-speed") ?: 1.0
            applySubSpeed(cur - 0.05)
        }
        findViewById<View>(R.id.btn_sub_speed_plus).setOnClickListener {
            val cur = MPVLib.getPropertyDouble("sub-speed") ?: 1.0
            applySubSpeed(cur + 0.05)
        }
        findViewById<View>(R.id.btn_sub_speed_close).setOnClickListener {
            applySubSpeedFromEdit()
            subSpeedBar.visibility = View.GONE
        }
        etSubSpeedValue.setOnEditorActionListener { _, _, _ ->
            applySubSpeedFromEdit()
            false
        }
        etSubSpeedValue.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySubSpeedFromEdit()
        }
    }

    private fun showSubSyncBar() {
        subSpeedBar.visibility = View.GONE
        val cur = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
        etSyncValue.setText(formatSyncNumber(cur))
        subSyncBar.visibility = View.VISIBLE
    }

    private fun applySubSync(value: Double) {
        val v = value.coerceIn(-30.0, 30.0)
        MPVLib.setPropertyDouble("sub-delay", v)
        etSyncValue.setText(formatSyncNumber(v))
    }

    private fun applySubSyncFromEdit() {
        val v = etSyncValue.text.toString().toDoubleOrNull() ?: return
        applySubSync(v)
    }

    private fun formatSyncNumber(seconds: Double): String {
        return if (seconds == seconds.toInt().toDouble()) {
            seconds.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", seconds)
        }
    }

    private fun showSubSpeedBar() {
        subSyncBar.visibility = View.GONE
        val cur = MPVLib.getPropertyDouble("sub-speed") ?: 1.0
        etSubSpeedValue.setText((cur * 100).toInt().toString())
        subSpeedBar.visibility = View.VISIBLE
    }

    private fun applySubSpeed(value: Double) {
        val v = value.coerceIn(0.25, 4.0)
        MPVLib.setPropertyDouble("sub-speed", v)
        etSubSpeedValue.setText((v * 100).toInt().toString())
    }

    private fun applySubSpeedFromEdit() {
        val pct = etSubSpeedValue.text.toString().toDoubleOrNull() ?: return
        applySubSpeed(pct / 100.0)
    }

    // ---- 字幕自定义面板 ----
    private fun showSubCustomDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sub_custom, null)
        val B = PurePlayerSettingsBridge

        val spAlign = view.findViewById<Spinner>(R.id.sp_sub_align)
        val sbMarginY = view.findViewById<SeekBar>(R.id.sb_sub_margin_y)
        val tvMarginY = view.findViewById<TextView>(R.id.tv_sub_margin_y)
        val cbBack = view.findViewById<CheckBox>(R.id.cb_sub_back)
        val spBackColor = view.findViewById<Spinner>(R.id.sp_sub_back_color)
        val cbUseMargins = view.findViewById<CheckBox>(R.id.cb_sub_use_margins)
        val spFont = view.findViewById<Spinner>(R.id.sp_sub_font)
        val sbFontSize = view.findViewById<SeekBar>(R.id.sb_sub_font_size)
        val tvFontSize = view.findViewById<TextView>(R.id.tv_sub_font_size)
        val sbScale = view.findViewById<SeekBar>(R.id.sb_sub_scale)
        val tvScale = view.findViewById<TextView>(R.id.tv_sub_scale)
        val spColor = view.findViewById<Spinner>(R.id.sp_sub_color)
        val cbBold = view.findViewById<CheckBox>(R.id.cb_sub_bold)
        val cbShadow = view.findViewById<CheckBox>(R.id.cb_sub_shadow)
        val spShadowColor = view.findViewById<Spinner>(R.id.sp_sub_shadow_color)
        val cbBorder = view.findViewById<CheckBox>(R.id.cb_sub_border)
        val spBorderColor = view.findViewById<Spinner>(R.id.sp_sub_border_color)
        val sbBorderSize = view.findViewById<SeekBar>(R.id.sb_sub_border_size)
        val tvBorderSize = view.findViewById<TextView>(R.id.tv_sub_border_size)

        // 对齐：Spinner 顺序 底部/中部/顶部，对应 sub-align-y 2/1/0
        val alignAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("底部", "中部", "顶部")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spAlign.adapter = alignAdapter
        val alignVal = prefs.getInt(B.KEY_SUB_ALIGN, 2).coerceIn(0, 2)
        spAlign.setSelection(2 - alignVal)
        spAlign.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt(B.KEY_SUB_ALIGN, 2 - pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 底部边距 0..100
        sbMarginY.progress = prefs.getInt(B.KEY_SUB_MARGIN_Y, 8).coerceIn(0, 100)
        tvMarginY.text = sbMarginY.progress.toString()
        sbMarginY.setOnSeekBarChangeListener(simpleSeek { progress ->
            tvMarginY.text = progress.toString()
            prefs.edit().putInt(B.KEY_SUB_MARGIN_Y, progress).apply()
        })

        // 背景
        cbBack.isChecked = prefs.getBoolean(B.KEY_SUB_BACK_ENABLED, false)
        spBackColor.adapter = colorAdapter(subBgColors)
        spBackColor.setSelection(indexOfColor(subBgColors, prefs.getString(B.KEY_SUB_BACK_COLOR, "#CC000000")))
        cbBack.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(B.KEY_SUB_BACK_ENABLED, checked).apply()
        }
        spBackColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(B.KEY_SUB_BACK_COLOR, subBgColors[pos].second).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 贴合视频
        cbUseMargins.isChecked = prefs.getBoolean(B.KEY_SUB_USE_MARGINS, true)
        cbUseMargins.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(B.KEY_SUB_USE_MARGINS, checked).apply()
        }

        // 字体
        spFont.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            subFonts.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val fontVal = prefs.getString(B.KEY_SUB_FONT, "") ?: ""
        spFont.setSelection(subFonts.indexOfFirst { it.second == fontVal }.coerceAtLeast(0))
        spFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(B.KEY_SUB_FONT, subFonts[pos].second).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 大小 12..72（SeekBar 0..60 + 12）
        sbFontSize.progress = (prefs.getInt(B.KEY_SUB_FONT_SIZE, 32) - 12).coerceIn(0, 60)
        tvFontSize.text = (sbFontSize.progress + 12).toString()
        sbFontSize.setOnSeekBarChangeListener(simpleSeek { progress ->
            val size = progress + 12
            tvFontSize.text = size.toString()
            prefs.edit().putInt(B.KEY_SUB_FONT_SIZE, size).apply()
        })

        // 缩放 50..200%（SeekBar 0..150 + 50）
        sbScale.progress = (prefs.getInt(B.KEY_SUB_SCALE, 100) - 50).coerceIn(0, 150)
        tvScale.text = "${sbScale.progress + 50}%"
        sbScale.setOnSeekBarChangeListener(simpleSeek { progress ->
            val scale = progress + 50
            tvScale.text = "$scale%"
            prefs.edit().putInt(B.KEY_SUB_SCALE, scale).apply()
        })

        // 颜色 + 加粗
        spColor.adapter = colorAdapter(subTextColors)
        spColor.setSelection(indexOfColor(subTextColors, prefs.getString(B.KEY_SUB_COLOR, "#FFFFFF")))
        spColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(B.KEY_SUB_COLOR, subTextColors[pos].second).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        cbBold.isChecked = prefs.getBoolean(B.KEY_SUB_BOLD, false)
        cbBold.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(B.KEY_SUB_BOLD, checked).apply()
        }

        // 文字背景（阴影色）
        cbShadow.isChecked = prefs.getBoolean(B.KEY_SUB_SHADOW_ENABLED, false)
        spShadowColor.adapter = colorAdapter(subBgColors)
        spShadowColor.setSelection(indexOfColor(subBgColors, prefs.getString(B.KEY_SUB_SHADOW_COLOR, "#80000000")))
        cbShadow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(B.KEY_SUB_SHADOW_ENABLED, checked).apply()
        }
        spShadowColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(B.KEY_SUB_SHADOW_COLOR, subBgColors[pos].second).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 描边
        cbBorder.isChecked = prefs.getBoolean(B.KEY_SUB_BORDER_ENABLED, true)
        spBorderColor.adapter = colorAdapter(subTextColors)
        spBorderColor.setSelection(indexOfColor(subTextColors, prefs.getString(B.KEY_SUB_BORDER_COLOR, "#000000")))
        cbBorder.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(B.KEY_SUB_BORDER_ENABLED, checked).apply()
        }
        spBorderColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(B.KEY_SUB_BORDER_COLOR, subTextColors[pos].second).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sbBorderSize.progress = prefs.getInt(B.KEY_SUB_BORDER_SIZE, 2).coerceIn(0, 8)
        tvBorderSize.text = sbBorderSize.progress.toString()
        sbBorderSize.setOnSeekBarChangeListener(simpleSeek { progress ->
            tvBorderSize.text = progress.toString()
            prefs.edit().putInt(B.KEY_SUB_BORDER_SIZE, progress).apply()
        })

        AlertDialog.Builder(this)
            .setTitle("字幕自定义")
            .setView(view)
            .setPositiveButton("确定", null)
            .setNeutralButton("恢复默认") { _, _ -> resetSubCustom() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun colorAdapter(colors: List<Pair<String, String>>): ArrayAdapter<String> {
        return ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            colors.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun indexOfColor(colors: List<Pair<String, String>>, value: String?): Int {
        val idx = colors.indexOfFirst { it.second.equals(value, ignoreCase = true) }
        return if (idx >= 0) idx else 0
    }

    private fun simpleSeek(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onProgress(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
    }

    /** 恢复字幕自定义默认值：清掉自定义键，桥接自动按默认值重新应用 */
    private fun resetSubCustom() {
        val B = PurePlayerSettingsBridge
        prefs.edit().apply {
            remove(B.KEY_SUB_ALIGN)
            remove(B.KEY_SUB_MARGIN_Y)
            remove(B.KEY_SUB_BACK_ENABLED)
            remove(B.KEY_SUB_BACK_COLOR)
            remove(B.KEY_SUB_USE_MARGINS)
            remove(B.KEY_SUB_FONT)
            remove(B.KEY_SUB_FONT_SIZE)
            remove(B.KEY_SUB_SCALE)
            remove(B.KEY_SUB_COLOR)
            remove(B.KEY_SUB_BOLD)
            remove(B.KEY_SUB_SHADOW_ENABLED)
            remove(B.KEY_SUB_SHADOW_COLOR)
            remove(B.KEY_SUB_BORDER_ENABLED)
            remove(B.KEY_SUB_BORDER_COLOR)
            remove(B.KEY_SUB_BORDER_SIZE)
        }.apply()
        settingsBridge.syncAll()
        Toast.makeText(this, "字幕样式已恢复默认", Toast.LENGTH_SHORT).show()
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
                } else if (playerOverlay.visibility == View.VISIBLE) {
                    // 控制层（含顶部菜单一排）与系统栏一起隐藏
                    hideControls()
                } else {
                    showControls()
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
        if (locked) {
            controlsHideHandler.removeCallbacks(controlsHideRunnable)
            playerOverlay.visibility = View.GONE
            btnUnlockFloating.visibility = View.VISIBLE
            updateBigPlayVisibility()
        } else {
            btnUnlockFloating.visibility = View.GONE
            showControls()
        }
    }

    /**
     * 中央大播放按钮：只在「暂停」且「控制层可见」时显示。
     * 控制层隐藏/锁定/画中画时一律隐藏。
     */
    private fun updateBigPlayVisibility() {
        btnBigPlay.visibility =
            if (isPaused && playerOverlay.visibility == View.VISIBLE) View.VISIBLE else View.GONE
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
                    updateBigPlayVisibility()
                    // 暂停时不自动隐藏控制层，恢复播放后重新开始计时
                    if (value) {
                        controlsHideHandler.removeCallbacks(controlsHideRunnable)
                    } else if (playerOverlay.visibility == View.VISIBLE) {
                        scheduleControlsHide()
                    }
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

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                // 新文件开始加载：清掉手动切歌时立的标志，之后自然播完的
                // END_FILE 才能正常触发列表循环
                ignoreNextEndFile = false
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                // 手动切歌（playVideo 发起 loadfile）也会让旧文件走一次
                // END_FILE，忽略这一次，否则列表循环会在手动切歌后又多跳一首
                if (ignoreNextEndFile) {
                    ignoreNextEndFile = false
                    return
                }
                // 单只循环（loopMode 2）由 mpv loop-file 处理；其余模式播完自动进下一个：
                // 随机或列表循环（1）继续往下播（列表循环到末尾回到开头）；
                // 循环关（0）播到最后一个就停，不再往回绕
                if (loopMode == 2) return
                runOnUiThread {
                    if (folderPlaylist.size < 2) return@runOnUiThread
                    if (shuffleEnabled || loopMode == 1) {
                        playNext()
                    } else if (playlistIndex + 1 < folderPlaylist.size) {
                        playAt(playlistIndex + 1)
                    }
                }
            }
        }
    }
}
