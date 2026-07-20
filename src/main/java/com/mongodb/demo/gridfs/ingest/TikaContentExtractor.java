package com.mongodb.demo.gridfs.ingest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import com.mongodb.demo.gridfs.config.DemoProperties;
import com.mongodb.demo.gridfs.domain.ExtractionState;

/**
 * Apache Tika implementation of {@link ContentExtractor}.
 *
 * <p>Three things drive the design here:
 *
 * <ol>
 *   <li><b>Tika's verdict beats the browser's.</b> A browser will happily send
 *       {@code application/octet-stream} for a PDF, or trust a {@code .pdf}
 *       extension on a renamed ZIP. We detect from magic bytes plus the
 *       filename hint and store that, so search facets and the streaming
 *       endpoint work off the truth rather than off a client guess.
 *   <li><b>Not everything is worth parsing.</b> A 400MB mp4 has no text layer;
 *       running the full parser over it burns wall-clock time on the upload
 *       path for nothing. Media, images and types no parser claims are
 *       reported as {@link ExtractionState#SKIPPED}.
 *   <li><b>The upload must survive a bad parse.</b> The file still has to land
 *       in GridFS and stay downloadable even when Tika chokes, so this class
 *       never propagates a failure — it reports one.
 * </ol>
 */
@Component
public class TikaContentExtractor implements ContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaContentExtractor.class);

    /** Horizontal whitespace, including the NBSP that PDF extraction loves to emit. */
    private static final Pattern HORIZONTAL_WS = Pattern.compile("[ \\t\\x0B\\f\\u00A0]+");
    /** A newline plus any spaces hugging it. */
    private static final Pattern PADDED_NEWLINE = Pattern.compile(" *\\n *");
    /** Runs of blank lines. */
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{2,}");

    /** Walking a media type up its supertype chain should terminate long before this. */
    private static final int MAX_SUPERTYPE_DEPTH = 16;

    private final Detector detector;
    private final Parser parser;
    private final MediaTypeRegistry mediaTypeRegistry;

    /**
     * Every media type some registered parser claims to handle. Computed once:
     * it walks every parser on the classpath, which is far too slow to redo per
     * upload.
     */
    private final Set<MediaType> parseableTypes;

    /**
     * Character write limit handed to the SAX handler, and the UTF-8 byte
     * budget the result is finally clipped to.
     */
    private final int maxExtractedTextBytes;

    public TikaContentExtractor(DemoProperties properties) {
        TikaConfig config = TikaConfig.getDefaultConfig();
        this.detector = config.getDetector();
        this.mediaTypeRegistry = config.getMediaTypeRegistry();
        this.maxExtractedTextBytes = properties.maxExtractedTextBytes();

        AutoDetectParser autoDetect = new AutoDetectParser(config);
        this.parser = autoDetect;

        Map<MediaType, Parser> byType = autoDetect.getParsers(new ParseContext());
        this.parseableTypes = Set.copyOf(byType.keySet());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Stream ownership:</b> this method consumes and closes {@code in}.
     * Tika needs to spool the stream to a temp file for container formats, so
     * there is no way to hand it back re-readable. The caller must therefore
     * pass a stream it does not need again — buffer the upload, or open a
     * second independent stream for the GridFS write.
     *
     * <p>Never throws. Any failure, including a parser blowing up on a corrupt
     * file, comes back as {@link ExtractionState#FAILED} with the message in
     * {@link ExtractionResult#error()} and empty text.
     */
    @Override
    public ExtractionResult extract(InputStream in, String filename, String declaredContentType) {
        String detected = null;
        try (TikaInputStream tis = TikaInputStream.get(in)) {

            // Feed the detector both signals: the filename extension and the
            // client's claim are hints, the magic bytes in the stream decide.
            Metadata metadata = new Metadata();
            if (filename != null && !filename.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            if (declaredContentType != null && !declaredContentType.isBlank()) {
                metadata.set(HttpHeaders.CONTENT_TYPE, declaredContentType);
            }

            // Detector contract: it resets the stream, so tis stays parseable.
            MediaType type = detector.detect(tis, metadata);
            detected = type.getBaseType().toString();

            // Pin the verdict so the parser does not redo detection work.
            metadata.set(HttpHeaders.CONTENT_TYPE, detected);

            if (isMedia(type)) {
                return mediaResult(tis, metadata, filename, detected);
            }
            if (!hasTextParser(type)) {
                return skipped(metadata, filename, detected, null);
            }
            return parseText(tis, metadata, filename, detected);

        } catch (OutOfMemoryError e) {
            // Deliberately not swallowed: the JVM is unrecoverable at this
            // point and pretending otherwise just hides the real failure.
            throw e;
        } catch (Throwable t) {
            // Everything else — TikaException, IOException, SAXException, and
            // the StackOverflowError a deeply nested container can trigger —
            // is a report, not a throw. The file still belongs in GridFS.
            log.warn("Tika extraction failed for '{}' (detected={}): {}",
                    filename, detected, t.toString());
            return new ExtractionResult(
                    ExtractionState.FAILED, "", null, null, null, null, detected, t.toString());
        }
    }

    /**
     * Full text extraction. The {@link BodyContentHandler} write limit is what
     * keeps a pathological document (deeply nested archive, XML bomb, a
     * spreadsheet that expands to gigabytes of cells) from eating the heap:
     * once the limit is hit the handler aborts the parse by throwing.
     *
     * <p>Note the residual risk — the limit bounds <em>output</em>, not CPU
     * time. A file that spins for minutes while emitting almost no text will
     * still hold this thread. The real fix is an out-of-process parse
     * (tika-server or a forked parser); that is deliberately out of scope for
     * a demo, but it is the first thing to add before this sees hostile input.
     */
    private ExtractionResult parseText(TikaInputStream tis, Metadata metadata,
                                       String filename, String detected) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(maxExtractedTextBytes);
        boolean truncated = false;

        try {
            parser.parse(tis, handler, metadata, new ParseContext());
        } catch (Exception e) {
            // Tika wraps the write-limit signal several layers deep, so match
            // on the cause chain rather than on the top-level exception type.
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                throw e;
            }
            // Hitting the limit is a success with a haircut: the handler still
            // holds everything read up to the cut.
            truncated = true;
        }

        ClippedText body = clipToByteBudget(normaliseWhitespace(handler.toString()));
        ExtractionState state = (truncated || body.clipped())
                ? ExtractionState.TRUNCATED
                : ExtractionState.EXTRACTED;

        return new ExtractionResult(
                state,
                body.text(),
                metadata.getInt(PagedText.N_PAGES),
                author(metadata),
                title(metadata, filename),
                durationMillis(metadata),
                detected,
                null);
    }

    /**
     * Audio and video: parse for metadata only, discarding character events
     * into a {@link DefaultHandler}. We want {@code durationMillis} for the
     * player UI, but building a multi-megabyte string out of an mp4's stray
     * text atoms would be pure waste.
     *
     * <p>This still costs a spool to a temp file for container formats, which
     * is the price of reading the duration at all.
     */
    private ExtractionResult mediaResult(TikaInputStream tis, Metadata metadata,
                                         String filename, String detected) {
        try {
            ContentHandler sink = new DefaultHandler();
            parser.parse(tis, sink, metadata, new ParseContext());
        } catch (Exception e) {
            // A media file we cannot read metadata from is still a valid
            // upload — keep SKIPPED rather than downgrading to FAILED.
            log.warn("Media metadata parse failed for '{}' ({}): {}",
                    filename, detected, e.toString());
        }
        return skipped(metadata, filename, detected, durationMillis(metadata));
    }

    private ExtractionResult skipped(Metadata metadata, String filename,
                                     String detected, Long durationMillis) {
        return new ExtractionResult(
                ExtractionState.SKIPPED,
                "",
                metadata.getInt(PagedText.N_PAGES),
                author(metadata),
                title(metadata, filename),
                durationMillis,
                detected,
                null);
    }

    private boolean isMedia(MediaType type) {
        String top = type.getType();
        return "audio".equals(top) || "video".equals(top);
    }

    /**
     * True when some parser on the classpath claims this type or one of its
     * supertypes. Images are excluded explicitly: an image parser exists and
     * will happily run, but it yields EXIF noise rather than searchable text,
     * so treating images as text-bearing would pollute the search index.
     */
    private boolean hasTextParser(MediaType type) {
        if ("image".equals(type.getType())) {
            return false;
        }
        MediaType current = mediaTypeRegistry.normalize(type);
        for (int depth = 0; current != null && depth < MAX_SUPERTYPE_DEPTH; depth++) {
            if (MediaType.OCTET_STREAM.equals(current)) {
                return false;
            }
            if (parseableTypes.contains(current)) {
                return true;
            }
            current = mediaTypeRegistry.getSupertype(current);
        }
        return false;
    }

    private String author(Metadata metadata) {
        String creator = metadata.get(TikaCoreProperties.CREATOR);
        if (creator != null && !creator.isBlank()) {
            return creator.trim();
        }
        // Legacy key still emitted by some Office and PDF documents.
        String legacy = metadata.get("Author");
        return (legacy == null || legacy.isBlank()) ? null : legacy.trim();
    }

    /**
     * Document title, falling back to the filename without its extension —
     * a blank title column in the UI is worse than a derived one, and most
     * real-world PDFs carry either no title or the authoring tool's default.
     */
    private String title(Metadata metadata, String filename) {
        String title = metadata.get(TikaCoreProperties.TITLE);
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String base = filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.isBlank() ? null : base.trim();
    }

    /**
     * Duration in milliseconds, normalised across two parser conventions that
     * disagree on units:
     *
     * <ul>
     *   <li>{@code xmpDM:duration} — written by Mp3Parser and MP4Parser in
     *       <b>seconds</b> (MP4Parser formats it with {@code Locale.ROOT}, so
     *       the decimal separator is always a dot).
     *   <li>{@code duration} — passed straight through from
     *       {@code javax.sound}'s AudioFileFormat properties by AudioParser,
     *       and documented there in <b>microseconds</b>.
     * </ul>
     *
     * Reading either as "just a number" would be wrong by a factor of a
     * thousand in one direction or the other.
     */
    private Long durationMillis(Metadata metadata) {
        Double seconds = parseDouble(metadata.get(XMPDM.DURATION));
        if (seconds != null && seconds >= 0) {
            return Math.round(seconds * 1000d);
        }
        Double micros = parseDouble(metadata.get("duration"));
        if (micros != null && micros >= 0) {
            return Math.round(micros / 1000d);
        }
        return null;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return (Double.isNaN(parsed) || Double.isInfinite(parsed)) ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Tika's XHTML-to-text conversion leaves ragged whitespace: one word per
     * line from PDF column layouts, runs of blank lines between table cells.
     * Left alone that bloats the stored metadata and makes search snippets
     * read like ransom notes. Collapse horizontal runs to a single space and
     * blank-line runs to a single newline, keeping paragraph structure.
     */
    private String normaliseWhitespace(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = HORIZONTAL_WS.matcher(text).replaceAll(" ");
        text = PADDED_NEWLINE.matcher(text).replaceAll("\n");
        text = BLANK_LINES.matcher(text).replaceAll("\n");
        return text.trim();
    }

    /**
     * The write limit counts characters, but the BSON document cap this is
     * really defending is measured in bytes — one emoji or CJK glyph is up to
     * four. Enforce the real budget on the encoded form.
     *
     * @param clipped true when bytes were dropped, which the caller turns into
     *                {@link ExtractionState#TRUNCATED}
     */
    private ClippedText clipToByteBudget(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxExtractedTextBytes) {
            return new ClippedText(text, false);
        }
        // Walk back off any UTF-8 continuation byte (10xxxxxx) so we never cut
        // a multi-byte character in half and leave a replacement char behind.
        int end = maxExtractedTextBytes;
        while (end > 0 && (utf8[end] & 0xC0) == 0x80) {
            end--;
        }
        return new ClippedText(new String(utf8, 0, end, StandardCharsets.UTF_8), true);
    }

    /** Extracted text plus whether the byte budget forced a cut. */
    private record ClippedText(String text, boolean clipped) {}
}
