package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Optional, non-blocking bridge to the shared Mysterria audit ledger. */
public final class MysterriaAuditBridge {
    public static final String ITEM_UUID_KEY = "item_uuid";
    public static final String PARENT_ITEM_UUID_KEY = "parent_item_uuid";
    private static final NamespacedKey ITEM_UUID_PDC =
            new NamespacedKey("circleofimagination", "item_uuid");
    private static final NamespacedKey PARENT_ITEM_UUID_PDC =
            new NamespacedKey("circleofimagination", "item_parent");
    private static volatile AuditProducer producer;

    private MysterriaAuditBridge() {
    }

    public static void initialize(dev.ua.ikeepcalm.wiic.WIIC plugin) {
        try {
            producer = AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent()
                            .resolve("mysterria-audit-spool"),
                    "mysterria-wiiconomy", plugin.getPluginMeta().getVersion());
        } catch (RuntimeException | LinkageError failure) {
            // Audit is optional: without a producer every emit below is a no-op.
            producer = null;
            plugin.getLogger().warning("Audit producer could not start; audit events are disabled: " + failure);
        }
    }

    public static void close() {
        AuditProducer current = producer;
        producer = null;
        if (current == null) return;
        try {
            current.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Shutdown must continue even if the audit client cannot flush.
        }
    }

    /** Correlates one audit flow while retaining a stable domain-facing identifier. */
    public record AuditIdentity(UUID correlationId, String businessId) {
        public AuditIdentity {
            if (correlationId == null) throw new IllegalArgumentException("correlationId is required");
            if (businessId == null || businessId.isBlank()) throw new IllegalArgumentException("businessId is required");
        }
    }

    public static AuditIdentity randomIdentity(String domain) {
        UUID correlationId = UUID.randomUUID();
        return new AuditIdentity(correlationId, "wiic:" + domain + ":" + correlationId);
    }

    /** Builds a stable identity for entity-lifecycle and journal-recovery events. */
    public static AuditIdentity identity(String domain, UUID id) {
        return new AuditIdentity(id, "wiic:" + domain + ":" + id);
    }

    public static AuditIdentity identity(String domain, String id) {
        String safeId = id == null ? "" : id;
        UUID correlationId;
        try {
            correlationId = UUID.fromString(safeId);
        } catch (IllegalArgumentException invalidUuid) {
            correlationId = UUID.nameUUIDFromBytes((domain + ":" + safeId).getBytes(StandardCharsets.UTF_8));
        }
        return new AuditIdentity(correlationId, "wiic:" + domain + ":" + safeId);
    }

    /**
     * Wallet row. Every argument is a plain value: {@code itemAudit} and {@code location}
     * must be captured on the main thread (see {@link #itemMetadata(ItemStack)} and
     * {@link #playerLocation(Player)}) so this is safe to call from async tasks.
     */
    public static void emitWallet(String operation, UUID playerId, Map<String, ?> itemAudit,
                                  long amount, boolean success, boolean moneyMoved, String reason,
                                  BigDecimal before, BigDecimal after, AuditIdentity identity,
                                  Map<String, ?> location) {
        try {
            if (playerId == null || operation == null || operation.isBlank()) return;
            long delta = !moneyMoved ? 0 : switch (operation) {
                case "withdrawn" -> -amount;
                case "deposited", "sold" -> amount;
                default -> 0;
            };
            Map<String, Object> metadata = moneyMetadata(delta, before, after, itemAudit);
            metadata.put("attempted_amount", amount);
            metadata.put("success", success);
            if (location != null) location.forEach(metadata::put);
            emit("wallet." + operation, success, playerId, playerId, null,
                    identity, success ? null : (reason == null ? operation + " failed" : reason), metadata);
        } catch (RuntimeException | LinkageError ignored) {
            // Audit metadata construction is best effort too; it must not gate item recovery.
        }
    }

    /** Generic WIIC event helper. All callers pass only immutable values. */
    public static void emit(String operation, AuditOutcome outcome, UUID actorId,
                            UUID subjectId, UUID targetId, AuditIdentity identity,
                            String reason, Map<String, ?> metadata) {
        emit(operation, outcome, AuditRisk.NORMAL, actorId, subjectId, targetId, identity, reason, metadata);
    }

    /**
     * Risk-aware variant. Rows carry a position only when the caller passes one; the bridge
     * looks nothing up.
     */
    public static void emit(String operation, AuditOutcome outcome, AuditRisk risk, UUID actorId,
                            UUID subjectId, UUID targetId, AuditIdentity identity,
                            String reason, Map<String, ?> metadata) {
        try {
            AuditProducer current = producer;
            if (current == null) return;
            current.emit("mysterria-wiiconomy." + operation, outcome, risk == null ? AuditRisk.NORMAL : risk,
                    AuditPrivacy.STAFF_RESTRICTED, identity.correlationId(), identity.businessId(),
                    actorId, subjectId, targetId, reason, metadata);
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort and must never alter WIIC behavior.
            AuditProducer current = producer;
            if (current != null) current.recordFailure();
        }
    }

    public static void emit(String operation, boolean success, UUID actorId,
                            UUID subjectId, UUID targetId, AuditIdentity identity,
                            String reason, Map<String, ?> metadata) {
        emit(operation, success ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                actorId, subjectId, targetId, identity, reason, metadata);
    }

    /**
     * High-risk row for a Vault call whose outcome could not be proven either way. The
     * caller has neither compensated nor finalized; staff must reconcile the balance.
     */
    public static void emitPaymentIndeterminate(String operation, UUID actorId, UUID subjectId, UUID targetId,
                                                AuditIdentity identity, long amount, BigDecimal before,
                                                BigDecimal after, Map<String, ?> extra) {
        Map<String, Object> metadata = moneyMetadata(0, before, after, extra);
        metadata.put("attempted_amount", amount);
        metadata.put("payment", "INDETERMINATE");
        emit(operation + ".payment_indeterminate", AuditOutcome.FAILED, AuditRisk.HIGH, actorId, subjectId,
                targetId, identity, "economy provider failed; outcome unproven, manual reconciliation required",
                metadata);
    }

    /**
     * Main-thread capture of an online player's position for rows that are emitted later from
     * an async task. Empty for null/offline players and when called off the main thread.
     */
    public static Map<String, Object> playerLocation(Player player) {
        try {
            if (player == null || !player.isOnline() || !Bukkit.isPrimaryThread()) return new LinkedHashMap<>();
            return locationMetadata(player.getLocation());
        } catch (RuntimeException | LinkageError ignored) {
            return new LinkedHashMap<>();
        }
    }

    /** Block position projection using the shared location keys; empty when unavailable. */
    public static Map<String, Object> locationMetadata(Location location) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (location == null || location.getWorld() == null) return metadata;
        metadata.put("world", location.getWorld().getName());
        metadata.put("x", location.getBlockX());
        metadata.put("y", location.getBlockY());
        metadata.put("z", location.getBlockZ());
        return metadata;
    }

    /** Standard indexed monetary projection. Delta is signed from the subject's perspective. */
    public static Map<String, Object> moneyMetadata(long delta, BigDecimal before, BigDecimal after,
                                                     Map<String, ?> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("currency", "coppets");
        metadata.put("amount", BigDecimal.valueOf(delta).abs());
        metadata.put("delta", delta);
        if (before != null) metadata.put("balance_before", before);
        if (after != null) metadata.put("balance_after", after);
        if (extra != null) extra.forEach(metadata::put);
        return metadata;
    }

    public static Map<String, Object> moneyMetadata(long delta, Map<String, ?> extra) {
        return moneyMetadata(delta, null, null, extra);
    }

    /**
     * Item projection from the stack's own type and amount. Item meta and PDC are not read, so
     * it has no physical UUID keys; rows that need them use the byte-array overload off the
     * main thread.
     */
    public static Map<String, Object> itemMetadata(ItemStack item) {
        if (item == null) return new LinkedHashMap<>();
        return itemMetadata(item.getType(), item.getAmount());
    }

    /** Item projection from a material and amount the caller already holds; reads no item data. */
    public static Map<String, Object> itemMetadata(Material material, int amount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (material != null) metadata.put("material", material.name().toLowerCase());
        if (amount > 0) metadata.put("item_amount", amount);
        return metadata;
    }

    /**
     * Thread-safe projection of {@code ItemStack#serializeAsBytes()} output. Parses the NBT
     * directly instead of deserializing an ItemStack, so async and DB-executor callbacks
     * may use it freely.
     */
    public static Map<String, Object> itemMetadata(byte[] itemBytes) {
        if (itemBytes == null) return Map.of();
        try {
            Map<String, Object> raw = SerializedItemProjection.read(itemBytes);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (raw.get("material") != null) metadata.put("material", raw.get("material"));
            metadata.put("item_amount", raw.getOrDefault("item_amount", 1));
            copyString(asString(raw.get(ITEM_UUID_PDC.toString())), ITEM_UUID_KEY, metadata);
            copyString(asString(raw.get(PARENT_ITEM_UUID_PDC.toString())), PARENT_ITEM_UUID_KEY, metadata);
            return metadata;
        } catch (java.io.IOException | RuntimeException ignored) {
            AuditProducer current = producer;
            if (current != null) current.recordFailure();
            return Map.of();
        }
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : null;
    }

    public static Map<String, Object> metadata(Map<String, ?> first, Map<String, ?> second) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (first != null) first.forEach(merged::put);
        if (second != null) second.forEach(merged::put);
        return merged;
    }

    private static void copyString(String value, String key, Map<String, Object> target) {
        if (value == null || value.isBlank()) return;
        try {
            target.put(key, UUID.fromString(value.trim()).toString());
        } catch (IllegalArgumentException ignored) {
            // Physical identity fields are UUIDs; ignore malformed player-controlled PDC data.
        }
    }
}
