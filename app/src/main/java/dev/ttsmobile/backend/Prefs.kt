package dev.ttsmobile.backend

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preferências centrais do app: URL do backend, configuração do PRoot e log.
 */
object Prefs {
    private const val FILE = "tts_mobile_prefs"

    const val KEY_URL_BASE = "url_base"
    const val KEY_ROOTFS_PATH = "prroot_rootfs_path"
    const val KEY_PACKAGES = "prroot_packages"
    const val KEY_MODEL = "last_model"
    const val KEY_SPEAKER = "last_speaker"

    /** URL padrão: Flask escutando no loopback dentro do PRoot. */
    const val DEFAULT_URL = "http://127.0.0.1:5000/"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun urlBase(ctx: Context): String =
        sp(ctx).getString(KEY_URL_BASE, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrlBase(ctx: Context, value: String) =
        sp(ctx).edit { putString(KEY_URL_BASE, value.trimEnd('/') + '/') }

    fun rootfsPath(ctx: Context): String =
        sp(ctx).getString(KEY_ROOTFS_PATH, "") ?: ""

    fun setRootfsPath(ctx: Context, value: String) =
        sp(ctx).edit { putString(KEY_ROOTFS_PATH, value) }

    fun packages(ctx: Context): String =
        sp(ctx).getString(KEY_PACKAGES, "python3 python3-pip curl ca-certificates")
            ?: "python3 python3-pip curl ca-certificates"

    fun setPackages(ctx: Context, value: String) =
        sp(ctx).edit { putString(KEY_PACKAGES, value) }

    fun lastModel(ctx: Context): String? = sp(ctx).getString(KEY_MODEL, null)
    fun setLastModel(ctx: Context, v: String) = sp(ctx).edit { putString(KEY_MODEL, v) }

    fun lastSpeaker(ctx: Context): String? = sp(ctx).getString(KEY_SPEAKER, null)
    fun setLastSpeaker(ctx: Context, v: String) = sp(ctx).edit { putString(KEY_SPEAKER, v) }
}

/**
 * Barramento de log em memória — a UI (aba Backend) exibe os eventos de
 * inicialização do PRoot, erros de síntese etc. Mantém as últimas 500 linhas.
 */
object AppLog {
    private const val MAX_LINES = 500
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun add(message: String) {
        val entry = "[${timeFmt.format(Date())}] $message"
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(entry)
        listeners.toList().forEach { it(entry) }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    fun addListener(l: (String) -> Unit) {
        synchronized(this) { listeners.add(l) }
    }

    fun removeListener(l: (String) -> Unit) {
        synchronized(this) { listeners.remove(l) }
    }
}
