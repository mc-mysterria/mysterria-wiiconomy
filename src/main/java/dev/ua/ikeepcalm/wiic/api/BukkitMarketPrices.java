package dev.ua.ikeepcalm.wiic.api;

import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopPricing;
import org.bukkit.Material;

import java.util.OptionalDouble;

/** Bukkit service implementation of {@link MarketPrices}, registered in {@code onEnable}. */
public final class BukkitMarketPrices implements MarketPrices {

    private final ShopPricing pricing;

    public BukkitMarketPrices(ShopPricing pricing) {
        this.pricing = pricing;
    }

    @Override
    public OptionalDouble price(Material material) {
        if (material == null) return OptionalDouble.empty();
        long unit = pricing.unitPrice(material);
        return unit < 0 ? OptionalDouble.empty() : OptionalDouble.of(unit);
    }
}
