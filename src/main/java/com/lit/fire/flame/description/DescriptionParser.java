package com.lit.fire.flame.description;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the free-text "Description" column found in IMDb list-export CSVs into
 * structured box-office figures.
 *
 * The raw text packs multiple "Label : value" pairs together with no reliable
 * whitespace between a value and the next label (e.g. "...Budget : 300crWorldwide
 * gross : 1152cr..."), and a handful of files use a decorative variant that
 * separates label/value with "=" or "◄" instead of ":" and states totals as a
 * bare "<number> USD" instead of "$<number>". Both are handled by scanning for a
 * fixed set of known labels (longest/most-specific first) and treating the text
 * between one recognised label and the next as that label's value.
 */
public class DescriptionParser {

    public enum Field {
        BUDGET, REVENUE, DOMESTIC_COLLECTION, OVERSEAS_COLLECTION,
        INDIA_GROSS_COLLECTION, FIRST_DAY_WORLDWIDE, FIRST_DAY_INDIA,
        OPENING_WEEKEND, DISTRIBUTOR_SHARE, VERDICT, NOTE, IGNORED
    }

    /** A money amount extracted from the text. isUsd=true means the value IS the final USD
     *  amount already; isUsd=false means the value is in INR Crore and still needs conversion. */
    public record Amount(BigDecimal value, boolean isUsd) {}

    public record ParsedDescription(Map<Field, Amount> amounts, String verdict, String note) {}

    private record LabelSpec(String phrase, Field field) {}

    // Longest / most specific phrase first so overlapping prefixes never pre-empt a more
    // specific label (e.g. "Domestic net collection" must be tried before bare "Domestic").
    private static final List<LabelSpec> LABELS = List.of(
        new LabelSpec("First Day Collection Worldwide", Field.FIRST_DAY_WORLDWIDE),
        new LabelSpec("First Day Collection India",      Field.FIRST_DAY_INDIA),
        new LabelSpec("Domestic net collection",         Field.DOMESTIC_COLLECTION),
        new LabelSpec("Domestic / India Nett Gross",     Field.DOMESTIC_COLLECTION),
        new LabelSpec("India Net Collection",            Field.DOMESTIC_COLLECTION),
        new LabelSpec("Total Worldwide Collection",      Field.REVENUE),
        new LabelSpec("Worldwide Collection",            Field.REVENUE),
        new LabelSpec("Worldwide gross",                 Field.REVENUE),
        new LabelSpec("WW Gross",                        Field.REVENUE),
        new LabelSpec("Total Gross",                     Field.REVENUE),
        new LabelSpec("Box office",                      Field.REVENUE),
        new LabelSpec("Indian Gross Collection",         Field.INDIA_GROSS_COLLECTION),
        new LabelSpec("India Gross Collection",          Field.INDIA_GROSS_COLLECTION),
        new LabelSpec("India Gross",                     Field.INDIA_GROSS_COLLECTION),
        new LabelSpec("India Collection",                Field.INDIA_GROSS_COLLECTION),
        new LabelSpec("Worldwide Share",                 Field.DISTRIBUTOR_SHARE),
        new LabelSpec("Share",                           Field.DISTRIBUTOR_SHARE),
        new LabelSpec("Overseas Collection",             Field.OVERSEAS_COLLECTION),
        new LabelSpec("Overseas Gross",                  Field.OVERSEAS_COLLECTION),
        new LabelSpec("Overseas",                        Field.OVERSEAS_COLLECTION),
        new LabelSpec("International",                   Field.OVERSEAS_COLLECTION),
        new LabelSpec("Opening Weekend",                 Field.OPENING_WEEKEND),
        new LabelSpec("Pre-release Business",            Field.IGNORED),
        new LabelSpec("Budget",                          Field.BUDGET),
        new LabelSpec("Domestic",                        Field.DOMESTIC_COLLECTION),
        new LabelSpec("Worldwide",                       Field.REVENUE),
        new LabelSpec("Verdict",                         Field.VERDICT),
        new LabelSpec("Status",                          Field.VERDICT),
        new LabelSpec("Note",                            Field.NOTE),
        new LabelSpec("Language",                        Field.IGNORED),
        new LabelSpec("Release date",                    Field.IGNORED),
        new LabelSpec("Certification",                   Field.IGNORED),
        new LabelSpec("Screens",                         Field.IGNORED),
        new LabelSpec("Simultaneously shot in",          Field.IGNORED),
        new LabelSpec("Dubbed in",                       Field.IGNORED),
        new LabelSpec("Year",                            Field.IGNORED)
    );

    private static final Pattern LABEL_PATTERN;
    private static final Map<String, Field> GROUP_TO_FIELD = new LinkedHashMap<>();
    static {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LABELS.size(); i++) {
            LabelSpec spec = LABELS.get(i);
            String group = "L" + i;
            if (i > 0) sb.append('|');
            sb.append("(?<").append(group).append(">").append(Pattern.quote(spec.phrase())).append(")");
            GROUP_TO_FIELD.put(group, spec.field());
        }
        // Separator after a label is normally ":" but a decorative variant uses arrows and "=",
        // e.g. "► Worldwide Gross ◄ = 8,079,100,000 INR ...".
        LABEL_PATTERN = Pattern.compile(
            "(?i)(?:" + sb + ")\\s*(?:[◄►➤●➾║]\\s*)*[:=]");
    }

    private static final Pattern DOLLAR_PREFIX = Pattern.compile(
        "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(million|billion)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern USD_SUFFIX = Pattern.compile(
        "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*USD(?![a-z])", Pattern.CASE_INSENSITIVE);
    // Trailing boundary is "not followed by a lowercase letter" rather than \b: these
    // descriptions often run a value straight into the next (unrecognised) label with no
    // space (e.g. "336.2crPre-release Business"), and a plain \b would fail there since
    // 'r' and 'P' are both word characters. A following *lowercase* letter (e.g. the "s" in
    // "crores", or genuine prose) still correctly disqualifies the match.
    private static final Pattern CRORE = Pattern.compile(
        "₹?\\s*([0-9][0-9,./]*)\\+?\\s*(?:crore|cr)s?(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAKH = Pattern.compile(
        "₹?\\s*([0-9][0-9,./]*)\\+?\\s*lakhs?(?![a-z])", Pattern.CASE_INSENSITIVE);

    public ParsedDescription parse(String description) {
        if (description == null || description.isBlank()) {
            return new ParsedDescription(Map.of(), null, null);
        }

        List<int[]> bounds = new ArrayList<>(); // [matchStart, valueStart]
        List<Field> fields = new ArrayList<>();

        Matcher m = LABEL_PATTERN.matcher(description);
        while (m.find()) {
            Field field = null;
            for (Map.Entry<String, Field> e : GROUP_TO_FIELD.entrySet()) {
                if (m.start(e.getKey()) != -1) { field = e.getValue(); break; }
            }
            if (field == null) continue;
            bounds.add(new int[]{m.start(), m.end()});
            fields.add(field);
        }

        if (bounds.isEmpty()) {
            // No recognised labels at all: these descriptions are themselves a single
            // box-office figure (curated "highest grossing" list style), so treat the
            // whole string as the worldwide gross. If it isn't a money figure either
            // (e.g. a bare "BLOCKBUSTER"), fall back to treating it as the verdict.
            Map<Field, Amount> amounts = new EnumMap<>(Field.class);
            Optional<Amount> revenue = parseAmount(description.trim());
            revenue.ifPresent(a -> amounts.put(Field.REVENUE, a));
            String verdict = revenue.isPresent() ? null : cleanText(description);
            return new ParsedDescription(amounts, verdict, null);
        }

        Map<Field, Amount> amounts = new EnumMap<>(Field.class);
        String verdict = null, note = null;

        for (int i = 0; i < bounds.size(); i++) {
            int valueStart = bounds.get(i)[1];
            int valueEnd = (i + 1 < bounds.size()) ? bounds.get(i + 1)[0] : description.length();
            if (valueEnd <= valueStart) continue;
            String rawValue = description.substring(valueStart, valueEnd).trim();
            Field field = fields.get(i);
            if (rawValue.isEmpty() || field == Field.IGNORED) continue;

            switch (field) {
                case VERDICT -> verdict = cleanText(rawValue);
                case NOTE    -> note = cleanText(rawValue);
                default -> parseAmount(rawValue).ifPresent(a -> amounts.put(field, a));
            }
        }

        return new ParsedDescription(amounts, verdict, note);
    }

    private String cleanText(String raw) {
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("[\\s.•\\-–—|►◄➤●➾║]+$", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Extracts a money amount from a raw value fragment.
     * Priority: "$" figure or "<number> USD" (already USD — used as-is whether or not
     * accompanying INR text is present) → crore figure (caller must convert INR→USD)
     * → lakh figure (idem, divided by 100 to crore first).
     * A "50/125/130/140/190 crore" multi-estimate (seen on a decorative box-office format)
     * is resolved to its maximum, used as the conservative single figure.
     */
    private Optional<Amount> parseAmount(String raw) {
        Matcher d = DOLLAR_PREFIX.matcher(raw);
        if (d.find()) {
            BigDecimal n = applyUnit(new BigDecimal(d.group(1).replace(",", "")), d.group(2));
            return Optional.of(new Amount(n, true));
        }
        Matcher u = USD_SUFFIX.matcher(raw);
        if (u.find()) {
            BigDecimal n = new BigDecimal(u.group(1).replace(",", ""));
            return Optional.of(new Amount(n, true));
        }
        Matcher c = CRORE.matcher(raw);
        if (c.find()) {
            BigDecimal max = maxOfSlashList(c.group(1));
            if (max != null) return Optional.of(new Amount(max, false));
        }
        Matcher l = LAKH.matcher(raw);
        if (l.find()) {
            BigDecimal max = maxOfSlashList(l.group(1));
            if (max != null) {
                return Optional.of(new Amount(max.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP), false));
            }
        }
        return Optional.empty();
    }

    private BigDecimal maxOfSlashList(String token) {
        BigDecimal max = null;
        for (String part : token.split("/")) {
            String cleaned = part.replace(",", "").trim();
            if (cleaned.isEmpty() || cleaned.equals(".")) continue;
            try {
                BigDecimal v = new BigDecimal(cleaned);
                if (max == null || v.compareTo(max) > 0) max = v;
            } catch (NumberFormatException ignored) {
                // stray fragment from the split (e.g. trailing '.'); skip it
            }
        }
        return max;
    }

    private BigDecimal applyUnit(BigDecimal n, String unit) {
        if (unit == null) return n;
        return switch (unit.toLowerCase()) {
            case "million" -> n.multiply(BigDecimal.valueOf(1_000_000));
            case "billion" -> n.multiply(BigDecimal.valueOf(1_000_000_000));
            default -> n;
        };
    }
}
