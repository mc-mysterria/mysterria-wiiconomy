package dev.ua.ikeepcalm.market.npc.commands;

import dev.ua.ikeepcalm.market.npc.listeners.NpcListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

public class SpawnNpcCommand implements CommandExecutor {
    private final NpcListener npcListener;

    public SpawnNpcCommand(NpcListener listener) {
        npcListener = listener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String[] args) {
        if (sender instanceof Player player) {
            if (!player.hasPermission("wiic.spawnNPCs")) {
                player.sendMessage(Component.text("У Вас немає прав на використання цієї команди.", NamedTextColor.RED));
                return false;
            }
            Villager npc = player.getWorld().spawn(player.getLocation(), Villager.class);
            npc.setInvulnerable(true);
            npc.setAI(false);
            npc.getPersistentDataContainer().set(npcListener.getShopkeeperKey(), PersistentDataType.BOOLEAN, true);
            return true;
        }
        return false;
    }
}
