package com.example.p2pmesh

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Peer(
    val endpointId: String,
    val name: String,
    var status: PeerStatus = PeerStatus.DISCOVERED
)

enum class PeerStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

class PeerAdapter(
    private val peers: MutableList<Peer>,
    private val onPeerClick: (Peer) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvPeerName: TextView = itemView.findViewById(R.id.tvPeerName)
        val tvEndpointId: TextView = itemView.findViewById(R.id.tvEndpointId)
        val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        val vStatusIndicator: View = itemView.findViewById(R.id.vStatusIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = peers[position]
        holder.tvPeerName.text = peer.name
        holder.tvEndpointId.text = "ID: ${peer.endpointId}"
        holder.tvStatus.text = peer.status.name

        val hexColor = when (peer.status) {
            PeerStatus.CONNECTED -> "#10B981"
            PeerStatus.CONNECTING -> "#F59E0B"
            PeerStatus.DISCOVERED -> "#0EA5E9"
            PeerStatus.DISCONNECTED -> "#94A3B8"
        }
        val colorInt = Color.parseColor(hexColor)
        holder.tvStatus.setTextColor(colorInt)
        holder.vStatusIndicator.background?.setTint(colorInt)

        holder.itemView.setOnClickListener {
            onPeerClick(peer)
        }
    }

    override fun getItemCount(): Int = peers.size

    fun updatePeers(newList: List<Peer>) {
        peers.clear()
        peers.addAll(newList)
        notifyDataSetChanged()
    }
}
