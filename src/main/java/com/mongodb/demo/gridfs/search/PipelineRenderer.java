package com.mongodb.demo.gridfs.search;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns the aggregation pipeline we actually executed into indented JSON.
 *
 * <p>Why this exists: the whole point of the demo is to show a client that the
 * search box is not magic — it is a MongoDB aggregation they could paste into
 * Compass or mongosh. {@code SearchResponse.explain} carries that text, so it
 * has to be the real pipeline object we handed to the driver, not a
 * hand-maintained string that can drift away from the code.
 *
 * <p>Relaxed extended JSON is deliberate: it renders dates and numbers in a
 * form a human reads comfortably, at the cost of not being round-trippable for
 * every BSON type. Our pipelines only contain strings, numbers and booleans, so
 * nothing is lost and the output pastes straight into mongosh.
 */
final class PipelineRenderer {

    private static final JsonWriterSettings SETTINGS = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .build();

    private PipelineRenderer() {
    }

    /** Renders the pipeline as an indented JSON array of stages. */
    static String render(List<? extends Bson> pipeline) {
        String stages = pipeline.stream()
                .map(PipelineRenderer::renderStage)
                .map(PipelineRenderer::indentBlock)
                .collect(Collectors.joining(",\n"));
        return "[\n" + stages + "\n]";
    }

    private static String renderStage(Bson stage) {
        if (stage instanceof Document document) {
            return document.toJson(SETTINGS);
        }
        // Any other Bson implementation still knows how to describe itself as a
        // document; go through BsonDocument so operators built with the driver's
        // Filters/Aggregates helpers render too.
        return stage.toBsonDocument().toJson(SETTINGS);
    }

    /** Pushes a rendered stage two spaces right so it nests inside the array. */
    private static String indentBlock(String json) {
        return json.lines().map(line -> "  " + line).collect(Collectors.joining("\n"));
    }
}
