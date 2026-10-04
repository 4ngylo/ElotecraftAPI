package me.angylo.elotecraftAPI.menu;

import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link Menu} that splits a list of buttons into pages.
 * <pre>{@code
 * new PaginatedMenu(plugin, 6, "<gold>All items")
 *         .items(buttons)
 *         .set(49, closeButton)
 *         .open(player);
 * }</pre>
 * The top {@code rows - 1} rows show the items. The bottom row has a previous arrow in its first
 * slot and a next arrow in its last slot, each hidden when there is no page that way. Put fixed
 * buttons only in the bottom row; content slots are redrawn on every page change.
 * Create one per viewer: viewers of the same instance share its page.
 */
public class PaginatedMenu extends Menu {

    private static final int MIN_ROWS = 2;

    private final int pageSize;
    private final int previousSlot;
    private final int nextSlot;
    private List<Button> items = List.of();
    private ItemStack previousItem = ItemBuilder.of(Material.ARROW).name("<yellow>Previous page").build();
    private ItemStack nextItem = ItemBuilder.of(Material.ARROW).name("<yellow>Next page").build();
    private ItemStack filler;
    private int page;

    /** @param title MiniMessage, e.g. {@code "<gold>All items"} */
    public PaginatedMenu(Plugin plugin, int rows, String title) {
        this(plugin, rows, Text.mm(title));
    }

    /** @throws IllegalArgumentException if {@code rows} is not 2 to 6 */
    public PaginatedMenu(Plugin plugin, int rows, Component title) {
        super(plugin, rows, title, MIN_ROWS);
        this.pageSize = (rows - 1) * 9;
        this.previousSlot = pageSize;
        this.nextSlot = pageSize + 8;
    }

    public PaginatedMenu items(List<Button> items) {
        this.items = List.copyOf(items);
        page = Math.min(page, pages() - 1);
        render();
        return this;
    }

    public PaginatedMenu previousButton(ItemStack item) {
        this.previousItem = item.clone();
        render();
        return this;
    }

    public PaginatedMenu nextButton(ItemStack item) {
        this.nextItem = item.clone();
        render();
        return this;
    }

    @Override
    public PaginatedMenu set(int slot, Button button) {
        super.set(slot, button);
        return this;
    }

    @Override
    public PaginatedMenu set(int slot, ItemStack item) {
        super.set(slot, item);
        return this;
    }

    /** Fills empty content slots, hidden arrow slots and empty bottom-row slots. */
    @Override
    public PaginatedMenu fill(ItemStack item) {
        this.filler = item.clone();
        render();
        return this;
    }

    @Override
    public PaginatedMenu refresh(long periodTicks, Consumer<? super Menu> update) {
        super.refresh(periodTicks, update);
        return this;
    }

    @Override
    public void open(HumanEntity viewer) {
        render();
        super.open(viewer);
    }

    /** Current page, starting at 0. */
    public int page() {
        return page;
    }

    /** Number of pages; at least 1. */
    public int pages() {
        return Math.max(1, (items.size() + pageSize - 1) / pageSize);
    }

    private void render() {
        for (int slot = 0; slot < pageSize; slot++) {
            int index = page * pageSize + slot;
            placeOrFill(slot, index < items.size() ? items.get(index) : null);
        }
        placeOrFill(previousSlot, page > 0 ? Button.of(previousItem, (player, click) -> turn(-1)) : null);
        placeOrFill(nextSlot, page < pages() - 1 ? Button.of(nextItem, (player, click) -> turn(1)) : null);
        if (filler != null) {
            for (int slot = previousSlot + 1; slot < nextSlot; slot++) {
                if (getInventory().getItem(slot) == null) {
                    super.set(slot, filler);
                }
            }
        }
    }

    private void placeOrFill(int slot, Button button) {
        if (button != null) {
            super.set(slot, button);
        } else if (filler != null) {
            super.set(slot, filler);
        } else {
            clear(slot);
        }
    }

    private void turn(int delta) {
        page = Math.clamp(page + delta, 0, pages() - 1);
        render();
    }
}
