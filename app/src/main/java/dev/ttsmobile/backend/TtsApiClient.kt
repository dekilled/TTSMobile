package dev.ttsmobile.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para o backend Flask (PocketTTSCtrl) que roda dentro do
 * Ubuntu em PRoot, acessível via http://127.0.0.1:5000/.
 *
 * Endpoints usados (ver backend/server.py):
 *   GET  /health                 -> status do serviço e modelos carregados
 *   GET  /voices                 -> vozes disponíveis por modelo
 *   POST /synthesize             -> síntese multipart (retorna WAV + métricas)
 *   POST /synthesize_stream      -> síntese em chunks PCM (streaming bruto)
 *   POST /cancel                 -> cancela a síntese em andamento
 */
class TtsApiClient(private val baseUrl: String) {

    companion object {
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }

    // Timeout de leitura alto: síntese em CPU mobile pode demorar.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    @Throws(IOException::class)
    fun health(): JSONObject = getJson(url("/health"))

    @Throws(IOException::class)
    fun voices(): JSONArray {
        val body = getJson(url("/voices?all=true"))
        return body.optJSONArray("voices") ?: JSONArray()
    }

    /**
     * Síntese não-streaming. Retorna os bytes WAV e as métricas que o servidor
     * envia nos headers X-RTF e X-Audio-Duration.
     */
    @Throws(IOException::class)
    fun synthesize(
        text: String,
        model: String?,
        speaker: String?,
        speed: Float,
        seed: Long?,
    ): SynthesisResult {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("speed", speed.toString())
        if (!model.isNullOrBlank()) builder.addFormDataPart("model", model)
        if (!speaker.isNullOrBlank()) builder.addFormDataPart("speaker", speaker)
        if (seed != null) builder.addFormDataPart("seed", seed.toString())

        val request = Request.Builder()
            .url(url("/synthesize"))
            .post(builder.build())
            .build()

        return execute(request) { resp ->
            val rtf = resp.header("X-RTF")?.toFloatOrNull()
            val dur = resp.header("X-Audio-Duration")?.toDoubleOrNull()
            val bytes = resp.body?.bytes() ?: throw IOException("resposta vazia")
            SynthesisResult(wav = bytes, rtf = rtf, audioSeconds = dur)
        }
    }

    /**
     * Síntese streaming: lê cada chunk PCM16/22050-Mono na ordem e chama
     * [onChunk] com um WAV completo por chunk (para tocar sequencialmente).
     */
    @Throws(IOException::class)
    fun synthesizeStream(
        text: String,
        model: String?,
        speaker: String?,
        speed: Float,
        seed: Long?,
        onChunk: (WavChunk) -> Boolean,
    ) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("speed", speed.toString())
        if (!model.isNullOrBlank()) builder.addFormDataPart("model", model)
        if (!speaker.isNullOrBlank()) builder.addFormDataPart("speaker", speaker)
        if (seed != null) builder.addFormDataPart("seed", seed.toString())

        val request = Request.Builder()
            .url(url("/synthesize_stream"))
            .post(builder.build())
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException(errMessage(resp))
            }
            val src = resp.body?.byteStream() ?: throw IOException("resposta vazia")
            src.use { input ->
                var index = 0
                while (true) {
                    val size = readInt32(input) ?: break // fim do stream
                    val payload = readExactly(input, size)
                        ?: throw IOException("stream truncado")
                    val last = readInt8(input) ?: 0
                    val pcm = if (last.toInt() == 1) payload.copyOf(payload.size - 1) else payload
                    val keepGoing = onChunk(WavChunk(index++, pcmToWav(pcm, sampleRate = 22050)))
                    if (!keepGoing) break
                    if (last.toInt() == 1) break
                }
            }
        }
    }

    @Throws(IOException::class)
    fun cancelSynthesis() {
        val request = Request.Builder()
            .url(url("/cancel"))
            .post(ByteArray(0).toRequestBody(JSON_MT))
            .build()
        client.newCall(request).execute().use { /* melhor esforço */ }
    }

    // ---------- helpers ----------

    data class SynthesisResult(val wav: ByteArray, val rtf: Float?, val audioSeconds: Double?)
    data class WavChunk(val index: Int, val wav: ByteArray)

    private fun getJson(u: String): JSONObject {
        val request = Request.Builder().url(u).get().build()
        return execute(request) { resp ->
            val text = resp.body?.string() ?: throw IOException("resposta vazia")
            JSONObject(text)
        }
    }

    private fun <T> execute(request: Request, parse: (Response) -> T): T {
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(errMessage(resp))
            return parse(resp)
        }
    }

    private fun errMessage(resp: Response): String {
        val raw = runCatching { resp.body?.string() }.getOrNull()
        val msg = raw?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
        return "HTTP ${resp.code}" + (if (!msg.isNullOrBlank()) ": $msg" else "")
    }

    private fun readInt32(input: java.io.InputStream): Int? {
        val b = readExactly(input, 4) ?: return null
        return ((b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24))
    }

    private fun readInt8(input: java.io.InputStream): Int? {
        val v = input.read()
        return if (v < 0) null else v
    }

    private fun readExactly(input: java.io.InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return if (off == 0 && n == 0) ByteArray(0) else null
            off += r
        }
        return buf
    }

    /** Empacota PCM16 mono como WAV RIFF para reprodução via MediaPlayer. */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val dataLen = pcm.size
        val out = java.io.ByteArrayOutputStream(44 + dataLen)
        val w = java.io.DataOutputStream(out)
        fun str(s: String) = s.toByteArray(Charsets.US_ASCII)
        w.writeBytes(str("RIFF")); w.writeIntLE(36 + dataLen)
        w.writeBytes(str("WAVE")); w.writeBytes(str("fmt "))
        w.writeIntLE(16); w.writeShortLE(1) // PCM
        w.writeShortLE(channels); w.writeIntLE(sampleRate)
        w.writeIntLE(byteRate); w.writeShortLE(channels * bits / 8); w.writeShortLE(bits)
        w.writeBytes(str("data")); w.writeIntLE(dataLen)
        w.write(pcm)
        w.flush()
        return out.toByteArray()
    }

    private fun java.io.DataOutputStream.writeIntLE(v: Int) {
        write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        ))
    }

    private fun java.io.DataOutputStream.writeShortLE(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }
}
