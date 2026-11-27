package `is`.xyz.mpv

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors

/**
 * 承载 7 个 MX 风格分类设置页的宿主 Activity。
 * 直接使用 android.R.id.content 作为容器，无需额外布局文件。
 *
 * 主题（通用 → 主题）变更时通过 PureTheme 立即应用；
 * AppCompat 会自动重建未声明 uiMode 的 Activity。
 */
class PureSettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        const val CAT_LIST = "list"
        const val CAT_PLAYER = "player"
        const val CAT_DECODER = "decoder"
        const val CAT_AUDIO = "audio"
        const val CAT_SUBTITLE = "subtitle"
        const val CAT_GENERAL = "general"
        const val CAT_DEV = "dev"
    }

    private lateinit var prefs: SharedPreferences

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            PureTheme.KEY_APP_THEME ->
                PureTheme.apply(sharedPreferences.getString(key, PureTheme.DEFAULT) ?: PureTheme.DEFAULT)
            "material_you_theming" -> {
                if (sharedPreferences.getBoolean(key, false))
                    DynamicColors.applyToActivityIfAvailable(this)
                recreate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("material_you_theming", false))
            DynamicColors.applyToActivityIfAvailable(this)
        applyEdgeToEdge()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Edge-to-edge: keep the preference list clear of the status/nav bars
        // (same pattern the old PreferenceActivity used for its container).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            val initialFragment = when (intent?.getStringExtra(EXTRA_CATEGORY)) {
                CAT_LIST -> CategoryPreferenceFragment.newInstance(R.xml.frag_list, "列表设置")
                CAT_PLAYER -> CategoryPreferenceFragment.newInstance(R.xml.frag_player, "播放器设置")
                CAT_DECODER -> CategoryPreferenceFragment.newInstance(R.xml.frag_decoder, "解码器设置")
                CAT_AUDIO -> CategoryPreferenceFragment.newInstance(R.xml.frag_audio, "音频设置")
                CAT_SUBTITLE -> CategoryPreferenceFragment.newInstance(R.xml.frag_subtitle, "字幕设置")
                CAT_GENERAL -> CategoryPreferenceFragment.newInstance(R.xml.frag_general, "通用设置")
                CAT_DEV -> CategoryPreferenceFragment.newInstance(R.xml.frag_dev, "开发设置")
                else -> RootSettingsFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, initialFragment)
                .commit()
        }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragment = when (pref.key) {
            CAT_LIST -> CategoryPreferenceFragment.newInstance(R.xml.frag_list, "列表设置")
            CAT_PLAYER -> CategoryPreferenceFragment.newInstance(R.xml.frag_player, "播放器设置")
            CAT_DECODER -> CategoryPreferenceFragment.newInstance(R.xml.frag_decoder, "解码器设置")
            CAT_AUDIO -> CategoryPreferenceFragment.newInstance(R.xml.frag_audio, "音频设置")
            CAT_SUBTITLE -> CategoryPreferenceFragment.newInstance(R.xml.frag_subtitle, "字幕设置")
            CAT_GENERAL -> CategoryPreferenceFragment.newInstance(R.xml.frag_general, "通用设置")
            CAT_DEV -> CategoryPreferenceFragment.newInstance(R.xml.frag_dev, "开发设置")
            else -> return false
        }
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    /**
     * 设置首页：动态构建 7 个分类入口（MX 风格）。
     */
    class RootSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(ctx)

            val categories = listOf(
                Triple(CAT_LIST, "列表", "文件夹与视频列表的显示与排序"),
                Triple(CAT_PLAYER, "播放器", "界面交互、倍速、续播与画中画"),
                Triple(CAT_DECODER, "解码器", "硬件/软件解码、线程数与去隔行"),
                Triple(CAT_AUDIO, "音频", "输出后端、音量增强、延迟与直通"),
                Triple(CAT_SUBTITLE, "字幕", "目录、编码、同步延迟与渲染模式"),
                Triple(CAT_GENERAL, "通用", "主题、动态取色等通用设置"),
                Triple(CAT_DEV, "开发", "统计信息、GPU 调试等高级选项")
            )

            categories.forEach { (key, title, summary) ->
                screen.addPreference(Preference(ctx).apply {
                    this.key = key
                    this.title = title
                    this.summary = summary
                    setOnPreferenceClickListener {
                        (activity as? OnPreferenceStartFragmentCallback)
                            ?.onPreferenceStartFragment(this@RootSettingsFragment, this) == true
                    }
                })
            }

            preferenceScreen = screen
        }

        override fun onResume() {
            super.onResume()
            activity?.title = "设置"
        }
    }

    /**
     * 通用子设置页 Fragment，加载对应的 frag_*.xml
     */
    class CategoryPreferenceFragment : PreferenceFragmentCompat() {
        companion object {
            private const val ARG_XML_RES = "arg_xml_res"
            private const val ARG_TITLE = "arg_title"

            fun newInstance(xmlResId: Int, title: String) = CategoryPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_XML_RES, xmlResId)
                    putString(ARG_TITLE, title)
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val xmlRes = requireArguments().getInt(ARG_XML_RES)
            setPreferencesFromResource(xmlRes, rootKey)
            // 列表页：“已排除的文件夹”入口（在文件夹长按菜单里排除的，点这里查看/恢复）
            if (xmlRes == R.xml.frag_list) {
                findPreference<Preference>("excluded_folders")?.apply {
                    updateExcludedSummary()
                    setOnPreferenceClickListener {
                        showExcludedFoldersDialog()
                        true
                    }
                }
            }
        }

        /** “已排除的文件夹”一行的小字：显示已排除的数量 */
        private fun updateExcludedSummary() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val count = prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet())?.size ?: 0
            findPreference<Preference>("excluded_folders")?.summary =
                if (count == 0) getString(R.string.excluded_folders_none)
                else getString(R.string.excluded_folders_count, count)
        }

        /**
         * 已排除的文件夹：多选框列出被排除的目录；取消勾选后点确定，
         * 即恢复显示该文件夹（首页下次刷新时重新出现）。
         */
        private fun showExcludedFoldersDialog() {
            val ctx = requireContext()
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val excluded = prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet())
                ?.toList()?.sorted() ?: emptyList()
            if (excluded.isEmpty()) {
                Toast.makeText(ctx, R.string.excluded_folders_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val checked = BooleanArray(excluded.size) { true }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.excluded_folders_dialog_title)
                .setMessage(R.string.excluded_folders_dialog_hint)
                .setMultiChoiceItems(excluded.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val kept = excluded.filterIndexed { i, _ -> checked[i] }.toSet()
                    prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, kept).apply()
                    updateExcludedSummary()
                    Toast.makeText(ctx, R.string.excluded_folders_updated, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.vs_cancel, null)
                .show()
        }

        override fun onResume() {
            super.onResume()
            activity?.title = requireArguments().getString(ARG_TITLE, "设置")
        }
    }
}
