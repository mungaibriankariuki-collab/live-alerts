package com.livealerts.app

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livealerts.app.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onOpen: (Channel) -> Unit,
    private val onRemove: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val c = items[position]
        val ctx = holder.b.root.context
        holder.b.name.text = c.name
        holder.b.status.text = when {
            c.live -> c.liveTitle?.ifEmpty { null } ?: ctx.getString(R.string.status_live)
            c.error -> ctx.getString(R.string.status_error, c.path)
            c.lastChecked == 0L -> ctx.getString(R.string.status_pending, c.path)
            c.lastLiveAt > 0 -> ctx.getString(
                R.string.status_last_live,
                c.path,
                DateUtils.getRelativeTimeSpanString(
                    c.lastLiveAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                ),
            )
            c.lastLiveAt < 0 -> ctx.getString(R.string.status_never_live, c.path)
            else -> ctx.getString(R.string.status_offline, c.path)
        }
        holder.b.liveBadge.visibility = if (c.live) View.VISIBLE else View.GONE
        holder.b.root.setOnClickListener { onOpen(c) }
        holder.b.remove.setOnClickListener { onRemove(c) }
    }
}
