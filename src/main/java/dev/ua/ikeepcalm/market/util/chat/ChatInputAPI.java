package dev.ua.ikeepcalm.market.util.chat;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ChatInputAPI {
    private static final Map<Player, ChatListener> activeListeners = new HashMap<>();

    private final Player player;
    private final Component message;
    private final Consumer<ChatInputEvent> handler;
    private final Consumer<ChatInputCancelEvent> onCancel;

    public ChatInputAPI(Player player, Component message, Consumer<ChatInputEvent> handler, Consumer<ChatInputCancelEvent> onCancel) {
        this.player = player;
        this.message = message;
        this.handler = handler;
        this.onCancel = onCancel;
    }

    public void listen() {
        player.sendMessage(message);
        ChatListener chatListener = new ChatListener(this);
        ChatListener previousListener = activeListeners.get(player);
        if (previousListener != null) {
            previousListener.chatInputAPI.getOnCancel().accept(new ChatInputCancelEvent(player));
            previousListener.unregister();
        }
        activeListeners.put(player, chatListener);
        Bukkit.getPluginManager().registerEvents(chatListener, WIIC.INSTANCE);
    }

    public Player getPlayer() { return player; }

    public Consumer<ChatInputEvent> getHandler() { return handler; }

    public Consumer<ChatInputCancelEvent> getOnCancel() { return onCancel; }

    private record ChatListener(ChatInputAPI chatInputAPI) implements Listener {

        @EventHandler
        public void onPlayerChat(AsyncPlayerChatEvent event) {
            if (event.getPlayer() == chatInputAPI.getPlayer()) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> chatInputAPI.getHandler().accept(new ChatInputEvent(event.getPlayer(), event.getMessage())));
                unregister();
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            chatInputAPI.getOnCancel().accept(new ChatInputCancelEvent(event.getPlayer()));
            unregister();
        }

        private void unregister() {
            activeListeners.remove(chatInputAPI.getPlayer());
            AsyncPlayerChatEvent.getHandlerList().unregister(this);
            PlayerQuitEvent.getHandlerList().unregister(this);
        }
    }
}
