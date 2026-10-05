package dev.ua.ikeepcalm.wiic.domain.agora.ledger.service;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.agora.db.LedgerDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
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
 * Startup recovery completes CLAIMING rows with the marker and leaves those without it
 * CLAIMING (deposit can't be proven either way, so the batch stays withheld for staff).
 * Rows left CLAIMING block further claims by that owner: revert and finish act on every
 * CLAIMING row the owner has, so a later batch would otherwise reopen or close them too.
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
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("ledger-claim", batchId);
        db.transactionThenMain(conn -> LedgerDao.hasClaiming(conn, uuid)
                ? -1L : LedgerDao.beginClaim(conn, uuid), sum -> {
            if (sum < 0) {
                // An earlier batch is still unresolved. Its rows may already be paid, and this
                // batch's revert would hand them back as UNCLAIMED.
                plugin.getLogger().warning("Ledger claim refused for " + owner.getName() + " (" + uuid
                        + "): an earlier claim is still CLAIMING and needs staff reconciliation");
                MysterriaAuditBridge.emit("ledger.claim_failed", false, uuid, uuid, null, identity,
                        "earlier claim unresolved", MysterriaAuditBridge.moneyMetadata(0,
                                MysterriaAuditBridge.playerLocation(owner)));
                IN_FLIGHT.remove(uuid);
                callback.accept(false, 0L);
                return;
            }
            if (sum == 0) {
                IN_FLIGHT.remove(uuid);
                callback.accept(true, 0L);
                return;
            }
            // Entity position may only be read on the main thread; the async rows below reuse it.
            Map<String, Object> location = MysterriaAuditBridge.playerLocation(owner);
            try {
                journal.append(MarketJournal.Type.CLAIM, batchId, uuid, sum, null);
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal unavailable, aborting ledger claim: " + e.getMessage());
                revert(uuid, reverted -> {
                    MysterriaAuditBridge.emit("ledger.claim_failed", false, uuid, uuid, null, identity,
                            "claim journal initialization failed", MysterriaAuditBridge.moneyMetadata(0,
                                    MysterriaAuditBridge.metadata(Map.of("batch_id", batchId, "claim_sum", sum,
                                            "reverted", reverted), location)));
                    IN_FLIGHT.remove(uuid);
                    callback.accept(false, 0L);
                });
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                BigDecimal balanceBefore = VaultUtil.balance(uuid);
                VaultUtil.Payment payment = VaultUtil.depositChecked(uuid, sum, "ledger claim batch " + batchId);
                if (payment == VaultUtil.Payment.INDETERMINATE) {
                    // Neither revert (could pay twice) nor finalize (payment unproven). The rows
                    // stay CLAIMING for staff to reconcile against the balance. Dropping the
                    // intent is only tidiness: if it survives, recovery withholds it the same way.
                    TransactionLogger.logNote(owner, "MARKET LEDGER claim deposit of " + sum + " coppets UNCERTAIN");
                    boolean intentRemoved = journal.remove(batchId);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        IN_FLIGHT.remove(uuid);
                        callback.accept(false, 0L);
                    });
                    MysterriaAuditBridge.emitPaymentIndeterminate("ledger.claim", uuid, uuid, null, identity, sum,
                            balanceBefore, VaultUtil.balance(uuid), MysterriaAuditBridge.metadata(Map.of("batch_id", batchId,
                                    "intent_removed", intentRemoved), location));
                    return;
                }
                if (!payment.succeeded()) {
                    TransactionLogger.logNote(owner, "MARKET LEDGER claim deposit of " + sum + " coppets FAILED");
                    journal.remove(batchId);
                    revert(uuid, ignored -> {
                        IN_FLIGHT.remove(uuid);
                        callback.accept(false, 0L);
                    });
                    MysterriaAuditBridge.emit("ledger.claim_failed", false, uuid, uuid, null, identity,
                            "proceeds deposit failed", MysterriaAuditBridge.moneyMetadata(0,
                                    balanceBefore, VaultUtil.balance(uuid), location));
                    return;
                }
                // Marker first: recovery must be able to prove the deposit happened.
                try {
                    journal.append(MarketJournal.Type.CLAIM_DEPOSITED, batchId, uuid, sum, null);
                } catch (IllegalStateException e) {
                    // The money is already in their hands and we cannot prove it. Recovery
                    // withholds an unproven CLAIM rather than reverting it, so the rows stay
                    // CLAIMING whether or not the intent below can be dropped; staff then mark
                    // them CLAIMED. The two failures that lead here are correlated (a full disk
                    // fails the marker write and the commit below alike).
                    plugin.getLogger().severe("Market journal marker write failed after ledger deposit: " + e.getMessage());
                    boolean intentRemoved = journal.remove(batchId);
                    BigDecimal balanceAfterDeposit = VaultUtil.balance(uuid);
                    MysterriaAuditBridge.emit("ledger.claim_marker_failed", AuditOutcome.FAILED, AuditRisk.HIGH,
                            uuid, uuid, null, identity,
                            "claim deposit landed; deposit marker write failed",
                            MysterriaAuditBridge.moneyMetadata(sum, balanceBefore, balanceAfterDeposit,
                                    MysterriaAuditBridge.metadata(Map.of("deposit_landed", true, "batch_id", batchId,
                                            "intent_removed", intentRemoved), location)));
                    if (!intentRemoved) {
                        plugin.getLogger().severe("CRITICAL: ledger claim of " + sum + " coppets for " + uuid
                                + " was deposited but is neither proven nor retractable in the journal."
                                + " Recovery will withhold batch " + batchId + " as unproven; mark its"
                                + " CLAIMING rows CLAIMED, since the deposit landed.");
                    }
                }
                BigDecimal balanceAfterDeposit = VaultUtil.balance(uuid);
                db.transactionThenMain(conn -> {
                    LedgerDao.finishClaim(conn, uuid, System.currentTimeMillis());
                    TransactionDao.log(conn, "CLAIM_PROCEEDS", uuid, null, null, sum, null);
                    return null;
                }, done -> {
                    journal.remove(batchId);
                    TransactionLogger.logNote(owner, "MARKET LEDGER claimed " + sum + " coppets");
                    MysterriaAuditBridge.emit("ledger.claimed", true, uuid, uuid, null, identity,
                            "proceeds claimed", MysterriaAuditBridge.moneyMetadata(sum,
                                    balanceBefore, balanceAfterDeposit, location));
                    IN_FLIGHT.remove(uuid);
                    callback.accept(true, sum);
                }, error -> {
                    // Deposit landed but the CLAIMED flip failed — recovery replays it from the journal.
                    plugin.getLogger().severe("Ledger finishClaim failed for " + owner.getName()
                            + " (journal will complete on restart): " + error);
                    MysterriaAuditBridge.emit("ledger.claim_pending_recovery", false, uuid, uuid, null, identity,
                            "proceeds deposited; claim pending recovery", MysterriaAuditBridge.moneyMetadata(sum,
                                    balanceBefore, balanceAfterDeposit, location));
                    IN_FLIGHT.remove(uuid);
                    callback.accept(true, sum);
                });
            });
        }, error -> {
            IN_FLIGHT.remove(uuid);
            plugin.getLogger().severe("Ledger beginClaim failed for " + owner.getName() + ": " + error);
            MysterriaAuditBridge.emit("ledger.claim_failed", false, uuid, uuid, null, identity,
                    "claim initialization failed", MysterriaAuditBridge.moneyMetadata(0, Map.of()));
            callback.accept(false, 0L);
        });
    }

    /** {@code then} runs on the main thread with whether the CLAIMING rows were reverted. */
    private void revert(UUID uuid, Consumer<Boolean> then) {
        db.transactionThenMain(conn -> {
            LedgerDao.revertClaim(conn, uuid);
            return null;
        }, ignored -> then.accept(true), error -> {
            plugin.getLogger().severe("Ledger revertClaim failed for " + uuid + ": " + error);
            then.accept(false);
        });
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        IN_FLIGHT.clear();
    }

}
