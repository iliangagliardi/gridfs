package com.mongodb.demo.gridfs.web;

import com.mongodb.demo.gridfs.config.DemoProperties;
import com.mongodb.demo.gridfs.domain.SearchMode;
import com.mongodb.demo.gridfs.ingest.OcrService;
import com.mongodb.demo.gridfs.search.FileSearchService;
import com.mongodb.demo.gridfs.search.SearchIndexBootstrap;
import com.mongodb.demo.gridfs.storage.FileStorageService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Dashboard header data and the one operator action the demo needs.
 *
 * <p>Everything here is best-effort: the deployment badge must never be the
 * reason the page fails to load, so each probe degrades to a placeholder rather
 * than propagating an exception.
 */
@RestController
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final FileStorageService storage;
    private final FileSearchService search;
    private final SearchIndexBootstrap searchIndex;
    private final DemoProperties properties;
    private final OcrService ocr;
    private final MongoTemplate mongoTemplate;
    private final String bucket;
    private final String mongoUri;

    public AdminController(FileStorageService storage,
                           FileSearchService search,
                           SearchIndexBootstrap searchIndex,
                           DemoProperties properties,
                           OcrService ocr,
                           MongoTemplate mongoTemplate,
                           @Value("${spring.data.mongodb.gridfs.bucket:fs}") String bucket,
                           @Value("${spring.data.mongodb.uri:}") String mongoUri) {
        this.storage = storage;
        this.search = search;
        this.searchIndex = searchIndex;
        this.properties = properties;
        this.ocr = ocr;
        this.mongoTemplate = mongoTemplate;
        this.bucket = bucket;
        this.mongoUri = mongoUri;
    }

    @GetMapping("/api/stats")
    public FileStorageService.StorageStats stats() {
        return storage.stats();
    }

    @GetMapping("/api/admin/info")
    public AdminInfo info() {
        SearchMode mode = safeMode();
        Document buildInfo = safeBuildInfo();
        return new AdminInfo(
                mode == null ? null : mode.name(),
                properties.searchIndexName(),
                // Measured, not inferred. Resolving to ATLAS_SEARCH only means
                // the deployment *can* serve $search; the index itself is built
                // asynchronously and may still be mid-build, or may have failed
                // to create at all. Reporting mode here would tell the presenter
                // the demo is ready when searches would still return nothing.
                safeIndexReady(),
                mongoVersion(buildInfo),
                isAtlas(buildInfo, mongoUri),
                bucket,
                databaseName(),
                safeOcrAvailable(),
                safeOcrEngine()
        );
    }

    /**
     * Creates the Atlas Search index on demand. The demo runs this from a button
     * so a cluster that has just been pointed at a fresh database can be made
     * searchable without leaving the page.
     */
    @PostMapping("/api/admin/search-index")
    public IndexResult createSearchIndex() {
        boolean created = search.ensureSearchIndex();
        SearchMode mode = safeMode();
        return new IndexResult(created, mode == null ? null : mode.name());
    }

    // ------------------------------------------------------------ diagnostics

    private SearchMode safeMode() {
        try {
            return search.activeMode();
        } catch (RuntimeException ex) {
            log.debug("Could not resolve active search mode", ex);
            return null;
        }
    }

    private boolean safeIndexReady() {
        try {
            return searchIndex.isIndexReady();
        } catch (RuntimeException ex) {
            log.debug("Could not resolve search index readiness", ex);
            return false;
        }
    }

    /**
     * Probing for tesseract may shell out, so it gets the same best-effort
     * treatment as every other probe here: an unavailable engine and a failed
     * probe are indistinguishable to the UI, and both correctly render as
     * "OCR off".
     */
    private boolean safeOcrAvailable() {
        try {
            return ocr.isAvailable();
        } catch (RuntimeException ex) {
            log.debug("Could not resolve OCR availability", ex);
            return false;
        }
    }

    private String safeOcrEngine() {
        try {
            return ocr.engineVersion();
        } catch (RuntimeException ex) {
            log.debug("Could not resolve OCR engine version", ex);
            return null;
        }
    }

    private Document safeBuildInfo() {
        try {
            return mongoTemplate.executeCommand(new Document("buildInfo", 1));
        } catch (RuntimeException ex) {
            log.debug("buildInfo unavailable", ex);
            return null;
        }
    }

    private String databaseName() {
        try {
            // Reached through a collection handle rather than the template's
            // database accessor: getCollection() is a local operation, so this
            // costs nothing and stays on the narrowest API surface.
            return mongoTemplate.getCollection(bucket + ".files").getNamespace().getDatabaseName();
        } catch (RuntimeException ex) {
            log.debug("Could not resolve database name", ex);
            return null;
        }
    }

    private static String mongoVersion(Document buildInfo) {
        if (buildInfo == null) {
            return "unknown";
        }
        Object version = buildInfo.get("version");
        return version == null ? "unknown" : version.toString();
    }

    /**
     * Best-effort Atlas detection. There is no single authoritative flag, so we
     * take the two signals that are cheap and rarely wrong: an {@code mongodb.net}
     * host in the connection string, and the enterprise module marker that Atlas
     * clusters report in {@code buildInfo}. A false negative here only softens a
     * UI badge.
     */
    private static boolean isAtlas(Document buildInfo, String uri) {
        if (uri != null && uri.toLowerCase(Locale.ROOT).contains("mongodb.net")) {
            return true;
        }
        if (buildInfo != null) {
            Object modules = buildInfo.get("modules");
            if (modules instanceof List<?> list) {
                for (Object module : list) {
                    if (module != null && "enterprise".equalsIgnoreCase(module.toString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @param searchMode      engine the search service resolved to
     * @param searchIndexName configured Atlas Search index name
     * @param indexReady      whether the search index has actually reached a
     *                        queryable state, as reported by mongot's own
     *                        readiness poll — not merely whether the deployment
     *                        supports {@code $search}
     * @param atlas           best-effort guess, see {@link #isAtlas}
     * @param ocrAvailable    whether the tesseract binary was found, so the UI
     *                        knows whether to offer the OCR button at all; false
     *                        also covers "the probe itself failed"
     * @param ocrEngine       human-readable engine version for the badge, null
     *                        whenever {@code ocrAvailable} is false and possibly
     *                        null even when it is true — the engine is usable
     *                        without necessarily reporting a version
     */
    public record AdminInfo(
            String searchMode,
            String searchIndexName,
            boolean indexReady,
            String mongoVersion,
            boolean atlas,
            String bucket,
            String database,
            boolean ocrAvailable,
            String ocrEngine
    ) {}

    public record IndexResult(boolean created, String mode) {}
}
