#!/usr/bin/env bash
#
# Generates a realistic sample corpus for the GridFS demo, using only tools
# that are commonly present on macOS. Everything is optional: if a generator
# is missing, that format is skipped with a clear message and the script
# carries on. Output lands in samples/out/.
#
#   ./samples/generate-samples.sh
#   ./samples/upload-samples.sh          # then load it into the running app
#
# What it tries to produce:
#   text   .txt / .md   always (pure shell)
#   pdf    .pdf         via macOS `cupsfilter` (built in)
#   docx   .docx        via python-docx, else a minimal OOXML zip via python3
#   video  .mp4         via ffmpeg (burned-in second counter, for seek proof)
#   audio  .mp3         via ffmpeg
#   image  .png/.jpg    via ffmpeg
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${SCRIPT_DIR}/out"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
skip() { printf '  \033[33m–\033[0m SKIP %s\n' "$*"; }
info() { printf '  \033[36m•\033[0m %s\n' "$*"; }

have() { command -v "$1" >/dev/null 2>&1; }

mkdir -p "${OUT}"
bold "Generating sample corpus into ${OUT}"

# =============================================================================
# 1. Text documents — deliberately different topics so search relevance is
#    visibly meaningful during the demo. Each carries a distinctive phrase.
# =============================================================================

cat > "${OUT}/mongodb-sharding.md" <<'EOF'
# Horizontal Scaling with MongoDB Sharding

Sharding is how MongoDB scales a single logical collection across many
machines. A sharded cluster has three moving parts: the shards themselves,
which hold the data; the config servers, which hold the cluster metadata; and
the mongos routers, which sit in front and send each operation to the right
place.

## Choosing a shard key

The shard key is the single most consequential decision in a sharded
deployment. A good key has high cardinality, low frequency, and does not
increase monotonically. A monotonically increasing key such as a timestamp or
an ObjectId funnels every new write into the same chunk, producing the classic
hot shard problem where one machine absorbs the entire insert workload while
the rest of the cluster idles.

Hashed sharding solves the hot shard problem by distributing writes evenly,
but it destroys range locality: a query for a contiguous span of values becomes
a scatter gather across every shard. Ranged sharding preserves locality at the
cost of requiring a genuinely well distributed key.

## Chunks, balancing, and jumbo chunks

Data is divided into chunks of 128 MB by default. The balancer migrates chunks
between shards in the background to keep the distribution even. A chunk that
cannot be split because every document in it shares the same shard key value is
marked jumbo, and jumbo chunks are the usual symptom of a low cardinality key.

## Sharding GridFS

GridFS collections shard well. Shard fs.chunks on the compound key
{ files_id: 1, n: 1 }, or on { files_id: "hashed" } when you would rather
distribute whole files evenly and never split a single file across shards.
Shard fs.files on _id. Because every chunk read is keyed by files_id, reads
are targeted rather than scattered, which is exactly the property you want for
range requests against large media objects.
EOF
ok "mongodb-sharding.md"

cat > "${OUT}/coffee-roasting.txt" <<'EOF'
Notes on Small Batch Coffee Roasting

Green coffee arrives dense, grassy, and completely uninteresting. Everything
that makes a cup worth drinking happens in the eleven or twelve minutes it
spends in the drum.

The drying phase runs from charge to roughly three hundred degrees Fahrenheit
of bean temperature. Free moisture leaves the bean and the colour shifts from
sage green to a pale yellow. Rush this phase and the bean surface scorches
while the core stays raw, producing a cup that is simultaneously ashy and sour.

The Maillard phase follows. Sugars and amino acids react, the bean turns
tan then cinnamon, and the aroma in the roastery changes from cut hay to
toasted bread. This is where body and sweetness are built. Roasters who chase
a fast total time almost always steal it from here, and the resulting coffee
tastes thin no matter how carefully it is brewed.

First crack is an audible snap, somewhere near three hundred and eighty five
degrees, as steam pressure ruptures the cell walls. From that moment the bean
is exothermic and the roast will run away from you if the gas is not cut. The
interval between first crack and drop is called development time, and the ratio
of development time to total roast time is the single number most roasters
track. Twenty percent is a reasonable target for a washed Ethiopian; a dense
Kenyan will want a little more.

Drop the batch into the cooling tray and agitate hard. A batch that sits hot in
a still tray keeps roasting for another ninety seconds and every one of those
seconds is a second you did not choose.

Rest the beans for at least three days. Carbon dioxide degassing is why a
freshly roasted coffee bloats the brew bed and tastes closed. By day four the
cup opens up. By day twenty-one it is a memory of itself.
EOF
ok "coffee-roasting.txt"

cat > "${OUT}/sailing-offshore.md" <<'EOF'
# Offshore Passage Planning for Short-Handed Crews

A coastal sailor who steps offshore for the first time is rarely undone by the
weather. They are undone by fatigue, by a watch system that looked fine on
paper, and by gear that was never tested at three in the morning in a seaway.

## Watch systems

The Swedish watch system — alternating four and six hour blocks — gives a
two-person crew a rotating schedule so nobody permanently owns the graveyard
watch. For a double-handed crew on a five day passage this is worth more than
any piece of equipment on the boat.

## Heaving to

Heaving to is the most underrated manoeuvre in the sailing canon. Back the
headsail, lash the helm to leeward, and the boat settles into a slow forereach
of about one knot, making a slick of disturbed water to windward that flattens
the approaching seas. It turns a survival situation into a lunch break. Practise
it in fifteen knots long before you need it in forty.

## Reefing early

The old rule holds: the moment you first wonder whether you should reef, you
should already have reefed. Shortening sail costs you a quarter of a knot of
boat speed and buys back the entire margin of the rig.

## Landfall

Time your landfall for daylight. An unfamiliar harbour entrance at night, after
four days of broken sleep, with a tide running across the approach, is where
otherwise sound passages come apart. If you are going to arrive at two in the
morning, slow down and arrive at eight.
EOF
ok "sailing-offshore.md"

cat > "${OUT}/gridfs-architecture-notes.txt" <<'EOF'
GridFS Architecture Notes

GridFS is not a separate storage engine. It is a convention: a driver-level
specification for splitting a large binary object across ordinary MongoDB
documents in two ordinary collections.

fs.files holds one metadata document per file: filename, length, chunkSize,
uploadDate, and an application-owned metadata sub-document. fs.chunks holds
the payload, split into fixed size binary chunks of 255 KB by default, each
tagged with its parent files_id and its ordinal position n. A unique index on
{ files_id: 1, n: 1 } makes any chunk of any file a single index seek.

Because chunks are addressable by ordinal, a byte range translates directly
into a chunk range. Serving HTTP Range bytes=5242880-7340031 means reading
chunks twenty through twenty-eight and trimming the ends. Nothing streams from
the beginning of the file, which is what makes mid-file seeking in a two hour
video as cheap as seeking at the start.

The 16 MB BSON document limit is the reason GridFS exists at all, and it is
also the constraint on extracted text: the text pulled out of a document is
stored inside the fs.files document, so it must be clipped well below 16 MB.
EOF
ok "gridfs-architecture-notes.txt"

# =============================================================================
# 2. PDF — built from a long text with a marker phrase deliberately buried in
#    the middle, so the demo can search for something that only appears on an
#    interior page and prove full-text extraction really read the whole file.
# =============================================================================

build_pdf_source() {
  local src="$1"
  : > "${src}"
  {
    echo "ANNUAL FIELD REPORT — NORTHERN LIGHTHOUSE SURVEY"
    echo "Volume IV, Section 3"
    echo
    echo "Prepared for the Coastal Infrastructure Committee."
    echo
    for i in $(seq 1 60); do
      echo "Station log entry ${i}: routine inspection completed, optic assembly"
      echo "cleaned, rotation gear within tolerance, no structural findings."
      echo
    done
    echo
    echo "  *** INTERIOR PAGE MARKER ***"
    echo
    echo "  The keeper's journal records a peculiar detail from the winter of"
    echo "  that year: the phrase PHOSPHORESCENT ALBATROSS appears in the"
    echo "  margin beside the entry for the fourteenth of February, in a hand"
    echo "  that is not the keeper's own. No explanation was ever recorded and"
    echo "  the annotation has never been reproduced in any other volume."
    echo
    echo "  This paragraph exists in the middle of the document and nowhere"
    echo "  else. If a search for PHOSPHORESCENT ALBATROSS returns this file,"
    echo "  the full text of every page was extracted and indexed."
    echo
    for i in $(seq 61 120); do
      echo "Station log entry ${i}: routine inspection completed, lamp changed,"
      echo "fog signal tested, ventilation clear, no structural findings."
      echo
    done
    echo
    echo "END OF SECTION 3."
  } >> "${src}"
}

if have cupsfilter; then
  PDF_SRC="${OUT}/.lighthouse-survey.txt"
  build_pdf_source "${PDF_SRC}"
  if cupsfilter "${PDF_SRC}" > "${OUT}/lighthouse-survey.pdf" 2>/dev/null \
     && [[ -s "${OUT}/lighthouse-survey.pdf" ]]; then
    rm -f "${PDF_SRC}"
    ok "lighthouse-survey.pdf ($(wc -c < "${OUT}/lighthouse-survey.pdf" | tr -d ' ') bytes, multi-page, marker phrase on an interior page)"
  else
    rm -f "${PDF_SRC}" "${OUT}/lighthouse-survey.pdf"
    skip "PDF — cupsfilter present but conversion failed"
  fi
elif have python3 && python3 -c "import reportlab" >/dev/null 2>&1; then
  PDF_SRC="${OUT}/.lighthouse-survey.txt"
  build_pdf_source "${PDF_SRC}"
  if python3 - "${PDF_SRC}" "${OUT}/lighthouse-survey.pdf" <<'PY' 2>/dev/null
import sys
from reportlab.lib.pagesizes import letter
from reportlab.pdfgen import canvas
src, dst = sys.argv[1], sys.argv[2]
c = canvas.Canvas(dst, pagesize=letter)
w, h = letter
y = h - 54
c.setFont("Helvetica", 10)
for line in open(src, encoding="utf-8"):
    if y < 54:
        c.showPage(); c.setFont("Helvetica", 10); y = h - 54
    c.drawString(54, y, line.rstrip()[:100]); y -= 13
c.save()
PY
  then
    rm -f "${PDF_SRC}"
    ok "lighthouse-survey.pdf (via reportlab)"
  else
    rm -f "${PDF_SRC}" "${OUT}/lighthouse-survey.pdf"
    skip "PDF — reportlab present but generation failed"
  fi
else
  skip "PDF — needs macOS cupsfilter or Python reportlab (pip3 install reportlab)"
fi

# =============================================================================
# 3. DOCX — python-docx if installed, otherwise a minimal but perfectly valid
#    OOXML package built with the Python standard library. Tika parses both.
# =============================================================================

DOCX_TEXT='Quarterly Storage Review

MongoDB GridFS was selected for the binary object tier after a comparison
against a filesystem-backed approach. The deciding factor was operational: one
backup, one replication stream, one security model, one set of credentials.

Median object size in the sample corpus was four megabytes, comfortably above
the sixteen megabyte BSON ceiling in the worst case, which is why the objects
live in GridFS rather than inline in a document field.

The distinctive term for this document is SEDIMENTARY BOOKKEEPING, which
appears in no other file in the corpus.'

if have python3 && python3 -c "import docx" >/dev/null 2>&1; then
  if DOCX_TEXT="${DOCX_TEXT}" python3 - "${OUT}/quarterly-storage-review.docx" <<'PY' 2>/dev/null
import os, sys
from docx import Document
d = Document()
for para in os.environ["DOCX_TEXT"].split("\n\n"):
    d.add_paragraph(para.replace("\n", " "))
d.save(sys.argv[1])
PY
  then
    ok "quarterly-storage-review.docx (via python-docx)"
  else
    skip "DOCX — python-docx present but generation failed"
  fi
elif have python3; then
  if DOCX_TEXT="${DOCX_TEXT}" python3 - "${OUT}/quarterly-storage-review.docx" <<'PY' 2>/dev/null
import os, sys, zipfile
from xml.sax.saxutils import escape

paras = [p.replace("\n", " ") for p in os.environ["DOCX_TEXT"].split("\n\n")]
body = "".join(
    '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>' % escape(p)
    for p in paras
)

CT = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>'''

RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

DOC = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>%s<w:sectPr/></w:body></w:document>''' % body

with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", CT)
    z.writestr("_rels/.rels", RELS)
    z.writestr("word/document.xml", DOC)
PY
  then
    ok "quarterly-storage-review.docx (minimal OOXML via python3 stdlib)"
  else
    skip "DOCX — python3 present but generation failed"
  fi
else
  skip "DOCX — needs python3"
fi

# =============================================================================
# 4. Video — 90 seconds at 1280x720. Long enough that the file spans hundreds
#    of 255 KB GridFS chunks, so seeking to the middle genuinely exercises
#    chunk-range reads rather than being served from the first chunk.
#
#    The frame carries a burned-in running second counter. Seek to 00:45 in
#    the demo and the number on screen proves the Range request landed exactly
#    where it was asked to. drawtext gives a nicer timecode when this ffmpeg
#    build has libfreetype; testsrc's own counter is the fallback and works
#    on every build.
# =============================================================================

if have ffmpeg; then
  VID="${OUT}/seek-test-clip.mp4"
  FONT=""
  for f in /System/Library/Fonts/Supplemental/Arial.ttf \
           /System/Library/Fonts/Helvetica.ttc \
           /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf; do
    [[ -f "$f" ]] && FONT="$f" && break
  done

  VF=""
  if [[ -n "${FONT}" ]] && ffmpeg -hide_banner -h filter=drawtext 2>&1 | grep -q "drawtext AVOptions"; then
    VF="drawtext=fontfile='${FONT}':text='%{pts\\:hms}':x=(w-tw)/2:y=h-th-40:fontsize=64:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=16"
    info "ffmpeg has drawtext — burning in an hh:mm:ss timecode"
  else
    info "ffmpeg has no drawtext filter — relying on testsrc's built-in second counter"
  fi

  if ffmpeg -y -v error \
      -f lavfi -i "testsrc=duration=90:size=1280x720:rate=30" \
      -f lavfi -i "sine=frequency=440:duration=90" \
      ${VF:+-vf "${VF}"} \
      -c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 4000k \
      -c:a aac -b:a 128k \
      -movflags +faststart \
      -shortest "${VID}" 2>/dev/null && [[ -s "${VID}" ]]; then
    bytes=$(wc -c < "${VID}" | tr -d ' ')
    ok "seek-test-clip.mp4 (90s, ${bytes} bytes ≈ $(( (bytes + 261119) / 261120 )) GridFS chunks)"
  else
    rm -f "${VID}"
    skip "MP4 — ffmpeg present but encode failed"
  fi
else
  skip "MP4 — needs ffmpeg (brew install ffmpeg)"
fi

# =============================================================================
# 5. Audio — 120 s MP3, stepping through three tones so a mid-file seek is
#    audibly different from the start of the file.
# =============================================================================

if have ffmpeg; then
  AUD="${OUT}/tone-sweep.mp3"
  if ffmpeg -y -v error \
      -f lavfi -i "sine=frequency=220:duration=40" \
      -f lavfi -i "sine=frequency=440:duration=40" \
      -f lavfi -i "sine=frequency=880:duration=40" \
      -filter_complex "[0:a][1:a][2:a]concat=n=3:v=0:a=1[out]" -map "[out]" \
      -c:a libmp3lame -b:a 128k "${AUD}" 2>/dev/null && [[ -s "${AUD}" ]]; then
    ok "tone-sweep.mp3 (120s, three tone segments — seek to 1:00 and the pitch changes)"
  else
    rm -f "${AUD}"
    skip "MP3 — ffmpeg present but encode failed (libmp3lame missing?)"
  fi
else
  skip "MP3 — needs ffmpeg"
fi

# =============================================================================
# 6. Images
# =============================================================================

if have ffmpeg; then
  if ffmpeg -y -v error -f lavfi -i "testsrc=duration=1:size=1600x900:rate=1" \
       -frames:v 1 "${OUT}/colour-bars.png" 2>/dev/null \
     && [[ -s "${OUT}/colour-bars.png" ]]; then
    ok "colour-bars.png"
  else
    rm -f "${OUT}/colour-bars.png"; skip "PNG — ffmpeg encode failed"
  fi

  if ffmpeg -y -v error -f lavfi -i "mandelbrot=size=1200x900:rate=1" \
       -frames:v 1 -q:v 3 "${OUT}/mandelbrot.jpg" 2>/dev/null \
     && [[ -s "${OUT}/mandelbrot.jpg" ]]; then
    ok "mandelbrot.jpg"
  else
    rm -f "${OUT}/mandelbrot.jpg"; skip "JPG — ffmpeg encode failed"
  fi
else
  skip "images — needs ffmpeg"
fi

# =============================================================================
# Scanned document -> PNG of rendered text, with NO text layer at all.
# This is the OCR demo asset: the marker phrase exists purely as pixels, so
# searching for it proves OCR ran rather than a text layer being read.
# =============================================================================
step "Scanned document image (for OCR)"

if command -v cupsfilter >/dev/null 2>&1 && command -v sips >/dev/null 2>&1; then
  _scan_src="$(mktemp -t gridfs-scan).txt"
  cat > "${_scan_src}" <<'SCAN'
INVOICE 2026-0447

Bellweather Cartographic Services
14 Harbour Road, Anstruther

Description: Hydrographic survey of the
Ferndale approach channel.

The distinctive marker phrase for this
scanned document is TRILOBITE SEMAPHORE
and it exists only as pixels.

Total due: 4,820.00 GBP
SCAN
  _scan_pdf="$(mktemp -t gridfs-scan).pdf"
  if cupsfilter "${_scan_src}" > "${_scan_pdf}" 2>/dev/null \
     && sips -s format png "${_scan_pdf}" --out "${OUT}/scanned-invoice.png" >/dev/null 2>&1 \
     && [[ -s "${OUT}/scanned-invoice.png" ]]; then
    ok "scanned-invoice.png (no text layer — searchable only after OCR)"
  else
    rm -f "${OUT}/scanned-invoice.png"
    skip "scanned image — cupsfilter/sips conversion failed"
  fi
  rm -f "${_scan_src}" "${_scan_pdf}"
else
  skip "scanned image — needs macOS cupsfilter + sips"
fi

# =============================================================================
echo
bold "Done — $(find "${OUT}" -type f ! -name '.*' | wc -l | tr -d ' ') files, $(du -sh "${OUT}" | cut -f1) total"
find "${OUT}" -type f ! -name '.*' -exec ls -lh {} \; | awk '{printf "    %-10s %s\n", $5, $NF}'
echo
info "Load them into a running app with:  ./samples/upload-samples.sh"
