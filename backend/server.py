#!/usr/bin/env python3
"""
Backend de teste para modelos TTS locais (foco: PocketTTS / kyutai) rodando em
um Ubuntu sobre PRoot no Android.

O app Android (Kotlin) fala com este servidor via http://127.0.0.1:5000/.

Endpoints
---------
GET  /health              status, versões e modelos carregados
GET  /voices              vozes disponíveis (filtro ?model=&language=)
POST /synthesize          multipart -> WAV completo + headers X-RTF / X-Audio-Duration
POST /synthesize_stream   multipart -> stream binário de chunks PCM16/22050 mono
POST /cancel              cancela a síntese em andamento

Protocolo do /synthesize_stream (little-endian, na ordem):
    [4 bytes] tamanho do payload N
    [N bytes] payload = PCM16 mono 22050 Hz (+ 1 byte flag "last" se last==1)
    [1 byte]  last (0 = mais chunks, 1 = último chunk)
Repete até o fim; conexão fechada = fim do stream. O cliente monta WAVs por
chunk e toca sequencialmente (ver ChunkPlayer.kt).

Uso:
    python3 server.py --host 127.0.0.1 --port 5000 [--mock]

--mock sobe o servidor sem PocketTTS instalado (útil para desenvolver a UI no
emulador). As vozes retornadas são fictícias e o áudio é um beep suave.
"""

import argparse
import io
import json
import math
import struct
import sys
import threading
import time
import wave

from flask import Flask, Response, jsonify, request

SAMPLE_RATE = 22050

app = Flask(__name__)

# ---------------------------------------------------------------------------
# Camada PocketTTS (kyutai pockets) — import tardio para permitir --mock.
# ---------------------------------------------------------------------------


class PocketEngine:
    """Envolve `pockets` (kyutai/pocket-tts). Ver README para instalação."""

    def __init__(self):
        self._lock = threading.Lock()
        self._cancel = threading.Event()
        self._generators = {}
        self._gen_counter = 0
        try:
            from pockets import AudioModel  # type: ignore
        except Exception as e:  # pragma: no cover
            raise RuntimeError(f"pockets não disponível: {e}")
        self._AudioModel = AudioModel
        self._models = {}  # nome -> AudioModel carregado

    def get_model(self, name: str):
        with self._lock:
            if name not in self._models:
                t0 = time.time()
                self._models[name] = self._AudioModel(name)
                print(f"[engine] modelo '{name}' carregado em {time.time()-t0:.1f}s")
            return self._models[name]

    def loaded_models(self):
        return sorted(self._models.keys())

    def speakers_for(self, model_name: str):
        model = self.get_model(model_name)
        try:
            return list(model.list_speakers())
        except Exception:
            return []

    # ---- geração -----------------------------------------------------------

    def synthesize(self, text, model, speaker, speed, seed):
        """Retorna (pcm_bytes, duracao_segundos)."""
        gid = self.start(text, model, speaker, speed, seed)
        chunks = []
        for pcm, _sr, _last in self.iter_chunks(gid):
            chunks.append(pcm)
        pcm = b"".join(chunks)
        dur = len(pcm) / 2 / SAMPLE_RATE
        return pcm, dur

    def start(self, text, model, speaker, speed, seed):
        model = self.get_model(model or 'default')
        kwargs = {"text": text}
        if speaker:
            kwargs["speaker"] = speaker
        if speed and abs(speed - 1.0) > 1e-3:
            kwargs["speed_factor"] = float(speed)
        if seed is not None:
            kwargs["seed"] = int(seed)
        # API do pockets (kyutai/pocket-tts): gera um iterator de blocos de áudio.
        gen_fn = getattr(model, "generate_audio_stream", None) or model.generate_audio
        gen = gen_fn(**kwargs)
        with self._lock:
            self._gen_counter += 1
            gid = self._gen_counter
            self._generators[gid] = gen
        return gid

    def iter_chunks(self, gid, block=False):
        with self._lock:
            gen = self._generators.get(gid)
        if gen is None:
            return
        try:
            for item in gen:
                if self._cancel.is_set():
                    break
                # item pode ser np.ndarray ou (array, sr)
                if isinstance(item, tuple):
                    arr, sr = item
                else:
                    arr, sr = item, SAMPLE_RATE
                pcm = float_array_to_pcm16(arr)
                yield pcm, sr, False
        finally:
            with self._lock:
                self._generators.pop(gid, None)

    def cancel(self):
        self._cancel.set()
        time.sleep(0.05)
        self._cancel.clear()


def float_array_to_pcm16(arr) -> bytes:
    import numpy as np  # type: ignore

    a = np.asarray(arr, dtype=np.float32).reshape(-1)
    a = np.clip(a, -1.0, 1.0)
    return (a * 32767.0).astype("<i2").tobytes()


# ---------------------------------------------------------------------------
# Engine mock (sem PocketTTS instalado — desenvolvimento da UI / CI)
# ---------------------------------------------------------------------------


class MockEngine:
    def __init__(self):
        import numpy as np  # noqa: F401 (usado em _chunks)
        self._np = np
        self._cancel = threading.Event()
        self.speakers = {
            "cpu-xf-tiny-2025-07-25": ["br_f1", "br_m1", "en_default"],
            "cpu-f16-240M-2025-09-02": ["br_f1", "br_m1", "pt_novo"],
        }

    def loaded_models(self):
        return sorted(self.speakers.keys())

    def speakers_for(self, model_name):
        return self.speakers.get(model_name, [])

    def synthesize(self, text, model, speaker, speed, seed):
        pcm = b"".join(c for c, _ in self._chunks(text, speed))
        dur = len(pcm) / 2 / SAMPLE_RATE
        return pcm, dur

    def start(self, text, model, speaker, speed, seed):
        return ("mock", text, speed)

    def iter_chunks(self, gid, block=False):
        _, text, speed = gid
        yield from ((pcm, SAMPLE_RATE, False) for pcm, _ in self._chunks(text, speed))

    def _chunks(self, text, speed):
        np = self._np
        # ~0.06s de "fala" por palavra, tom senoidal suave por voz
        words = max(1, len(text.split()))
        total = words * 0.06 / max(0.5, speed or 1.0)
        n = int(total * SAMPLE_RATE)
        step = SAMPLE_RATE // 8  # chunks de 1/8 s
        off = 0
        while off < n:
            k = min(step, n - off)
            idx = np.arange(off, off + k, dtype=np.float64) / SAMPLE_RATE
            samples = (12000 * np.sin(2 * np.pi * 220 * idx) * np.exp(-idx)).astype("<i2")
            yield samples.tobytes(), k / SAMPLE_RATE
            off += k

    def cancel(self):
        pass


# ---------------------------------------------------------------------------
# Instanciação da engine
# ---------------------------------------------------------------------------

ENGINE = None  # definida em main()


# ---------------------------------------------------------------------------
# Rotas
# ---------------------------------------------------------------------------


@app.get("/health")
def health():
    return jsonify(
        {
            "status": "ok",
            "engine": ENGINE.__class__.__name__,
            "loaded_models": ENGINE.loaded_models(),
            "sample_rate": SAMPLE_RATE,
            "python": sys.version.split()[0],
            "time": time.time(),
        }
    )


@app.get("/voices")
def voices():
    model_filter = request.args.get("model")
    out = []
    for m in ENGINE.loaded_models():
        if model_filter and m != model_filter:
            continue
        for spk in ENGINE.speakers_for(m):
            out.append({"model": m, "speaker": spk, "language": "auto"})
    return jsonify({"voices": out})


def _form():
    text = (request.form.get("text") or "").strip()
    if not text:
        return None, (jsonify({"error": "campo 'text' obrigatório"}), 400)
    model = request.form.get("model") or (
        ENGINE.loaded_models()[0] if ENGINE.loaded_models() else None
    )
    speaker = request.form.get("speaker") or None
    try:
        speed = float(request.form.get("speed") or 1.0)
    except ValueError:
        speed = 1.0
    seed_raw = request.form.get("seed")
    seed = int(seed_raw) if seed_raw and seed_raw.isdigit() else None
    return dict(text=text, model=model, speaker=speaker, speed=speed, seed=seed), None


@app.post("/synthesize")
def synthesize():
    params, err = _form()
    if err:
        return err
    t0 = time.time()
    try:
        pcm, dur = ENGINE.synthesize(**params)
    except Exception as e:  # pragma: no cover
        return jsonify({"error": f"síntese falhou: {e}"}), 500
    elapsed = time.time() - t0
    rtf = elapsed / dur if dur > 0 else 0.0

    wav_buf = io.BytesIO()
    with wave.open(wav_buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm)

    resp = Response(wav_buf.getvalue(), mimetype="audio/wav")
    resp.headers["X-RTF"] = f"{rtf:.4f}"
    resp.headers["X-Audio-Duration"] = f"{dur:.3f}"
    resp.headers["Content-Disposition"] = 'attachment; filename="tts.wav"'
    return resp


@app.post("/synthesize_stream")
def synthesize_stream():
    params, err = _form()
    if err:
        return err

    def gen():
        try:
            gid = ENGINE.start(**params)
            pending = []
            for pcm, _sr, _last in ENGINE.iter_chunks(gid):
                pending.append(pcm)
                # acumula ~0.5s por frame para reduzir overhead no PRoot
                if sum(len(p) for p in pending) >= SAMPLE_RATE * 2:
                    data = b"".join(pending)
                    pending = []
                    yield struct.pack("<I", len(data)) + data + b"\x00"
            tail = b"".join(pending)
            yield struct.pack("<I", len(tail)) + tail + b"\x01"
        except Exception as e:  # encerra o stream; cliente verá truncamento
            app.logger.error("stream error: %s", e)

    return Response(gen(), mimetype="application/octet-stream")


@app.post("/cancel")
def cancel():
    ENGINE.cancel()
    return jsonify({"cancelled": True})


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    global ENGINE
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument(
        "--mock",
        action="store_true",
        help="usa engine simulada (sem PocketTTS) para testar a UI",
    )
    args = ap.parse_args()

    if args.mock:
        ENGINE = MockEngine()
        print("[server] engine MOCK ativa (sem PocketTTS)")
    else:
        try:
            ENGINE = PocketEngine()
        except Exception as e:
            print(f"[server] {e}\n[server] use --mock para desenvolver sem o modelo.")
            sys.exit(1)

    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
