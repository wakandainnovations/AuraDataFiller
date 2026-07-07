package com.lit.fire.flame.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal robots.txt policy for the "User-agent: *" group — the group that applies
 * to the generic browser User-Agent this crawler presents (it doesn't identify
 * itself as a named bot, so per-bot Disallow blocks like "GPTBot"/"ClaudeBot" don't
 * apply to it; those blocks exist on kulfiy/fandango but explicitly target AI/scraper
 * bots by name, not "*").
 *
 * Collects Crawl-delay and Disallow prefixes declared under "User-agent: *" blocks.
 * Does not implement full RFC 9309 precedence (longest-match Allow-over-Disallow) —
 * sufficient for the simple robots.txt files on these two sites.
 */
public final class RobotsTxtPolicy {

    private static final Pattern LINE_SPLIT = Pattern.compile("\\r?\\n");

    private final long crawlDelayMs;
    private final List<String> disallowedPrefixes;

    private RobotsTxtPolicy(long crawlDelayMs, List<String> disallowedPrefixes) {
        this.crawlDelayMs = crawlDelayMs;
        this.disallowedPrefixes = disallowedPrefixes;
    }

    public long crawlDelayMs() { return crawlDelayMs; }

    /** Returns false if the given path is disallowed for User-agent: * . */
    public boolean isAllowed(String path) {
        for (String prefix : disallowedPrefixes) {
            if (!prefix.isEmpty() && path.startsWith(prefix)) return false;
        }
        return true;
    }

    public static RobotsTxtPolicy parse(String robotsTxt, long defaultDelayMs) {
        List<String> disallowed = new ArrayList<>();
        long delayMs = defaultDelayMs;

        // Consecutive "User-agent:" lines form one group; a rule line (Disallow/Allow/
        // Crawl-delay) ends that group's user-agent list, so the next "User-agent:" line
        // starts a fresh group.
        boolean lastLineWasUserAgent = false;
        boolean currentGroupIsWildcard = false;

        for (String rawLine : LINE_SPLIT.split(robotsTxt)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            if (key.equals("user-agent")) {
                if (!lastLineWasUserAgent) currentGroupIsWildcard = false; // starting a new group
                if (value.equals("*")) currentGroupIsWildcard = true;
                lastLineWasUserAgent = true;
                continue;
            }

            lastLineWasUserAgent = false;
            if (key.equals("disallow") && currentGroupIsWildcard && !value.isEmpty()) {
                disallowed.add(value);
            } else if (key.equals("crawl-delay") && currentGroupIsWildcard) {
                try { delayMs = Long.parseLong(value) * 1000L; } catch (NumberFormatException ignored) {}
            }
        }

        return new RobotsTxtPolicy(delayMs, disallowed);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }
}
