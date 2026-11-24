package io.alithes.oracle.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitizes candidate GDELT DOC 2.0 search queries to conform to GDELT syntax rules.
 */
public final class GdeltQuerySanitizer {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^A-Za-z0-9\\s\"():\\-]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final List<String> GDELT_FILTER_PREFIXES = List.of(
            "domain:",
            "sourcelang:",
            "sourcecountry:",
            "theme:"
    );
    private static final Set<String> BOOLEAN_OPERATORS = Set.of("OR", "AND");

    private GdeltQuerySanitizer() {
    }

    /**
     * Sanitizes a raw query string according to GDELT DOC 2.0 requirements.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("GDELT query must not be null or blank");
        }

        // 1. Normalize smart quotes and strip unsupported characters.
        String cleaned = raw.replace('\u201C', '"').replace('\u201D', '"');
        cleaned = DISALLOWED_CHARS.matcher(cleaned).replaceAll("");

        // 2. Collapse whitespace and trim.
        cleaned = MULTI_SPACE.matcher(cleaned).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("GDELT query became blank after sanitization");
        }

        // 3. Tokenize preserving quoted phrases and parentheses; drop short unquoted tokens.
        List<String> tokens = tokenize(cleaned);
        List<String> filtered = new ArrayList<>();
        for (String token : tokens) {
            if (isQuoted(token) || "(".equals(token) || ")".equals(token)) {
                if (!"\"\"".equals(token)) {
                    filtered.add(token);
                }
                continue;
            }
            if (BOOLEAN_OPERATORS.contains(token) || isGdeltFilter(token) || token.length() >= 3) {
                filtered.add(token);
            }
        }

        List<String> normalized = cleanupOperatorsAndEmptyParens(filtered);

        // 4. Wrap unparenthesized top-level OR clauses in (...).
        List<String> wrapped = wrapTopLevelOrClauses(normalized);
        String result = renderTokens(wrapped);

        // 5. Truncate safely to 200 characters without leaving unmatched quotes or parens.
        result = truncateSafely(result, MAX_QUERY_LENGTH);

        // 6. Reject blank or too-short queries.
        if (result.isBlank() || result.length() < 3) {
            throw new IllegalArgumentException("Sanitized GDELT query is too short or blank: '" + result + "'");
        }
        return result;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        int n = input.length();
        int i = 0;
        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                i++;
                continue;
            }
            if (c == '"') {
                int end = input.indexOf('"', i + 1);
                if (end == -1) {
                    // Unmatched opening quote: skip the stray quote character and tokenize the rest.
                    i++;
                } else {
                    String quoted = input.substring(i, end + 1).trim();
                    if (quoted.length() > 2) {
                        tokens.add(quoted);
                    }
                    i = end + 1;
                }
                continue;
            }
            int start = i;
            while (i < n) {
                char ch = input.charAt(i);
                if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == '"') {
                    break;
                }
                i++;
            }
            tokens.add(input.substring(start, i));
        }
        return tokens;
    }

    private static boolean isQuoted(String token) {
        return token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"");
    }

    private static boolean isGdeltFilter(String token) {
        for (String prefix : GDELT_FILTER_PREFIXES) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> cleanupOperatorsAndEmptyParens(List<String> tokens) {
        List<String> current = new ArrayList<>(tokens);
        boolean changed = true;
        while (changed) {
            changed = false;
            List<String> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i++) {
                String tok = current.get(i);
                if (")".equals(tok) && !next.isEmpty() && "(".equals(next.get(next.size() - 1))) {
                    next.remove(next.size() - 1);
                    changed = true;
                    continue;
                }
                if (BOOLEAN_OPERATORS.contains(tok)) {
                    boolean noLeft = next.isEmpty() || "(".equals(next.get(next.size() - 1))
                            || BOOLEAN_OPERATORS.contains(next.get(next.size() - 1));
                    boolean noRight = (i + 1 >= current.size()) || ")".equals(current.get(i + 1));
                    if (noLeft || noRight) {
                        changed = true;
                        continue;
                    }
                }
                next.add(tok);
            }
            // Drop any unmatched parentheses so depth tracking stays consistent.
            current = balanceParenthesesTokens(next);
            if (!current.equals(next)) {
                changed = true;
            }
        }
        return current;
    }

    private static List<String> balanceParenthesesTokens(List<String> tokens) {
        int balance = 0;
        List<Integer> openIndices = new ArrayList<>();
        boolean[] keep = new boolean[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            if ("(".equals(tok)) {
                balance++;
                openIndices.add(i);
                keep[i] = true;
            } else if (")".equals(tok)) {
                if (balance > 0) {
                    balance--;
                    openIndices.remove(openIndices.size() - 1);
                    keep[i] = true;
                }
            } else {
                keep[i] = true;
            }
        }
        for (int unmatchedOpenIdx : openIndices) {
            keep[unmatchedOpenIdx] = false;
        }
        List<String> out = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            if (keep[i]) {
                out.add(tokens.get(i));
            }
        }
        return out;
    }

    /**
     * Groups top-level terms into logical units and wraps any contiguous {@code OR} chain in parentheses.
     */
    private static List<String> wrapTopLevelOrClauses(List<String> tokens) {
        // Parse into top-level units (a parenthesized group is one unit; every other token is one unit).
        List<List<String>> units = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String tok = tokens.get(i);
            if ("(".equals(tok)) {
                int depth = 1;
                int start = i;
                i++;
                while (i < tokens.size() && depth > 0) {
                    if ("(".equals(tokens.get(i))) {
                        depth++;
                    } else if (")".equals(tokens.get(i))) {
                        depth--;
                    }
                    i++;
                }
                units.add(tokens.subList(start, i));
            } else {
                units.add(List.of(tok));
                i++;
            }
        }

        List<String> out = new ArrayList<>();
        int u = 0;
        while (u < units.size()) {
            boolean startsOrChain = u + 2 < units.size()
                    && units.get(u + 1).size() == 1
                    && "OR".equals(units.get(u + 1).get(0));
            if (startsOrChain) {
                out.add("(");
                out.addAll(units.get(u));
                u++;
                while (u + 1 < units.size()
                        && units.get(u).size() == 1
                        && "OR".equals(units.get(u).get(0))) {
                    out.add("OR");
                    out.addAll(units.get(u + 1));
                    u += 2;
                }
                out.add(")");
            } else {
                out.addAll(units.get(u));
                u++;
            }
        }
        return out;
    }

    private static String renderTokens(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            if (i > 0) {
                String prev = tokens.get(i - 1);
                if (!"(".equals(prev) && !")".equals(tok)) {
                    sb.append(' ');
                }
            }
            sb.append(tok);
        }
        return sb.toString().trim();
    }

    private static String truncateSafely(String query, int maxLen) {
        if (query.length() <= maxLen) {
            return query;
        }

        // First slice to maxLen, backing up to a token boundary if cut mid-word.
        String cut = query.substring(0, maxLen);
        if (!Character.isWhitespace(query.charAt(maxLen)) && query.charAt(maxLen) != ')') {
            int lastSpace = cut.lastIndexOf(' ');
            if (lastSpace > 0) {
                cut = cut.substring(0, lastSpace);
            }
        }
        cut = cut.trim();

        // Ensure quotes are balanced; if an opening quote was cut off mid-phrase, close it safely.
        cut = closeOrDropUnmatchedQuote(cut, maxLen);

        // Trim dangling operators and balance any unclosed parentheses within maxLen.
        cut = stripTrailingDanglingTokens(cut);
        int openCount = countUnclosedParens(cut);
        if (openCount > 0) {
            int budget = maxLen - openCount;
            if (budget <= 0) {
                return "";
            }
            if (cut.length() > budget) {
                cut = cut.substring(0, budget);
                int lastSpace = cut.lastIndexOf(' ');
                if (lastSpace > 0) {
                    cut = cut.substring(0, lastSpace);
                }
                cut = cut.trim();
                cut = closeOrDropUnmatchedQuote(cut, budget);
                cut = stripTrailingDanglingTokens(cut);
                openCount = countUnclosedParens(cut);
            }
            if (openCount > 0 && cut.length() + openCount <= maxLen) {
                cut = cut + ")".repeat(openCount);
            }
        }

        // Clean any empty "()" that may result after closing.
        cut = cut.replaceAll("\\(\\s*\\)", "").replaceAll("\\s+", " ").trim();
        return cut;
    }

    private static String closeOrDropUnmatchedQuote(String s, int maxLen) {
        int quoteCount = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') {
                quoteCount++;
            }
        }
        if (quoteCount % 2 == 0) {
            return s;
        }
        int lastQuote = s.lastIndexOf('"');
        if (lastQuote == s.length() - 1 || s.substring(lastQuote + 1).isBlank()) {
            return s.substring(0, lastQuote).trim();
        }
        if (s.length() + 1 <= maxLen) {
            return s + '"';
        }
        int lastSpace = s.lastIndexOf(' ');
        if (lastSpace > lastQuote) {
            return s.substring(0, lastSpace).trim() + '"';
        }
        return s.substring(0, lastQuote).trim();
    }

    private static String stripTrailingDanglingTokens(String s) {
        String current = s.trim();
        boolean changed = true;
        while (changed && !current.isEmpty()) {
            changed = false;
            if (current.endsWith(" OR")) {
                current = current.substring(0, current.length() - 3).trim();
                changed = true;
            } else if (current.endsWith(" AND")) {
                current = current.substring(0, current.length() - 4).trim();
                changed = true;
            } else if (current.equals("OR") || current.equals("AND")) {
                current = "";
                changed = true;
            } else if (current.endsWith("(")) {
                current = current.substring(0, current.length() - 1).trim();
                changed = true;
            }
        }
        return current;
    }

    private static int countUnclosedParens(String s) {
        int depth = 0;
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                }
            }
        }
        return depth;
    }
}
