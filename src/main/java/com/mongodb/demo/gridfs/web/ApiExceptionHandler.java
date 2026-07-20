package com.mongodb.demo.gridfs.web;

import com.mongodb.demo.gridfs.ingest.NotOcrableException;
import com.mongodb.demo.gridfs.ingest.OcrUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single place where every API failure becomes the frozen error shape
 * {@code { error, detail, status }}.
 *
 * <p>Stack traces stay in the log and never reach the body: the UI shows
 * {@code detail} verbatim in a toast, and a Java trace there is both useless to
 * the viewer and a gift to anyone probing the demo.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * @param error  short, stable label — the HTTP reason phrase
     * @param detail human-readable explanation, safe to show a user
     * @param status the HTTP status, repeated in the body so a client that only
     *               reads JSON does not have to reach for the response object
     */
    public record ApiError(String error, String detail, int status) {}

    /** Thrown deliberately by the controllers for 404s, empty uploads and the like. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        String detail = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return build(ex.getStatusCode(), detail, ex);
    }

    /**
     * Upload larger than {@code spring.servlet.multipart.max-file-size}. Worth a
     * dedicated handler because the default rendering is a 500 that tells the
     * user nothing, and hitting the cap is the single most likely way to break
     * this demo live.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "That file is larger than this demo accepts. The limit is set by "
                        + "spring.servlet.multipart.max-file-size (512MB by default).",
                ex);
    }

    /** Malformed multipart bodies, bad JSON, missing or untypeable parameters. */
    @ExceptionHandler({
            MultipartException.class,
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, shortMessage(ex), ex);
    }

    /**
     * The file exists but its type cannot be OCR'd — a video, a spreadsheet, a
     * PDF that already carries a text layer. That is a conflict with the state of
     * the resource, not a malformed request, so it is a 409 and not a 400: the
     * same request would succeed against a different id.
     *
     * <p>Scoped to {@link NotOcrableException} rather than the bare JDK
     * {@code UnsupportedOperationException}, for the same reason as the 503
     * mapping below: an unmodifiable-collection mistake somewhere unrelated
     * should surface as a 500, not as "this file type cannot be OCR'd".
     */
    @ExceptionHandler(NotOcrableException.class)
    public ResponseEntity<ApiError> handleNotOcrable(NotOcrableException ex) {
        String detail = shortMessage(ex);
        return build(HttpStatus.CONFLICT,
                detail != null ? detail : "This file type cannot be OCR'd.", ex);
    }

    /**
     * The OCR engine is missing or unusable. 503, because nothing about the
     * request is wrong and retrying after the operator installs tesseract will
     * work.
     *
     * <p>Note on ordering: this does <em>not</em> collide with the
     * {@link IllegalArgumentException} → 400 mapping above.
     * {@code IllegalStateException} and {@code IllegalArgumentException} are
     * siblings under {@code RuntimeException}, so neither is assignable to the
     * other and Spring's most-specific-match resolution never has to choose
     * between them. It does take precedence over the {@code Exception}
     * catch-all, which is the whole point.
     *
     * <p>Deliberately scoped to {@link OcrUnavailableException} rather than the
     * bare JDK {@code IllegalStateException}. Mapping the generic type would be
     * global: any unrelated illegal-state bug anywhere in the app would report
     * "OCR engine unavailable, 503" instead of surfacing as the 500 it actually
     * is, which hides real faults behind a plausible-looking message.
     */
    @ExceptionHandler(OcrUnavailableException.class)
    public ResponseEntity<ApiError> handleOcrUnavailable(OcrUnavailableException ex) {
        String detail = shortMessage(ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                detail != null ? detail
                        : "The OCR engine is not installed on this server. Install tesseract and retry.",
                ex);
    }

    /**
     * Catch-all. Spring's own exceptions carry their intended status via
     * {@link ErrorResponse} (a missing static resource is a 404, not a 500), so
     * honour that before falling back.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAnythingElse(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return build(errorResponse.getStatusCode(), shortMessage(ex), ex);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected server error. See the application log for details.", ex);
    }

    // ----------------------------------------------------------------- helpers

    private ResponseEntity<ApiError> build(HttpStatusCode statusCode, String detail, Exception ex) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String label = status != null ? status.getReasonPhrase() : "Error";
        String safeDetail = (detail == null || detail.isBlank()) ? label : detail;

        if (statusCode.is5xxServerError()) {
            log.error("{} -> {}", ex.getClass().getSimpleName(), safeDetail, ex);
        } else {
            log.debug("{} -> {} {}", ex.getClass().getSimpleName(), statusCode.value(), safeDetail);
        }
        return ResponseEntity.status(statusCode)
                .body(new ApiError(label, safeDetail, statusCode.value()));
    }

    /** First line only: multi-line driver messages make an unreadable toast. */
    private static String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        int newline = message.indexOf('\n');
        String firstLine = newline > 0 ? message.substring(0, newline) : message;
        return firstLine.length() > 300 ? firstLine.substring(0, 300) + "…" : firstLine;
    }
}
