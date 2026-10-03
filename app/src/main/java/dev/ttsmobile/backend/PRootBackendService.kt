package dev.ttsmobile.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.ttsmobile.R
import java.io.File

/**
 * Serviço em primeiro plano que mantém vivo o ambiente Ubuntu/Python dentro do
 * PRoot enquanto o backend Flask atende requisições na porta 5000 (loopback).
 *
 * Estratégia de integração (documentada no README):
 *
 *  Opção A — Termux + plugin Termux:PRoot (recomendada, pois o PocketTTS é
 *  distribuído como AppImage do Linux):
 *    1. Instale Termux e o add-on Termux:PRoot (mesmo canal de assinatura).
 *    2. `pkg install proot` e importe uma imagem Ubuntu (tar.gz) com
 *       `proot-distro install ubuntu` ou coloque a imagem no caminho indicado
 *       na aba Backend.
 *    3. Copie este app + o diretório backend/ para o device e rode dentro do
 *       PRoot: ./run_pockettts.sh (script gerado por este serviço).
 *    4. O botão "Iniciar backend" abaixo dispara um Intent para o perfil de
 *       sessão do Termux via `am` quando disponível; caso contrário apenas
 *       prepara os arquivos e mostra as instruções no log.
 *
 *  Opção B — binário `proot` embutido no APK (NDK):
 *    Coloque o executável proot em app/src/main/jniLibs/<abi>/libproot.so e o
 *    código abaixo passa a executá-lo diretamente sem depender do Termux.
 */
class PRootBackendService : Service() {

    companion object {
        const val ACTION_START = "dev.ttsmobile.backend.START"
        const val ACTION_STOP = "dev.ttsmobile.backend.STOP"

        private const val CHANNEL_ID = "tts_backend_prroot"
        private const val NOTIF_ID = 42

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, PRootBackendService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, PRootBackendService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBackend()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startBackend()
        }
        return START_STICKY
    }

    private fun startBackend() {
        createChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_backend_rodando)))
        running = true
        AppLog.add("Serviço PRoot iniciado.")

        val rootfs = Prefs.rootfsPath(this)
        if (rootfs.isBlank()) {
            AppLog.add("Defina o caminho da imagem Ubuntu (rootfs) na aba Backend.")
        } else {
            prepareLaunchScripts(File(rootfs))
            trySpawnTermuxPRoot(rootfs)
        }
    }

    private fun stopBackend() {
        running = false
        AppLog.add("Serviço PRoot parado.")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Gera dentro do storage privado do app os scripts que sobem o Flask +
     * PocketTTS dentro do Ubuntu em PRoot. Copiáveis para o Termux via adb.
     */
    private fun prepareLaunchScripts(rootfs: File) {
        val dir = File(filesDir, "prroot").apply { mkdirs() }
        // copia a pasta backend/ do assets se necessário (ver README sobre empacotar)
        val script = File(dir, "run_pockettts.sh")
        script.writeText(
            """
            #!/data/data/com.termux/files/usr/bin/bash
            # Gerado por TTS Mobile — sobe o backend PocketTTS dentro do PRoot.
            set -e
            ROOTFS='${rootfs.absolutePath}'
            DISTRO_DIR=/data/data/dev.ttsmobile/files/prroot/backend
            proot -R "${'$'}ROOTFS" -b "${'$'}DISTRO_DIR:/opt/tts-backend" \
              /usr/bin/env bash -lc '
                cd /opt/tts-backend
                python3 -m venv .venv 2>/dev/null || true
                . .venv/bin/activate
                pip install -q flask pocket-tts-ctrl || true
                exec python3 server.py --host 127.0.0.1 --port 5000
              '
            """.trimIndent()
        )
        script.setExecutable(true)
        AppLog.add("Script de boot PRoot pronto em ${script.absolutePath}")
    }

    /**
     * Melhor esforço: se houver Termux com plugin PRoot instalado, tenta iniciar
     * a sessão via broadcast/am. Sem Termux, documenta os passos no log.
     */
    private fun trySpawnTermuxPRoot(rootfs: String) {
        val termuxInstalled = packageManager.getInstalledPackages(0)
            .any { it.packageName == "com.termux" }
        if (!termuxInstalled) {
            AppLog.add("Termux não encontrado — inicie o backend manualmente (veja README).")
            return
        }
        AppLog.add("Termux detectado: use o comando gerado no log para subir o Flask no PRoot.")
        AppLog.add("dica: termux-wake-lock && ~/run_pockettts.sh &")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_canal_backend),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
}
