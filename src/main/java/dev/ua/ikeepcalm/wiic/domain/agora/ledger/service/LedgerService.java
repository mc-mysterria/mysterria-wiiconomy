package dev.ua.ikeepcalm.wiic.domain.agora.ledger.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.agora.db.LedgerDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sale-proceeds ledger. Proceeds accumulate as UNCLAIMED entries when listings
 * sell; the seller claims the total at the Banker NPC (physical-presence rule —
 * nothing auto-deposits).
 *
 * <p>Claim protocol (see {@code LedgerDao}): rows flip to CLAIMING and the sum is
 * journaled before the Vault deposit; a {@code CLAIM_DEPOSITED} marker is written
 * immediately after the deposit succeeds and before the rows flip to CLAIMED.
 * Startup recovery reverts CLAIMING rows without the marker (deposit can't be
 * proven — the player just re-claims) and completes those with it.
 */
public class LedgerService {

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /**
     * Entries the Ledger screen has room for.
     */
    private static final int PAGE_SIZE = 36;

    private final WIIC plugin;
    private final MarketDatabase db;
    private final MarketJournal journal;

    public LedgerService(WIIC plugin, MarketDatabase db, MarketJournal journal) {
        this.plugin = plugin;
        this.db = db;
        this.journal = journal;
    }

    /**
     * The page of entries the Ledger screen shows, and the <b>full</b> unclaimed total.
     * The two are read separately on purpose: a busy seller can have far more than a page
     * of sales, and summing only what fits on screen would quote them less than the claim
     * button actually pays out.
     */
    public record Summary(List<LedgerEntry> entries, long total) {
    }

    public void summary(Player owner, Consumer<Summary> callback) {
        db.submitThenMain(conn -> new Summary(
                        LedgerDao.unclaimedEntries(conn, owner.getUniqueId(), PAGE_SIZE),
                        LedgerDao.sumUnclaimed(conn, owner.getUniqueId())),
                callback, error -> {
                    plugin.getLogger().severe("Ledger query failed for " + owner.getName() + ": " + error);
                    callback.accept(new Summary(List.of(), 0));
                });
    }

    /**
     * Claims all unclaimed proceeds. Callback receives (success, amount deposited).
     */
    public void claim(Player owner, BiConsumer<Boolean, Long> callback) {
        UUID uuid = owner.getUniqueId();
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(false, 0L);
            return;
        }

        String batchId = UUID.randomUUID().toString();
        db.transactionThenMain(conn -> LedgerDao.beginClaim(conn, uuid), sum -> {
            if (sum <= 0) {
                IN_FLIGHT.remove(uuid);
                callback.accept(true, 0L);
                return;
            }
            try {
                journal.append(MarketJournal.Type.CLAIM, batchId, uuid, sum, null);
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal unavailable, aborting ledger claim: " + e.getMessage());
                revert(uuid, () -> {
                    IN_FLIGHT.remove(uuid);
                    callback.accept(false, 0L);
                });
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean deposited = VaultUtil.deposit(uuid, sum);
                if (!deposited) {
                    TransactionLogger.logNote(owner, "MARKET LEDGER claim deposit of " + sum + " coppets FAILED");
                    journal.remove(batchId);
                    revert(uuid, () -> {
                        IN_FLIGHT.remove(uuid);
                        callback.accept(false, 0L);
                    });
                    return;
                }
                // Marker first: recovery must be able to prove the deposit happened.
                try {
                    journal.append(MarketJournal.Type.CLAIM_DEPOSITED, batchId, uuid, sum, null);
                } catch (IllegalStateException e) {
                    // The money is already in their hands and we cannot prove it. Recovery
                    // treats an unproven CLAIM as "never paid" and hands the rows back as
                    // UNCLAIMED, which would pay this batch out a second time — and the two
                    // failures that lead here are correlated (a full disk fails the marker
                    // write and the commit below alike). Dropping the intent entry instead
                    // leaves recovery with nothing to act on: the rows stay CLAIMING, which
                    // is wrong-but-harmless, rather than reverting into a double payout.
                    plugin.getLogger().severe("Market journal marker write failed after ledger deposit: " + e.getMessage());
                    if (!journal.remove(batchId)) {
                        plugin.getLogger().severe("CRITICAL: ledger claim of " + sum + " coppets for " + uuid
                                + " was deposited but is neither proven nor retractable in the journal."
                                + " Delete the CLAIM entry for batch " + batchId + " from market-journal.dat"
                                + " before restarting, or the claim will be paid out twice.");
                    }
                }
                db.transactionThenMain(conn -> {
                    LedgerDao.finishClaim(conn, uuid, System.currentTimeMillis());
                    TransactionDao.log(conn, "CLAIM_PROCEEDS", uuid, null, null, sum, null);
                    return null;
                }, done -> {
                    journal.remove(batchId);
                    TransactionLogger.logNote(owner, "MARKET LEDGER claimed " + sum + " coppets");
                    IN_FLIGHT.remove(uuid);
                    callback.accept(true, sum);
                }, error -> {
                    // Deposit landed but the CLAIMED flip failed — recovery replays it from the journal.
                    plugin.getLogger().severe("Ledger finishClaim failed for " + owner.getName()
                            + " (journal will complete on restart): " + error);
                    IN_FLIGHT.remove(uuid);
                    callback.accept(true, sum);
                });
            });
        }, error -> {
            IN_FLIGHT.remove(uuid);
            plugin.getLogger().severe("Ledger beginClaim failed for " + owner.getName() + ": " + error);
            callback.accept(false, 0L);
        });
    }

    private void revert(UUID uuid, Runnable then) {
        db.transactionThenMain(conn -> {
            LedgerDao.revertClaim(conn, uuid);
            return null;
        }, ignored -> then.run(), error -> {
            plugin.getLogger().severe("Ledger revertClaim failed for " + uuid + ": " + error);
            then.run();
        });
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        IN_FLIGHT.clear();
    }

}
