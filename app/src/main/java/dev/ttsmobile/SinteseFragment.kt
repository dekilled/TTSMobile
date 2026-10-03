package dev.ttsmobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ttsmobile.backend.AppLog
import dev.ttsmobile.backend.PRootBackendService
import dev.ttsmobile.backend.Prefs
import dev.ttsmobile.backend.TtsApiClient
import dev.ttsmobile.databinding.FragmentSinteseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Tela principal de teste: texto + voz + modelo + velocidade -> síntese.
 * Usa /synthesize_stream quando disponível; cai para /synthesize se o
 * streaming falhar (ex.: servidor antigo).
 */
class SinteseFragment : Fragment() {

    private var _b: FragmentSinteseBinding? = null
    private val b get() = _b!!

    private var player: ChunkPlayer? = null
    private var lastWav: ByteArray? = null
    private var speakersByModel: Map<String, List<String>> = emptyMap()
    private var models: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSinteseBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        player = ChunkPlayer(requireContext().cacheDir)

        b.tvVelocidadeLabel.text = getString(R.string.label_velocidade, "1.00")
        b.slVelocidade.addOnChangeListener { _, value, _ ->
            b.tvVelocidadeLabel.text =
                getString(R.string.label_velocidade, String.format("%.2f", value))
        }

        b.btnSintetizar.setOnClickListener { doSynthesize(streaming = true) }
        b.btnParar.setOnClickListener { doStop() }
        b.btnSalvarWav.setOnClickListener { saveWav() }

        refreshVoices()
    }

    override fun onResume() {
        super.onResume()
        checkHealth()
    }

    override fun onDestroyView() {
        player?.stop()
        _b = null
        super.onDestroyView()
    }

    // ---------- health / vozes ----------

    private fun client(): TtsApiClient = TtsApiClient(Prefs.urlBase(requireContext()))

    private fun checkHealth() {
        val c = client()
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { c.health() }.getOrNull()
            }
            if (_b == null) return@launch
            if (info != null) {
                val loaded = info.optJSONArray("loaded_models")?.length() ?: 0
                b.tvStatusBackend.text = getString(R.string.status_backend_online, loaded)
            } else {
                b.tvStatusBackend.text = getString(R.string.status_backend_offline)
            }
        }
    }

    private fun refreshVoices() {
        val c = client()
        lifecycleScope.launch {
            val voicesJson = withContext(Dispatchers.IO) {
                runCatching { c.voices() }.getOrElse { JSONArray() }
            }
            if (_b == null) return@launch
            speakersByModel = parseVoices(voicesJson)
            models = speakersByModel.keys.sorted()
            fillModels()
        }
    }

    private fun parseVoices(arr: JSONArray): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val model = obj.optString("model").ifBlank { "?" }
            val spk = obj.optString("speaker")
            out.getOrPut(model) { mutableListOf() }.add(spk)
        }
        return out
    }

    private fun fillModels() {
        b.ddModelo.setOnItemClickListener { _, _, pos, _ -> fillSpeakers() }
        if (models.isEmpty()) {
            // valores padrão documentados no README mesmo sem backend ligado
            setDropdown(b.ddModelo, listOf("cpu-xf-tiny-2025-07-25", "cpu-f16-240M-2025-09-02"))
            setDropdown(b.ddVoz, listOf("(sem vozes carregadas)"))
            return
        }
        setDropdown(b.ddModelo, models)
        val remembered = Prefs.lastModel(requireContext())
        val chosen = models.indexOfFirst { it == remembered }.takeIf { it >= 0 } ?: 0
        b.ddModelo.setText(models[chosen], false)
        fillSpeakers()
    }

    private fun fillSpeakers() {
        val model = b.ddModelo.text.toString().ifBlank { models.firstOrNull() ?: "" }
        val speakers = speakersByModel[model] ?: emptyList()
        setDropdown(b.ddVoz, speakers.ifEmpty { listOf("(padrão)") })
        val remembered = Prefs.lastSpeaker(requireContext())
        val idx = speakers.indexOfFirst { it == remembered }.takeIf { it >= 0 } ?: 0
        if (speakers.isNotEmpty()) b.ddVoz.setText(speakers[idx], false)
    }

    private fun setDropdown(tv: com.google.android.material.textfield.MaterialAutoCompleteTextView, items: List<String>) {
        tv.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                items
            )
        )
    }

    // ---------- síntese ----------

    private fun selectedSpeaker(): String? {
        val s = b.ddVoz.text.toString()
        return if (s.isBlank() || s.startsWith("(")) null else s
    }

    private fun doSynthesize(streaming: Boolean) {
        val text = b.etTexto.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            b.tvResultado.text = "Digite um texto primeiro."
            return
        }
        val model = b.ddModelo.text.toString().ifBlank { null }
        val speaker = selectedSpeaker()
        val speed = b.slVelocidade.value
        val seed = b.etSeed.text?.toString()?.toLongOrNull()
        if (model != null) Prefs.setLastModel(requireContext(), model)
        if (speaker != null) Prefs.setLastSpeaker(requireContext(), speaker)

        val c = client()
        b.btnSintetizar.isEnabled = false
        b.btnParar.isEnabled = true
        b.tvResultado.text = "Sintetizando…"
        AppLog.add("sintetizando ${text.length} chars (modelo=${model ?: "-"}, voz=${speaker ?: "-"})")

        lifecycleScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var usedStreaming = streaming
            val chunkTimes = mutableListOf<Long>()
            try {
                if (usedStreaming) {
                    player?.start()
                    try {
                        c.synthesizeStream(text, model, speaker, speed, seed) { chunk ->
                            synchronized(chunkTimes) {
                                chunkTimes += System.currentTimeMillis() - t0
                            }
                            player?.enqueue(chunk.wav) ?: false
                        }
                    } catch (e: Exception) {
                        // fallback para rota não-streaming
                        AppLog.add("stream falhou (${e.message}); tentando /synthesize")
                        usedStreaming = false
                        player?.stop()
                    }
                }
                if (!usedStreaming) {
                    val res = c.synthesize(text, model, speaker, speed, seed)
                    lastWav = res.wav
                    withContext(Dispatchers.Main) { if (_b != null) b.btnSalvarWav.isEnabled = true }
                    ChunkPlayer.playOneShot(requireContext().cacheDir, res.wav)
                }
                val elapsed = System.currentTimeMillis() - t0
                val msg = buildString {
                    appendLine(
                        "OK — ${elapsed} ms de requisição" +
                            if (usedStreaming) " (streaming)" else " (não-streaming)"
                    )
                    if (usedStreaming && chunkTimes.isNotEmpty()) {
                        appendLine("primeiro chunk em ${chunkTimes.first()} ms")
                        appendLine("${chunkTimes.size} chunks recebidos")
                    }
                }
                withContext(Dispatchers.Main) {
                    if (_b != null) b.tvResultado.text = msg
                    AppLog.add(msg.trim())
                }
            } catch (e: Exception) {
                val msg = "Erro: ${e.message}"
                AppLog.add(msg)
                withContext(Dispatchers.Main) {
                    if (_b != null) {
                        b.tvResultado.text = msg
                        b.tvStatusBackend.text = getString(R.string.status_backend_erro)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _b?.btnSintetizar?.isEnabled = true
                    _b?.btnParar?.isEnabled = false
                }
            }
        }
    }

    private fun doStop() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { client().cancelSynthesis() }
        }
        player?.stop()
        b.btnParar.isEnabled = false
        b.tvResultado.text = "Cancelado."
        AppLog.add("síntese cancelada pelo usuário")
    }

    private fun saveWav() {
        val wav = lastWav ?: return
        val name = "tts_${System.currentTimeMillis()}.wav"
        val exports = File(
            requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
            name
        )
        exports.writeBytes(wav)
        b.tvResultado.append("\nSalvo em: ${exports.absolutePath}")
        AppLog.add("WAV salvo: ${exports.name}")
    }
}
