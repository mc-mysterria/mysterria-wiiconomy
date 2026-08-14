package dev.ua.ikeepcalm.wiic.domain.agora.plots.model;

import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A stall counter: one sign, one container behind it, one kind of goods at one price.
 *
 * <p>The physical counterpart to a broker listing. A listing is anonymous, escrowed in
 * the database and delivered by courier; a shop is a chest somebody stocked by hand,
 * standing in a rented plot, that a buyer walks up to and clicks. Both route their money
 * through the same tax and ledger so the market stays one economy.
 *
 * <p>{@code itemBytes} is null until the owner binds their goods to the sign — a shop can
 * be built before there is anything to put in it, and unbinding is how you close for the
 * season without tearing the counter down. Stock itself is never recorded here: it is
 * whatever is in the container at the moment of the click, which is the whole point.
 */
public record PlotShop(
        UUID id,
        String plotId,
        String world,
        int signX, int signY, int signZ,
        int chestX, int chestY, int chestZ,
        UUID ownerUuid,
        String ownerName,
        byte @Nullable [] itemBytes,
        @Nullable Material material,
        @Nullable String displayName,
        long price,
        int bundle,
        long soldCount,
        long createdAt
) {

    /** Whether the sign has goods bound to it yet. An unbound shop refuses to sell. */
    public boolean isStocked() {
        return itemBytes != null && material != null;
    }

    public @Nullable Location signLocation() {
        World resolved = WorldUtil.resolve(world);
        return resolved == null ? null : new Location(resolved, signX, signY, signZ);
    }

    public @Nullable Location chestLocation() {
        World resolved = WorldUtil.resolve(world);
        return resolved == null ? null : new Location(resolved, chestX, chestY, chestZ);
    }

    /**
     * The goods this counter sells, as a single item. Deserialised fresh each time rather
     * than cached: {@code ItemStack} is mutable, and a shared instance handed to comparison
     * code that decides to normalise it would quietly change what the shop sells.
     */
    public @Nullable ItemStack template() {
        if (itemBytes == null) return null;
        try {
            return ItemStack.deserializeBytes(itemBytes);
        } catch (Exception e) {
            return null;
        }
    }

    /** What the sign's price line reads: "3ᴠ 12ʟ 5ᴄ", non-zero denominations only. */
    public static String compactPrice(long coppets) {
        long verldors = coppets / 4096;
        long licks = (coppets % 4096) / 64;
        long rest = coppets % 64;
        StringBuilder out = new StringBuilder();
        if (verldors > 0) out.append(verldors).append("ᴠ");
        if (licks > 0) out.append(out.isEmpty() ? "" : " ").append(licks).append("ʟ");
        if (rest > 0 || out.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(rest).append("ᴄ");
        return out.toString();
    }

    /** Cache key for the sign block, matching {@code EntranceService}'s convention. */
    public static String key(String world, int x, int y, int z) {
        return WorldUtil.canonical(world) + ":" + x + ":" + y + ":" + z;
    }

    public String signKey() {
        return key(world, signX, signY, signZ);
    }

    public String chestKey() {
        return key(world, chestX, chestY, chestZ);
    }
}
