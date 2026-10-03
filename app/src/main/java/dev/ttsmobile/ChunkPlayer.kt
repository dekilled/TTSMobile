package dev.ttsmobile

import android.media.AudioAttributes
import android.media.MediaPlayer
import dev.ttsmobile.backend.TtsApiClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Player sequencial de chunks WAV: recebe os blocos gerados pelo streaming do
 * backend e toca um após o outro sem buracos perceptíveis.
 */
class ChunkPlayer {

    private val queue = ArrayBlockingQueue<PlayerItem>(64)
    @Volatile private var playing = false
    private var current: MediaPlayer? = null
    private var worker: Thread? = null
    private val dir: File

    data class PlayerItem(val wav: ByteArray, val onDone: () -> Unit)

    constructor(cacheDir: File) {
        dir = File(cacheDir, "chunks").apply { mkdirs() }
    }

    fun start() {
        if (playing) return
        playing = true
        worker = Thread({ runLoop() }, "chunk-player").apply {
            isDaemon = true
            start()
        }
    }

    /** Enfileira um chunk; bloqueia se a fila estiver cheia (backpressure). */
    fun enqueue(wav: ByteArray): Boolean {
        if (!playing) return false
        return try {
            queue.offer(PlayerItem(wav) {}, 5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
    }

    fun stop() {
        playing = false
        queue.clear()
        worker?.interrupt()
        releaseCurrent()
    }

    private fun runLoop() {
        while (playing) {
            val item = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            playBlocking(item.wav)
            item.onDone()
        }
        releaseCurrent()
    }

    private fun playBlocking(wav: ByteArray) {
        val tmp = File(dir, "chunk_${System.nanoTime()}.wav")
        try {
            tmp.writeBytes(wav)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tmp.absolutePath)
                prepare()
            }
            current = mp
            val done = java.util.concurrent.CountDownLatch(1)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.start()
            // espera no máximo a duração do áudio + margem
            done.await(300, TimeUnit.SECONDS)
            mp.release()
        } catch (e: IOException) {
            // chunk corrompido: ignora e segue
        } finally {
            runCatching { tmp.delete() }
            current = null
        }
    }

    private fun releaseCurrent() {
        current?.let { runCatching { it.stop() }; runCatching { it.release() } }
        current = null
    }

    companion object {
        /** Toca um WAV completo (síntese não-streaming). */
        fun playOneShot(cacheDir: File, wav: ByteArray): MediaPlayer? {
            return try {
                val tmp = File(cacheDir, "oneshot_${System.currentTimeMillis()}.wav")
                tmp.writeBytes(wav)
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(tmp.absolutePath)
                    setOnCompletionListener {
                        runCatching { it.release() }
                        runCatching { File(it.toString()).delete() }
                    }
                    prepare()
                    start()
                }
            } catch (e: IOException) {
                null
            }
        }

        fun clientFor(baseUrl: String): TtsApiClient = TtsApiClient(baseUrl)
    }
}
