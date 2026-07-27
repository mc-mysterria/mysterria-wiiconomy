package dev.ua.ikeepcalm.wiic.domain.shop.service;

import dev.ua.ikeepcalm.wiic.config.ShopConfig;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopCatalog;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopPricing;

/**
 * Bundles the shop's collaborator services so GUI classes take a single constructor
 * parameter instead of five. Constructed once in {@code WIIC.onEnable} and handed to
 * every {@code gui/shop/*} screen and {@code ShopCommand}.
 */
public record ShopServices(
        ShopConfig config,
        ShopCatalog catalog,
        ShopPricing pricing,
        MarketIndex marketIndex,
        PurchaseService purchaseService
) {}
