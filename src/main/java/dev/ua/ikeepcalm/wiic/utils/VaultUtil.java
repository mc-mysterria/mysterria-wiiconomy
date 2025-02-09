package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.milkbowl.vault2.economy.Economy;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public class VaultUtil {

    private static Economy economy = WIIC.getEcon();

    public static void deposit(UUID player, double amount) {
        economy.deposit("iConomyUnlocked", player, BigDecimal.valueOf(amount));
    }

    public static void withdraw(UUID player, double amount) {
        economy.withdraw("iConomyUnlocked", player, BigDecimal.valueOf(amount));
    }

    public static double getBalance(UUID player) {
        return economy.balance("iConomyUnlocked", player).doubleValue();
    }

    public static WalletData getWalletData(@NotNull UUID uniqueId) {
        return new WalletData((int) getBalance(uniqueId));
    }
}
