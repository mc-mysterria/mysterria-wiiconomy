package dev.ua.ikeepcalm.wiic.economy.vault;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VaultUtil {

    private static Economy economy = WIIC.getEcon();

    public static void deposit(UUID player, double amount) {
        economy.deposit("iConomyUnlocked", player, BigDecimal.valueOf(amount));
    }

    public static void withdraw(UUID player, double amount) {
        economy.withdraw("iConomyUnlocked", player, BigDecimal.valueOf(amount));
    }

    public static CompletableFuture<Double> getBalance(UUID player) {
        final CompletableFuture<Double> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> result.complete(economy.balance("iConomyUnlocked", player).doubleValue()));
        return result;
    }

    public static CompletableFuture<WalletData> getWalletData(@NotNull UUID uniqueId) {
        return getBalance(uniqueId).thenApplyAsync(value -> new WalletData(value.intValue()));
    }
}
