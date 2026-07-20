package com.mongodb.demo.gridfs.web;

import com.mongodb.demo.gridfs.config.DemoProperties;
import com.mongodb.demo.gridfs.domain.FileCategory;
import com.mongodb.demo.gridfs.domain.StoredFile;
import com.mongodb.demo.gridfs.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Byte delivery for {@code GET /api/files/{id}/content}, including HTTP Range.
 *
 * <p>This endpoint is the point of the whole demo. A {@code <video>} element
 * does not download a file, it issues a burst of {@code Range} requests as the
 * user scrubs, and each one must be answered by seeking GridFS to the chunk
 * holding the requested offset rather than by reading the file from byte zero.
 * {@link FileStorageService#openStream(String, long)} does that seek; this class
 * does the HTTP half — parsing the header, bounding the copy, and being honest
 * in the response headers about what it served.
 *
 * <p><strong>Multi-range:</strong> a request such as
 * {@code bytes=0-99,200-299} is answered with the <em>first</em> range only,
 * as a normal single-range {@code 206}. A genuine multipart/byteranges response
 * buys nothing here — no browser media element ever asks for one — and the
 * spec explicitly permits a server to satisfy fewer ranges than requested.
 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    /** 64 KB copy buffer: large enough to keep syscalls down, small enough to stay off the heap's radar. */
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final FileStorageService storage;
    private final DemoProperties properties;

    public StreamController(FileStorageService storage, DemoProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @GetMapping("/api/files/{id}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @PathVariable String id,
            @RequestParam(value = "download", defaultValue = "false") boolean download,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        StoredFile file = storage.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No file with id " + id));

        long total = file.length();
        HttpHeaders headers = baseHeaders(file, download);

        if (rangeHeader == null || rangeHeader.isBlank()) {
            // Whole file. Accept-Ranges still goes out so the browser knows it
            // may seek on the next request instead of re-fetching from zero.
            headers.setContentLength(total);
            headers.set(SEEK_CHUNK_HEADER, String.valueOf(chunkIndex(0, file.chunkSize())));
            return ResponseEntity.ok().headers(headers).body(body(id, 0, total));
        }

        ByteRange range = parseRange(rangeHeader, total, file.category() == FileCategory.MEDIA);
        if (range == null) {
            // Unparseable or non-"bytes" unit: RFC 9110 says ignore the header
            // and serve the whole representation.
            headers.setContentLength(total);
            headers.set(SEEK_CHUNK_HEADER, String.valueOf(chunkIndex(0, file.chunkSize())));
            return ResponseEntity.ok().headers(headers).body(body(id, 0, total));
        }
        if (!range.satisfiable()) {
            HttpHeaders errorHeaders = new HttpHeaders();
            errorHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            errorHeaders.set(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .headers(errorHeaders)
                    .build();
        }

        long length = range.end() - range.start() + 1;
        headers.setContentLength(length);
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + range.start() + "-" + range.end() + "/" + total);
        headers.set(SEEK_CHUNK_HEADER, String.valueOf(chunkIndex(range.start(), file.chunkSize())));

        log.debug("Range {} on {} ({} bytes) -> {}-{}, GridFS chunk {}",
                rangeHeader, id, total, range.start(), range.end(), chunkIndex(range.start(), file.chunkSize()));

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(body(id, range.start(), length));
    }

    // ---------------------------------------------------------------- headers

    /**
     * Demo-only diagnostic. Reporting which GridFS chunk the seek landed on is
     * the whole argument being made on stage: dragging to the middle of a
     * 500&nbsp;MB video reports chunk 812, not chunk 0, which is the visible
     * proof that GridFS jumped straight there. Nothing in the app depends on
     * this header; strip it for production.
     */
    private static final String SEEK_CHUNK_HEADER = "X-GridFS-Seek-Chunk";

    private static long chunkIndex(long startByte, int chunkSize) {
        return chunkSize <= 0 ? 0 : startByte / chunkSize;
    }

    private HttpHeaders baseHeaders(StoredFile file, boolean download) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentType(mediaType(file.contentType()));

        ContentDisposition disposition = (download
                ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename(file.filename() == null ? "file" : file.filename(),
                        java.nio.charset.StandardCharsets.UTF_8)
                .build();
        headers.setContentDisposition(disposition);

        // Cacheable, but never transformed: a proxy that gzips or rewrites the
        // body would invalidate the byte offsets the player is seeking against.
        // GridFS content is immutable once written, so a long TTL is safe and
        // keeps repeated seeks off the database.
        headers.set(HttpHeaders.CACHE_CONTROL, "public, max-age=3600, no-transform");
        return headers;
    }

    private static MediaType mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (org.springframework.http.InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    // ---------------------------------------------------------------- copying

    /**
     * Streams exactly {@code length} bytes starting at {@code start} to the
     * response. A plain {@code transferTo} is wrong here: the GridFS stream
     * runs to the end of the file, so it would overrun the end of the range and
     * desynchronise the response from its own {@code Content-Length}.
     */
    private StreamingResponseBody body(String id, long start, long length) {
        return out -> {
            try (InputStream in = storage.openStream(id, start)) {
                copyBounded(in, out, length);
            } catch (IOException ex) {
                if (isClientAbort(ex)) {
                    // Browsers abandon range requests constantly while the user
                    // drags the seek bar. Logging a stack trace per scrub would
                    // drown every other line in the demo console.
                    log.debug("Client aborted range read of {} at offset {}: {}", id, start, ex.getMessage());
                    return;
                }
                throw ex;
            }
        };
    }

    private static void copyBounded(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long remaining = length;
        while (remaining > 0) {
            int wanted = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, wanted);
            if (read < 0) {
                break; // file shorter than advertised; nothing more to send
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    /**
     * Recognises a disconnected client without compiling against Tomcat's
     * {@code ClientAbortException}, so the container stays swappable. The
     * message check covers the raw {@code IOException}s the OS raises when the
     * peer has gone away.
     */
    private static boolean isClientAbort(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if ("ClientAbortException".equals(t.getClass().getSimpleName())) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("connection abort")
                        || lower.contains("an established connection was aborted")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- parsing

    /**
     * One resolved range, already clamped to the file.
     *
     * @param satisfiable false when the client asked for bytes that do not
     *                    exist, which is a 416 rather than an empty 206
     */
    record ByteRange(long start, long end, boolean satisfiable) {
        static ByteRange unsatisfiable() {
            return new ByteRange(0, 0, false);
        }
    }

    /**
     * Parses a {@code Range} header against a known file length.
     *
     * @param mediaCap when true, an open-ended range is capped at
     *                 {@code gridfs-demo.media-chunk-bytes} so a scrub returns a
     *                 small, fast, immediately playable slice instead of the
     *                 entire remainder of a large video
     * @return null when the header should be ignored (serve 200), otherwise a
     *         range that is either satisfiable or explicitly not
     */
    ByteRange parseRange(String header, long total, boolean mediaCap) {
        String spec = header.trim();
        if (!spec.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null; // unknown unit, e.g. "items=0-9"
        }
        spec = spec.substring(6).trim();

        // Multi-range: honour the first range only (documented on the class).
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma).trim();
        }

        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null; // malformed; ignore rather than fail the playback
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();

        try {
            if (startText.isEmpty()) {
                // Suffix form: bytes=-N means "the last N bytes".
                if (endText.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endText);
                if (suffix <= 0 || total == 0) {
                    return ByteRange.unsatisfiable();
                }
                long start = Math.max(0, total - suffix);
                return new ByteRange(start, total - 1, true);
            }

            long start = Long.parseLong(startText);
            if (start < 0 || start >= total) {
                return ByteRange.unsatisfiable();
            }

            long end;
            if (endText.isEmpty()) {
                long lastByte = total - 1;
                end = mediaCap
                        ? Math.min(lastByte, start + properties.mediaChunkBytes() - 1)
                        : lastByte;
            } else {
                end = Math.min(Long.parseLong(endText), total - 1);
                if (end < start) {
                    return ByteRange.unsatisfiable();
                }
            }
            return new ByteRange(start, end, true);
        } catch (NumberFormatException ex) {
            return null; // garbage offsets; fall back to the full response
        }
    }
}
