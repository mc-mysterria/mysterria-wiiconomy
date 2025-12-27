package dev.ua.ikeepcalm.wiic.locale;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import net.kyori.adventure.util.UTF8ResourceBundleControl;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Function;

public class TranslationManager {

    private static final String NAMESPACE = "wiic";
    private static final Key REGISTRY_KEY = Key.key(NAMESPACE, "translations");
    private static TranslationStore.StringBased<MessageFormat> translationStore;
    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) {
            return;
        }

        try {
            translationStore = TranslationStore.messageFormat(REGISTRY_KEY);

            loadTranslations(Locale.ENGLISH);
            loadTranslations(Locale.forLanguageTag("uk"));

            GlobalTranslator.translator().addSource(translationStore);

            initialized = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadTranslations(Locale locale) {
        try {
            String bundleName = "lang/lang";

            ResourceBundle bundle = ResourceBundle.getBundle(bundleName, locale,
                    WIIC.INSTANCE.getClass().getClassLoader(),
                    UTF8ResourceBundleControl.utf8ResourceBundleControl());

            Set<String> originalKeys = bundle.keySet();
            Set<String> namespacedKeys = new HashSet<>();

            for (String key : originalKeys) {
                namespacedKeys.add(NAMESPACE + ":" + key);
            }

            Function<String, MessageFormat> function = namespacedKey -> {
                String actualKey = namespacedKey.substring(NAMESPACE.length() + 1);
                return new MessageFormat(bundle.getString(actualKey), locale);
            };

            translationStore.registerAll(locale, namespacedKeys, function);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getNamespace() {
        return NAMESPACE;
    }
}