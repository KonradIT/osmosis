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
    private val files: List<CameraFile>,
    private val loader: ImageLoader,
    private val meta: MetaLoader,
    private val onOpen: (Int) -> Unit,
) : BaseAdapter() {

    // position -> optional trim (null = whole file). Presence in the map = queued.
    private val selected = LinkedHashMap<Int, TrimRange?>()

    override fun getCount() = files.size
    override fun getItem(position: Int) = files[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        val check = v.findViewById<CheckBox>(R.id.check)
        val name = v.findViewById<TextView>(R.id.name)

        val f = files[position]
        check.isChecked = selected.containsKey(position)
        loader.load(f.thumbUrlPath(), thumb)
        val prefix = "%04d·%s".format(f.seq, f.ext) + if (selected[position] != null) " ✂" else ""
        meta.load(f, name, prefix)

        v.setOnClickListener { onOpen(position) }
        return v
    }

    fun isQueued(position: Int): Boolean = selected.containsKey(position)
    fun trimFor(position: Int): TrimRange? = selected[position]

    /** Apply the preview's add/remove decision (with optional trim) and refresh. */
    fun setQueued(position: Int, queued: Boolean, trim: TrimRange? = null) {
        if (queued) selected[position] = trim else selected.remove(position)
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
