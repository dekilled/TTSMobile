#!/usr/bin/env bash
# Boot do backend PocketTTS dentro do Ubuntu em PRoot.
# Copie este arquivo + server.py para dentro da imagem (ex.: /opt/tts-backend).
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d .venv ]; then
  python3 -m venv .venv
fi
. .venv/bin/activate
pip install -q -r requirements.txt

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-5000}"
EXTRA=""
# Sem o modelo instalado (ex.: primeiro boot), sobe em modo mock p/ testar a UI.
python3 -c "import pockets" 2>/dev/null || EXTRA="--mock"

exec python3 server.py --host "$HOST" --port "$PORT" $EXTRA
