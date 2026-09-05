package com.lit.fire.flame.marketing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses AuraLLM's reply into subClassificationNumber -> tactic-detail-strings. AuraLLM's
 * active provider is less reliable at obeying "JSON only" instructions than a hosted frontier
 * model, so this is deliberately tolerant: it strips markdown code fences, allows a trailing
 * comma before the closing brace/bracket (observed from the gemma4:31b-cloud provider — it
 * reliably tacks one on after the last field of a long object, which strict JSON rejects
 * outright and would otherwise silently discard an otherwise-perfect, data-rich response),
 * skips unknown/malformed keys instead of failing the whole movie, and accepts either a JSON
 * array or (if the model collapses to the "empty string" shape the product owner mentioned) a
 * lone empty/blank string as "no tactic for this classification".
 */
public class MarketingTacticsParser {

    private final ObjectMapper objectMapper = new ObjectMapper(
        JsonFactory.builder().enable(JsonReadFeature.ALLOW_TRAILING_COMMA).build());

    /** Returns subClassificationNumber -> non-empty tactic detail strings. Never null. */
    public Map<Integer, List<String>> parse(String llmReply) throws Exception {
        String json = stripMarkdownFences(llmReply == null ? "" : llmReply.trim());
        JsonNode root = objectMapper.readTree(extractJsonObject(json));

        Map<Integer, List<String>> result = new LinkedHashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            Integer subNumber = parseKey(entry.getKey());
            if (subNumber == null || MarketingTacticTaxonomy.bySubNumber(subNumber) == null) continue;

            List<String> details = new ArrayList<>();
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    String text = item.asText("").trim();
                    if (!text.isEmpty()) details.add(text);
                }
            } else if (value.isTextual()) {
                String text = value.asText("").trim();
                if (!text.isEmpty()) details.add(text);
            }
            if (!details.isEmpty()) result.put(subNumber, details);
        }
        return result;
    }

    private static Integer parseKey(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Strips a leading/trailing ```json ... ``` or ``` ... ``` fence, if present. */
    private static String stripMarkdownFences(String text) {
        if (!text.startsWith("```")) return text;
        int newline = text.indexOf('\n');
        if (newline < 0) return text;
        int closing = text.lastIndexOf("```");
        if (closing > newline) return text.substring(newline + 1, closing).trim();
        return text;
    }

    /**
     * Some local models pad the JSON object with a sentence of preamble/trailer text despite
     * instructions not to. Narrows to the outermost {...} span so ObjectMapper doesn't choke
     * on surrounding prose.
     */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return text;
        return text.substring(start, end + 1);
    }
}
