package dev.ua.ikeepcalm.wiic.domain.agora.plots.listener;

import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The admin plot-selection wand ({@code /wiicmarket plot wand}): left-click sets
 * corner one, right-click sets corner two, then {@code /wiicmarket plot define <id>}
 * writes the cuboid into {@code market.yml}.
 *
 * <p>Selections are per-player, in memory only — they exist to fill in a config file,
 * so losing them on restart costs nothing. Tagged with the same PDC {@code wiic:type}
 * convention as coins and the entrance item.
 */
public class PlotWandListener implements Listener {

    public static final String TYPE = "market_plot_wand";

    /**
     * A player's in-progress corner pair; either corner may still be unset.
     */
    public record Selection(int @Nullable [] first, int @Nullable [] second) {
        public boolean complete() {
            return first != null && second != null;
        }
    }

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public static ItemStack create() {
        ItemStack wand = new ItemStack(Material.NETHERITE_HOE);
        ItemUtil.setType(wand, TYPE);
        wand.editMeta(meta -> {
            meta.displayName(Component.text("Plot Wand")
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Left-click: first corner").color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Right-click: second corner").color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Then /wiicmarket plot define <id>").color(NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        });
        return wand;
    }

    public static boolean isWand(@Nullable ItemStack item) {
        return item != null && item.hasItemMeta() && TYPE.equals(ItemUtil.getType(item));
    }

    public @Nullable Selection selection(Player player) {
        return selections.get(player.getUniqueId());
    }

    public void clear(Player player) {
        selections.remove(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onWandClick(PlayerInteractEvent event) {
        if (!isWand(event.getItem())) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("wiic.market.admin")) return;

        boolean first = event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!first && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);

        int[] corner = {block.getX(), block.getY(), block.getZ()};
        Selection current = selections.getOrDefault(player.getUniqueId(), new Selection(null, null));
        Selection updated = first
                ? new Selection(corner, current.second())
                : new Selection(current.first(), corner);
        selections.put(player.getUniqueId(), updated);

        player.sendMessage(Component.text((first ? "First" : "Second") + " corner: "
                        + corner[0] + " " + corner[1] + " " + corner[2]
                        + (updated.complete() ? " — selection complete." : ""))
                .color(NamedTextColor.GOLD));
    }
}
