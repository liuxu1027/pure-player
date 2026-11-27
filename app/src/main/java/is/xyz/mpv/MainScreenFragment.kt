package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MX-style home screen: folder list first, two-level navigation.
 *
 * Tabs (bottom nav): Local (folders) / All (flat video list) / Mine (settings & about).
 * Top bar: title + All / Search / List-grid toggle.
 * Tapping a folder opens its videos; tapping a video plays it in PurePlayerActivity.
 */
class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding

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

        Utils.handleInsetsAsPadding(binding.root)

        // top bar
        binding.backBtn.setOnClickListener { goBackToFolders() }
        binding.allBtn.setOnClickListener { switchTab(Tab.ALL) }
        binding.searchBtn.setOnClickListener { toggleSearch() }
        binding.viewBtn.setOnClickListener { toggleViewMode() }
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

        // list
        binding.mediaList.layoutManager = LinearLayoutManager(requireContext())
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
        refresh()
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

    private fun toggleViewMode() {
        isGrid = !isGrid
        binding.mediaList.layoutManager =
            if (isGrid) GridLayoutManager(requireContext(), 2)
            else LinearLayoutManager(requireContext())
        binding.viewBtn.setImageResource(if (isGrid) R.drawable.ic_list else R.drawable.ic_grid)
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
        allVideos = videos.sortedByDescending { it.dateAdded }
        val nowSec = System.currentTimeMillis() / 1000
        val newThreshold = nowSec - NEW_WINDOW_SEC
        val grouped = videos.groupBy { it.folder }
        folders = grouped.entries
            .sortedByDescending { (_, list) -> list.maxOf { it.dateAdded } }
            .map { (name, list) ->
                val sorted = list.sortedByDescending { it.dateAdded }
                val newCount = sorted.count { it.dateAdded >= newThreshold }
                FolderItem(name, sorted.size, newCount, sorted)
            }
        refresh()
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
            if (item.newCount > 0) {
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
            videoName.text = item.name
            videoMeta.text = "${formatDuration(item.durationMs)}  ·  ${formatSize(item.sizeBytes)}  ·  ${formatDate(item.dateAdded)}"
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
