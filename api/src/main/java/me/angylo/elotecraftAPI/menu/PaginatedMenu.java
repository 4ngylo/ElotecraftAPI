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
import java.util.stream.IntStream;

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
 * {@link #centered()} keeps the items off the top row and the side columns instead, which may then hold fixed
 * buttons too.
 * Create one per viewer: viewers of the same instance share its page.
 */
public class PaginatedMenu extends Menu {

    private static final int MIN_ROWS = 2;
    private static final int WIDTH = 9;
    /** Columns 1 to 7: a centered menu keeps the first and last empty. */
    private static final int INNER = 7;
    private static final int MIDDLE = 4;

    private final int rows;
    private final int previousSlot;
    private final int nextSlot;
    private List<Button> items = List.of();
    private ItemStack previousItem = ItemBuilder.of(Material.ARROW).name("<yellow>Previous page").build();
    private ItemStack nextItem = ItemBuilder.of(Material.ARROW).name("<yellow>Next page").build();
    private ItemStack filler;
    private boolean centered;
    private int page;

    /** @param title MiniMessage, e.g. {@code "<gold>All items"} */
    public PaginatedMenu(Plugin plugin, int rows, String title) {
        this(plugin, rows, Text.mm(title));
    }

    /** @throws IllegalArgumentException if {@code rows} is not 2 to 6 */
    public PaginatedMenu(Plugin plugin, int rows, Component title) {
        super(plugin, rows, title, MIN_ROWS);
        this.rows = rows;
        this.previousSlot = (rows - 1) * WIDTH;
        this.nextSlot = previousSlot + WIDTH - 1;
    }

    /**
     * Shows the items in columns 1 to 7 only, below an empty top row (except with 2 rows), and centers a
     * row that is not full: an odd count side by side, an even one around an empty middle column.
     */
    public PaginatedMenu centered() {
        this.centered = true;
        page = Math.min(page, pages() - 1);
        render();
        return this;
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
        int pageSize = pageSize();
        return Math.max(1, (items.size() + pageSize - 1) / pageSize);
    }

    /** The columns (1 to 7) of {@code count} items centered in a row; see {@link #centered()}. */
    static List<Integer> centeredColumns(int count) {
        int width = count % 2 == 0 ? count + 1 : count;
        int first = 1 + (INNER - width) / 2;
        return IntStream.range(first, first + width).filter(column -> column != MIDDLE || count % 2 == 1).boxed().toList();
    }

    private int pageSize() {
        return centered ? contentRows() * INNER : previousSlot;
    }

    private int firstContentRow() {
        return centered && rows > MIN_ROWS ? 1 : 0;
    }

    private int contentRows() {
        return rows - 1 - firstContentRow();
    }

    /** Whether pages draw {@code slot}: every slot above the bottom row, or only the inner area when centered. */
    private boolean isContentSlot(int slot) {
        int column = slot % WIDTH;
        return !centered || (slot / WIDTH >= firstContentRow() && column >= 1 && column <= INNER);
    }

    /** The buttons of the current page by slot, for the slots above the bottom row. */
    private Button[] layout() {
        int pageSize = pageSize();
        int from = Math.min(page * pageSize, items.size());
        List<Button> shown = items.subList(from, Math.min(from + pageSize, items.size()));
        Button[] slots = new Button[previousSlot];
        if (!centered) {
            shown.toArray(slots);
            return slots;
        }
        for (int start = 0; start < shown.size(); start += INNER) {
            List<Button> row = shown.subList(start, Math.min(start + INNER, shown.size()));
            int rowStart = (firstContentRow() + start / INNER) * WIDTH;
            List<Integer> columns = centeredColumns(row.size());
            for (int i = 0; i < row.size(); i++) {
                slots[rowStart + columns.get(i)] = row.get(i);
            }
        }
        return slots;
    }

    private void render() {
        Button[] slots = layout();
        for (int slot = 0; slot < slots.length; slot++) {
            if (isContentSlot(slot)) {
                placeOrFill(slot, slots[slot]);
            } else if (filler != null && getInventory().getItem(slot) == null) {
                super.set(slot, filler);
            }
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
