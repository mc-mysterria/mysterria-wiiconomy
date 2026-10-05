package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
public class TransactionLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicLong DROPPED = new AtomicLong();
    /** Replaced by {@link #start()} on enable, because {@link #shutdown()} ends the previous one. */
    private static volatile ThreadPoolExecutor writer = newWriter();
    /** Shared by every writer generation, so an abandoned one can never touch a file at the same time as its replacement. */
    private static final Object FILE_LOCK = new Object();
    private static final long HANDOVER_WAIT_MS = 3000;
    private static long lastWarning;
    static final long ROTATE_BYTES = 8L * 1024 * 1024;
    static final int BACKUPS = 5;

    private TransactionLogger() {}

    private static ThreadPoolExecutor newWriter() {
        return new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8192), task -> {
                    Thread thread = new Thread(task, "WIIC-transaction-log");
                    thread.setDaemon(true);
                    return thread;
                }, (task, executor) -> DROPPED.incrementAndGet());
    }

    /** Called on enable so a plugin reload in the same JVM gets a working writer again. */
    public static synchronized void start() {
        ThreadPoolExecutor previous = writer;
        if (!previous.isShutdown()) return;
        try {
            // Bounded wait. If the old generation is still draining, it is abandoned and
            // FILE_LOCK keeps its remaining writes from overlapping the new generation.
            previous.awaitTermination(HANDOVER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        writer = newWriter();
    }

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

    /** Logs a {@code /shop} purchase — the coppets spent here are destroyed, not deposited anywhere. */
    public static void logPurchase(Player player, Material material, int amount, long coppets, double marketIndex, boolean success) {
        write(player, String.format(
                "PURCHASE %-18s x%-5d <- %d coppets   index=%.3f   %s",
                material.name().toLowerCase(), amount, coppets, marketIndex, success ? "OK" : "FAILED"));
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
        // Compatibility export, on by default because staff tooling still reads it.
        // Canonical market SQL history and shared audit remain active regardless;
        // no gameplay/recovery state depends on these text files.
        if (!WIIC.INSTANCE.getConfig().getBoolean("logging.legacy-text-history", true)) return;
        UUID id = player.getUniqueId();
        String name = player.getName().replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.length() > 64) name = name.substring(0, 64);
        if (body.length() > 8192) body = body.substring(0, 8192);
        String line = String.format("[%s] [%s] %s%n", LocalDateTime.now().format(TS), id, body);
        Path dir = WIIC.INSTANCE.getDataFolder().toPath().resolve("logs");
        Path file = dir.resolve(name + ".log");
        java.util.logging.Logger logger = WIIC.INSTANCE.getLogger();
        try {
            writer.execute(() -> append(dir, file, line, logger));
        } catch (RuntimeException e) {
            // Legacy history is best effort and must never reach the transaction path.
            DROPPED.incrementAndGet();
        }
    }

    private static void append(Path dir, Path file, String line, java.util.logging.Logger logger) {
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(dir);
                if (Files.exists(file) && Files.size(file) >= ROTATE_BYTES) rotate(file);
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                DROPPED.incrementAndGet();
            }
            long dropped = DROPPED.get();
            long now = System.currentTimeMillis();
            if (dropped > 0 && now - lastWarning >= 60_000) {
                lastWarning = now;
                logger.warning("WIIC transaction logger has dropped " + dropped + " records");
            }
        }
    }

    /**
     * Retention policy: the live file rotates at {@link #ROTATE_BYTES} into numbered
     * backups {@code .1} (newest) through {@code .}{@link #BACKUPS} (oldest). Each rotation
     * shifts every backup up by one; only the file beyond the oldest slot is discarded, so
     * at least {@value #BACKUPS} full generations of history always remain on disk.
     */
    private static void rotate(Path file) throws IOException {
        String base = file.getFileName().toString();
        Files.deleteIfExists(file.resolveSibling(base + "." + BACKUPS));
        for (int i = BACKUPS - 1; i >= 1; i--) {
            Path from = file.resolveSibling(base + "." + i);
            if (Files.exists(from)) {
                Files.move(from, file.resolveSibling(base + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, file.resolveSibling(base + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }

    public static synchronized void shutdown() {
        ThreadPoolExecutor closing = writer;
        closing.shutdown();
        Thread guard = new Thread(() -> {
            try {
                closing.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "WIIC-transaction-log-close");
        guard.setDaemon(false);
        guard.start();
    }
}
