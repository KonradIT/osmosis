package com.chernowii.osmosis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import com.chernowii.osmosis.R
import com.chernowii.osmosis.core.CameraFile
import com.chernowii.osmosis.net.ImageLoader
import com.chernowii.osmosis.net.MetaLoader

/** GridView adapter: thumbnail + seq/ext/duration/size label + selection checkbox per media item. */
class MediaGridAdapter(
    private val files: List<CameraFile>,
    private val loader: ImageLoader,
    private val meta: MetaLoader,
) : BaseAdapter() {

    private val selected = HashSet<Int>()

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
        check.isChecked = selected.contains(position)
        loader.load(f.thumbUrlPath(), thumb)
        meta.load(f, name, "%04d·%s".format(f.seq, f.ext))

        v.setOnClickListener {
            if (!selected.add(position)) selected.remove(position)
            check.isChecked = selected.contains(position)
        }
        return v
    }

    fun selectedFiles(): List<CameraFile> = selected.sorted().map { files[it] }
    fun selectedCount() = selected.size

    fun toggleAll() {
        if (selected.size < files.size) {
            selected.clear(); files.indices.forEach { selected.add(it) }
        } else {
            selected.clear()
        }
        notifyDataSetChanged()
    }
}
