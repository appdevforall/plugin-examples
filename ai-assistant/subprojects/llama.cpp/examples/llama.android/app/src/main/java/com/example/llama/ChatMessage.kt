package com.example.llama

data class ChatMessage(
    val id: Long,
    val text: String,
    val type: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long? = null
)
