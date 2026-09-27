package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

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
        producer = AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent()
                        .resolve("mysterria-audit-spool"),
                "mysterria-wiiconomy", plugin.getPluginMeta().getVersion());
    }

    public static void close() {
        AuditProducer current = producer;
        producer = null;
        if (current != null) current.close();
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

    public static void emitWallet(String operation, Player player, ItemStack item,
                                  long amount, boolean success, BigDecimal before,
                                  BigDecimal after, AuditIdentity identity) {
        try {
            if (player == null || operation == null || operation.isBlank()) return;
            long delta = switch (operation) {
                case "withdrawn" -> -amount;
                case "deposited", "sold" -> amount;
                default -> 0;
            };
            Map<String, Object> metadata = moneyMetadata(delta,
                    before, after, itemMetadata(item));
            metadata.put("success", success);
            UUID playerId = player.getUniqueId();
            emit("wallet." + operation, success, playerId, playerId, null,
                    identity, success ? null : operation + " failed", metadata);
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
     * Risk-aware variant. When the actor is an online player and the caller did not supply
     * a position, the actor's current {@code world}/{@code x}/{@code y}/{@code z} is added.
     */
    public static void emit(String operation, AuditOutcome outcome, AuditRisk risk, UUID actorId,
                            UUID subjectId, UUID targetId, AuditIdentity identity,
                            String reason, Map<String, ?> metadata) {
        try {
            AuditProducer current = producer;
            if (current == null) return;
            current.emit("mysterria-wiiconomy." + operation, outcome, risk == null ? AuditRisk.NORMAL : risk,
                    AuditPrivacy.STAFF_RESTRICTED, identity.correlationId(), identity.businessId(),
                    actorId, subjectId, targetId, reason, withActorLocation(actorId, metadata));
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
     * Copies {@code metadata} and appends the online actor's position under the shared
     * {@code world}/{@code x}/{@code y}/{@code z} keys. Rows that already carry a position,
     * system rows (null actor) and offline actors are returned unchanged.
     */
    static Map<String, ?> withActorLocation(UUID actorId, Map<String, ?> metadata) {
        if (actorId == null || (metadata != null && metadata.containsKey("world"))) return metadata;
        try {
            Player player = Bukkit.getPlayer(actorId);
            if (player == null || !player.isOnline()) return metadata;
            Map<String, Object> located = new LinkedHashMap<>();
            if (metadata != null) metadata.forEach(located::put);
            located.putAll(locationMetadata(player.getLocation()));
            return located;
        } catch (RuntimeException | LinkageError ignored) {
            return metadata;
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

    /** Bounded item projection, including canonical physical UUID keys when present. */
    public static Map<String, Object> itemMetadata(ItemStack item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        try {
            if (item == null) return metadata;
            metadata.put("material", item.getType().name().toLowerCase());
            metadata.put("item_amount", item.getAmount());
            if (!item.hasItemMeta()) return metadata;
            var pdc = item.getItemMeta().getPersistentDataContainer();
            copyString(pdc.get(ITEM_UUID_PDC, PersistentDataType.STRING), ITEM_UUID_KEY, metadata);
            copyString(pdc.get(PARENT_ITEM_UUID_PDC, PersistentDataType.STRING), PARENT_ITEM_UUID_KEY, metadata);
        } catch (RuntimeException | LinkageError ignored) {
            AuditProducer current = producer;
            if (current != null) current.recordFailure();
        }
        return metadata;
    }

    public static Map<String, Object> itemMetadata(byte[] itemBytes) {
        if (itemBytes == null) return Map.of();
        try {
            return itemMetadata(ItemStack.deserializeBytes(itemBytes));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
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
