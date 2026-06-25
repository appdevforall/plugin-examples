package com.example.llama

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val VIEW_TYPE_SYSTEM = 0
private const val VIEW_TYPE_USER = 1
private const val VIEW_TYPE_MODEL = 2

class MessageAdapter(context: Context) :
    ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val markwon = Markwon.create(context)
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(message: ChatMessage, markwon: Markwon, timeFormatter: SimpleDateFormat)

        class SystemMessageViewHolder(view: View) : MessageViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.messageTextView)
            private val timestampTextView: TextView = view.findViewById(R.id.timestampTextView)
            override fun bind(
                message: ChatMessage,
                markwon: Markwon,
                timeFormatter: SimpleDateFormat
            ) {
                textView.text = message.text
                timestampTextView.text = timeFormatter.format(Date(message.timestamp))
            }
        }

        class UserMessageViewHolder(view: View) : MessageViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.messageTextView)
            private val timestampTextView: TextView = view.findViewById(R.id.timestampTextView)
            override fun bind(
                message: ChatMessage,
                markwon: Markwon,
                timeFormatter: SimpleDateFormat
            ) {
                textView.text = message.text
                timestampTextView.text = timeFormatter.format(Date(message.timestamp))
            }
        }

        class ModelMessageViewHolder(view: View) : MessageViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.messageTextView)
            private val timestampTextView: TextView = view.findViewById(R.id.timestampTextView)
            private val durationTextView: TextView = view.findViewById(R.id.durationTextView)
            override fun bind(
                message: ChatMessage,
                markwon: Markwon,
                timeFormatter: SimpleDateFormat
            ) {
                markwon.setMarkdown(textView, message.text)
                textView.movementMethod = LinkMovementMethod.getInstance()
                timestampTextView.text = timeFormatter.format(Date(message.timestamp))

                if (message.durationMs != null) {
                    val seconds = message.durationMs / 1000.0
                    durationTextView.text = String.format(Locale.US, "(%.2fs)", seconds)
                    durationTextView.visibility = View.VISIBLE
                } else {
                    durationTextView.visibility = View.GONE
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).type) {
            Sender.SYSTEM -> VIEW_TYPE_SYSTEM
            Sender.USER -> VIEW_TYPE_USER
            Sender.AGENT -> VIEW_TYPE_MODEL
            Sender.TOOL -> VIEW_TYPE_SYSTEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val view = inflater.inflate(R.layout.item_message_system, parent, false)
                MessageViewHolder.SystemMessageViewHolder(view)
            }
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                MessageViewHolder.UserMessageViewHolder(view)
            }
            VIEW_TYPE_MODEL -> {
                val view = inflater.inflate(R.layout.item_message_model, parent, false)
                MessageViewHolder.ModelMessageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position), markwon, timeFormatter)
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
