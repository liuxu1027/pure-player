package `is`.xyz.mpv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放器顶部功能菜单（MX 风格横向一排）的适配器。
 * 每项 = 图标 + 文字；激活态（功能已开启）用 MX 蓝 #3c8cf0，未激活为白色。
 * 图标统一用 setColorFilter 着色，所以黑底/白底的源图标都能用。
 */
data class PlayerMenuItem(
    val id: String,
    val label: String,
    val iconRes: Int,
    var isActive: Boolean = false
)

class PlayerMenuAdapter(
    private val onItemClick: (PlayerMenuItem) -> Unit
) : RecyclerView.Adapter<PlayerMenuAdapter.MenuViewHolder>() {

    private var items: List<PlayerMenuItem> = emptyList()

    fun submitItems(newItems: List<PlayerMenuItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun currentItems(): List<PlayerMenuItem> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_action, parent, false)
        return MenuViewHolder(view)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MenuViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_menu_icon)
        private val label: TextView = view.findViewById(R.id.tv_menu_label)

        fun bind(item: PlayerMenuItem) {
            icon.setImageResource(item.iconRes)
            label.text = item.label
            val color = if (item.isActive) Color.parseColor("#3c8cf0") else Color.WHITE
            icon.setColorFilter(color)
            label.setTextColor(color)
            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
