package com.lit.fire.flame.releaseevent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses AuraLLM's reply into a {@link ReleaseEventParser.EventResult}. Mirrors
 * MarketingTacticsParser's tolerant approach — AuraLLM's active provider doesn't always obey
 * "JSON only": this strips markdown code fences, allows a trailing comma before the closing
 * brace (observed from the gemma3:31b-cloud provider), and narrows to the outermost {...} span
 * so stray preamble/trailer prose doesn't break parsing.
 */
public class ReleaseEventParser {

    /** Lowercase event_type (as the LLM may send it) -> canonical display casing. */
    private static final Map<String, String> CANONICAL_TYPES = Map.of(
        "holiday",             "Holiday",
        "festival",            "Festival",
        "election",            "Election",
        "normal",              "Normal",
        "cricket world cup",   "Cricket World Cup",
        "football world cup",  "Football World Cup"
    );

    /**
     * A test run once returned event_name "2018 FIFA World Cup (Wait, this is 2017)" — the
     * model's own mid-generation self-correction leaking into a field meant to be a short proper
     * noun. event_name should never contain this kind of chain-of-thought residue, so any match
     * here is treated as a parse failure rather than written to the DB as-is.
     */
    private static final Pattern LEAKED_REASONING = Pattern.compile(
        "(?i)\\b(wait|hmm|actually|let me|i think|on second thought|correction:|i made a mistake)\\b");

    private final ObjectMapper objectMapper = new ObjectMapper(
        JsonFactory.builder().enable(JsonReadFeature.ALLOW_TRAILING_COMMA).build());

    /** One classified release-window event, as returned by {@link #parse}. */
    public record EventResult(String type, String name, String detail) {}

    /**
     * @throws Exception when the reply isn't parseable JSON or its event_type isn't one of
     *                    Holiday/Festival/Election/Normal/Cricket World Cup/Football World Cup
     *                    — callers should treat this as a failed attempt (leave the row
     *                    untouched, retry next cycle) rather than writing a guessed value.
     */
    public EventResult parse(String llmReply) throws Exception {
        String json = stripMarkdownFences(llmReply == null ? "" : llmReply.trim());
        JsonNode root = objectMapper.readTree(extractJsonObject(json));

        String rawType = root.path("event_type").asText("").trim();
        String type = CANONICAL_TYPES.get(rawType.toLowerCase());
        if (type == null) {
            throw new IllegalArgumentException("Unrecognised event_type: " + rawType);
        }

        String name = textOrNull(root.path("event_name"));
        String detail = textOrNull(root.path("event_detail"));

        if ("normal".equalsIgnoreCase(type) && (name == null || name.isBlank())) {
            name = "Normal";
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Missing event_name for event_type=" + type);
        }
        if (LEAKED_REASONING.matcher(name).find()) {
            throw new IllegalArgumentException("event_name looks like leaked reasoning, not a clean name: " + name);
        }

        return new EventResult(type, name, detail);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String text = node.asText("").trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
        return text;
    }

    private static String stripMarkdownFences(String text) {
        if (!text.startsWith("```")) return text;
        int newline = text.indexOf('\n');
        if (newline < 0) return text;
        int closing = text.lastIndexOf("```");
        if (closing > newline) return text.substring(newline + 1, closing).trim();
        return text;
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return text;
        return text.substring(start, end + 1);
    }
}
