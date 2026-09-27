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

public class VaultUtil {

    /**
     * Outcome of a Vault money movement. {@code INDETERMINATE} means the provider threw and a
     * balance re-read could not prove whether the movement was applied; callers must neither
     * compensate (return items, refund) nor finalize (hand over goods) on it, and must flag
     * the operation for manual reconciliation instead.
     */
    public enum Payment {
        SUCCESS, FAILED, INDETERMINATE;

        public boolean succeeded() {
            return this == SUCCESS;
        }
    }

    public static Payment deposit(UUID player, double amount) {
        return move(player, amount, true);
    }

    public static Payment withdraw(UUID player, double amount) {
        return move(player, amount, false);
    }

    private static Payment move(UUID player, double amount, boolean credit) {
        if (WIIC.getEcon() == null) return Payment.FAILED;
        BigDecimal value = BigDecimal.valueOf(amount);
        BigDecimal before = balance(player);
        try {
            EconomyResponse response = credit
                    ? WIIC.getEcon().deposit("iConomyUnlocked", player, value)
                    : WIIC.getEcon().withdraw("iConomyUnlocked", player, value);
            return response != null && response.transactionSuccess() ? Payment.SUCCESS : Payment.FAILED;
        } catch (RuntimeException providerFailure) {
            return reconcile(before, balance(player), credit ? value : value.negate());
        }
    }

    /**
     * Re-reads the balance after a provider exception. Only an exact match of the expected
     * delta proves the movement landed, and only an unchanged balance proves it did not;
     * anything else (unreadable balance, concurrent movement) stays indeterminate.
     */
    static Payment reconcile(@Nullable BigDecimal before, @Nullable BigDecimal after, BigDecimal expectedDelta) {
        if (before == null || after == null) return Payment.INDETERMINATE;
        BigDecimal delta = after.subtract(before);
        if (delta.compareTo(expectedDelta) == 0) return Payment.SUCCESS;
        if (delta.signum() == 0) return Payment.FAILED;
        return Payment.INDETERMINATE;
    }

    /**
     * Reads the same account used by {@link #deposit(UUID, double)} and
     * {@link #withdraw(UUID, double)}. A null result means the balance was not
     * observable; callers must not treat it as a real zero balance.
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
