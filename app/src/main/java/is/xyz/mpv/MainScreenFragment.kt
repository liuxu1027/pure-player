package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 视图 / 排序弹窗与列表显示用的 SharedPreferences 键
private const val KEY_VIEW_MODE = "home_view_mode"
private const val KEY_DEFAULT_VIEW = "home_default_view"
private const val KEY_SORT_KEY = "home_sort_key"
private const val KEY_SORT_ASC = "home_sort_asc"
private const val KEY_FIELD_THUMB = "home_field_thumb"
private const val KEY_FIELD_DURATION = "home_field_duration"
private const val KEY_FIELD_EXT = "home_field_ext"
private const val KEY_FIELD_PATH = "home_field_path"
private const val KEY_FIELD_SIZE = "home_field_size"
private const val KEY_FIELD_DATE = "home_field_date"
private const val KEY_SHOW_LENGTH_ON_THUMB = "home_show_length_on_thumb"
private const val KEY_SHOW_HIDDEN = "home_show_hidden"
private const val KEY_RECOGNIZE_NOMEDIA = "home_recognize_nomedia"
private const val KEY_SHOW_NEW_BADGE = "home_show_new_badge"

private const val VIEW_ALL_FOLDERS = "all_folders"
private const val VIEW_FILES = "files"
private const val VIEW_FOLDERS = "folders"

/**
 * MX-style home screen: folder list first, two-level navigation.
 *
 * Tabs (bottom nav): Local (folders) / All (flat video list) / Mine (settings & about).
 * Top bar: title + Open / All / Search / View toggle.
 * Tapping a folder opens its videos; tapping a video plays it in PurePlayerActivity.
 */
class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding
    private lateinit var prefs: SharedPreferences

    private lateinit var documentTreeOpener: ActivityResultLauncher<Uri?>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var playerLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var consentLauncher: ActivityResultLauncher<IntentSenderRequest>

    private var pendingVaultMove: VaultStore.PendingMove? = null

    private var lastPath = ""

    private var allVideos: List<VideoItem> = emptyList()
    private var folders: List<FolderItem> = emptyList()

    private var currentTab = Tab.LOCAL
    private var currentFolder: String? = null
    private var query = ""
    private var isGrid = false

    private val adapter = HomeAdapter(
        onFolderClick = { folder -> openFolder(folder.name) },
        onVideoClick = { video -> playFile(video.uri) },
        onVideoLongClick = { video -> moveVideoToVault(video) },
        onMineClick = { action -> onMineAction(action) }
    )

    private val mediaPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        documentTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { root ->
                requireContext().contentResolver.takePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", root.toString())
                i.putExtra("allow_dir", true)
                filePickerLauncher.launch(i)
            }
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            it.data?.getStringExtra("last_path")?.let { path ->
                lastPath = path
            }
            it.data?.getStringExtra("path")?.let { path ->
                playFile(path)
                return@registerForActivityResult
            }
            it.data?.getStringArrayExtra("paths")?.let { paths ->
                // need to convert to playlist at this point
                if (paths.size == 1) {
                    playFile(paths[0])
                } else {
                    val memoryUri = Utils.makeMemoryPlaylist(paths.asIterable())
                    playFile(memoryUri)
                }
            }
        }
        playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // we don't care about the result but remember that we've been here
            Log.v(TAG, "returned from player ($it)")
        }
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadMedia() else refresh()
        }
        consentLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val pending = pendingVaultMove
            pendingVaultMove = null
            val ctx = context
            if (pending == null || ctx == null) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) {
                Thread {
                    try {
                        VaultStore.deleteOriginal(ctx, pending)
                        VaultStore.completeMove(ctx, pending)
                        activity?.runOnUiThread {
                            if (isAdded) loadMedia()
                            Toast.makeText(ctx, R.string.vault_move_done, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        VaultStore.cancelMove(pending)
                        activity?.runOnUiThread {
                            Toast.makeText(ctx, R.string.vault_move_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                VaultStore.cancelMove(pending)
                Toast.makeText(ctx, R.string.vault_move_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMainScreenBinding.bind(view)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        Utils.handleInsetsAsPadding(binding.root)

        // top bar: only Search + List (view/sort dialog)
        binding.backBtn.setOnClickListener { goBackToFolders() }
        binding.searchBtn.setOnClickListener { toggleSearch() }
        binding.viewBtn.setOnClickListener { showViewSortDialog() }
        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refresh()
            }
        })

        // bottom nav
        binding.navLocal.setOnClickListener { switchTab(Tab.LOCAL) }
        binding.navAll.setOnClickListener { switchTab(Tab.ALL) }
        binding.navMine.setOnClickListener { switchTab(Tab.MINE) }

        // list: initial tab from view-mode pref, initial layout from default-view pref
        currentTab = if (prefs.getString(KEY_VIEW_MODE, VIEW_ALL_FOLDERS) == VIEW_FILES) Tab.ALL else Tab.LOCAL
        isGrid = prefs.getString(KEY_DEFAULT_VIEW, "list") == "grid"
        applyLayoutMode()
        binding.mediaList.adapter = adapter

        // back: folder detail -> folder list, otherwise let the activity handle it
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentFolder != null) {
                        goBackToFolders()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            })

        ensurePermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) loadMedia()
    }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), mediaPermission) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionAndLoad() {
        if (hasMediaPermission()) loadMedia()
        else permissionLauncher.launch(mediaPermission)
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab && currentFolder == null) return
        currentTab = tab
        currentFolder = null
        clearSearch()
        refresh()
    }

    private fun openFolder(name: String) {
        currentFolder = name
        clearSearch()
        // 打开文件夹时重新查一遍媒体库，避免新视频没扫进来导致显示不全
        loadMedia()
    }

    private fun goBackToFolders() {
        currentFolder = null
        refresh()
    }

    private fun clearSearch() {
        query = ""
        binding.searchBox.setText("")
        binding.searchBox.isVisible = false
    }

    private fun toggleSearch() {
        val show = !binding.searchBox.isVisible
        binding.searchBox.isVisible = show
        if (show) {
            binding.searchBox.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchBox, InputMethodManager.SHOW_IMPLICIT)
        } else {
            query = ""
            binding.searchBox.setText("")
            refresh()
        }
    }

    private fun applyLayoutMode() {
        binding.mediaList.layoutManager =
            if (isGrid) GridLayoutManager(requireContext(), 2)
            else LinearLayoutManager(requireContext())
    }

    /** MX 风格的视图 / 排序弹窗（首页顶部“列表图标”打开） */
    private fun showViewSortDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_view_sort, null)

        // 弹窗内待定的选择，点“完成”才写入
        var viewMode = prefs.getString(KEY_VIEW_MODE, VIEW_ALL_FOLDERS) ?: VIEW_ALL_FOLDERS
        var layout = prefs.getString(KEY_DEFAULT_VIEW, "list") ?: "list"
        var sortKey = prefs.getString(KEY_SORT_KEY, "date") ?: "date"
        var sortAsc = prefs.getBoolean(KEY_SORT_ASC, false)

        val rowViewMode = view.findViewById<LinearLayout>(R.id.row_view_mode)
        val rowLayout = view.findViewById<LinearLayout>(R.id.row_layout)
        val rowSort = view.findViewById<LinearLayout>(R.id.row_sort)
        val btnAsc = view.findViewById<Button>(R.id.btn_sort_asc)
        val btnDesc = view.findViewById<Button>(R.id.btn_sort_desc)

        fun refreshOptionRows() {
            rowViewMode.removeAllViews()
            addOption(rowViewMode, R.drawable.ic_folder, getString(R.string.vs_all_folders), viewMode == VIEW_ALL_FOLDERS) { viewMode = VIEW_ALL_FOLDERS; refreshOptionRows() }
            addOption(rowViewMode, R.drawable.ic_video, getString(R.string.vs_files), viewMode == VIEW_FILES) { viewMode = VIEW_FILES; refreshOptionRows() }
            addOption(rowViewMode, R.drawable.ic_folder, getString(R.string.vs_folders), viewMode == VIEW_FOLDERS) { viewMode = VIEW_FOLDERS; refreshOptionRows() }

            rowLayout.removeAllViews()
            addOption(rowLayout, R.drawable.ic_list, getString(R.string.vs_list), layout == "list") { layout = "list"; refreshOptionRows() }
            addOption(rowLayout, R.drawable.ic_grid, getString(R.string.vs_grid), layout == "grid") { layout = "grid"; refreshOptionRows() }

            rowSort.removeAllViews()
            val sortOptions = listOf(
                Triple("title", R.drawable.ic_sort, getString(R.string.vs_sort_title)),
                Triple("date", R.drawable.ic_calendar, getString(R.string.vs_sort_date)),
                Triple("duration", R.drawable.ic_timer, getString(R.string.vs_sort_duration)),
                Triple("size", R.drawable.ic_sd_card_white_24dp, getString(R.string.vs_sort_size)),
                Triple("path", R.drawable.ic_folder, getString(R.string.vs_sort_path))
            )
            for ((key, icon, label) in sortOptions) {
                addOption(rowSort, icon, label, sortKey == key) { sortKey = key; refreshOptionRows() }
            }
            updateSortDirectionButtons(btnAsc, btnDesc, sortAsc)
        }

        btnAsc.setOnClickListener { sortAsc = true; updateSortDirectionButtons(btnAsc, btnDesc, sortAsc) }
        btnDesc.setOnClickListener { sortAsc = false; updateSortDirectionButtons(btnAsc, btnDesc, sortAsc) }

        // 字段
        val cbThumb = view.findViewById<CheckBox>(R.id.cb_field_thumb)
        val cbDuration = view.findViewById<CheckBox>(R.id.cb_field_duration)
        val cbExt = view.findViewById<CheckBox>(R.id.cb_field_ext)
        val cbPath = view.findViewById<CheckBox>(R.id.cb_field_path)
        val cbSize = view.findViewById<CheckBox>(R.id.cb_field_size)
        val cbDate = view.findViewById<CheckBox>(R.id.cb_field_date)
        cbThumb.isChecked = prefs.getBoolean(KEY_FIELD_THUMB, true)
        cbDuration.isChecked = prefs.getBoolean(KEY_FIELD_DURATION, true)
        cbExt.isChecked = prefs.getBoolean(KEY_FIELD_EXT, false)
        cbPath.isChecked = prefs.getBoolean(KEY_FIELD_PATH, false)
        cbSize.isChecked = prefs.getBoolean(KEY_FIELD_SIZE, true)
        cbDate.isChecked = prefs.getBoolean(KEY_FIELD_DATE, true)

        // 高级
        val swLengthOnThumb = view.findViewById<Switch>(R.id.sw_show_length_on_thumb)
        val swShowHidden = view.findViewById<Switch>(R.id.sw_show_hidden)
        val swNomedia = view.findViewById<Switch>(R.id.sw_recognize_nomedia)
        swLengthOnThumb.isChecked = prefs.getBoolean(KEY_SHOW_LENGTH_ON_THUMB, true)
        swShowHidden.isChecked = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        swNomedia.isChecked = prefs.getBoolean(KEY_RECOGNIZE_NOMEDIA, true)

        refreshOptionRows()

        AlertDialog.Builder(requireContext())
            .setView(view)
            .setNegativeButton(R.string.vs_cancel, null)
            .setPositiveButton(R.string.vs_done) { _, _ ->
                prefs.edit().apply {
                    putString(KEY_VIEW_MODE, viewMode)
                    putString(KEY_DEFAULT_VIEW, layout)
                    putString(KEY_SORT_KEY, sortKey)
                    putBoolean(KEY_SORT_ASC, sortAsc)
                    putBoolean(KEY_FIELD_THUMB, cbThumb.isChecked)
                    putBoolean(KEY_FIELD_DURATION, cbDuration.isChecked)
                    putBoolean(KEY_FIELD_EXT, cbExt.isChecked)
                    putBoolean(KEY_FIELD_PATH, cbPath.isChecked)
                    putBoolean(KEY_FIELD_SIZE, cbSize.isChecked)
                    putBoolean(KEY_FIELD_DATE, cbDate.isChecked)
                    putBoolean(KEY_SHOW_LENGTH_ON_THUMB, swLengthOnThumb.isChecked)
                    putBoolean(KEY_SHOW_HIDDEN, swShowHidden.isChecked)
                    putBoolean(KEY_RECOGNIZE_NOMEDIA, swNomedia.isChecked)
                }.apply()
                isGrid = layout == "grid"
                applyLayoutMode()
                // 视图模式切页签（“文件”= 所有视频平铺，其余 = 文件夹）
                switchTab(if (viewMode == VIEW_FILES) Tab.ALL else Tab.LOCAL)
                loadMedia()
            }
            .show()
    }

    /** 弹窗里的一个图标 + 文字选项（选中态蓝色，未选中灰色） */
    private fun addOption(
        container: LinearLayout,
        iconRes: Int,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val ctx = container.context
        val accent = Color.parseColor(ACCENT_BLUE)
        val normal = Color.parseColor("#757575")
        val item = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(4, 8, 4, 8)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            val size = (36 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setColorFilter(if (selected) accent else normal)
        }
        val text = TextView(ctx).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (selected) accent else normal)
            setPadding(0, 4, 0, 0)
        }
        item.addView(icon)
        item.addView(text)
        container.addView(item)
    }

    private fun updateSortDirectionButtons(btnAsc: Button, btnDesc: Button, asc: Boolean) {
        val accent = Color.parseColor(ACCENT_BLUE)
        val normal = Color.parseColor("#757575")
        btnAsc.setBackgroundColor(if (asc) accent else Color.TRANSPARENT)
        btnAsc.setTextColor(if (asc) Color.WHITE else normal)
        btnDesc.setBackgroundColor(if (!asc) accent else Color.TRANSPARENT)
        btnDesc.setTextColor(if (!asc) Color.WHITE else normal)
    }

    private fun refresh() {
        binding.titleText.text = when {
            currentTab == Tab.MINE -> getString(R.string.home_title_mine)
            currentTab == Tab.ALL -> getString(R.string.home_title_all)
            currentFolder != null -> currentFolder
            else -> getString(R.string.home_title_folders)
        }
        binding.backBtn.isVisible = currentFolder != null && currentTab == Tab.LOCAL

        val items = buildItems()
        adapter.submit(items)

        if (items.isEmpty()) {
            binding.mediaList.isVisible = false
            binding.emptyView.isVisible = true
            binding.emptyView.text = if (!hasMediaPermission() && currentTab != Tab.MINE) {
                getString(R.string.home_need_permission)
            } else {
                getString(R.string.home_no_videos)
            }
        } else {
            binding.mediaList.isVisible = true
            binding.emptyView.isVisible = false
        }

        updateBottomNav()
    }

    private fun updateBottomNav() {
        val selected = Color.parseColor(ACCENT_BLUE)
        val normal = Color.parseColor(NAV_NORMAL)
        binding.navLocalIcon.setColorFilter(if (currentTab == Tab.LOCAL) selected else normal)
        binding.navLocalLabel.setTextColor(if (currentTab == Tab.LOCAL) selected else normal)
        binding.navAllIcon.setColorFilter(if (currentTab == Tab.ALL) selected else normal)
        binding.navAllLabel.setTextColor(if (currentTab == Tab.ALL) selected else normal)
        binding.navMineIcon.setColorFilter(if (currentTab == Tab.MINE) selected else normal)
        binding.navMineLabel.setTextColor(if (currentTab == Tab.MINE) selected else normal)
    }

    private fun buildItems(): List<HomeListItem> {
        return when (currentTab) {
            Tab.MINE -> mineItems()
            Tab.ALL -> {
                val list = if (query.isEmpty()) allVideos
                else allVideos.filter { it.name.contains(query, ignoreCase = true) }
                list.map { HomeListItem.Video(it) }
            }
            Tab.LOCAL -> {
                val folder = currentFolder
                if (folder == null) {
                    val list = if (query.isEmpty()) folders
                    else folders.filter { it.name.contains(query, ignoreCase = true) }
                    list.map { HomeListItem.Folder(it) }
                } else {
                    val target = folders.find { it.name == folder }
                    val list = target?.videos ?: emptyList()
                    val filtered = if (query.isEmpty()) list
                    else list.filter { it.name.contains(query, ignoreCase = true) }
                    filtered.map { HomeListItem.Video(it) }
                }
            }
        }
    }

    private fun mineItems(): List<HomeListItem> {
        return buildList {
            add(
                HomeListItem.Mine(
                    MineAction.SETTINGS,
                    getString(R.string.home_mine_settings),
                    getString(R.string.home_mine_settings_sub),
                    R.drawable.ic_settings
                )
            )
            // 打开入口：顶部“+ 打开”已去掉，挪回“我的”
            add(
                HomeListItem.Mine(
                    MineAction.OPEN_FILE,
                    getString(R.string.home_mine_open_file),
                    getString(R.string.home_mine_open_file_sub),
                    R.drawable.ic_folder
                )
            )
            add(
                HomeListItem.Mine(
                    MineAction.OPEN_URL,
                    getString(R.string.home_mine_open_url),
                    getString(R.string.home_mine_open_url_sub),
                    R.drawable.ic_video
                )
            )
            add(
                HomeListItem.Mine(
                    MineAction.OPEN_DOC_TREE,
                    getString(R.string.home_mine_open_doc_tree),
                    getString(R.string.home_mine_open_doc_tree_sub),
                    R.drawable.ic_folder
                )
            )
            add(
                HomeListItem.Mine(
                    MineAction.VAULT,
                    getString(R.string.home_mine_vault),
                    getString(R.string.home_mine_vault_sub, VaultStore.loadItems(requireContext()).size),
                    R.drawable.ic_lock
                )
            )
            if (PinStore.hasPin(requireContext())) {
                add(
                    HomeListItem.Mine(
                        MineAction.CHANGE_PIN,
                        getString(R.string.home_mine_change_pin),
                        getString(R.string.home_mine_change_pin_sub),
                        R.drawable.ic_lock
                    )
                )
            }
            add(
                HomeListItem.Mine(
                    MineAction.ABOUT,
                    getString(R.string.home_mine_about),
                    getString(R.string.home_mine_about_sub),
                    R.drawable.ic_settings
                )
            )
        }
    }

    private fun onMineAction(action: MineAction) {
        when (action) {
            MineAction.SETTINGS -> {
                startActivity(Intent(context, PureSettingsActivity::class.java))
            }
            MineAction.VAULT -> {
                val i = Intent(context, PinActivity::class.java)
                i.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_UNLOCK)
                startActivity(i)
            }
            MineAction.CHANGE_PIN -> {
                val i = Intent(context, PinActivity::class.java)
                i.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CHANGE)
                startActivity(i)
            }
            MineAction.OPEN_FILE -> {
                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.FILE_PICKER)
                i.putExtra("allow_dir", true)
                if (lastPath != "") i.putExtra("default_path", lastPath)
                filePickerLauncher.launch(i)
            }
            MineAction.OPEN_URL -> {
                val helper = Utils.OpenUrlDialog(requireContext())
                with (helper) {
                    builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                        playFile(helper.text)
                    }
                    builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                    create().show()
                }
            }
            MineAction.OPEN_DOC_TREE -> {
                try {
                    documentTreeOpener.launch(null)
                } catch (e: ActivityNotFoundException) {
                    // Android TV doesn't come with a document picker and certain versions just throw
                    // instead of handling this gracefully
                }
            }
            MineAction.ABOUT -> showAbout()
        }
    }

    private fun showAbout() {
        val version = try {
            val pi = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
            pi.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_mine_about)
            .setMessage(getString(R.string.home_about_text, version))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun loadMedia() {
        val resolver = requireContext().contentResolver
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val videos = mutableListOf<VideoItem>()
        resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val data = cursor.getString(dataCol) ?: ""
                val dateAdded = cursor.getLong(dateCol)
                val duration = cursor.getLong(durCol)
                val size = cursor.getLong(sizeCol)
                val bucket = cursor.getString(bucketCol)
                val folder = folderOf(data, bucket)
                val contentUri = ContentUris.withAppendedId(uri, id).toString()
                videos.add(VideoItem(id, name, data, contentUri, folder, duration, dateAdded, size))
            }
        }
        // 过滤：隐藏文件/文件夹、.nomedia 文件夹（按弹窗“高级”里的开关）
        val showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        val recognizeNomedia = prefs.getBoolean(KEY_RECOGNIZE_NOMEDIA, true)
        var filtered = videos
        if (!showHidden) {
            filtered = filtered.filter { !it.name.startsWith(".") && !it.folder.startsWith(".") }
        }
        if (recognizeNomedia) {
            filtered = filtered.filter { video ->
                val dir = File(video.path).parent
                dir == null || !File(dir, ".nomedia").exists()
            }
        }
        allVideos = sortVideos(filtered)
        val nowSec = System.currentTimeMillis() / 1000
        val newThreshold = nowSec - NEW_WINDOW_SEC
        val grouped = allVideos.groupBy { it.folder }
        folders = sortFolders(
            grouped.entries.map { (name, list) ->
                val newCount = list.count { it.dateAdded >= newThreshold }
                FolderItem(name, list.size, newCount, list)
            }
        )
        refresh()
    }

    /**
     * 按弹窗里的排序（标题/日期/长度/大小/路径 + A到Z/Z到A）排视频。
     * 默认日期降序（最新在前）。
     */
    private fun sortVideos(list: List<VideoItem>): List<VideoItem> {
        val key = prefs.getString(KEY_SORT_KEY, "date") ?: "date"
        val asc = prefs.getBoolean(KEY_SORT_ASC, false)
        val comp: Comparator<VideoItem> = when (key) {
            "title" -> Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            "duration" -> Comparator { a, b -> a.durationMs.compareTo(b.durationMs) }
            "size" -> Comparator { a, b -> a.sizeBytes.compareTo(b.sizeBytes) }
            "path" -> Comparator { a, b -> a.path.compareTo(b.path, ignoreCase = true) }
            else -> Comparator { a, b -> a.dateAdded.compareTo(b.dateAdded) }
        }
        val sorted = list.sortedWith(comp)
        return if (asc) sorted else sorted.reversed()
    }

    /** 文件夹也按同一排序：标题/路径按文件夹名，长度/大小按合计，日期按最新视频 */
    private fun sortFolders(list: List<FolderItem>): List<FolderItem> {
        val key = prefs.getString(KEY_SORT_KEY, "date") ?: "date"
        val asc = prefs.getBoolean(KEY_SORT_ASC, false)
        val comp: Comparator<FolderItem> = when (key) {
            "title", "path" -> Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            "duration" -> Comparator { a, b ->
                a.videos.sumOf { it.durationMs }.compareTo(b.videos.sumOf { it.durationMs })
            }
            "size" -> Comparator { a, b ->
                a.videos.sumOf { it.sizeBytes }.compareTo(b.videos.sumOf { it.sizeBytes })
            }
            else -> Comparator { a, b ->
                a.videos.maxOf { it.dateAdded }.compareTo(b.videos.maxOf { it.dateAdded })
            }
        }
        val sorted = list.sortedWith(comp)
        return if (asc) sorted else sorted.reversed()
    }

    private fun folderOf(data: String, bucket: String?): String {
        if (data.isNotEmpty()) {
            val parent = data.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) return parent.substringAfterLast('/', parent)
        }
        if (!bucket.isNullOrEmpty()) return bucket
        return "其他"
    }

    private fun playFile(filepath: String) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            i = Intent()
            // if we pass 'filepath' it will go directly to mpv without any processing
            i.putExtra("video_path", filepath)
        }
        i.setClass(requireContext(), PurePlayerActivity::class.java)
        playerLauncher.launch(i)
    }

    private fun moveVideoToVault(video: VideoItem) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.vault_move_title)
            .setMessage(getString(R.string.vault_move_confirm, video.name))
            .setPositiveButton(R.string.vault_move_ok) { _, _ ->
                Thread {
                    val pending = VaultStore.copyToVault(ctx, video)
                    if (pending == null) {
                        activity?.runOnUiThread {
                            Toast.makeText(ctx, R.string.vault_move_failed, Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    try {
                        VaultStore.deleteOriginal(ctx, pending)
                        VaultStore.completeMove(ctx, pending)
                        activity?.runOnUiThread {
                            if (isAdded) loadMedia()
                            Toast.makeText(ctx, R.string.vault_move_done, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: RecoverableSecurityException) {
                        pendingVaultMove = pending
                        val sender = e.userAction.actionIntent.intentSender
                        activity?.runOnUiThread {
                            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    } catch (e: Exception) {
                        VaultStore.cancelMove(pending)
                        activity?.runOnUiThread {
                            Toast.makeText(ctx, R.string.vault_move_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "mpv"
        private const val ACCENT_BLUE = "#3c8cf0"
        private const val NAV_NORMAL = "#757575"
        private const val NEW_WINDOW_SEC = 3 * 24 * 3600L
    }
}

private enum class Tab { LOCAL, ALL, MINE }

private enum class MineAction { SETTINGS, VAULT, CHANGE_PIN, OPEN_FILE, OPEN_URL, OPEN_DOC_TREE, ABOUT }

/** Shared with the private vault (VaultStore) — kept public on purpose. */
data class VideoItem(
    val id: Long,
    val name: String,
    val path: String,
    val uri: String,
    val folder: String,
    val durationMs: Long,
    val dateAdded: Long,
    val sizeBytes: Long
)

private data class FolderItem(
    val name: String,
    val count: Int,
    val newCount: Int,
    val videos: List<VideoItem>
)

private sealed class HomeListItem {
    data class Folder(val item: FolderItem) : HomeListItem()
    data class Video(val item: VideoItem) : HomeListItem()
    data class Mine(val action: MineAction, val title: String, val subtitle: String, val iconRes: Int) : HomeListItem()
}

private class HomeAdapter(
    private val onFolderClick: (FolderItem) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Unit,
    private val onMineClick: (MineAction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<HomeListItem> = emptyList()

    fun submit(newItems: List<HomeListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeListItem.Folder -> TYPE_FOLDER
        is HomeListItem.Video -> TYPE_VIDEO
        is HomeListItem.Mine -> TYPE_MINE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FOLDER -> {
                val v = inflater.inflate(R.layout.item_media_folder, parent, false)
                FolderViewHolder(v, onFolderClick)
            }
            TYPE_VIDEO -> {
                val v = inflater.inflate(R.layout.item_media_video, parent, false)
                VideoViewHolder(v, onVideoClick, onVideoLongClick)
            }
            else -> {
                val v = inflater.inflate(R.layout.item_mine, parent, false)
                MineViewHolder(v, onMineClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeListItem.Folder -> (holder as FolderViewHolder).bind(item.item)
            is HomeListItem.Video -> (holder as VideoViewHolder).bind(item.item)
            is HomeListItem.Mine -> (holder as MineViewHolder).bind(item)
        }
    }

    private class FolderViewHolder(
        view: View,
        private val onFolderClick: (FolderItem) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val folderName: TextView = view.findViewById(R.id.folderName)
        private val folderCount: TextView = view.findViewById(R.id.folderCount)
        private val folderBadge: TextView = view.findViewById(R.id.folderBadge)
        private var folder: FolderItem? = null

        init {
            view.setOnClickListener { folder?.let(onFolderClick) }
        }

        fun bind(item: FolderItem) {
            folder = item
            folderName.text = item.name
            folderCount.text = itemView.context.getString(R.string.home_videos_count, item.count)
            val showBadge = PreferenceManager.getDefaultSharedPreferences(itemView.context)
                .getBoolean(KEY_SHOW_NEW_BADGE, true)
            if (showBadge && item.newCount > 0) {
                folderBadge.isVisible = true
                folderBadge.text = item.newCount.toString()
            } else {
                folderBadge.isVisible = false
            }
        }
    }

    private class VideoViewHolder(
        view: View,
        private val onVideoClick: (VideoItem) -> Unit,
        private val onVideoLongClick: (VideoItem) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val thumbWrap: View = view.findViewById(R.id.videoThumbWrap)
        private val videoDurationBadge: TextView = view.findViewById(R.id.videoDurationBadge)
        private val videoCheck: ImageView = view.findViewById(R.id.videoCheck)
        private val videoName: TextView = view.findViewById(R.id.videoName)
        private val videoMeta: TextView = view.findViewById(R.id.videoMeta)
        private var video: VideoItem? = null

        init {
            view.setOnClickListener { video?.let(onVideoClick) }
            view.setOnLongClickListener {
                video?.let(onVideoLongClick)
                true
            }
        }

        fun bind(item: VideoItem) {
            video = item
            val prefs = PreferenceManager.getDefaultSharedPreferences(itemView.context)
            // 文件扩展名：关时名字里不显示 .mp4 这类后缀
            videoName.text = if (prefs.getBoolean(KEY_FIELD_EXT, false)) {
                item.name
            } else {
                item.name.substringBeforeLast('.', item.name)
            }
            videoMeta.text = buildMeta(item, prefs)
            // 缩略图 + 时长角标（按弹窗“字段 / 高级”里的开关）
            val showThumb = prefs.getBoolean(KEY_FIELD_THUMB, true)
            thumbWrap.isVisible = showThumb
            val showBadge = showThumb && prefs.getBoolean(KEY_SHOW_LENGTH_ON_THUMB, true)
            videoDurationBadge.isVisible = showBadge
            if (showBadge) videoDurationBadge.text = formatDuration(item.durationMs)
            videoCheck.isVisible = false
        }
    }

    private class MineViewHolder(
        view: View,
        private val onMineClick: (MineAction) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val mineIcon: ImageView = view.findViewById(R.id.mineIcon)
        private val mineTitle: TextView = view.findViewById(R.id.mineTitle)
        private val mineSubtitle: TextView = view.findViewById(R.id.mineSubtitle)
        private var action: MineAction? = null

        init {
            view.setOnClickListener { action?.let(onMineClick) }
        }

        fun bind(item: HomeListItem.Mine) {
            action = item.action
            mineIcon.setImageResource(item.iconRes)
            mineTitle.text = item.title
            mineSubtitle.text = item.subtitle
        }
    }

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_VIDEO = 1
        private const val TYPE_MINE = 2
    }
}

/** 按弹窗“字段”里的勾选拼视频行的第二行（长度 · 大小 · 日期 · 路径） */
private fun buildMeta(item: VideoItem, prefs: SharedPreferences): String {
    val parts = mutableListOf<String>()
    if (prefs.getBoolean(KEY_FIELD_DURATION, true)) parts.add(formatDuration(item.durationMs))
    if (prefs.getBoolean(KEY_FIELD_SIZE, true)) parts.add(formatSize(item.sizeBytes))
    if (prefs.getBoolean(KEY_FIELD_DATE, true)) parts.add(formatDate(item.dateAdded))
    if (prefs.getBoolean(KEY_FIELD_PATH, false)) parts.add(item.path)
    return parts.joinToString("  ·  ")
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatDate(sec: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sec * 1000))
}
