package com.mangaverse.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalização de títulos usada para deduplicação e comparação. */
public final class TextNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private TextNormalizer() {
    }

    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String n = Normalizer.normalize(title, Normalizer.Form.NFKD);
        n = DIACRITICS.matcher(n).replaceAll("");
        n = n.toLowerCase(Locale.ROOT).replace("&", " and ");
        n = NON_ALNUM.matcher(n).replaceAll(" ").trim();
        if (n.startsWith("the ")) {
            n = n.substring(4);
        }
        return n.replaceAll("\\s+", " ");
    }

    /** Similaridade simples (0..1) baseada em tokens, usada para ordenar resultados. */
    public static double similarity(String query, String title) {
        String q = normalizeTitle(query);
        String t = normalizeTitle(title);
        if (q.isEmpty() || t.isEmpty()) {
            return 0;
        }
        if (q.equals(t)) {
            return 1.0;
        }
        if (t.startsWith(q)) {
            return 0.9;
        }
        String[] qTokens = q.split(" ");
        int hits = 0;
        for (String token : qTokens) {
            if ((" " + t + " ").contains(" " + token + " ")) {
                hits++;
            }
        }
        double tokenScore = (double) hits / qTokens.length;
        double lengthPenalty = Math.min(1.0, (double) q.length() / t.length());
        return 0.75 * tokenScore + 0.15 * lengthPenalty;
    }

    public static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        String text = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return text.replaceAll("\n{3,}", "\n\n").trim();
    }
}
