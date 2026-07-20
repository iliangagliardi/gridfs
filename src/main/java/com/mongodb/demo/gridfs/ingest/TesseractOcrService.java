package com.mongodb.demo.gridfs.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mongodb.demo.gridfs.config.DemoProperties;

/**
 * Tesseract implementation of {@link OcrService}, invoking the {@code tesseract}
 * binary directly through {@link ProcessBuilder}.
 *
 * <p><b>Why not Tika's {@code TesseractOCRParser}?</b> It is on the classpath
 * (tika-parser-ocr-module) and it does expose a timeout knob
 * ({@code TesseractOCRConfig.setTimeoutSeconds}), so the usual argument for
 * shelling out ourselves does not actually apply. Three other things decided it:
 *
 * <ol>
 *   <li><b>Diagnostics.</b> Everything that goes wrong with OCR announces itself
 *       on tesseract's stderr — a missing language pack prints
 *       {@code "Error opening data file .../deu.traineddata"} and exits 1.
 *       Routed through Tika that becomes a {@code TikaException} with the
 *       original message logged and discarded, and the user sees an empty
 *       result with no explanation. Running the process ourselves lets that text
 *       land in {@link OcrResult#error()}, which is exactly what requirement
 *       "a missing language pack produces a useful message rather than silence"
 *       asks for.
 *   <li><b>Availability and version.</b> The interface promises
 *       {@link #isAvailable()} and {@link #engineVersion()} for a UI badge.
 *       Tika keeps its tesseract discovery internal to the parser and simply
 *       declines to support the media type when the binary is missing; there is
 *       no supported accessor to build a badge from. {@code tesseract --version}
 *       answers both questions in one call.
 *   <li><b>No benefit from the pipeline.</b> Tika's parser would spool our
 *       stream to its own temp file and shell out to the same binary. We gain
 *       the Tika metadata integration, which for a plain image-to-text call is
 *       nothing we use — {@link TikaContentExtractor} already owns metadata.
 * </ol>
 *
 * <p><b>Verified against tesseract 5.5.2</b> (Homebrew, langs {@code eng},
 * {@code osd}, {@code snum}): a rasterised page OCRs cleanly, a colour-bars test
 * image exits 0 with completely empty stdout (so "no text" is a success, not an
 * error), a bad {@code -l} exits 1 with the message above on stderr, and
 * leptonica identifies the input by magic bytes — the temp file's extension is
 * irrelevant.
 *
 * <p><b>PDFs.</b> {@link #isOcrCandidate(String)} accepts {@code application/pdf}
 * as the interface requires, but tesseract itself cannot read one: leptonica
 * reports {@code "Pdf reading is not supported"} and exits 1. Handing raw PDF
 * bytes to {@link #recognise} therefore returns an empty result carrying that
 * message. A caller that wants OCR over a scanned PDF has to rasterise the pages
 * first and pass images.
 *
 * <p><b>One sharp edge worth knowing.</b> When leptonica cannot decode the input
 * as an image, tesseract falls back to treating it as a <em>list of image
 * paths</em>, one per line. Observed here by accident: feeding it a plain text
 * file produced {@code "image file not found: Notes on Small Batch Coffee
 * Roasting"} — it had read the first line as a filename. A file whose bytes are
 * paths could therefore make the engine OCR unrelated files off local disk into
 * the search index. The guard is upstream and it holds: callers gate on
 * {@link #isOcrCandidate(String)} applied to Tika's magic-byte verdict, and a
 * text file never detects as {@code image/*}. Do not weaken that to trusting a
 * client-declared content type.
 *
 * <p><b>Thread safety.</b> Singleton, called from request threads. Every field
 * is final and set during construction; each {@link #recognise} call works only
 * on its own temp files and its own {@link Process}.
 */
@Component
public class TesseractOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);

    /**
     * Hard ceiling on a single OCR run. OCR is by far the slowest thing in this
     * application and it runs on a request thread, so a tesseract process that
     * wedges — a pathological multi-page TIFF, a hung filesystem under its temp
     * dir — would hold that thread until the container gave up on it. Thirty
     * seconds is comfortably above a normal scanned page (well under a second
     * for the samples here) while still bounding the damage.
     */
    private static final Duration OCR_TIMEOUT = Duration.ofSeconds(30);

    /** The startup probe only reads a version banner; it must never delay boot. */
    private static final Duration VERSION_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Recognition language. Only {@code eng}, {@code osd} and {@code snum} are
     * installed in the target environment, and {@code eng} is tesseract's own
     * default — passing it explicitly makes the run reproducible rather than
     * dependent on how the binary was packaged.
     */
    private static final String OCR_LANGUAGE = "eng";

    /**
     * Where to look for the binary. PATH first, then the two Homebrew prefixes
     * and the system locations: a Spring Boot process launched from an IDE
     * frequently inherits a minimal PATH that contains neither
     * {@code /opt/homebrew/bin} nor {@code /usr/local/bin}, and silently
     * reporting "OCR unavailable" on a machine where tesseract is installed is
     * the single most confusing failure this class could have.
     */
    private static final List<String> CANDIDATE_EXECUTABLES = List.of(
            "tesseract",
            "/opt/homebrew/bin/tesseract",
            "/usr/local/bin/tesseract",
            "/usr/bin/tesseract",
            "/opt/local/bin/tesseract");

    /**
     * Raster image types worth feeding to the engine. {@code image/svg+xml} is
     * deliberately absent: it is vector markup, there are no pixels to read, and
     * its text is already extractable as XML.
     */
    private static final Set<String> OCR_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/tiff",
            "image/bmp",
            "image/gif",
            "image/webp",
            "application/pdf");

    /** First line of {@code tesseract --version}, e.g. {@code "tesseract 5.5.2"}. */
    private static final Pattern VERSION_BANNER = Pattern.compile("^\\s*(tesseract\\s+\\S+).*$");

    /** Horizontal whitespace, including the NBSP the engine emits around glyph gaps. */
    private static final Pattern HORIZONTAL_WS = Pattern.compile("[ \\t\\x0B\\u00A0]+");

    /** A line carrying no letter and no digit — pure OCR noise such as {@code . , ~ '}. */
    private static final Pattern NOISE_LINE = Pattern.compile("^[^\\p{L}\\p{N}]*$");

    /** Below this many letters/digits the result is not worth trusting. */
    private static final int MIN_USEFUL_CHARACTERS = 10;

    /** Fraction of visible characters that must be letters or digits. */
    private static final double MIN_ALPHANUMERIC_RATIO = 0.5d;

    /** Enough stderr to diagnose a failure, not enough to bloat a stored error. */
    private static final int MAX_ERROR_CHARS = 2_000;

    /** UTF-8 byte budget for recognised text, shared with the Tika extraction path. */
    private final int maxExtractedTextBytes;

    /** Resolved binary, or null when the probe found none. Cached for the process lifetime. */
    private final String executable;

    /** Version banner, or null when unavailable. */
    private final String version;

    public TesseractOcrService(DemoProperties properties) {
        this.maxExtractedTextBytes = properties.maxExtractedTextBytes();

        // Probe once, at construction. Re-probing per request would add a
        // process spawn to every upload, and the binary does not appear or
        // vanish underneath a running JVM in any scenario worth handling.
        String found = null;
        String banner = null;
        for (String candidate : CANDIDATE_EXECUTABLES) {
            banner = probeVersion(candidate);
            if (banner != null) {
                found = candidate;
                break;
            }
        }
        this.executable = found;
        this.version = banner;

        if (found == null) {
            log.info("Tesseract not found on PATH or in {} — OCR will be reported unavailable.",
                    CANDIDATE_EXECUTABLES.subList(1, CANDIDATE_EXECUTABLES.size()));
        } else {
            log.info("OCR available: {} ({})", banner, found);
        }
    }

    /**
     * Runs {@code <candidate> --version} and returns the parsed banner, or null
     * when the candidate is not a working tesseract. Never throws: a candidate
     * that does not exist, is not executable, is a different program entirely,
     * or hangs is simply not the one we want.
     */
    private String probeVersion(String candidate) {
        try {
            ProcessOutcome outcome = run(List.of(candidate, "--version"), VERSION_PROBE_TIMEOUT, 8_192);
            if (outcome.timedOut() || outcome.exitCode() != 0) {
                return null;
            }
            // The banner goes to stdout on 5.x; older builds used stderr, so
            // check both rather than depending on which one this build picked.
            String banner = parseVersionBanner(outcome.stdout());
            return banner != null ? banner : parseVersionBanner(outcome.stderr());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // IOException for "no such file", SecurityException under a manager.
            return null;
        }
    }

    private String parseVersionBanner(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String firstLine = output.replace("\r\n", "\n").split("\n", 2)[0];
        Matcher matcher = VERSION_BANNER.matcher(firstLine);
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    @Override
    public boolean isAvailable() {
        return executable != null;
    }

    @Override
    public String engineVersion() {
        return version;
    }

    @Override
    public boolean isOcrCandidate(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        // Strip any parameters ("image/tiff; charset=binary") and normalise case
        // before matching, since a declared header rarely arrives bare.
        String base = contentType;
        int semicolon = base.indexOf(';');
        if (semicolon >= 0) {
            base = base.substring(0, semicolon);
        }
        return OCR_CONTENT_TYPES.contains(base.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes {@code in} to a temp file because tesseract takes a path, not a
     * pipe, then runs the engine with both of its streams redirected to files —
     * that sidesteps the classic pipe-deadlock where a process blocks writing to
     * a full stderr buffer while we are blocked reading stdout. The temp files go
     * in a finally block regardless of outcome.
     *
     * <p><b>Stream ownership:</b> this method consumes and closes {@code in}.
     */
    @Override
    public OcrResult recognise(InputStream in, String filename) {
        long startNanos = System.nanoTime();

        if (executable == null) {
            return OcrResult.empty("Tesseract is not installed, so OCR was skipped.");
        }
        if (in == null) {
            return OcrResult.empty("No image bytes to recognise.");
        }

        Path image = null;
        try (InputStream source = in) {
            image = Files.createTempFile("gridfs-ocr-", ".img");
            Files.copy(source, image, StandardCopyOption.REPLACE_EXISTING);

            // "stdout" is tesseract's magic output base meaning "write the text
            // to standard output" rather than to <base>.txt, which saves us
            // owning a third temp file. Read a little past the budget so the
            // byte-safe clip below has a whole character to cut on.
            ProcessOutcome outcome = run(
                    List.of(executable, image.toString(), "stdout", "-l", OCR_LANGUAGE),
                    OCR_TIMEOUT,
                    maxExtractedTextBytes + 4);

            if (outcome.timedOut()) {
                log.warn("OCR timed out after {}s for '{}'", OCR_TIMEOUT.toSeconds(), filename);
                return OcrResult.empty("OCR timed out after " + OCR_TIMEOUT.toSeconds() + "s.");
            }
            if (outcome.exitCode() != 0) {
                // stderr is the whole point of capturing it separately: this is
                // where "Failed loading language 'deu'" and "Pdf reading is not
                // supported" actually live.
                String detail = firstNonBlank(outcome.stderr(), "no diagnostic output");
                log.warn("OCR failed for '{}' (exit {}): {}", filename, outcome.exitCode(), detail);
                return OcrResult.empty("Tesseract exited " + outcome.exitCode() + ": " + detail);
            }

            String text = clipToByteBudget(normalise(outcome.stdout()));
            boolean confident = looksLikeRealText(text);
            long tookMillis = elapsedMillis(startNanos);

            if (!confident && !text.isEmpty()) {
                log.debug("OCR output for '{}' judged low confidence ({} chars)", filename, text.length());
            }
            return new OcrResult(text, confident, tookMillis, null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OcrResult.empty("OCR was interrupted.");
        } catch (OutOfMemoryError e) {
            // Same reasoning as TikaContentExtractor: the JVM is unrecoverable
            // here and swallowing it would only hide the real failure.
            throw e;
        } catch (Throwable t) {
            // The contract is absolute — a failed OCR run must never break an
            // upload or a button press.
            log.warn("OCR failed for '{}': {}", filename, t.toString());
            return OcrResult.empty(t.toString());
        } finally {
            deleteQuietly(image);
        }
    }

    /**
     * Runs a command with both streams redirected to temp files, enforcing a
     * hard timeout and forcibly destroying the process when it expires. Returns
     * whatever the streams held; the caller decides what a non-zero exit means.
     *
     * @param maxStdoutBytes cap on how much stdout is read back into the heap
     */
    private ProcessOutcome run(List<String> command, Duration timeout, int maxStdoutBytes)
            throws IOException, InterruptedException {

        Path out = Files.createTempFile("gridfs-ocr-out-", ".txt");
        Path err = Files.createTempFile("gridfs-ocr-err-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();

            // tesseract reads its input from the path we gave it, never from
            // stdin. Close the pipe immediately so the child sees EOF and we do
            // not hold a descriptor open for the life of the run.
            process.getOutputStream().close();

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                // Give the OS a moment to reap it so the redirected files are
                // closed before we read them; the result is discarded either way.
                process.waitFor(2, TimeUnit.SECONDS);
                return new ProcessOutcome(-1, "", "", true);
            }

            return new ProcessOutcome(
                    process.exitValue(),
                    readCapped(out, maxStdoutBytes),
                    truncate(readCapped(err, MAX_ERROR_CHARS * 4), MAX_ERROR_CHARS),
                    false);
        } finally {
            // Covers the interrupted and exceptional paths: never leave a
            // tesseract process orphaned behind a request thread.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /**
     * Reads at most {@code maxBytes} of a file as UTF-8, dropping a trailing
     * partial character rather than decoding it to a replacement glyph.
     */
    private String readCapped(Path file, int maxBytes) throws IOException {
        try (InputStream fin = Files.newInputStream(file)) {
            byte[] bytes = fin.readNBytes(Math.max(maxBytes, 0));
            return new String(bytes, 0, completeUtf8Length(bytes), StandardCharsets.UTF_8);
        }
    }

    /**
     * Length of the longest prefix of {@code bytes} ending on a <em>complete</em>
     * UTF-8 sequence. Note the "complete": naively walking back off every
     * continuation byte would amputate a perfectly good trailing em dash, which
     * OCR output is full of. This only shortens the array when the read stopped
     * at the cap part-way through a character.
     */
    private static int completeUtf8Length(byte[] bytes) {
        int end = bytes.length;

        // Find the lead byte of the final sequence. UTF-8 sequences are at most
        // four bytes, so at most three continuation bytes precede it.
        int lead = end - 1;
        while (lead >= 0 && (bytes[lead] & 0xC0) == 0x80 && (end - lead) <= 3) {
            lead--;
        }
        if (lead < 0) {
            return end;
        }

        int first = bytes[lead] & 0xFF;
        int expected;
        if (first < 0x80) {
            expected = 1;
        } else if (first < 0xC0) {
            return end; // Stray continuation byte: malformed input, not a cut.
        } else if (first < 0xE0) {
            expected = 2;
        } else if (first < 0xF0) {
            expected = 3;
        } else {
            expected = 4;
        }
        return (end - lead) >= expected ? end : lead;
    }

    /**
     * OCR output is ragged: the engine emits one word per line on a columned
     * scan, stray blank lines between blocks, and a form feed between pages of a
     * multi-page input. Left alone that bloats the stored text and makes search
     * snippets unreadable. Collapse horizontal runs to a single space, drop
     * lines carrying neither a letter nor a digit, and keep paragraph breaks.
     */
    private String normalise(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\f", "\n");
        text = HORIZONTAL_WS.matcher(text).replaceAll(" ");

        StringBuilder cleaned = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || NOISE_LINE.matcher(trimmed).matches()) {
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(trimmed);
        }
        return cleaned.toString().trim();
    }

    /**
     * Confidence heuristic — and it is only a heuristic, not a measurement.
     * Tesseract does report per-word confidences, but only through its TSV or
     * hOCR output modes; reading plain text back means judging the string
     * itself.
     *
     * <p>Two cheap signals, both aimed at the same failure: a photo, a colour
     * chart or a texture gets OCR'd and a handful of imagined glyphs end up in
     * the search index as a document's entire searchable content.
     *
     * <ol>
     *   <li>Fewer than {@value #MIN_USEFUL_CHARACTERS} letters or digits — too
     *       little to be a real text layer, and too little to search on.
     *   <li>Less than half the visible characters are alphanumeric — the shape
     *       of recognised noise, which comes back heavy in punctuation.
     * </ol>
     *
     * <p>Both directions of error exist: a legitimate scan that is mostly
     * numbers and symbols (a receipt, a formula) can be marked unconfident, and
     * a page of confidently-wrong words will pass. {@code confident} is a hint
     * for the caller and the UI, not a correctness guarantee. Worth noting that
     * on tesseract 5 the LSTM engine is conservative enough that the samples
     * here (colour bars, a Mandelbrot render, random noise, random scribbles)
     * all returned completely empty output — this check mostly earns its keep on
     * marginal low-quality scans rather than on outright garbage.
     */
    private boolean looksLikeRealText(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int alphanumeric = 0;
        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            visible++;
            if (Character.isLetterOrDigit(c)) {
                alphanumeric++;
            }
        }
        if (alphanumeric < MIN_USEFUL_CHARACTERS) {
            return false;
        }
        return alphanumeric >= visible * MIN_ALPHANUMERIC_RATIO;
    }

    /**
     * Enforces the same UTF-8 byte budget the Tika path uses, since both end up
     * in the same BSON document. Cuts on a character boundary so a clipped page
     * never ends in a replacement glyph.
     */
    private String clipToByteBudget(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxExtractedTextBytes) {
            return text;
        }
        // Walk back off any UTF-8 continuation byte (10xxxxxx) so we never cut a
        // multi-byte character in half.
        int end = maxExtractedTextBytes;
        while (end > 0 && (utf8[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(utf8, 0, end, StandardCharsets.UTF_8);
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "…";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // A leaked temp file is a nuisance, not a reason to fail an upload.
            log.debug("Could not delete OCR temp file {}: {}", path, e.toString());
        }
    }

    /**
     * @param exitCode process exit status, meaningless when {@code timedOut}
     * @param stdout   captured standard output, capped
     * @param stderr   captured standard error, capped — this is where tesseract
     *                 explains itself
     * @param timedOut true when the hard timeout fired and the process was killed
     */
    private record ProcessOutcome(int exitCode, String stdout, String stderr, boolean timedOut) {}
}
