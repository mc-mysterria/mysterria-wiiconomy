package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Per-player transaction log writer.
 *
 * <p>Each player's economy events (deposits, withdrawals, sells, failures) are appended
 * to {@code <data-folder>/logs/<player-name>.log} so staff can audit a player's history
 * if they report missing balance. Lines are timestamped and include the UUID so renames
 * don't break the trail.
 *
 * <p>All writes are best-effort: I/O failures are logged to the plugin logger and do not
 * affect the in-game transaction.
 */
public final class TransactionLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TransactionLogger() {}

    public static void logDeposit(Player player, ItemStack item, long coppets, boolean success) {
        write(player, String.format(
                "DEPOSIT  %-18s x%-3d -> %d coppets   %s",
                describe(item), item.getAmount(), coppets, success ? "OK" : "FAILED"));
    }

    public static void logWithdraw(Player player, ItemStack item, long coppets, boolean success) {
        write(player, String.format(
                "WITHDRAW %-18s x%-3d <- %d coppets   %s",
                describe(item), item.getAmount(), coppets, success ? "OK" : "FAILED"));
    }

    public static void logSell(Player player, ItemStack item, int coppets, boolean success) {
        write(player, String.format(
                "SELL     %-18s x%-3d -> %d coppets   %s",
                item.getType().name().toLowerCase(), item.getAmount(), coppets, success ? "OK" : "FAILED"));
    }

    public static void logBalance(Player player, BigDecimal balance, String note) {
        write(player, String.format("BALANCE  %s coppets (%s)", balance.toPlainString(), note));
    }

    public static void logNote(Player player, String note) {
        write(player, "NOTE     " + note);
    }

    private static String describe(ItemStack item) {
        String type = ItemUtil.getType(item);
        return type != null ? type : item.getType().name().toLowerCase();
    }

    private static void write(Player player, String body) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        String line = String.format("[%s] [%s] %s%n", LocalDateTime.now().format(TS), id, body);
        try {
            Path dir = WIIC.INSTANCE.getDataFolder().toPath().resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".log");
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            WIIC.INSTANCE.getLogger().warning("Failed to write transaction log for " + name + ": " + e.getMessage());
        }
    }
}
