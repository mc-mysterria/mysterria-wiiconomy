package dev.ua.ikeepcalm.wiic.domain.agora.market.model;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The market's sound and particle vocabulary — the difference between clicking through
 * an auction house and standing in a place where deals are struck.
 *
 * <p>Every cue is a {@code sounds.<key>} entry in market.yml written as
 * {@code <sound id>:<volume>:<pitch>} (volume and pitch optional), e.g.
 * {@code block.iron_door.open:0.7:0.6}. Sounds are addressed by their vanilla string id
 * rather than the {@code Sound} enum, so a resource pack's custom sounds work and a
 * Minecraft release that reshuffles the enum can't break the build. Setting a key to an
 * empty string silences that cue.
 */
public class MarketFeedback {

    private final MarketConfig config;

    public MarketFeedback(MarketConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // The cues
    // -------------------------------------------------------------------------

    /**
     * The door swings inward and the world goes dark — the first half of the descent.
     *
     * <p>The blindness is the whole point of staging the arrival: without it the market
     * is a coordinate change, and with it the player goes <i>down</i> somewhere. It runs
     * a little past the teleport so the far side fades in rather than snapping into view.
     *
     * @param descentTicks how long until the teleport fires.
     */
    public void descentBegins(Player traveller, Location door, int descentTicks) {
        playAt(door, "entrance-open", "block.iron_door.open:0.7:0.5");
        spray(door, "SOUL", 18, 0.35);
        play(traveller, "descend", "ambient.cave:0.9:0.5");
        blind(traveller, descentTicks + config.entranceFadeTicks());
        traveller.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                descentTicks + 10, 4, false, false, false));
    }

    /** Arrival in the market: the hush on the far side, the dark still lifting. */
    public void arrived(Player player) {
        play(player, "arrive", "block.sculk_catalyst.bloom:0.7:0.6");
        spray(player.getLocation(), "SOUL_FIRE_FLAME", 12, 0.4);
    }

    /** The descent was interrupted — give the player their eyes back immediately. */
    public void descentAborted(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /** Back out into daylight — a shorter fade, since nobody sneaks out slowly. */
    public void departing(Player player, int descentTicks) {
        play(player, "depart", "block.iron_door.close:0.7:0.7");
        blind(player, descentTicks + config.entranceFadeTicks() / 2);
    }

    public void departed(Player player) {
        play(player, "surface", "block.beacon.deactivate:0.5:1.4");
    }

    private void blind(Player player, int ticks) {
        if (ticks <= 0) return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, false, false));
    }

    /** A new secret entrance takes hold in someone's land. */
    public void entranceForged(Player player, Location door) {
        playAt(door, "entrance-forged", "block.respawn_anchor.set_spawn:0.8:0.6");
        spray(door, "REVERSE_PORTAL", 60, 0.6);
    }

    /** The fence accepts goods over the counter. */
    public void listed(Player player) {
        play(player, "listed", "block.vault.insert_item:0.8:1.0");
    }

    /** A deal is struck. */
    public void dealStruck(Player player) {
        play(player, "purchase", "block.amethyst_block.chime:0.8:1.2");
    }

    /** The fence shakes their head. */
    public void refused(Player player) {
        play(player, "refused", "block.note_block.didgeridoo:0.6:0.6");
    }

    /** Proceeds counted out of the ledger. */
    public void coinsCounted(Player player) {
        play(player, "coins", "block.amethyst_block.resonate:0.8:1.4");
    }

    /** Parcels handed across the counter. */
    public void parcelHandedOver(Player player) {
        play(player, "stash", "block.barrel.open:0.7:1.1");
    }

    /** A horn goes up on the Courier Post wall, or comes back down. */
    public void hornChanged(Player player) {
        play(player, "horn", "item.goat_horn.sound.0:0.5:1.0");
    }

    /**
     * Word that something of yours has sold. Deliberately quiet and a little uncanny —
     * it is a rumour reaching you, not a transaction receipt.
     */
    public void rumour(Player player) {
        play(player, "rumour", "block.amethyst_block.hit:0.4:0.7");
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    /** Plays {@code key} for one player, at their own position. */
    public void play(Player player, String key, String def) {
        Cue cue = cue(key, def);
        if (cue != null) player.playSound(player.getLocation(), cue.id(), cue.volume(), cue.pitch());
    }

    /** Plays {@code key} in the world, so bystanders hear it too. */
    public void playAt(Location location, String key, String def) {
        Cue cue = cue(key, def);
        World world = location.getWorld();
        if (cue != null && world != null) world.playSound(location, cue.id(), cue.volume(), cue.pitch());
    }

    /**
     * A short burst of {@code particle} around {@code centre}. Unknown particle names are
     * skipped rather than thrown — the name set shifts between Minecraft releases and a
     * cosmetic flourish must never abort the flow it decorates.
     */
    private void spray(Location centre, String particle, int count, double spread) {
        World world = centre.getWorld();
        if (world == null) return;
        Particle resolved = particle(particle);
        if (resolved == null) return;
        world.spawnParticle(resolved, centre.clone().add(0.5, 1.0, 0.5), count, spread, spread, spread, 0.02);
    }

    private static @Nullable Particle particle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Cue(String id, float volume, float pitch) {}

    /** Parses {@code <id>[:volume[:pitch]]}, or null when the cue is configured off. */
    private @Nullable Cue cue(String key, String def) {
        String raw = config.raw().getString("sounds." + key, def);
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(":");
        // A namespaced id ("mypack:market.chime") puts a colon in the id itself, so the
        // volume/pitch tail is only taken when the trailing fields parse as numbers.
        float volume = 1f;
        float pitch = 1f;
        int idEnd = parts.length;
        if (parts.length >= 2 && isNumber(parts[parts.length - 1])) {
            if (parts.length >= 3 && isNumber(parts[parts.length - 2])) {
                volume = Float.parseFloat(parts[parts.length - 2]);
                pitch = Float.parseFloat(parts[parts.length - 1]);
                idEnd = parts.length - 2;
            } else {
                volume = Float.parseFloat(parts[parts.length - 1]);
                idEnd = parts.length - 1;
            }
        }
        String id = String.join(":", java.util.Arrays.copyOfRange(parts, 0, idEnd));
        return id.isBlank() ? null : new Cue(id, volume, pitch);
    }

    private static boolean isNumber(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }
}
