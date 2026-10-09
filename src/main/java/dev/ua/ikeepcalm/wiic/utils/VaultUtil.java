package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.wallet.models.WalletData;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class VaultUtil {

    /**
     * Outcome of a Vault money movement. {@code INDETERMINATE} means the provider threw, so
     * nothing proves whether the movement was applied; callers must neither compensate
     * (return items, refund) nor finalize (hand over goods) on it, and must leave the
     * operation for manual reconciliation instead.
     */
    public enum Payment {
        SUCCESS, FAILED, INDETERMINATE;

        public boolean succeeded() {
            return this == SUCCESS;
        }
    }

    public static boolean deposit(UUID player, double amount) {
        if (WIIC.getEcon() == null) return false;
        EconomyResponse response = WIIC.getEcon().deposit("iConomyUnlocked", player, BigDecimal.valueOf(amount));
        return response != null && response.transactionSuccess();
    }

    public static boolean withdraw(UUID player, double amount) {
        if (WIIC.getEcon() == null) return false;
        EconomyResponse response = WIIC.getEcon().withdraw("iConomyUnlocked", player, BigDecimal.valueOf(amount));
        return response != null && response.transactionSuccess();
    }

    /**
     * Like {@link #deposit(UUID, double)}, but a provider exception is reported as
     * {@link Payment#INDETERMINATE} instead of escaping. {@code operation} names the flow
     * in the warning logged for that case.
     */
    public static Payment depositChecked(UUID player, double amount, String operation) {
        return move(player, amount, true, operation);
    }

    /** Checked counterpart of {@link #withdraw(UUID, double)}; see {@link #depositChecked}. */
    public static Payment withdrawChecked(UUID player, double amount, String operation) {
        return move(player, amount, false, operation);
    }

    private static Payment move(UUID player, double amount, boolean credit, String operation) {
        if (WIIC.getEcon() == null) return Payment.FAILED;
        BigDecimal value = BigDecimal.valueOf(amount);
        try {
            EconomyResponse response = credit
                    ? WIIC.getEcon().deposit("iConomyUnlocked", player, value)
                    : WIIC.getEcon().withdraw("iConomyUnlocked", player, value);
            return response != null && response.transactionSuccess() ? Payment.SUCCESS : Payment.FAILED;
        } catch (RuntimeException providerFailure) {
            // The provider may have committed before throwing. A balance re-read cannot settle
            // it: other plugins and WIIC's own async flows move the same account concurrently,
            // so an unchanged balance can hide a landed credit and a matching delta can be
            // someone else's payment. Nothing here identifies this movement, so it stays unproven.
            WIIC.INSTANCE.getLogger().log(Level.WARNING, "UNCERTAIN: economy provider threw during "
                    + (credit ? "deposit" : "withdrawal") + " of " + amount + " coppets for " + player
                    + " (" + operation + "). The movement may or may not have applied; check the balance"
                    + " before compensating by hand.", providerFailure);
            return Payment.INDETERMINATE;
        }
    }

    /**
     * Reads the same account used by the deposit and withdraw helpers. A null result means
     * the balance was not observable; callers must not treat it as a real zero balance.
     */
    public static @Nullable BigDecimal balance(UUID player) {
        if (WIIC.getEcon() == null) return null;
        try {
            return WIIC.getEcon().balance("iConomyUnlocked", player);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static CompletableFuture<Double> getBalance(UUID player) {
        final CompletableFuture<Double> result = new CompletableFuture<>();
        if (WIIC.getEcon() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                BigDecimal balance = balance(player);
                result.complete(balance == null ? 0.0 : balance.doubleValue());
            });
        } else {
            result.complete(0.0);
        }
        return result;
    }

    public static CompletableFuture<WalletData> getWalletData(@NotNull UUID uniqueId) {
        return getBalance(uniqueId).thenApplyAsync(value -> new WalletData(value.intValue()));
    }
}
