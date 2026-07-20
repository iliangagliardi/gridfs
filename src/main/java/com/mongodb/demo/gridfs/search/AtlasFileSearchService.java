package com.mongodb.demo.gridfs.search;

import com.mongodb.client.MongoCollection;
import com.mongodb.demo.gridfs.domain.ExtractionState;
import com.mongodb.demo.gridfs.domain.ExtractionMethod;
import com.mongodb.demo.gridfs.domain.FileCategory;
import com.mongodb.demo.gridfs.domain.SearchMode;
import com.mongodb.demo.gridfs.domain.SearchRequest;
import com.mongodb.demo.gridfs.domain.SearchResponse;
import com.mongodb.demo.gridfs.domain.StoredFile;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-text search over the Tika-extracted content we stash in
 * {@code fs.files.metadata.extractedText}.
 *
 * <p>Two engines, one response shape. When the deployment has a search node
 * (Atlas, the mongodb-atlas-local image, or Community 8.2+ with Search) we run
 * a real {@code $search} aggregation with relevance scoring, fuzzy matching and
 * server-side highlighting. When it does not, we degrade to a case-insensitive
 * {@code $regex} scan and build the snippet ourselves. The API contract does not
 * change either way — only {@link SearchResponse#mode()} tells the UI which one
 * ran, which is exactly the story the demo wants to tell.
 *
 * <h2>Why one pipeline with {@code $facet} rather than {@code $searchMeta}</h2>
 * We could get the total and the category facets from a second
 * {@code $searchMeta} round trip, which is cheaper at scale because mongot
 * counts from its own index instead of streaming matches through mongod. We use
 * a single {@code $facet} pipeline instead because it gives an exact total and
 * the facet counts in one round trip, and because the identical stage list works
 * unchanged in browse mode and in regex fallback — one code path, one pipeline
 * to show the client in the {@code explain} panel. On a corpus of tens of
 * thousands of files that trade is comfortably right; past that, split it.
 *
 * <h2>Never return extractedText</h2>
 * That field is capped at 1&nbsp;MB per document. Twenty hits would be a 20&nbsp;MB
 * JSON response for text the UI never shows. Every pipeline drops it as early as
 * possible and the client only ever receives the rendered snippet.
 */
@Service
public class AtlasFileSearchService implements FileSearchService {

    private static final Logger log = LoggerFactory.getLogger(AtlasFileSearchService.class);

    /**
     * Relevance weights. Matching the actual document body is the headline
     * feature, so it outranks a lucky filename match by a wide margin.
     */
    private static final int BOOST_EXTRACTED_TEXT = 5;
    private static final int BOOST_TITLE = 3;
    private static final int BOOST_FILENAME = 2;
    private static final int BOOST_AUTHOR = 1;

    /** Characters of context either side of a regex hit when we build snippets by hand. */
    private static final int FALLBACK_SNIPPET_CONTEXT = 75;

    private final SearchIndexBootstrap bootstrap;

    public AtlasFileSearchService(SearchIndexBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public SearchMode activeMode() {
        return bootstrap.activeMode();
    }

    @Override
    public boolean ensureSearchIndex() {
        return bootstrap.ensureSearchIndex();
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        long startNanos = System.nanoTime();
        SearchMode mode = activeMode();

        List<Document> pipeline;
        if (request.isBrowse()) {
            // No query text means there is nothing to score or highlight, so
            // $search would only add latency. Browse is a plain find-and-sort.
            pipeline = browsePipeline(request);
        } else if (mode == SearchMode.ATLAS_SEARCH) {
            pipeline = atlasPipeline(request);
        } else {
            pipeline = regexPipeline(request);
        }

        Document faceted = runFaceted(pipeline);
        List<Document> hits = faceted.getList("results", Document.class, List.of());

        boolean renderSnippetsLocally = !request.isBrowse() && mode != SearchMode.ATLAS_SEARCH;
        List<StoredFile> results = new ArrayList<>(hits.size());
        for (Document hit : hits) {
            String snippet = renderSnippetsLocally
                    ? fallbackSnippet(hit, request.query())
                    : highlightSnippet(hit);
            results.add(toStoredFile(hit, snippet));
        }

        long took = (System.nanoTime() - startNanos) / 1_000_000L;
        return new SearchResponse(
                results,
                readTotal(faceted),
                mode,
                took,
                readFacets(faceted),
                PipelineRenderer.render(pipeline));
    }

    @Override
    public List<String> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        int cap = limit <= 0 ? 8 : Math.min(limit, 50);

        List<Document> pipeline = activeMode() == SearchMode.ATLAS_SEARCH
                ? atlasAutocompletePipeline(prefix, cap)
                : regexAutocompletePipeline(prefix, cap);

        // A file's name and its embedded document title are both plausible
        // suggestions and often differ, so we harvest each hit for both and
        // de-duplicate. LinkedHashSet keeps the relevance order mongot gave us.
        Set<String> suggestions = new LinkedHashSet<>();
        try {
            for (Document doc : collection().aggregate(pipeline)) {
                addSuggestion(suggestions, doc.getString("filename"), prefix);
                addSuggestion(suggestions, doc.getString("title"), prefix);
                if (suggestions.size() >= cap) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Autocomplete failed for prefix '{}'", prefix, e);
            return List.of();
        }
        return suggestions.stream().limit(cap).toList();
    }

    // ------------------------------------------------------------------
    // Pipelines
    // ------------------------------------------------------------------

    /**
     * The real thing: a {@code compound} query with weighted {@code should}
     * clauses for relevance and {@code filter} clauses for the facet
     * restrictions.
     *
     * <p>{@code filter} rather than {@code must} for category and tags because
     * filters are non-scoring — a file is not more relevant for being in the
     * category you already restricted to, and mongot can evaluate them more
     * cheaply.
     *
     * <p>{@code minimumShouldMatch: 1} is load-bearing. Without it a request
     * that carries only filters would match every document in the collection,
     * because {@code should} clauses are optional by definition.
     */
    private List<Document> atlasPipeline(SearchRequest request) {
        String query = request.query().trim();

        List<Document> should = List.of(
                textClause(query, "metadata.extractedText", BOOST_EXTRACTED_TEXT, request.fuzzy()),
                textClause(query, "metadata.title", BOOST_TITLE, request.fuzzy()),
                textClause(query, "filename", BOOST_FILENAME, request.fuzzy()),
                textClause(query, "metadata.author", BOOST_AUTHOR, request.fuzzy()));

        Document compound = new Document("should", should)
                .append("minimumShouldMatch", 1);

        List<Document> filters = searchFilters(request);
        if (!filters.isEmpty()) {
            compound.append("filter", filters);
        }

        Document search = new Document("index", bootstrap.indexName())
                .append("compound", compound)
                // Ask mongot for the passages of extractedText that matched, so
                // the UI can show *where* in the PDF the term appeared. This is
                // the demo's money shot.
                .append("highlight", new Document("path", "metadata.extractedText")
                        .append("maxNumPassages", 3));

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$search", search));
        // Drop the megabyte before anything else touches the document.
        pipeline.add(new Document("$unset", "metadata.extractedText"));
        pipeline.add(facetStage(request, true));
        return pipeline;
    }

    /**
     * One weighted clause against one field. Fuzzy is opt-in per request:
     * {@code maxEdits: 1} tolerates a single typo and {@code prefixLength: 2}
     * pins the first two characters so "report" cannot drift to "export",
     * which also keeps the term expansion cheap.
     */
    private Document textClause(String query, String path, int boost, boolean fuzzy) {
        Document text = new Document("query", query)
                .append("path", path)
                .append("score", new Document("boost", new Document("value", boost)));
        if (fuzzy) {
            text.append("fuzzy", new Document("maxEdits", 1).append("prefixLength", 2));
        }
        return new Document("text", text);
    }

    /**
     * Filter clauses for the {@code token}-mapped fields.
     *
     * <p>{@code token} fields are indexed verbatim with no analysis, so the
     * text operator would be wrong here — {@code equals} and {@code in} are the
     * operators that address them. Categories are an OR ({@code in} over the
     * selected values); tags are an AND, per the {@code SearchRequest} contract
     * that a file must carry *all* the requested tags, so each tag gets its own
     * {@code equals} clause and {@code compound.filter} conjoins them.
     */
    private List<Document> searchFilters(SearchRequest request) {
        List<Document> filters = new ArrayList<>();
        if (!request.categories().isEmpty()) {
            List<String> names = request.categories().stream().map(Enum::name).toList();
            filters.add(new Document("in", new Document("path", "metadata.category")
                    .append("value", names)));
        }
        for (String tag : request.tags()) {
            if (tag != null && !tag.isBlank()) {
                filters.add(new Document("equals", new Document("path", "metadata.tags")
                        .append("value", tag.trim())));
            }
        }
        return filters;
    }

    /** Blank query: no scoring to do, so just filter and sort newest-first. */
    private List<Document> browsePipeline(SearchRequest request) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", mongoFilter(request)));
        pipeline.add(new Document("$sort", new Document("uploadDate", -1)));
        pipeline.add(new Document("$unset", "metadata.extractedText"));
        pipeline.add(facetStage(request, false));
        return pipeline;
    }

    /**
     * The no-search-node path. Deliberately simple: a case-insensitive regex
     * over the extracted text and the filename.
     *
     * <p>This is a collection scan and it has no relevance ranking — that is the
     * honest cost of running without a search node, and saying so out loud
     * during the demo is more useful than pretending the two paths are equal.
     * Unlike the Atlas path we keep {@code extractedText} on the documents that
     * reach us, because we need it to locate the match and cut a snippet; it is
     * dropped before anything is serialised to the client.
     */
    private List<Document> regexPipeline(SearchRequest request) {
        String pattern = escapeRegex(request.query().trim());
        Document regex = new Document("$regex", pattern).append("$options", "i");

        Document match = mongoFilter(request);
        match.append("$or", List.of(
                new Document("metadata.extractedText", regex),
                new Document("filename", regex)));

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", match));
        pipeline.add(new Document("$sort", new Document("uploadDate", -1)));
        pipeline.add(facetStage(request, false));
        return pipeline;
    }

    /**
     * The one stage that produces the page, the exact total and the category
     * facets together.
     *
     * <p>{@code $skip}/{@code $limit} sit inside the {@code results} branch so
     * the {@code $meta} projection only runs for the documents we are about to
     * return, not for every match.
     */
    private Document facetStage(SearchRequest request, boolean withSearchMeta) {
        List<Document> results = new ArrayList<>();
        results.add(new Document("$skip", (long) request.page() * request.size()));
        results.add(new Document("$limit", request.size()));
        if (withSearchMeta) {
            results.add(new Document("$addFields", new Document()
                    .append("searchScore", new Document("$meta", "searchScore"))
                    .append("searchHighlights", new Document("$meta", "searchHighlights"))));
        }

        Document facets = new Document()
                .append("results", results)
                .append("total", List.of(new Document("$count", "value")))
                .append("categories", List.of(new Document("$group", new Document()
                        .append("_id", "$metadata.category")
                        .append("count", new Document("$sum", 1)))));

        return new Document("$facet", facets);
    }

    /** Plain MQL equivalent of {@link #searchFilters} for the non-{@code $search} paths. */
    private Document mongoFilter(SearchRequest request) {
        Document filter = new Document();
        if (!request.categories().isEmpty()) {
            filter.append("metadata.category", new Document("$in",
                    request.categories().stream().map(Enum::name).toList()));
        }
        List<String> tags = request.tags().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .toList();
        if (!tags.isEmpty()) {
            // $all, not $in: the contract says a file must carry every tag.
            filter.append("metadata.tags", new Document("$all", tags));
        }
        return filter;
    }

    private List<Document> atlasAutocompletePipeline(String prefix, int cap) {
        Document compound = new Document("should", List.of(
                        new Document("autocomplete", new Document("query", prefix).append("path", "filename")),
                        new Document("autocomplete", new Document("query", prefix).append("path", "metadata.title"))))
                .append("minimumShouldMatch", 1);

        return List.of(
                new Document("$search", new Document("index", bootstrap.indexName())
                        .append("compound", compound)),
                // Over-fetch a little: two hits can collapse to one suggestion
                // once filename and title are de-duplicated.
                new Document("$limit", cap * 2L),
                new Document("$project", new Document("_id", 0)
                        .append("filename", 1)
                        .append("title", "$metadata.title")));
    }

    private List<Document> regexAutocompletePipeline(String prefix, int cap) {
        Document anchored = new Document("$regex", "^" + escapeRegex(prefix)).append("$options", "i");
        return List.of(
                new Document("$match", new Document("$or", List.of(
                        new Document("filename", anchored),
                        new Document("metadata.title", anchored)))),
                new Document("$limit", cap * 2L),
                new Document("$project", new Document("_id", 0)
                        .append("filename", 1)
                        .append("title", "$metadata.title")));
    }

    // ------------------------------------------------------------------
    // Execution and mapping
    // ------------------------------------------------------------------

    private MongoCollection<Document> collection() {
        return bootstrap.collection();
    }

    /** A {@code $facet} pipeline always yields exactly one document. */
    private Document runFaceted(List<Document> pipeline) {
        Document result = collection().aggregate(pipeline).first();
        return result == null ? new Document() : result;
    }

    private long readTotal(Document faceted) {
        List<Document> total = faceted.getList("total", Document.class, List.of());
        if (total.isEmpty()) {
            return 0L;
        }
        Number value = total.get(0).get("value", Number.class);
        return value == null ? 0L : value.longValue();
    }

    private Map<String, Long> readFacets(Document faceted) {
        Map<String, Long> facets = new LinkedHashMap<>();
        for (Document bucket : faceted.getList("categories", Document.class, List.of())) {
            Object id = bucket.get("_id");
            Number count = bucket.get("count", Number.class);
            facets.put(id == null ? FileCategory.OTHER.name() : id.toString(),
                    count == null ? 0L : count.longValue());
        }
        return facets;
    }

    private StoredFile toStoredFile(Document doc, String snippet) {
        Document metadata = doc.get("metadata", Document.class);
        if (metadata == null) {
            metadata = new Document();
        }
        Number score = doc.get("searchScore", Number.class);

        return new StoredFile(
                idOf(doc),
                doc.getString("filename"),
                longValue(doc.get("length"), 0L),
                (int) longValue(doc.get("chunkSize"), 0L),
                instantOf(doc.get("uploadDate")),
                metadata.getString("contentType"),
                categoryOf(metadata.getString("category")),
                stringList(metadata.get("tags")),
                extractionStateOf(metadata.getString("extractionState")),
                (int) longValue(metadata.get("textLength"), 0L),
                intOrNull(metadata.get("pageCount")),
                metadata.getString("author"),
                metadata.getString("title"),
                metadata.get("durationMillis") == null ? null : longValue(metadata.get("durationMillis"), 0L),
                metadata.getString("uploadedBy"),
                metadata.getString("checksumSha256"),
                extractionMethodOf(metadata),
                Boolean.TRUE.equals(metadata.getBoolean("ocrApplied")),
                snippet,
                score == null ? null : score.doubleValue());
    }

    /**
     * Mirrors the defaulting in {@code StoredFileMapper}: documents written
     * before OCR existed carry neither field, and a search hit must render
     * identically to the same file seen through the listing endpoint. Derived
     * rather than stored so no migration is needed over existing buckets.
     */
    private ExtractionMethod extractionMethodOf(Document metadata) {
        String stored = metadata.getString("extractionMethod");
        if (stored != null) {
            try {
                return ExtractionMethod.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through to derivation
            }
        }
        return longValue(metadata.get("textLength"), 0L) > 0
                ? ExtractionMethod.TIKA
                : ExtractionMethod.NONE;
    }

    private String idOf(Document doc) {
        Object id = doc.get("_id");
        if (id instanceof ObjectId objectId) {
            return objectId.toHexString();
        }
        return id == null ? null : id.toString();
    }

    private Instant instantOf(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return value instanceof Instant instant ? instant : null;
    }

    private long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    /** Unknown or missing enum values must not 500 a search over legacy data. */
    private FileCategory categoryOf(String name) {
        if (name == null) {
            return FileCategory.OTHER;
        }
        try {
            return FileCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            return FileCategory.OTHER;
        }
    }

    private ExtractionState extractionStateOf(String name) {
        if (name == null) {
            return ExtractionState.SKIPPED;
        }
        try {
            return ExtractionState.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ExtractionState.SKIPPED;
        }
    }

    // ------------------------------------------------------------------
    // Snippets
    //
    // Everything below produces HTML that the UI injects into the page. Every
    // character that originated in an uploaded file MUST be escaped before a
    // <mark> tag goes near it: a PDF whose text layer contains "<script>" is
    // attacker-controlled content, and rendering it raw would be stored XSS.
    // The rule is: escape the untrusted fragments first, then concatenate our
    // own trusted tags around them. Never the other way round.
    // ------------------------------------------------------------------

    /**
     * Renders mongot's highlight passages into HTML.
     *
     * <p>The {@code searchHighlights} metadata is a list of passages, each split
     * into {@code text} and {@code hit} runs and ordered best-first. We take the
     * top passage and wrap only its {@code hit} runs, which means the marks land
     * exactly where Lucene matched rather than wherever a naive string search
     * would have guessed.
     */
    private String highlightSnippet(Document doc) {
        List<Document> highlights = doc.getList("searchHighlights", Document.class, List.of());
        if (highlights.isEmpty()) {
            return null;
        }
        List<Document> texts = highlights.get(0).getList("texts", Document.class, List.of());
        if (texts.isEmpty()) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        for (Document run : texts) {
            String value = run.getString("value");
            if (value == null) {
                continue;
            }
            String escaped = escapeHtml(value);
            if ("hit".equals(run.getString("type"))) {
                out.append("<mark>").append(escaped).append("</mark>");
            } else {
                out.append(escaped);
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    /**
     * Builds a snippet by hand for the regex path, where the server gives us no
     * highlight metadata.
     *
     * <p>We locate the first case-insensitive occurrence and cut a window around
     * it. The window is split on *raw* offsets before escaping, because escaping
     * changes string lengths — escaping first and then trying to index into the
     * result is the classic way to slice an entity in half and emit broken HTML.
     */
    private String fallbackSnippet(Document doc, String query) {
        Document metadata = doc.get("metadata", Document.class);
        String text = metadata == null ? null : metadata.getString("extractedText");
        String term = query == null ? "" : query.trim();
        if (text == null || text.isEmpty() || term.isEmpty()) {
            return null;
        }

        int at = text.toLowerCase().indexOf(term.toLowerCase());
        if (at < 0) {
            // Matched on filename only; there is nothing to point at in the body.
            return null;
        }

        int from = Math.max(0, at - FALLBACK_SNIPPET_CONTEXT);
        int to = Math.min(text.length(), at + term.length() + FALLBACK_SNIPPET_CONTEXT);

        String before = text.substring(from, at);
        String hit = text.substring(at, at + term.length());
        String after = text.substring(at + term.length(), to);

        return (from > 0 ? "…" : "")
                + escapeHtml(before)
                + "<mark>" + escapeHtml(hit) + "</mark>"
                + escapeHtml(after)
                + (to < text.length() ? "…" : "");
    }

    /**
     * Minimal HTML entity escaping. Quotes are escaped as well as angle
     * brackets so the snippet stays safe if a future template ever drops it
     * into an attribute rather than a text node.
     */
    private static String escapeHtml(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Neutralises regex metacharacters in user input.
     *
     * <p>Without this a user typing {@code (} produces an unterminated group and
     * the server answers 500 — and a user typing something like
     * {@code (a+)+$} hands us a catastrophic-backtracking denial of service.
     * We escape rather than use {@code \Q...\E} so the pattern stays readable in
     * the {@code explain} panel the demo shows to the client.
     */
    private static String escapeRegex(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private void addSuggestion(Set<String> into, String value, String prefix) {
        if (value == null || value.isBlank() || into.size() >= 50) {
            return;
        }
        // mongot's edgeGram matching is per-token, so a hit can come from a word
        // in the middle of the name. Keep only suggestions the user can see
        // their prefix in, so the dropdown never looks arbitrary.
        if (value.toLowerCase().contains(prefix.trim().toLowerCase())) {
            into.add(value);
        }
    }
}
