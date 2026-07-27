package dev.ua.ikeepcalm.wiic.domain.shop.model;

import dev.ua.ikeepcalm.wiic.domain.shop.service.MarketIndex;
import org.bukkit.Material;

/**
 * Resolves the live per-unit price of a catalogued material: the entry's base price
 * (from {@code shop.yml}, see {@link ShopCatalog}) scaled by the current
 * {@link MarketIndex}. Computing the unit price first and multiplying by the amount
 * everywhere guarantees the total a player is quoted always equals unit price × amount
 * exactly — no rounding surprises between the quantity screen and the confirm screen.
 */
public final class ShopPricing {

    private final ShopCatalog catalog;
    private final MarketIndex marketIndex;

    public ShopPricing(ShopCatalog catalog, MarketIndex marketIndex) {
        this.catalog = catalog;
        this.marketIndex = marketIndex;
    }

    /** Live unit price in coppets, or {@code -1} if the material isn't purchasable. */
    public long unitPrice(Material material) {
        ShopEntry entry = catalog.get(material);
        if (entry == null) return -1;
        double index = marketIndex.currentIndex();
        return Math.max(1, Math.round(Math.ceil(entry.basePrice() * index)));
    }

    /** Live total in coppets for {@code amount} units, or {@code -1} if not purchasable. */
    public long total(Material material, int amount) {
        long unit = unitPrice(material);
        if (unit < 0) return -1;
        return unit * amount;
    }
}
