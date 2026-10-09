package dev.ua.ikeepcalm.wiic.domain.agora.ledger.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.AuditSampler;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Hands stash rows (purchases, expired/cancelled listings) to their owner at the
 * Banker NPC.
 *
 * <p>Claim ordering is mark-then-give: rows are CAS-claimed in the DB first, then
 * the items are deserialized and added to the inventory on the main thread. The
 * house rule (see {@code PurchaseService}) is that the failure direction must
 * never be a dupe — a crash in the one-tick window between commit and hand-over
 * loses the batch (recoverable from the audit trail by staff) rather than ever
 * doubling it. Rows that don't fit in the inventory are reverted to unclaimed.
 */
public class StashService {

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final int BATCH_LIMIT = 36;

    private final WIIC plugin;
    private final MarketDatabase db;

    /** Click-level claim refusals: one row per player and reason per 5 s. */
    private static final AuditSampler PREFLIGHT_AUDIT =
            new AuditSampler();

    public StashService(WIIC plugin, MarketDatabase db) {
        this.plugin = plugin;
        this.db = db;
    }

    /**
     * Puts {@code item} on the owner's stash shelf. The last resort for goods that could
     * not be handed over directly — a seller who logged out mid-flow, say — so that the
     * failure to reach them never becomes a failure to keep them.
     */
    public void deposit(UUID owner, ItemStack item, String source, String ref, Consumer<Boolean> callback) {
        String safeSource = source == null ? "unknown" : source;
        StashItem row = new StashItem(UUID.randomUUID(), owner, item.serializeAsBytes(),
                item.getType(), item.getAmount(), null, safeSource, ref, System.currentTimeMillis());
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("stash-item", row.id());
        db.transactionThenMain(conn -> {
            StashDao.insert(conn, row);
            return true;
        }, stored -> {
            if (stored) MysterriaAuditBridge.emit("stash.deposited", true, owner, owner, row.id(), identity,
                    safeSource, MysterriaAuditBridge.metadata(Map.of("source", safeSource,
                                    "reference", ref == null ? "" : ref), MysterriaAuditBridge.itemMetadata(item)));
            callback.accept(stored);
        }, error -> {
            plugin.getLogger().severe("Failed to stash undeliverable " + item.getType()
                    + " x" + item.getAmount() + " for " + owner + ": " + error);
            MysterriaAuditBridge.emit("stash.deposit_failed", false, owner, owner, row.id(), identity,
                    safeSource, MysterriaAuditBridge.metadata(
                            Map.of("source", safeSource, "reference", ref == null ? "" : ref),
                            MysterriaAuditBridge.itemMetadata(item)));
            callback.accept(false);
        });
    }

    public void listUnclaimed(Player owner, Consumer<List<StashItem>> callback) {
        db.submitThenMain(conn -> StashDao.unclaimedByOwner(conn, owner.getUniqueId(), BATCH_LIMIT),
                callback, error -> {
                    plugin.getLogger().severe("Stash query failed for " + owner.getName() + ": " + error);
                    callback.accept(List.of());
                });
    }

    /**
     * Claims up to {@code ids.size()} rows into the owner's inventory.
     * Callback receives (delivered count, remaining unclaimed estimate).
     */
    public void claim(Player owner, List<UUID> ids, BiConsumer<Integer, Integer> callback) {
        UUID uuid = owner.getUniqueId();
        // Immutable snapshot taken before the lock: the caller's list may be mutable or hold
        // nulls, and the async membership check below must see exactly this selection.
        List<UUID> requestedIds = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull)
                .limit(BATCH_LIMIT)
                .toList();
        List<String> requestedIdValues = requestedIds.stream()
                .map(UUID::toString)
                .toList();
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.randomIdentity("stash-claim");
        if (!IN_FLIGHT.add(uuid)) {
            if (PREFLIGHT_AUDIT.shouldEmit(List.of(uuid, "stash claim already in progress"))) {
                MysterriaAuditBridge.emit("stash.claimed", false, uuid, uuid, null, identity,
                        "stash claim already in progress", Map.of("delivered", 0,
                                "remaining", -1, "requested_ids", requestedIdValues));
            }
            callback.accept(0, -1);
            return;
        }

        int freeSlots = countFreeSlots(owner);
        if (freeSlots <= 0) {
            IN_FLIGHT.remove(uuid);
            if (PREFLIGHT_AUDIT.shouldEmit(List.of(uuid, "stash claim rejected: no free slots"))) {
                MysterriaAuditBridge.emit("stash.claimed", false, uuid, uuid, null, identity,
                        "stash claim rejected: no free slots", Map.of("delivered", 0,
                                "remaining", -1, "requested_ids", requestedIdValues));
            }
            callback.accept(0, -1);
            return;
        }
        int budget = Math.min(freeSlots, requestedIds.size());

        db.transactionThenMain(conn -> {
            List<StashItem> claimed = new ArrayList<>();
            long now = System.currentTimeMillis();
            List<StashItem> rows = StashDao.unclaimedByOwner(conn, uuid, BATCH_LIMIT);
            for (StashItem row : rows) {
                if (claimed.size() >= budget) break;
                if (!requestedIds.contains(row.id())) continue;
                if (StashDao.markClaimed(conn, row.id(), now)) {
                    claimed.add(row);
                    TransactionDao.log(conn, "CLAIM_STASH", uuid, null, null, 0,
                            row.material().name() + " x" + row.amount() + " (" + row.source() + ")");
                }
            }
            return claimed;
        }, claimed -> {
            int delivered = 0;
            List<UUID> deliveredIds = new ArrayList<>();
            List<UUID> undeliverable = new ArrayList<>();
            for (StashItem row : claimed) {
                if (!owner.isOnline()) {
                    undeliverable.add(row.id());
                    continue;
                }
                ItemStack item;
                try {
                    item = ItemStack.deserializeBytes(row.itemBytes());
                } catch (Exception e) {
                    plugin.getLogger().severe("Corrupt stash item " + row.id() + " for " + owner.getName() + ": " + e);
                    undeliverable.add(row.id());
                    continue;
                }
                if (ItemInspector.containsTemporaryItem(item)) {
                    plugin.getLogger().warning("Withheld temporary stash item " + row.id() + " for " + owner.getUniqueId());
                    // Retain the original row for review; do not silently burn paid goods.
                    undeliverable.add(row.id());
                    continue;
                }
                if (owner.getInventory().firstEmpty() == -1) {
                    undeliverable.add(row.id());
                    continue;
                }
                // A blob can hold more than one slot's worth (an over-stacked stack written by
                // another plugin), so the leftover is dropped rather than silently discarded —
                // the row is already marked claimed and can't be handed out twice.
                for (ItemStack leftover : owner.getInventory().addItem(item).values()) {
                    owner.getWorld().dropItem(owner.getLocation(), leftover);
                }
                delivered++;
                deliveredIds.add(row.id());
                TransactionLogger.logNote(owner, "MARKET STASH claim " + row.material().name() + " x" + row.amount());
            }
            if (!undeliverable.isEmpty()) {
                // These rows are marked claimed but nothing was handed over. If the revert
                // never lands the goods are stranded, so a failure here has to be loud and
                // name every row a human would need to restore by hand.
                db.submit(conn -> {
                    for (UUID id : undeliverable) StashDao.revertClaim(conn, id);
                    return null;
                }).exceptionally(error -> {
                    plugin.getLogger().severe("Failed to release undelivered stash rows for "
                            + owner.getName() + " (" + uuid + "): " + error
                            + " — rows stranded as claimed: " + undeliverable);
                    return null;
                });
            }
            int finalDelivered = delivered;
            List<String> deliveredIdValues = deliveredIds.stream().map(UUID::toString).toList();
            List<String> restashedIdValues = undeliverable.stream().map(UUID::toString).toList();
            db.submitThenMain(conn -> StashDao.countUnclaimed(conn, uuid),
                    remaining -> {
                        IN_FLIGHT.remove(uuid);
                        MysterriaAuditBridge.emit("stash.claimed", finalDelivered > 0, uuid, uuid, null, identity,
                                "stash claim", Map.of("delivered", finalDelivered, "remaining", remaining,
                                        "claimed_ids", deliveredIdValues,
                                        "restashed_ids", restashedIdValues));
                        callback.accept(finalDelivered, remaining);
                    },
                    error -> {
                        IN_FLIGHT.remove(uuid);
                        MysterriaAuditBridge.emit("stash.claimed", finalDelivered > 0, uuid, uuid, null, identity,
                                "stash claim count failed", Map.of("delivered", finalDelivered, "remaining", -1,
                                        "claimed_ids", deliveredIdValues,
                                        "restashed_ids", restashedIdValues));
                        callback.accept(finalDelivered, -1);
                    });
        }, error -> {
            IN_FLIGHT.remove(uuid);
            plugin.getLogger().severe("Stash claim failed for " + owner.getName() + ": " + error);
            MysterriaAuditBridge.emit("stash.claimed", false, uuid, uuid, null, identity,
                    "stash claim failed", Map.of());
            callback.accept(0, -1);
        });
    }

    private static int countFreeSlots(Player player) {
        int free = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) free++;
        }
        return free;
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        PREFLIGHT_AUDIT.clear();
        IN_FLIGHT.clear();
    }

}
