package dev.ua.ikeepcalm.wiic.api;

import org.bukkit.Material;

import java.util.OptionalDouble;

/**
 * Read-only access to the prices WIIC currently quotes. Registered with Bukkit's
 * {@code ServicesManager} while WIIC is enabled, so other plugins need no compile
 * dependency on WIIC to look a price up.
 *
 * <p>The price is WIIC's live {@code /shop} unit price for a material: its base price from
 * {@code shop.yml} scaled by the server's current market index. That index changes over
 * time, so callers should read the price when they need it rather than cache it.
 */
public interface MarketPrices {

    /**
     * The current unit price of one {@code material}, in coppets. Values are whole numbers,
     * even though the return type is {@code OptionalDouble} to match other market hooks.
     * Safe to call from any thread.
     *
     * @param material a Bukkit material
     * @return the live unit price, or empty when WIIC does not sell this material
     */
    OptionalDouble price(Material material);
}
