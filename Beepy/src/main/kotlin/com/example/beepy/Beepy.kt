package com.example.beepy

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.IdeBuildService

class Beepy : IPlugin, BuildStatusListener {

    private lateinit var context: PluginContext
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private val loadedIds = mutableSetOf<Int>()

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        return true
    }

    override fun activate(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()

        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds += sampleId
        }

        soundPool = pool
        loadSound(EVENT_STARTED, R.raw.started)
        loadSound(EVENT_FINISHED, R.raw.finished)
        loadSound(EVENT_FAILED, R.raw.failed)

        context.services.get(IdeBuildService::class.java)?.addBuildStatusListener(this)
        return true
    }

    override fun deactivate(): Boolean {
        context.services.get(IdeBuildService::class.java)?.removeBuildStatusListener(this)
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loadedIds.clear()
        return true
    }

    override fun dispose() {}

    override fun onBuildStarted()              = play(EVENT_STARTED)
    override fun onBuildFinished()             = play(EVENT_FINISHED)
    override fun onBuildFailed(error: String?) = play(EVENT_FAILED)

    private fun loadSound(key: String, @RawRes rawId: Int) {
        val pool = soundPool ?: return
        val id = context.androidContext.resources.openRawResourceFd(rawId).use { pool.load(it, 1) }
        soundIds[key] = id
    }

    private fun play(key: String) {
        val pool = soundPool ?: return
        val id = soundIds[key] ?: return
        if (id !in loadedIds) return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    companion object {
        private const val EVENT_STARTED = "started"
        private const val EVENT_FINISHED = "finished"
        private const val EVENT_FAILED = "failed"
    }
}
