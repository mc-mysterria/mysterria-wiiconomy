package dev.ua.ikeepcalm.wiic.domain.shop.model.source;

import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopCatalog;

/**
 * Top-level catalogue grouping for {@code /shop}. Every included material is
 * assigned exactly one category by {@link ShopCatalog}; the config key used in
 * {@code shop.yml} ({@code shop-gui.categories.<key>}, {@code pricing.category-prices})
 * is the lower-case {@link #name()}.
 */
public enum ShopCategory {
    WOOD,
    STONE,
    COLOR,
    NATURE,
    NETHER_END,
    DECORATION,
    MISC;

    public String configKey() {
        return name().toLowerCase();
    }
}
