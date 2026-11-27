package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Private vault: lists videos moved out of shared storage. Tap to play,
 * "restore" moves the file back to its original location and re-indexes it.
 */
class VaultActivity : AppCompatActivity() {

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private var items: List<VaultStore.VaultItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_vault)

        findViewById<View>(R.id.vaultBack).setOnClickListener { finish() }
        listView = findViewById(R.id.vaultList)
        emptyView = findViewById(R.id.vaultEmpty)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = VaultAdapter(
            items,
            onPlay = { playItem(it) },
            onRestore = { confirmRestore(it) }
        )
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = VaultStore.loadItems(this).filter { item ->
            val dir = VaultStore.vaultDir(this)
            dir != null && File(dir, item.vaultName).exists()
        }
        (listView.adapter as VaultAdapter).setItems(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playItem(item: VaultStore.VaultItem) {
        val dir = VaultStore.vaultDir(this) ?: return
        val file = File(dir, item.vaultName)
        if (!file.exists()) {
            Toast.makeText(this, R.string.vault_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PurePlayerActivity::class.java)
        intent.putExtra("video_path", Uri.fromFile(file).toString())
        startActivity(intent)
    }

    private fun confirmRestore(item: VaultStore.VaultItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_restore_title)
            .setMessage(getString(R.string.vault_restore_confirm, item.displayName))
            .setPositiveButton(R.string.vault_restore_ok) { _, _ ->
                Thread {
                    val ok = VaultStore.restoreItem(this, item)
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this, R.string.vault_restore_done, Toast.LENGTH_SHORT).show()
                            refresh()
                        } else {
                            Toast.makeText(this, R.string.vault_restore_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private class VaultAdapter(
        private var items: List<VaultStore.VaultItem>,
        private val onPlay: (VaultStore.VaultItem) -> Unit,
        private val onRestore: (VaultStore.VaultItem) -> Unit
    ) : RecyclerView.Adapter<VaultAdapter.Holder>() {

        fun setItems(newItems: List<VaultStore.VaultItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.vaultName)
            val meta: TextView = v.findViewById(R.id.vaultMeta)
            val restoreBtn: View = v.findViewById(R.id.vaultRestore)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vault, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.displayName
            holder.meta.text = formatMeta(holder.itemView.context, item)
            holder.itemView.setOnClickListener { onPlay(item) }
            holder.restoreBtn.setOnClickListener { onRestore(item) }
        }

        override fun getItemCount(): Int = items.size

        private fun formatMeta(context: android.content.Context, item: VaultStore.VaultItem): String {
            val dur = if (item.durationMs > 0) {
                val s = (item.durationMs / 1000).toInt()
                String.format("%02d:%02d", s / 60, s % 60)
            } else {
                "--:--"
            }
            val size = if (item.sizeBytes > 0) {
                if (item.sizeBytes >= 1024L * 1024L) {
                    String.format("%.1f MB", item.sizeBytes / 1024.0 / 1024.0)
                } else {
                    String.format("%.0f KB", item.sizeBytes / 1024.0)
                }
            } else {
                ""
            }
            val date = if (item.dateAdded > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(item.dateAdded * 1000))
            } else {
                ""
            }
            return listOf(dur, size, date).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}
