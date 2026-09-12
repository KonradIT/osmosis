package dev.konraditurbe.osmosis.ui

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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

class CameraListAdapter(private val rows: List<CamRow>) : BaseAdapter() {
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera, parent, false)
        val r = rows[position]
        val camType = v.findViewById<TextView>(R.id.camType)
        camType.text = r.model.name
        // An unverified model gets the flask after its name (compound drawable, sized to the 16sp row).
        if (!r.model.verified) {
            val d = ContextCompat.getDrawable(v.context, R.drawable.ic_unverified_model)!!.mutate()
            val s = (16 * v.resources.displayMetrics.density).toInt()
            d.setBounds(0, 0, s, s)
            camType.setCompoundDrawablesRelative(null, null, d, null)
            camType.compoundDrawablePadding = (6 * v.resources.displayMetrics.density).toInt()
        } else {
            camType.setCompoundDrawablesRelative(null, null, null, null)
        }
        v.findViewById<TextView>(R.id.camName).text = r.name ?: r.mac
        v.findViewById<ImageView>(R.id.camStatus)
            .setImageResource(if (r.inRange) R.drawable.ic_signal_in_range else R.drawable.ic_signal_out_of_range)
        v.findViewById<TextView>(R.id.camTag).visibility = if (r.saved) View.GONE else View.VISIBLE
        v.alpha = if (r.inRange) 1f else 0.5f // dim saved cameras that aren't in range
        return v
    }
}
