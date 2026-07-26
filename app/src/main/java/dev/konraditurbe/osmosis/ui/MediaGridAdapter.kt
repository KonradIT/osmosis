package dev.konraditurbe.osmosis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.TrimRange
import dev.konraditurbe.osmosis.net.ImageLoader
import dev.konraditurbe.osmosis.net.MetaLoader

/**
 * GridView adapter: thumbnail + seq/ext/duration/size label + a "queued" checkbox per item.
 * Tapping a cell (thumbnail or its checkbox — the checkbox isn't independently clickable) opens
 * the preview via [onOpen]; queueing itself happens there and flows back through [setQueued].
 */
class MediaGridAdapter(
    initial: List<CameraFile>,
    private val loader: ImageLoader,
    private val meta: MetaLoader,
    private val onOpen: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit = {},
) : BaseAdapter() {

    // Backing list grows as older pages load on scroll (append only — existing positions/queue stay valid).
    private val files: MutableList<CameraFile> = initial.toMutableList()

    // position -> optional trim (null = whole file). Presence in the map = queued.
    private val selected = LinkedHashMap<Int, TrimRange?>()

    /** Append the next (older) page to the end; existing positions and the queued set are unaffected. */
    fun append(more: List<CameraFile>) {
        if (more.isEmpty()) return
        files.addAll(more)
        notifyDataSetChanged()
    }

    override fun getCount() = files.size
    override fun getItem(position: Int) = files[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        val check = v.findViewById<CheckBox>(R.id.check)
        val name = v.findViewById<TextView>(R.id.name)
        val star = v.findViewById<TextView>(R.id.star)

        val f = files[position]
        check.isChecked = selected.containsKey(position)
        // ❤️ when favorited; otherwise a media-type hint (📷 photo / 📹 video).
        star.text = when {
            f.starred -> "❤️"
            f.isVideo -> "📹"
            else -> "📷"
        }
        loader.load(f.thumbUrlPath(), thumb)
        val prefix = "%04d".format(f.seq) + if (selected[position] != null) " ✂" else ""
        meta.load(f, name, prefix)

        v.setOnClickListener { onOpen(position) }
        v.setOnLongClickListener { onLongPress(position); true }
        return v
    }

    fun isQueued(position: Int): Boolean = selected.containsKey(position)
    fun trimFor(position: Int): TrimRange? = selected[position]

    /** Apply the preview's add/remove decision (with optional trim) and refresh. */
    fun setQueued(position: Int, queued: Boolean, trim: TrimRange? = null) {
        if (queued) selected[position] = trim else selected.remove(position)
        notifyDataSetChanged()
    }

    /** Reflect a favorite toggle (from the preview) on the grid's ⭐ badge. */
    fun setStarred(position: Int, starred: Boolean) {
        if (position !in files.indices || files[position].starred == starred) return
        files[position] = files[position].copy(starred = starred)
        notifyDataSetChanged()
    }

    fun selectedEntries(): List<Pair<CameraFile, TrimRange?>> =
        selected.entries.sortedBy { it.key }.map { files[it.key] to it.value }
    fun selectedCount() = selected.size

    fun toggleAll() {
        if (selected.size < files.size) files.indices.forEach { selected.putIfAbsent(it, null) }
        else selected.clear()
        notifyDataSetChanged()
    }
}
