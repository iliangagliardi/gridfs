package com.mongodb.demo.gridfs.search;

import com.mongodb.client.MongoCollection;
import com.mongodb.demo.gridfs.config.DemoProperties;
import com.mongodb.demo.gridfs.domain.SearchMode;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides, once per boot, whether this deployment can serve {@code $search} and
 * makes sure the index exists if it can.
 *
 * <p>Why a separate bean from the search service: index management is a
 * lifecycle concern with its own state machine (probe &rarr; create &rarr; wait
 * for build), and it needs to survive being called again later from
 * {@code POST /api/admin/search-index}. Keeping it out of the query path means
 * {@link AtlasFileSearchService} stays a pure "build pipeline, run it, map it"
 * class.
 *
 * <p>The probe is {@code listSearchIndexes()}. There is no server field that
 * reliably says "mongot is attached" across Atlas, the mongodb-atlas-local
 * image and Community 8.2+, but every deployment that can run {@code $search}
 * can also answer {@code $listSearchIndexes}, and every deployment that cannot
 * errors on it. Asking the question we actually care about beats sniffing
 * version strings or hostnames.
 *
 * <p>Index builds are asynchronous. We never block {@code ApplicationReadyEvent}
 * on them — a demo that takes a minute to show its login page is a bad demo —
 * so the readiness poll runs on a daemon thread and {@link #isIndexReady()}
 * reports progress to {@code /api/admin/info}.
 */
@Component
public class SearchIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    /** Where the index definition lives, so ops can diff it against Atlas. */
    private static final String DEFINITION_RESOURCE = "atlas/gridfs_content.index.json";

    /** Give a cold mongot a fair chance without hanging a demo forever. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final MongoTemplate mongoTemplate;
    private final DemoProperties properties;
    private final String filesCollection;

    /**
     * Resolved once and then read from every request thread, hence volatile.
     * Defaults to the safe engine so a request arriving before the probe
     * completes degrades rather than throws.
     */
    private volatile SearchMode resolvedMode = SearchMode.REGEX_FALLBACK;
    private volatile boolean indexReady;

    /** Guards against a second poller when the admin endpoint re-runs the bootstrap. */
    private final AtomicBoolean pollerRunning = new AtomicBoolean(false);

    /** How long to keep re-probing for a search node that is still starting up. */
    private static final Duration BOOTSTRAP_RETRY_WINDOW = Duration.ofMinutes(3);
    private static final Duration BOOTSTRAP_RETRY_INTERVAL = Duration.ofSeconds(5);

    /** Guards against stacking retry threads. */
    private final AtomicBoolean retryRunning = new AtomicBoolean(false);

    public SearchIndexBootstrap(MongoTemplate mongoTemplate,
                                DemoProperties properties,
                                @Value("${spring.data.mongodb.gridfs.bucket:fs}") String bucket) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.filesCollection = bucket + ".files";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        attemptBootstrap();

        // A single probe at startup is not enough. The app routinely wins the
        // race against MongoDB — under docker-compose, after a container
        // restart, or on a cold Atlas cluster — and a probe issued while the
        // server is unreachable resolves to REGEX_FALLBACK permanently, even
        // though $search becomes available seconds later. The demo would then
        // silently present a $regex pipeline while claiming to show Atlas
        // Search, which is the one failure that discredits the whole story.
        // So keep re-probing in the background until it resolves.
        if (resolvedMode != SearchMode.ATLAS_SEARCH) {
            startBootstrapRetry();
        }
    }

    private boolean attemptBootstrap() {
        try {
            ensureSearchIndex();
            return resolvedMode == SearchMode.ATLAS_SEARCH;
        } catch (RuntimeException e) {
            // Startup must never die because search is unavailable; the demo
            // still uploads, streams and deletes files without it.
            log.warn("Search index bootstrap attempt failed ({}); staying in {}",
                    e.getMessage(), resolvedMode);
            return false;
        }
    }

    /**
     * Retries the probe on a daemon thread until Atlas Search resolves or the
     * window closes. Bounded so a genuinely search-less deployment settles into
     * REGEX_FALLBACK instead of logging forever.
     */
    private void startBootstrapRetry() {
        if (!retryRunning.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            long deadline = System.nanoTime() + BOOTSTRAP_RETRY_WINDOW.toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    Thread.sleep(BOOTSTRAP_RETRY_INTERVAL.toMillis());
                    if (attemptBootstrap()) {
                        log.info("Search became available on retry; now running in ATLAS_SEARCH");
                        return;
                    }
                }
                log.info("Search still unavailable after {}s; settling in {}",
                        BOOTSTRAP_RETRY_WINDOW.toSeconds(), resolvedMode);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                retryRunning.set(false);
            }
        }, "search-bootstrap-retry");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Resolves the search mode and, when Atlas Search is in play, creates the
     * index if it is missing. Safe to call repeatedly.
     *
     * @return true when a search index exists (pre-existing or freshly created)
     */
    public boolean ensureSearchIndex() {
        String configured = properties.searchMode().trim().toUpperCase();

        if ("REGEX_FALLBACK".equals(configured)) {
            resolvedMode = SearchMode.REGEX_FALLBACK;
            indexReady = false;
            log.info("search-mode=REGEX_FALLBACK forced by configuration; skipping index bootstrap");
            return false;
        }

        boolean supported = probeSearchSupport();

        if (!supported) {
            if ("ATLAS_SEARCH".equals(configured)) {
                // Forced mode: do not silently downgrade. Leave the mode as
                // ATLAS_SEARCH so queries fail with the server's own error
                // rather than quietly returning regex results the operator
                // did not ask for.
                resolvedMode = SearchMode.ATLAS_SEARCH;
                log.error("search-mode=ATLAS_SEARCH was forced but this deployment cannot serve $search "
                        + "(no search node / mongot). Searches will fail. Point MONGODB_URI at Atlas or the "
                        + "mongodb-atlas-local image, or set gridfs-demo.search-mode=AUTO.");
                return false;
            }
            resolvedMode = SearchMode.REGEX_FALLBACK;
            log.info("No search node detected; running in REGEX_FALLBACK mode");
            return false;
        }

        resolvedMode = SearchMode.ATLAS_SEARCH;
        String name = properties.searchIndexName();

        Document existing = findIndex(name);
        if (existing != null) {
            // Do not overwrite an index an operator may have tuned by hand in
            // the Atlas UI; just report where it is in its lifecycle.
            indexReady = isQueryable(existing);
            log.info("Search index '{}' already exists (status={}, queryable={})",
                    name, existing.getString("status"), indexReady);
            if (!indexReady) {
                startReadinessPoll(name);
            }
            return true;
        }

        // mongot refuses to build an index over a namespace that does not exist
        // yet, and on a clean database nothing has been uploaded so fs.files is
        // absent. Materialise it first, otherwise the very first startup of a
        // fresh demo silently ends up with no search index at all.
        ensureFilesCollectionExists();

        Document definition = loadDefinition();
        collection().createSearchIndex(name, definition);
        log.info("Created search index '{}' on {}; build is asynchronous", name, filesCollection);
        indexReady = false;
        startReadinessPoll(name);
        return true;
    }

    /** Which engine the deployment resolved to, for the UI badge. */
    public SearchMode activeMode() {
        return resolvedMode;
    }

    /** True once mongot reports the index queryable; surfaced by /api/admin/info. */
    public boolean isIndexReady() {
        return indexReady;
    }

    public String indexName() {
        return properties.searchIndexName();
    }

    /** The {@code <bucket>.files} collection the index is defined over. */
    public String filesCollection() {
        return filesCollection;
    }

    MongoCollection<Document> collection() {
        return mongoTemplate.getCollection(filesCollection);
    }

    /**
     * Creates {@code <bucket>.files} when it is missing.
     *
     * <p>Racing another starter that creates the same collection is fine: a
     * concurrent create surfaces as NamespaceExists (code 48), which is exactly
     * the state we wanted, so it is swallowed rather than treated as a failure.
     */
    private void ensureFilesCollectionExists() {
        try {
            mongoTemplate.getDb().createCollection(filesCollection);
            log.info("Created empty {} so the search index has a namespace to build over", filesCollection);
        } catch (RuntimeException e) {
            log.debug("{} already present (or concurrently created): {}", filesCollection, e.toString());
        }
    }

    /**
     * Asks the deployment to list its search indexes. Success (even with an
     * empty list) means mongot is reachable; any exception means it is not.
     */
    private boolean probeSearchSupport() {
        try {
            listIndexes();
            return true;
        } catch (RuntimeException e) {
            log.debug("$listSearchIndexes probe failed, treating deployment as search-less", e);
            return false;
        }
    }

    private List<Document> listIndexes() {
        List<Document> out = new ArrayList<>();
        collection().listSearchIndexes().into(out);
        return out;
    }

    private Document findIndex(String name) {
        try {
            return listIndexes().stream()
                    .filter(d -> name.equals(d.getString("name")))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not list search indexes", e);
            return null;
        }
    }

    /**
     * mongot exposes both a lifecycle {@code status} and a {@code queryable}
     * flag; the flag is the one that decides whether a {@code $search} will
     * actually return rows, so it wins when present.
     */
    private boolean isQueryable(Document index) {
        Boolean queryable = index.getBoolean("queryable");
        if (queryable != null) {
            return queryable;
        }
        return "READY".equalsIgnoreCase(index.getString("status"));
    }

    /**
     * Polls for readiness off the startup thread. A daemon thread means a
     * still-building index can never keep the JVM alive on shutdown.
     */
    private void startReadinessPoll(String name) {
        if (!pollerRunning.compareAndSet(false, true)) {
            return;
        }
        Thread poller = new Thread(() -> {
            long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    Document index = findIndex(name);
                    if (index != null && isQueryable(index)) {
                        indexReady = true;
                        log.info("Search index '{}' is READY", name);
                        return;
                    }
                    Thread.sleep(POLL_INTERVAL.toMillis());
                }
                log.warn("Search index '{}' was not queryable within {}s; it may still be building. "
                        + "/api/admin/info will keep reporting indexReady=false until it is.",
                        name, READY_TIMEOUT.toSeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pollerRunning.set(false);
            }
        }, "search-index-readiness");
        poller.setDaemon(true);
        poller.start();
    }

    /**
     * Loads the index definition from the classpath rather than building it in
     * Java, so the exact same JSON can be pasted into the Atlas UI or fed to
     * {@code mongosh}'s {@code createSearchIndex} when demoing on Atlas.
     */
    private Document loadDefinition() {
        try (InputStream in = new ClassPathResource(DEFINITION_RESOURCE).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Document root = Document.parse(json);
            Document definition = root.get("definition", Document.class);
            if (definition == null) {
                throw new IllegalStateException(DEFINITION_RESOURCE + " has no 'definition' object");
            }
            return definition;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + DEFINITION_RESOURCE, e);
        }
    }
}
