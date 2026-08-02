package dev.konraditurbe.osmosis.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.TrimRange
import dev.konraditurbe.osmosis.net.ImageLoader
import dev.konraditurbe.osmosis.net.MetaLoader
import java.util.Calendar

/**
 * The gallery grid. A [RecyclerView.Adapter] with two row types: **date headers** (full-width, e.g.
 * "TODAY" / "YESTERDAY" / "30 JUL 2026") and **media cells**. The flat [rows] list is rebuilt from the
 * backing [all] whenever the filter, select mode, or the file set changes; headers are inserted on each
 * date change (the list arrives already sorted newest-first).
 *
 * Selection is keyed by the file's **path** (stable across filtering/pagination), not a grid position.
 * Tapping a cell opens the preview via [onOpen]; in **select mode** a tap in the cell's top-right
 * quadrant toggles its download checkbox instead — except on burst/interval groups (many frames), which
 * always open the preview so the user can pick the frame there.
 */
class MediaGridAdapter(
    initial: List<CameraFile>,
    private val loader: ImageLoader,
    private val meta: MetaLoader,
    private val spanCount: Int,
    private val onOpen: (CameraFile) -> Unit,
    private val onLongPress: (CameraFile) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class TypeFilter { ALL, PHOTOS, VIDEOS }

    // Backing list grows as older pages load on scroll (append only).
    private val all: MutableList<CameraFile> = initial.toMutableList()

    var typeFilter: TypeFilter = TypeFilter.ALL; private set
    var favedOnly: Boolean = false; private set
    var selectMode: Boolean = false; private set

    /** Fired whenever the queued set changes, so the host can refresh a count/FAB. */
    var onQueueChanged: (() -> Unit)? = null

    // path -> optional trim (null = whole file). Presence in the map = queued.
    private val selected = LinkedHashMap<String, TrimRange?>()
    // path -> the specific burst frame the user chose (from the preview) instead of the group's lead.
    private val selectedMember = HashMap<String, CameraFile>()

    private sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val file: CameraFile) : Row()
    }
    private var rows: List<Row> = emptyList()

    init { rows = buildRows() }

    // ---- row assembly ------------------------------------------------------

    private fun passesFilter(f: CameraFile): Boolean {
        if (favedOnly && !f.starred) return false
        return when (typeFilter) {
            TypeFilter.ALL -> true
            TypeFilter.PHOTOS -> !f.isVideo
            TypeFilter.VIDEOS -> f.isVideo
        }
    }

    private fun buildRows(): List<Row> {
        val out = ArrayList<Row>(all.size + 16)
        var lastYmd: String? = null
        for (f in all) {
            if (!passesFilter(f)) continue
            val ymd = f.ymd
            if (ymd != lastYmd) { out.add(Row.Header(headerLabel(ymd))); lastYmd = ymd }
            out.add(Row.Item(f))
        }
        return out
    }

    /** Swap in a freshly-built row list and dispatch the minimal diff, so the default item animator
     *  fades/slides cells in and out (e.g. when a Photos/Videos/Faved filter toggles) instead of the
     *  hard snap of notifyDataSetChanged(). */
    private fun setRows(newRows: List<Row>) {
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]; val b = newRows[n]
                return when {
                    a is Row.Header && b is Row.Header -> a.label == b.label
                    a is Row.Item && b is Row.Item -> a.file.path == b.file.path
                    else -> false
                }
            }
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]; val b = newRows[n]
                return a is Row.Item && b is Row.Item && a.file == b.file ||
                    a is Row.Header && b is Row.Header && a.label == b.label
            }
        })
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    /** "TODAY" / "YESTERDAY" for the two most recent days, else "30 JUL 2026"; "" → "UNKNOWN DATE". */
    private fun headerLabel(ymd: String): String {
        if (ymd.length != 8) return "UNKNOWN DATE"
        val now = Calendar.getInstance()
        val today = ymdOf(now)
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = ymdOf(now)
        return when (ymd) {
            today -> "TODAY"
            yesterday -> "YESTERDAY"
            else -> {
                val day = ymd.substring(6, 8).trimStart('0').ifEmpty { "0" }
                val month = ymd.substring(4, 6).toIntOrNull() ?: 0
                val mon = MONTHS.getOrElse(month - 1) { "?" }
                "$day $mon ${ymd.substring(0, 4)}"
            }
        }
    }

    private fun ymdOf(c: Calendar): String =
        "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    // ---- RecyclerView plumbing ---------------------------------------------

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    fun isHeader(position: Int) = position in rows.indices && rows[position] is Row.Header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderVH(inf.inflate(R.layout.item_media_header, parent, false))
        else ItemVH(inf.inflate(R.layout.item_media, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).label.text = row.label
            is Row.Item -> (holder as ItemVH).bind(row.file)
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.headerLabel)
    }

    inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.thumb)
        private val check: CheckBox = v.findViewById(R.id.check)
        private val name: TextView = v.findViewById(R.id.name)
        private val star: TextView = v.findViewById(R.id.star)
        private var file: CameraFile? = null
        private var downX = 0f
        private var downY = 0f

        init {
            @Suppress("ClickableViewAccessibility")
            v.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y }
                false   // observe only — the click/long-click listeners still fire
            }
            v.setOnClickListener {
                val f = file ?: return@setOnClickListener
                // Select mode: a tap in the top-right quadrant toggles the queue checkbox. Bursts are many
                // frames, so they always open the preview (the user picks the frame there) — no direct queue.
                if (selectMode && !f.isBurst && inCheckboxZone(downX, downY, it.width, it.height)) toggleQueue(f)
                else onOpen(f)
            }
            v.setOnLongClickListener { file?.let(onLongPress); true }
        }

        fun bind(f: CameraFile) {
            file = f
            val queued = selected.containsKey(f.path)
            check.visibility = if (selectMode || queued) View.VISIBLE else View.GONE
            check.isChecked = queued
            // ❤️ favorited; 🎞️ burst/interval group; else a media-type hint (📷 photo / 📹 video).
            star.text = when {
                f.starred -> "❤️"
                f.isBurst -> "🎞️"
                f.isVideo -> "📹"
                else -> "📷"
            }
            loader.load(f.thumbUrlPath(), thumb)
            val prefix = "%04d".format(f.seq) + (if (selected[f.path] != null) " ✂" else "")
            meta.load(f, name, prefix)
        }
    }

    private fun inCheckboxZone(x: Float, y: Float, w: Int, h: Int) = x >= w * 0.5f && y <= h * 0.5f

    // ---- queue / selection --------------------------------------------------

    private fun rowIndexOfPath(path: String): Int =
        rows.indexOfFirst { it is Row.Item && it.file.path == path }

    private fun toggleQueue(f: CameraFile) {
        if (selected.containsKey(f.path)) { selected.remove(f.path); selectedMember.remove(f.path) }
        else selected[f.path] = null
        rowIndexOfPath(f.path).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        onQueueChanged?.invoke()
    }

    fun isQueuedPath(path: String): Boolean = selected.containsKey(path)
    fun trimForPath(path: String): TrimRange? = selected[path]
    fun fileForPath(path: String): CameraFile? = all.firstOrNull { it.path == path }

    /** Apply the preview's add/remove + optional trim; [member] is the exact burst frame to queue (else lead). */
    fun setQueuedByPath(path: String, queued: Boolean, trim: TrimRange? = null, member: CameraFile? = null) {
        if (queued) {
            selected[path] = trim
            if (member != null) selectedMember[path] = member else selectedMember.remove(path)
        } else {
            selected.remove(path); selectedMember.remove(path)
        }
        val i = rowIndexOfPath(path)
        if (i >= 0) notifyItemChanged(i) else notifyDataSetChanged()
        onQueueChanged?.invoke()
    }

    /** Reflect a favorite toggle (from the grid long-press) on the ❤️ badge. */
    fun setStarredByPath(path: String, starred: Boolean) {
        val idx = all.indexOfFirst { it.path == path }
        if (idx < 0 || all[idx].starred == starred) return
        all[idx] = all[idx].copy(starred = starred)
        // A faved-only view may gain/lose this cell, so re-diff; otherwise just repaint its badge.
        if (favedOnly) setRows(buildRows())
        else rowIndexOfPath(path).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    /** The filtered items in display order (no headers) — the list the preview swipes through. */
    fun visibleFiles(): List<CameraFile> = rows.mapNotNull { (it as? Row.Item)?.file }
    fun visibleIndexOf(path: String): Int = visibleFiles().indexOfFirst { it.path == path }

    fun selectedEntries(): List<Pair<CameraFile, TrimRange?>> =
        selected.entries.map { (selectedMember[it.key] ?: fileForPath(it.key)!!) to it.value }
    /** The queue keys (cell paths) in the SAME order as [selectedEntries] — for mapping a job index back
     *  to the cell to dequeue after a successful download. */
    fun selectedKeys(): List<String> = selected.keys.toList()
    fun selectedCount() = selected.size
    fun totalFiles() = all.size
    fun allFilesSnapshot(): List<CameraFile> = all.toList()

    /** Drop the given cells from the queue (called after they successfully download). */
    fun dequeuePaths(paths: Collection<String>) {
        var changed = false
        for (p in paths) if (selected.containsKey(p)) { selected.remove(p); selectedMember.remove(p); changed = true }
        if (changed) { notifyDataSetChanged(); onQueueChanged?.invoke() }
    }

    /** Append the next (older) page; queued set (path-keyed) is unaffected. New cells fade in via the diff. */
    fun append(more: List<CameraFile>) {
        if (more.isEmpty()) return
        all.addAll(more)
        setRows(buildRows())
    }

    // ---- filter / mode toggles (driven by the toolbar chips) ----------------

    fun setTypeFilter(f: TypeFilter) {
        if (typeFilter == f) return
        typeFilter = f; setRows(buildRows())
    }

    fun setFavedOnly(on: Boolean) {
        if (favedOnly == on) return
        favedOnly = on; setRows(buildRows())
    }

    fun setSelectMode(on: Boolean) {
        if (selectMode == on) return
        selectMode = on
        notifyDataSetChanged()   // toggles checkbox visibility on every cell
    }

    /** Select-mode bulk helper (long-press the Select chip): queue / clear every currently-visible,
     *  non-burst cell. Bursts are skipped (they queue a specific frame via the preview). */
    fun selectAllVisible(select: Boolean) {
        for (r in rows) if (r is Row.Item && !r.file.isBurst) {
            if (select) selected.putIfAbsent(r.file.path, null)
            else { selected.remove(r.file.path); selectedMember.remove(r.file.path) }
        }
        notifyDataSetChanged()
        onQueueChanged?.invoke()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private val MONTHS = arrayOf(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    }
}
