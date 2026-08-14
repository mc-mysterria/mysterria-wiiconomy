package dev.ua.ikeepcalm.wiic.domain.agora.utils.coi;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ItemType;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.ItemSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Builds the denormalized {@link ItemSnapshot} for a listing and enforces the
 * listing deny-list.
 *
 * <p>CircleOfImagination items are identified by reading their PDC tags raw
 * ({@code circleofimagination:pathway} STRING / {@code sequence} INT) — the same
 * cross-plugin convention CoI itself uses to recognize WIIC coins by
 * {@code wiic:type}. No CoI classes are referenced, so this works (and degrades
 * to "not a beyonder item") whether or not CoI is installed.
 */
public class ItemInspector {

    public static final String CATEGORY_BEYONDER = "beyonder";
    public static final String CATEGORY_MISC = "misc";

    private static final String COI_NAMESPACE = "circleofimagination";

    private final MarketConfig config;

    public ItemInspector(MarketConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Deny-list
    // -------------------------------------------------------------------------

    /** Returns a denial reason message key, or null when the item may be listed. */
    public @Nullable String checkDenied(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return "listing-denied-invalid";
        if (config.isMaterialDenied(item.getType().name())) return "listing-denied-material";
        if (!config.allowContainers() && isContainer(item)) return "listing-denied-container";
        if (item.hasItemMeta()) {
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            for (String rule : config.denyPdcKeys()) {
                NamespacedKey key = NamespacedKey.fromString(rule);
                // has(key) is type-agnostic; getKeys() would materialise the whole key set.
                if (key != null && pdc.has(key)) return "listing-denied-tagged";
            }
        }
        return null;
    }

    private static boolean isContainer(ItemStack item) {
        String name = item.getType().name();
        if (name.endsWith("SHULKER_BOX") || name.equals("BUNDLE") || name.endsWith("_BUNDLE")) return true;
        return item.hasItemMeta() && item.getItemMeta() instanceof BlockStateMeta meta
                && meta.hasBlockState() && meta.getBlockState() instanceof org.bukkit.block.Container;
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    public ItemSnapshot snapshot(ItemStack item) {
        String displayName = null;
        if (item.hasItemMeta()) {
            Component name = item.getItemMeta().displayName();
            if (name != null) displayName = PlainTextComponentSerializer.plainText().serialize(name);
        }
        CoiFacts facts = inspect(item);
        String category = facts.isCoi() ? CATEGORY_BEYONDER : classify(item.getType());
        // declaredSequence, not servedSequence: the column feeds the Informant's
        // "show me Sequence 5 goods" search, which must mean what CoI stamped on the
        // item, never a sequence WIIC worked out for itself.
        return new ItemSnapshot(item.getType(), item.getAmount(), displayName, category,
                facts.isCoi(), facts.pathway(), facts.declaredSequence(), facts.valueKey());
    }

    // -------------------------------------------------------------------------
    // CoI identification
    // -------------------------------------------------------------------------

    /**
     * Reads every CircleOfImagination fact off {@code item} in one pass.
     *
     * <p>All of it is raw PDC on the {@code circleofimagination} namespace — the same
     * cross-plugin convention CoI uses to recognise WIIC coins by {@code wiic:type}.
     * With CoI absent the tags simply aren't there and everything degrades to
     * {@link ItemType#NONE}.
     */
    public CoiFacts inspect(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return CoiFacts.none(materialKey(item));
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();

        String pathway = pdc.get(key("pathway"), PersistentDataType.STRING);
        Integer sequence = pdc.get(key("sequence"), PersistentDataType.INTEGER);

        String ingredient = pdc.get(key("ingredient"), PersistentDataType.STRING);
        if (ingredient != null) {
            Integer served = config.ingredientSequence(ingredient);
            return new CoiFacts(ItemType.INGREDIENT, pathway, sequence, served, ingredient, null,
                    "coi:ingredient:" + ingredient);
        }

        if (Boolean.TRUE.equals(pdc.get(key("artifact"), PersistentDataType.BOOLEAN))) {
            String id = pdc.get(key("id"), PersistentDataType.STRING);
            Integer level = artifactLevel(id);
            return new CoiFacts(ItemType.ARTIFACT, pathway, sequence, sequence, null, level,
                    "coi:artifact:" + (level != null ? level : "?"));
        }

        String imbuedPathway = pdc.get(key("imbued_pathway"), PersistentDataType.STRING);
        Integer imbuedSequence = pdc.get(key("imbued_sequence"), PersistentDataType.INTEGER);
        if (imbuedPathway != null || imbuedSequence != null) {
            return new CoiFacts(ItemType.IMBUED, imbuedPathway, sequence, imbuedSequence, null, null,
                    "coi:imbued:" + item.getType().name() + ":" + imbuedSequence);
        }

        if (pathway == null && sequence == null) return CoiFacts.none(materialKey(item));

        // Everything below carries pathway + sequence; the material says which of the
        // three it is. CoI builds potions as POTION, characteristics as PLAYER_HEAD and
        // formulae as written books.
        ItemType kind;
        if (pdc.has(key("sequencePotion")) || item.getType() == Material.POTION
                || item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION) {
            kind = ItemType.POTION;
        } else if (item.getType() == Material.PLAYER_HEAD || item.getType() == Material.PLAYER_WALL_HEAD) {
            kind = ItemType.CHARACTERISTIC;
        } else if (item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.BOOK
                || item.getType() == Material.WRITABLE_BOOK || item.getType() == Material.KNOWLEDGE_BOOK) {
            kind = ItemType.FORMULA;
        } else {
            kind = ItemType.OTHER;
        }
        return new CoiFacts(kind, pathway, sequence, sequence, null, null,
                "coi:" + kind.name().toLowerCase(Locale.ROOT) + ":" + pathway + ":" + sequence);
    }

    /** CoI stores artifact ids as {@code "<level>-<name>"}; level 0 is a Sealed Artifact. */
    private static @Nullable Integer artifactLevel(@Nullable String id) {
        if (id == null) return null;
        int dash = id.indexOf('-');
        if (dash <= 0) return null;
        try {
            return Integer.parseInt(id.substring(0, dash));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey(COI_NAMESPACE, name);
    }

    /** Value identity for ordinary goods: the material alone. */
    private static String materialKey(@Nullable ItemStack item) {
        return item == null ? "air" : "mc:" + item.getType().name();
    }

    /**
     * First matching category in {@code market.yml categories} wins. Rules per
     * category: {@code materials} (exact names or {@code regex:} entries),
     * {@code fallback-if-block}, {@code fallback}.
     */
    private String classify(Material material) {
        ConfigurationSection categories = config.categories();
        if (categories == null) return CATEGORY_MISC;

        String fallbackBlock = null;
        String fallback = null;
        for (String id : categories.getKeys(false)) {
            ConfigurationSection section = categories.getConfigurationSection(id);
            if (section == null) continue;
            if (section.getBoolean("fallback-if-block", false) && fallbackBlock == null) fallbackBlock = id;
            if (section.getBoolean("fallback", false) && fallback == null) fallback = id;

            if (config.matchesAny(section.getStringList("materials"), material.name())) return id;
        }
        if (material.isBlock() && fallbackBlock != null) return fallbackBlock;
        return fallback != null ? fallback : CATEGORY_MISC;
    }
}
