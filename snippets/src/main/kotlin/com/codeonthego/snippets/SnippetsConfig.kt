package com.codeonthego.snippets

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File

data class SnippetsConfig(
    @SerializedName("snippets")
    val snippets: List<SnippetEntry> = emptyList()
)

data class SnippetEntry(
    @SerializedName("language")
    val language: String,
    @SerializedName("scope")
    val scope: String,
    @SerializedName("prefix")
    val prefix: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("body")
    val body: List<String>
)

object SnippetsConfigParser {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun parse(file: File): SnippetsConfig? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), SnippetsConfig::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun write(file: File, config: SnippetsConfig) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(config))
    }
}