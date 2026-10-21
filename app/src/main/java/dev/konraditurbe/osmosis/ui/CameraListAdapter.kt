package dev.konraditurbe.osmosis.ui

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.ble.CameraModel

/**
 * One row in the camera selector: a saved or freshly-scanned camera. [inRange] drives the 📶/🚫
 * status; unsaved cameras (surfaced by the scan) get a NEW tag. [device] is the live scan result,
 * present only when the camera is in range — that's what a tap connects to.
 */
data class CamRow(
    val mac: String,
    val name: String?,
    val model: CameraModel,
    val inRange: Boolean,
    val saved: Boolean,
    val device: BluetoothDevice?,
)

/**
 * The camera selector list. Swiping a row's card left reveals its actions on a [SwipeRevealLayout]:
 * an RTMP livestream on a streaming-capable camera in range, GPS sync on the models DJI's R-SDK
 * supports, Delete on a saved camera. A row with none of them doesn't swipe. At most one row is open at a time. Rows are
 * diffed by MAC, so the scan's frequent rebuilds leave an open or mid-drag row alone instead of
 * re-inflating it. While GPS sync or a livestream holds the camera's link the rows lock: dimmed and
 * unswipeable, except the streaming camera's, which stays bright under its LIVE tag.
 */
class CameraListAdapter(private val actions: Actions) : RecyclerView.Adapter<CameraListAdapter.Holder>() {

    interface Actions {
        fun onTap(r: CamRow)
        fun onLivestream(r: CamRow)
        fun onGpsSync(r: CamRow)
        fun onDelete(r: CamRow)
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val swipe = v as SwipeRevealLayout
        val card: View = v.findViewById(R.id.camCard)
        val text: View = v.findViewById(R.id.camText)
        val type: TextView = v.findViewById(R.id.camType)
        val name: TextView = v.findViewById(R.id.camName)
        val tag: TextView = v.findViewById(R.id.camTag)
        val liveTag: TextView = v.findViewById(R.id.camLive)
        val status: ImageView = v.findViewById(R.id.camStatus)
        val stream: View = v.findViewById(R.id.actionStream)
        val gps: View = v.findViewById(R.id.actionGps)
        val delete: View = v.findViewById(R.id.actionDelete)
        var row: CamRow? = null
        // The card's TalkBack actions, mirroring whichever tiles the row shows; 0 = not added.
        var streamAction = 0
        var gpsAction = 0
        var deleteAction = 0
    }

    private var rows: List<CamRow> = emptyList()
    private var openMac: String? = null
    private var list: RecyclerView? = null

    /** While GPS sync or a livestream owns the BLE no row swipes open; a tap still reaches [Actions.onTap], which says why. */
    var locked = false
        set(value) {
            field = value
            if (value) openMac = null
            forEachHolder { applyLock(it) }
        }

    /** The livestreaming camera's MAC: its row stays bright in the locked list and wears the LIVE tag. */
    var liveMac: String? = null
        set(value) {
            field = value
            forEachHolder { applyLock(it) }
        }

    fun submit(newRows: List<CamRow>) {
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].mac == newRows[n].mac
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newRows[n]
        })
        rows = newRows
        if (newRows.none { it.mac == openMac }) openMac = null
        diff.dispatchUpdatesTo(this)
    }

    /** Close the open row, if any: on a scroll, and when the Delete dialog goes away. */
    fun closeOpen() {
        val mac = openMac ?: return
        openMac = null
        holderFor(mac)?.swipe?.close()
    }

    /** Slide the open row out of view, then run [then], which takes it out of the list. */
    fun dismissOpen(then: () -> Unit) {
        val h = openMac?.let(::holderFor)
        openMac = null
        if (h != null) h.swipe.dismiss(then) else then()
    }

    override fun getItemCount() = rows.size

    override fun onAttachedToRecyclerView(rv: RecyclerView) { list = rv }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) { list = null }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val h = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_camera, parent, false))
        h.card.setOnClickListener {
            val r = h.row ?: return@setOnClickListener
            // With another row open, a tap elsewhere only closes it, like tapping outside a menu.
            if (openMac != null) closeOpen() else actions.onTap(r)
        }
        h.stream.setOnClickListener {
            val r = h.row ?: return@setOnClickListener
            h.swipe.close()
            actions.onLivestream(r)
        }
        h.gps.setOnClickListener {
            val r = h.row ?: return@setOnClickListener
            h.swipe.close()
            actions.onGpsSync(r)
        }
        // Delete leaves the row open behind its confirm dialog, so it's plain which camera is going.
        h.delete.setOnClickListener { h.row?.let(actions::onDelete) }
        h.swipe.listener = object : SwipeRevealLayout.Listener {
            override fun onSwipeStart() {
                if (openMac != h.row?.mac) closeOpen()
            }

            override fun onOpenChanged(open: Boolean) {
                val mac = h.row?.mac ?: return
                if (open) openMac = mac else if (openMac == mac) openMac = null
            }
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = rows[position]
        val ctx = h.itemView.context
        h.row = r
        h.type.text = r.model.name
        // An unverified model gets the flask after its name (compound drawable, sized to the 16sp row).
        if (!r.model.verified) {
            val d = ContextCompat.getDrawable(ctx, R.drawable.ic_unverified_model)!!.mutate()
            val s = (16 * ctx.resources.displayMetrics.density).toInt()
            d.setBounds(0, 0, s, s)
            h.type.setCompoundDrawablesRelative(null, null, d, null)
            h.type.compoundDrawablePadding = (6 * ctx.resources.displayMetrics.density).toInt()
        } else {
            h.type.setCompoundDrawablesRelative(null, null, null, null)
        }
        h.name.text = r.name ?: r.mac
        h.status.setImageResource(if (r.inRange) R.drawable.ic_signal_in_range else R.drawable.ic_signal_out_of_range)
        h.tag.visibility = if (r.saved) View.GONE else View.VISIBLE
        // Dim a camera that isn't in range by its content, not the whole card: a see-through card would
        // show the swipe actions behind it.
        val dim = if (r.inRange) 1f else 0.5f
        h.text.alpha = dim
        h.status.alpha = dim
        // A livestream only on the models that stream, and with the camera in range (it pairs and joins
        // the stream's network itself); GPS sync only where the R-SDK supports the model (never an
        // Xtra); Delete only for a saved camera, since an unsaved one has nothing to delete.
        val canStream = r.model.livestream && r.device != null
        h.stream.visibility = if (canStream) View.VISIBLE else View.GONE
        h.gps.visibility = if (r.model.gpsSync) View.VISIBLE else View.GONE
        h.delete.visibility = if (r.saved) View.VISIBLE else View.GONE
        // TalkBack can't drag the card, so the same actions sit on the card itself.
        h.streamAction = syncAction(h, h.streamAction, canStream, R.string.rtmp_start, actions::onLivestream)
        h.gpsAction = syncAction(h, h.gpsAction, r.model.gpsSync, R.string.gps_sync, actions::onGpsSync)
        h.deleteAction = syncAction(h, h.deleteAction, r.saved, R.string.delete, actions::onDelete)
        applyLock(h)
        if (!h.swipe.isDragging) h.swipe.snap(open = r.mac == openMac)
    }

    /** A locked row doesn't swipe and sits dimmed, except the live camera's, which keeps its LIVE tag lit. */
    private fun applyLock(h: Holder) {
        val live = liveMac != null && h.row?.mac == liveMac
        h.swipe.swipeEnabled = !locked
        h.card.alpha = if (locked && !live) LOCKED_ALPHA else 1f
        h.liveTag.visibility = if (live) View.VISIBLE else View.GONE
    }

    /** Add or remove one of the card's TalkBack actions to match [wanted]; returns its id, 0 if absent. */
    private fun syncAction(h: Holder, id: Int, wanted: Boolean, label: Int, run: (CamRow) -> Unit): Int = when {
        wanted && id == 0 ->
            ViewCompat.addAccessibilityAction(h.card, h.itemView.context.getString(label)) { _, _ ->
                h.row?.let(run)
                true
            }
        !wanted && id != 0 -> {
            ViewCompat.removeAccessibilityAction(h.card, id)
            0
        }
        else -> id
    }

    private fun holderFor(mac: String): Holder? {
        val i = rows.indexOfFirst { it.mac == mac }
        return if (i < 0) null else list?.findViewHolderForAdapterPosition(i) as? Holder
    }

    private fun forEachHolder(f: (Holder) -> Unit) {
        val rv = list ?: return
        for (i in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(i)) as? Holder)?.let(f)
    }

    private companion object {
        /** A locked row's opacity, matching the selector's other locked controls. */
        const val LOCKED_ALPHA = 0.4f
    }
}
