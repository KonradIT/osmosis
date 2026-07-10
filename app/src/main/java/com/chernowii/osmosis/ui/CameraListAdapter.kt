package com.chernowii.osmosis.ui

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.chernowii.osmosis.R
import com.chernowii.osmosis.ble.CameraModel

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
        v.findViewById<TextView>(R.id.camType).text =
            r.model.name + if (!r.model.verified) "  ~experimental" else ""
        v.findViewById<TextView>(R.id.camName).text = r.name ?: r.mac
        v.findViewById<TextView>(R.id.camStatus).text = if (r.inRange) "📶" else "🚫"
        v.findViewById<TextView>(R.id.camTag).visibility = if (r.saved) View.GONE else View.VISIBLE
        return v
    }
}
