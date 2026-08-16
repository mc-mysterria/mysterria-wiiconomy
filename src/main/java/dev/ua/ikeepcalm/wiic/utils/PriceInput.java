package dev.ua.ikeepcalm.wiic.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a price a player typed, in whatever notation they think in.
 *
 * <p>Prices are stored in coppets, but nobody holding a Sequence 4 characteristic thinks
 * in coppets — they think in verldors, the way you think in notes rather than pennies. So
 * both work, and so does mixing them:
 *
 * <pre>
 *   1200        1200 coppets
 *   3v          3 verldors            = 12288
 *   3v 10l      3 verldors, 10 licks  = 12928
 *   2l30c       2 licks, 30 coppets   = 158
 *   1.5v        one and a half verldors
 *   2k          2000 coppets
 * </pre>
 *
 * <p>Ukrainian initials are accepted alongside the Latin ones ({@code в}/{@code л}/{@code к}),
 * because the market speaks Ukrainian and a player typing on a Ukrainian layout should not
 * have to switch it to name a price.
 */
public final class PriceInput {

    /**
     * 64 coppets to a lick, 64 licks to a verldor — the same ratios the wallet uses.
     */
    private static final long COPPETS_PER_LICK = 64;
    private static final long COPPETS_PER_VERLDOR = 64 * 64;

    /**
     * One number with an optional unit initial; the string is consumed as a run of these.
     */
    private static final Pattern TERM = Pattern.compile(
            "\\s*(\\d+(?:[.,]\\d+)?)\\s*([a-zA-Zа-яА-ЯіІ]*)");

    private PriceInput() {
    }

    /**
     * Parses {@code text} into coppets, or returns {@code -1} when it is not a price. A
     * bare number means coppets, so the plainest possible input still works.
     */
    public static long parse(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return -1;

        Matcher matcher = TERM.matcher(trimmed);
        double total = 0;
        int consumed = 0;
        boolean matchedAnything = false;

        while (matcher.find() && matcher.start() == consumed) {
            matchedAnything = true;
            consumed = matcher.end();
            double amount;
            try {
                amount = Double.parseDouble(matcher.group(1).replace(',', '.'));
            } catch (NumberFormatException e) {
                return -1;
            }
            Long unit = unitOf(matcher.group(2));
            if (unit == null) return -1;
            total += amount * unit;
        }
        // Anything left over is a typo, not a unit we failed to think of: refuse rather
        // than silently pricing goods off the half of the input we understood.
        if (!matchedAnything || consumed != trimmed.length()) return -1;
        if (total < 0 || total > CEILING) return -1;
        return Math.round(total);
    }

    /**
     * Far above any sane {@code listings.max-price}, and low enough that the caller's own
     * clamp arithmetic cannot overflow on the way to rejecting it.
     */
    private static final long CEILING = 1_000_000_000L;

    /**
     * Coppets per unit for a suffix, or {@code null} when the suffix is not one.
     */
    private static Long unitOf(String suffix) {
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "", "c", "co", "cop", "coppet", "coppets", "к", "коп" -> 1L;
            case "l", "li", "lick", "licks", "л", "лік", "ліки" -> COPPETS_PER_LICK;
            case "v", "vd", "verldor", "verldors", "в", "верл" -> COPPETS_PER_VERLDOR;
            // A thousand coppets, for anyone who would rather not count zeroes. Latin `k`
            // only — Cyrillic `к` is the coppet initial and is spoken for above.
            case "k" -> 1000L;
            default -> null;
        };
    }
}
