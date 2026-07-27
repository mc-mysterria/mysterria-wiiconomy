package dev.ua.ikeepcalm.wiic.domain.shop.model;

import dev.ua.ikeepcalm.wiic.domain.shop.model.source.ShopCategory;
import org.bukkit.Material;

/**
 * A single catalogued material: what it is, how it's grouped for browsing, and
 * its base (pre-market-index) unit price in coppets.
 *
 * @param material  the purchasable block material
 * @param category  top-level browsing category
 * @param family    the variant axis within the category (wood species, dye color,
 *                  stone type, …), or {@code "misc"} if no finer grouping applies
 * @param basePrice unit price in coppets before the market index is applied
 */
public record ShopEntry(Material material, ShopCategory category, String family, int basePrice) {

    public String displayName() {
        String name = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
