package dev.ua.ikeepcalm.wiic.utils.common;

import net.md_5.bungee.api.ChatColor;

public final class Translator {
    public static final String WITH_DELIMITER = "((?<=%1$s)|(?=%1$s))";

    public static String translate(String text) {
        String[] texts = text.split(String.format(WITH_DELIMITER, "&"));
        StringBuilder finalText = new StringBuilder();
        for (int i = 0; i < texts.length; ++i) {
            if (texts[i].equalsIgnoreCase("&")) {
                if (texts[++i].charAt(0) == '#') {
                    finalText.append(ChatColor.of(texts[i].substring(0, 7))).append(texts[i].substring(7));
                    continue;
                }
                finalText.append(ChatColor.translateAlternateColorCodes('&', "&" + texts[i]));
                continue;
            }
            finalText.append(texts[i]);
        }
        return finalText.toString();
    }

    public static String firstUppercase(String text) {
        return text.substring(0, 1).toUpperCase() + text.substring(1).toLowerCase();
    }

    private Translator() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
