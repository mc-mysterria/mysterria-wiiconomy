package dev.ua.ikeepcalm.wiic.domain.agora.utils.journal;

import dev.ua.ikeepcalm.wiic.WIIC;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only, fsynced write-ahead journal bridging the gap between an external
 * side effect (item removed from a GUI, Vault withdraw/deposit) and the matching
 * SQLite commit. If the server dies inside that window, startup replay
 * ({@link JournalRecovery}) repairs the half-applied flow idempotently.
 *
 * <p>Entries are one pipe-separated line each. The file is tiny (normally empty —
 * entries live for milliseconds), so {@link #remove} simply rewrites it. Every
 * mutation forces the channel to disk before returning: a journal that can lose
 * its tail is worse than no journal, because recovery would then trust a lie.
 *
 * <p>All methods must be called on the DB thread or main thread — never
 * concurrently. In practice writes happen on the caller's thread right before a
 * {@code MarketDatabase} submit; the coarse synchronization below keeps the rare
 * overlap safe.
 */
public class MarketJournal {

    public enum Type { LIST, BUY, CLAIM, CLAIM_DEPOSITED, STASH_CLAIM }

    public record Entry(Type type, String id, UUID player, long amount, byte[] payload) {}

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public MarketJournal(WIIC plugin) {
        this.file = plugin.getDataFolder().toPath().resolve("market-journal.dat");
        load();
    }

    private synchronized void load() {
        entries.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 5) continue;
                Entry entry = new Entry(
                        Type.valueOf(parts[0]),
                        parts[1],
                        UUID.fromString(parts[2]),
                        Long.parseLong(parts[3]),
                        parts[4].isEmpty() ? null : Base64.getDecoder().decode(parts[4]));
                entries.put(key(entry.type(), entry.id()), entry);
            }
        } catch (Exception e) {
            WIIC.INSTANCE.getLogger().severe("Failed to read market journal (recovery may be incomplete): " + e);
        }
    }

    private static String key(Type type, String id) {
        return type + ":" + id;
    }

    /** Appends an entry and fsyncs. Throws unchecked on I/O failure — callers must abort their flow. */
    public synchronized void append(Type type, String id, UUID player, long amount, byte[] payload) {
        Entry entry = new Entry(type, id, player, amount, payload);
        String line = format(entry) + System.lineSeparator();
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(StandardCharsets.UTF_8.encode(line));
            channel.force(true);
        } catch (IOException e) {
            throw new IllegalStateException("Market journal append failed", e);
        }
        entries.put(key(type, id), entry);
    }

    /**
     * Removes all entries for {@code id} (any type) after their DB commit landed;
     * rewrites + fsyncs.
     *
     * <p>Unlike {@link #append}, this never throws. It runs at the tail of a completed
     * flow — usually inside a GUI callback, right before the player is told what happened —
     * and an exception there would strand the caller's in-flight lock and leave the player
     * staring at nothing. A rewrite that fails only means startup recovery will replay an
     * entry whose work already landed, and every repair in {@link JournalRecovery} is
     * idempotent by construction.
     */
    public synchronized void remove(String id) {
        boolean changed = entries.keySet().removeIf(k -> k.endsWith(":" + id));
        if (!changed) return;
        try {
            rewrite();
        } catch (RuntimeException e) {
            WIIC.INSTANCE.getLogger().severe("Could not prune market journal entry " + id
                    + " (startup recovery will re-check it): " + e.getMessage());
        }
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public synchronized boolean contains(Type type, String id) {
        return entries.containsKey(key(type, id));
    }

    private void rewrite() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries.values()) sb.append(format(entry)).append(System.lineSeparator());
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Market journal rewrite failed", e);
        }
    }

    private static String format(Entry entry) {
        return entry.type() + "|" + entry.id() + "|" + entry.player() + "|" + entry.amount() + "|"
                + (entry.payload() == null ? "" : Base64.getEncoder().encodeToString(entry.payload()));
    }
}
