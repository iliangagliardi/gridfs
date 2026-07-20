#!/usr/bin/env bash
#
# Uploads everything in samples/out/ into a running GridFS demo via the
# documented API: POST /api/files (multipart: file, tags, uploadedBy).
#
#   ./samples/upload-samples.sh
#   BASE_URL=http://localhost:9000 ./samples/upload-samples.sh
#
# Idempotent in the sense that it is safe to re-run, but note the API has no
# de-duplication: running it twice gives you two copies of every file.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${SCRIPT_DIR}/out"
BASE_URL="${BASE_URL:-http://localhost:8081}"
UPLOADED_BY="${UPLOADED_BY:-demo}"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl not found"
[[ -d "${OUT}" ]] || die "No ${OUT} directory. Run ./samples/generate-samples.sh first."

# Fail fast with a useful message rather than a wall of connection refused.
curl -fsS --max-time 5 "${BASE_URL}/actuator/health" >/dev/null 2>&1 \
  || die "No app answering at ${BASE_URL}. Start it with ./run.sh (or docker compose up)."

# Tags per file, so the demo has something to facet and filter on.
tags_for() {
  case "$(basename "$1")" in
    mongodb-sharding.md)             echo "mongodb,database,scaling,engineering" ;;
    gridfs-architecture-notes.txt)   echo "mongodb,gridfs,architecture,engineering" ;;
    coffee-roasting.txt)             echo "coffee,craft,process" ;;
    sailing-offshore.md)             echo "sailing,seamanship,safety" ;;
    lighthouse-survey.pdf)           echo "report,survey,pdf,archive" ;;
    quarterly-storage-review.docx)   echo "report,storage,finance,docx" ;;
    seek-test-clip.mp4)              echo "video,media,streaming,seek-demo" ;;
    tone-sweep.mp3)                  echo "audio,media,streaming" ;;
    colour-bars.png|mandelbrot.jpg)  echo "image,test-pattern" ;;
    scanned-invoice.png)             echo "invoice,scanned,ocr-demo" ;;
    *)                               echo "sample" ;;
  esac
}

bold "Uploading ${OUT} → ${BASE_URL}/api/files"

count=0; failed=0
while IFS= read -r f; do
  name="$(basename "$f")"
  tags="$(tags_for "$f")"
  body="$(curl -sS -w $'\n%{http_code}' -X POST "${BASE_URL}/api/files" \
            -F "file=@${f}" \
            -F "tags=${tags}" \
            -F "uploadedBy=${UPLOADED_BY}" 2>&1)"
  code="$(printf '%s' "${body}" | tail -n1)"
  json="$(printf '%s' "${body}" | sed '$d')"

  if [[ "${code}" == "201" ]]; then
    # Pull a couple of fields out without requiring jq.
    state="$(printf '%s' "${json}" | sed -n 's/.*"extractionState":"\([A-Z]*\)".*/\1/p')"
    tlen="$(printf '%s' "${json}" | sed -n 's/.*"textLength":\([0-9]*\).*/\1/p')"
    ok "$(printf '%-32s' "${name}") [${tags}] extraction=${state:-?} textLength=${tlen:-0}"
    count=$((count + 1))
  else
    bad "${name} → HTTP ${code}: $(printf '%s' "${json}" | head -c 200)"
    failed=$((failed + 1))
  fi
done < <(find "${OUT}" -type f ! -name '.*' | sort)

echo
bold "Uploaded ${count} file(s)${failed:+, ${failed} failed}"
echo "  Browse:  ${BASE_URL}/"
echo "  Try searching for:  PHOSPHORESCENT ALBATROSS   (only on an interior PDF page)"
echo "                      SEDIMENTARY BOOKKEEPING     (only in the .docx)"
echo "                      heaving to                  (sailing note ranks 1st of 3 — shows relevance)"
echo ""
echo "  OCR demo:  scanned-invoice.png has NO text layer — searching"
echo "             TRILOBITE SEMAPHORE finds nothing until you open the file"
echo "             and press Run OCR, then it becomes searchable."
