package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who ran a staff action, taken from the command sender already in hand so that emitting
 * a row never needs a lookup. Console and RCON senders have no uuid; they are recorded
 * with a null actor id and an {@code actor_name} ("CONSOLE" or the sender's name). A
 * player's block position is captured here, on the command thread, so rows that are
 * emitted later from a callback thread still carry it.
 */
public record AuditActor(@Nullable UUID id, String name, String type, Map<String, Object> position) {

    private static final int MAX_ARGS_CHARS = 512;

    public static AuditActor of(CommandSender sender) {
        if (sender instanceof Player player) {
            return new AuditActor(player.getUniqueId(), player.getName(), "player",
                    MysterriaAuditBridge.locationMetadata(player.getLocation()));
        }
        if (sender instanceof ConsoleCommandSender) {
            return new AuditActor(null, "CONSOLE", "console", Map.of());
        }
        return new AuditActor(null, sender.getName(),
                sender instanceof RemoteConsoleCommandSender ? "rcon" : "other", Map.of());
    }

    /** Key/value pairs as row metadata; pairs whose value is null are left out. */
    public static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }

    /** Copy of {@code metadata} with the actor's name, sender type and position added. */
    public Map<String, Object> tag(@Nullable Map<String, ?> metadata) {
        Map<String, Object> tagged = new LinkedHashMap<>();
        if (metadata != null) metadata.forEach(tagged::put);
        tagged.put("actor_name", name);
        tagged.put("sender_type", type);
        tagged.putAll(position);
        return tagged;
    }

    public void emit(String operation, AuditOutcome outcome, AuditRisk risk, @Nullable UUID subjectId,
                     @Nullable String reason, Map<String, ?> metadata) {
        emit(operation, outcome, risk, subjectId, MysterriaAuditBridge.randomIdentity("admin"), reason, metadata);
    }

    public void emit(String operation, AuditOutcome outcome, AuditRisk risk, @Nullable UUID subjectId,
                     MysterriaAuditBridge.AuditIdentity identity, @Nullable String reason, Map<String, ?> metadata) {
        try {
            MysterriaAuditBridge.emit(operation, outcome, risk, id, subjectId, null, identity, reason, tag(metadata));
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort and must never alter command behaviour.
        }
    }

    /** One row per admin command invocation, whatever the subcommand goes on to do. */
    public void observed(String command, String[] args) {
        String joined = String.join(" ", args);
        if (joined.length() > MAX_ARGS_CHARS) joined = joined.substring(0, MAX_ARGS_CHARS);
        emit("admin.command.observed", AuditOutcome.OBSERVED, AuditRisk.LOW, null, null,
                fields("command", command, "args", joined, "arg_count", args.length));
    }
}
