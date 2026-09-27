package dev.ua.ikeepcalm.wiic.domain.agora.utils.coi;

import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemInspectorTest {
    private ItemStack item(ItemMeta meta) {
        ItemStack item = mock(ItemStack.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        return item;
    }

    @Test
    void rejectsEveryPartialOrControlMarkerAndPreservesOrdinaryItems() {
        for (String marker : List.of("historical_pact_item_owner", "historical_pact_item_slot", "historical_pact_control_item")) {
            ItemStack item = item(mock(ItemMeta.class));
            when(item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey("circleofimagination", marker))).thenReturn(true);
            assertTrue(ItemInspector.containsTemporaryItem(item));
        }
        assertFalse(ItemInspector.containsTemporaryItem(item(mock(ItemMeta.class))));
        assertFalse(ItemInspector.containsTemporaryItem(null));
    }

    @Test
    void findsTemporaryItemsInsideBundleInsideShulkerWithoutEditingEscrow() {
        ItemStack real = item(mock(ItemMeta.class)), projection = item(mock(ItemMeta.class));
        when(projection.getItemMeta().getPersistentDataContainer().has(new NamespacedKey("circleofimagination", "historical_pact_item_slot"))).thenReturn(true);
        BundleMeta bundleMeta = mock(BundleMeta.class);
        ItemStack bundle = item(bundleMeta);
        when(bundleMeta.getItems()).thenReturn(List.of(real, projection));
        BlockStateMeta boxMeta = mock(BlockStateMeta.class);
        ItemStack box = item(boxMeta);
        ShulkerBox state = mock(ShulkerBox.class);
        Inventory inventory = mock(Inventory.class);
        when(boxMeta.hasBlockState()).thenReturn(true);
        when(boxMeta.getBlockState()).thenReturn(state);
        when(state.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[]{null, bundle});
        assertTrue(ItemInspector.containsTemporaryItem(box));
        when(bundleMeta.getItems()).thenReturn(List.of(real));
        assertFalse(ItemInspector.containsTemporaryItem(box));
        verify(box, never()).setItemMeta(any());
        verify(bundleMeta, never()).setItems(any());
        verify(inventory, never()).setItem(anyInt(), any());
    }
}
