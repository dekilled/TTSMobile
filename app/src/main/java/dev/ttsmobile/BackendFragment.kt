package dev.ttsmobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ttsmobile.backend.AppLog
import dev.ttsmobile.backend.PRootBackendService
import dev.ttsmobile.backend.Prefs
import dev.ttsmobile.databinding.FragmentBackendBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aba Backend: configura a URL do Flask, a imagem Ubuntu do PRoot e mostra o
 * log de eventos. Também permite iniciar/parar o serviço em primeiro plano.
 */
class BackendFragment : Fragment() {

    private var _b: FragmentBackendBinding? = null
    private val b get() = _b!!

    private val logListener: (String) -> Unit = { line ->
        _b?.tvLog?.post { b.tvLog.append("\n$line") }
    }

    private val pickRootfs = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::onRootfsPicked)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentBackendBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.tvLog.movementMethod = ScrollingMovementMethod()
        renderLog()
        AppLog.addListener(logListener)

        // carrega preferências atuais
        b.etUrlBase.setText(Prefs.urlBase(requireContext()))
        b.etRootfs.setText(Prefs.rootfsPath(requireContext()))
        b.etPacotes.setText(Prefs.packages(requireContext()))

        b.btnSalvarUrl.setOnClickListener {
            val url = b.etUrlBase.text?.toString()?.trim().orEmpty()
            if (url.startsWith("http")) {
                Prefs.setUrlBase(requireContext(), url)
                AppLog.add("URL salva: $url")
            } else {
                AppLog.add("URL inválida — use http://127.0.0.1:5000/")
            }
        }

        b.btnVerificarSaude.setOnClickListener { checkHealth() }

        b.btnEscolherRootfs.setOnClickListener { openRootfsPicker() }

        b.btnIniciarBackend.setOnClickListener {
            Prefs.setRootfsPath(requireContext(), b.etRootfs.text?.toString()?.trim().orEmpty())
            Prefs.setPackages(requireContext(), b.etPacotes.text?.toString()?.trim().orEmpty())
            PRootBackendService.start(requireContext())
        }

        b.btnPararBackend.setOnClickListener {
            PRootBackendService.stop(requireContext())
        }

        b.btnLimparLog.setOnClickListener {
            AppLog.clear()
            renderLog()
        }
    }

    override fun onResume() {
        super.onResume()
        renderLog()
    }

    override fun onDestroyView() {
        AppLog.removeListener(logListener)
        _b = null
        super.onDestroyView()
    }

    private fun renderLog() {
        val lines = AppLog.snapshot()
        _b?.tvLog?.text = if (lines.isEmpty()) getString(R.string.log_vazio)
        else lines.joinToString("\n")
    }

    private fun checkHealth() {
        val url = b.etUrlBase.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching { dev.ttsmobile.backend.TtsApiClient(url).health() }
            withContext(Dispatchers.Main) {
                if (_b == null) return@withContext
                res.onSuccess { json ->
                    AppLog.add("/health OK: $json")
                }.onFailure { e ->
                    AppLog.add("/health falhou: ${e.message}")
                }
            }
        }
    }

    /**
     * Abre o SAF para escolher a imagem do Ubuntu (.tar.gz/.ext2). Copiamos o
     * arquivo para storage privado apenas se for pequeno; caso contrário
     * gravamos o caminho selecionado e pedimos que o usuário aponte o PRoot
     * para ele (Termux não lê content:// diretamente).
     */
    private fun openRootfsPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            pickRootfs.launch(intent)
        } catch (e: Exception) {
            AppLog.add("Seletor de arquivos indisponível: ${e.message}")
        }
    }

    private fun onRootfsPicked(uri: Uri) {
        val name = queryName(uri) ?: "rootfs.tar.gz"
        val target = File(requireContext().filesDir, "prroot/$name")
        target.parentFile?.mkdirs()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                } ?: error("fluxo vazio")
            }
            withContext(Dispatchers.Main) {
                if (_b == null) return@withContext
                ok.onSuccess {
                    val path = target.absolutePath
                    b.etRootfs.setText(path)
                    Prefs.setRootfsPath(requireContext(), path)
                    AppLog.add("Imagem copiada para $path (${target.length()} bytes)")
                }.onFailure { e ->
                    AppLog.add("Falha ao copiar rootfs: ${e.message}")
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        return runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }
}
