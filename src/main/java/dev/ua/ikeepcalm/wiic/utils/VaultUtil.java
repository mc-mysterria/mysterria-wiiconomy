package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VaultUtil {

    public static void deposit(UUID player, double amount) {
        if (WIIC.getEcon() != null) {
            WIIC.getEcon().deposit("iConomyUnlocked", player, BigDecimal.valueOf(amount));
        }
    }

    public static void withdraw(UUID player, double amount) {
        if (WIIC.getEcon() != null) {
            WIIC.getEcon().withdraw("iConomyUnlocked", player, BigDecimal.valueOf(amount));
        }
    }

    public static CompletableFuture<Double> getBalance(UUID player) {
        final CompletableFuture<Double> result = new CompletableFuture<>();
        if (WIIC.getEcon() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> result.complete(WIIC.getEcon().balance("iConomyUnlocked", player).doubleValue()));
        } else {
            result.complete(0.0);
        }
        return result;
    }

    public static CompletableFuture<WalletData> getWalletData(@NotNull UUID uniqueId) {
        return getBalance(uniqueId).thenApplyAsync(value -> new WalletData(value.intValue()));
    }
}
