package dev.ua.ikeepcalm.wiic.domain.agora.dealer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.inventory.ItemStack;

@Getter
@AllArgsConstructor
public class DealerItem {
    private String id;
    private byte[] itemBytes;
    private long price;
    private int dailyStock;
    private int weight;

    public ItemStack item() {
        return ItemStack.deserializeBytes(itemBytes);
    }
}
