package dev.ua.ikeepcalm.wiic.locale;

import net.kyori.adventure.text.Component;

public class MessageManager {

    public static Component getMessage(String key, String... args) {
        Component[] componentArgs = new Component[args.length];
        for (int i = 0; i < args.length; i++) {
            componentArgs[i] = Component.text(args[i]);
        }
        return getTranslatableComponent(key, componentArgs);
    }

    private static Component getTranslatableComponent(String key, Component... args) {
        TranslationManager.initialize();
        return Component.translatable(key, args);
    }

}