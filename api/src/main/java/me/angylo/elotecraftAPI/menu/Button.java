package me.angylo.elotecraftAPI.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.function.BiConsumer;

/**
 * An item in a {@link Menu} and what happens when it is clicked.
 * <pre>{@code
 * Button.of(item, (player, click) -> {
 *     if (click.isShiftClick()) buyStack(player); else buyOne(player);
 * });
 * }</pre>
 */
public record Button(ItemStack item, BiConsumer<Player, ClickType> onClick) {

    private static final BiConsumer<Player, ClickType> NO_ACTION = (player, click) -> {
    };

    public Button {
        item = item.clone();
    }

    public static Button of(ItemStack item, BiConsumer<Player, ClickType> onClick) {
        return new Button(item, onClick);
    }

    /** A button that does nothing when clicked. */
    public static Button display(ItemStack item) {
        return new Button(item, NO_ACTION);
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
