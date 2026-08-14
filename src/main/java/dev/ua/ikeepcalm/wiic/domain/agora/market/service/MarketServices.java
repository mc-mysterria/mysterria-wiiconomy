package dev.ua.ikeepcalm.wiic.domain.agora.market.service;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.ContainmentService;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.CourierService;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.LedgerService;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.ListingService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotService;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotShopService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.PriceGuide;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.StashService;
import org.jetbrains.annotations.Nullable;

/**
 * The Underground Market's collaborators, threaded through every market GUI —
 * the market-side sibling of {@code ShopServices}.
 *
 * <p>{@code courier} is null whenever undead-postmans is absent: callers must treat a
 * missing courier as "purchases wait in the stash", never as an error.
 */
public record MarketServices(
        MarketConfig config,
        MarketDatabase db,
        MarketJournal journal,
        ItemInspector inspector,
        ListingService listings,
        MarketPurchaseService purchases,
        StashService stash,
        LedgerService ledger,
        EntranceService entrances,
        ContainmentService containment,
        PlotService plots,
        PlotShopService shops,
        @Nullable CourierService courier,
        MarketFeedback feedback,
        PriceGuide prices
) {}
