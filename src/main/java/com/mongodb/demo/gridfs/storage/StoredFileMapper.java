package com.mongodb.demo.gridfs.storage;

import com.mongodb.demo.gridfs.domain.ExtractionMethod;
import com.mongodb.demo.gridfs.domain.ExtractionState;
import com.mongodb.demo.gridfs.domain.FileCategory;
import com.mongodb.demo.gridfs.domain.StoredFile;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Turns a raw {@code fs.files} document into the API-facing {@link StoredFile}.
 *
 * <p>Deliberately hand-rolled rather than going through the {@code MongoConverter}:
 * the documents we read are GridFS's own shape, not something we mapped in, and
 * being explicit here keeps the exact BSON layout documented in
 * {@link StoredFile}'s javadoc visible in one place.
 *
 * <p>Every read is defensive about types and nulls. A files document written by
 * some other tool (mongofiles, a driver in another language) will not have our
 * {@code metadata} sub-document at all, and the UI should still be able to list it.
 */
final class StoredFileMapper {

    private StoredFileMapper() {
    }

    /**
     * Note that {@code metadata.extractedText} is never read here: it is
     * projected away by every query in {@link GridFsFileStorageService} and is
     * not a field of {@link StoredFile} anyway.
     */
    static StoredFile fromDocument(Document doc) {
        Document md = doc.get("metadata", Document.class);
        if (md == null) {
            md = new Document();
        }

        String contentType = md.getString("contentType");
        int textLength = (int) longValue(md.get("textLength"));

        return new StoredFile(
                idOf(doc),
                doc.getString("filename"),
                longValue(doc.get("length")),
                intValue(doc.get("chunkSize")),
                instantOf(doc.get("uploadDate")),
                contentType,
                categoryOf(md.getString("category"), contentType),
                tagsOf(md.get("tags")),
                extractionStateOf(md.getString("extractionState")),
                textLength,
                boxedInt(md.get("pageCount")),
                md.getString("author"),
                md.getString("title"),
                boxedLong(md.get("durationMillis")),
                md.getString("uploadedBy"),
                md.getString("checksumSha256"),
                extractionMethodOf(md.getString("extractionMethod"), textLength),
                booleanValue(md.get("ocrApplied")),
                null,   // snippet: search responses only
                null    // score: search responses only
        );
    }

    private static String idOf(Document doc) {
        Object id = doc.get("_id");
        return id instanceof ObjectId oid ? oid.toHexString() : String.valueOf(id);
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        return null;
    }

    /**
     * Falls back to deriving the category from the content type when the stored
     * value is missing or is an enum constant we no longer recognise, so an
     * older document never breaks the listing.
     */
    private static FileCategory categoryOf(String stored, String contentType) {
        if (stored != null) {
            try {
                return FileCategory.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through to content-type derivation
            }
        }
        return FileCategory.fromContentType(contentType);
    }

    private static ExtractionState extractionStateOf(String stored) {
        if (stored == null) return ExtractionState.SKIPPED;
        try {
            return ExtractionState.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return ExtractionState.SKIPPED;
        }
    }

    /**
     * Derives the method for documents written before {@code extractionMethod}
     * existed. Every one of those predates OCR entirely, so any text they carry
     * came from Tika and no text at all means NONE — which is exactly what
     * {@code textLength} tells us. Deriving rather than back-filling keeps the
     * upgrade a pure read-side concern: no migration script, no downtime, and a
     * document that is never edited again simply stays as it was written.
     *
     * <p>An unrecognised constant (a future method name read by an older build)
     * falls back to the same derivation rather than throwing, matching how
     * {@link #categoryOf} and {@link #extractionStateOf} behave.
     */
    private static ExtractionMethod extractionMethodOf(String stored, int textLength) {
        if (stored != null) {
            try {
                return ExtractionMethod.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through to derivation
            }
        }
        return textLength > 0 ? ExtractionMethod.TIKA : ExtractionMethod.NONE;
    }

    /**
     * Absent means false: a file stored before the OCR feature existed has, by
     * definition, never been through Tesseract, so the UI should still offer the
     * button for it.
     */
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean b && b;
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsOf(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /** BSON happily stores a small number as int32; read both widths. */
    private static long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Integer boxedInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Long boxedLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
