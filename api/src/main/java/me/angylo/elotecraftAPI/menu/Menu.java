package me.angylo.elotecraftAPI.menu;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A chest GUI made of {@link Button}s.
 * <pre>{@code
 * new Menu(plugin, 3, "<gold>Shop")
 *         .set(13, Button.of(gem, (player, click) -> buy(player)))
 *         .fill(glassPane)
 *         .open(player);
 * }</pre>
 * While it is open every click and drag is cancelled, including in the player's own inventory.
 * Requires {@link MenuListener} to be registered. Use from the main thread only.
 */
public class Menu implements InventoryHolder {

    private static final int MAX_ROWS = 6;

    private final Plugin plugin;
    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();
    private Consumer<? super Menu> refresher;
    private long refreshPeriodTicks;
    private BukkitTask refreshTask;

    /** @param title MiniMessage, e.g. {@code "<red><bold>Admin"} */
    public Menu(Plugin plugin, int rows, String title) {
        this(plugin, rows, Text.mm(title));
    }

    /**
     * @param plugin owner; its open menus are closed when it disables
     * @throws IllegalArgumentException if {@code rows} is not 1 to 6
     */
    public Menu(Plugin plugin, int rows, Component title) {
        this(plugin, rows, title, 1);
    }

    Menu(Plugin plugin, int rows, Component title, int minRows) {
        if (rows < minRows || rows > MAX_ROWS) {
            throw new IllegalArgumentException("Rows must be " + minRows + " to " + MAX_ROWS + ": " + rows);
        }
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public Menu set(int slot, Button button) {
        inventory.setItem(slot, button.item());
        buttons.put(slot, button);
        return this;
    }

    /** Places an item that does nothing when clicked. */
    public Menu set(int slot, ItemStack item) {
        return set(slot, Button.display(item));
    }

    /** Puts {@code item} in every slot that is still empty. */
    public Menu fill(ItemStack item) {
        Button filler = Button.display(item);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                set(slot, filler);
            }
        }
        return this;
    }

    /**
     * Calls {@code update} every {@code periodTicks} while anyone is viewing, for live menus
     * (timers, balances). Stops when the last viewer closes and starts again on {@link #open}.
     *
     * @throws IllegalArgumentException if {@code periodTicks} is less than 1
     */
    public Menu refresh(long periodTicks, Consumer<? super Menu> update) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("Refresh period must be at least 1 tick: " + periodTicks);
        }
        this.refreshPeriodTicks = periodTicks;
        this.refresher = update;
        return this;
    }

    public void open(HumanEntity viewer) {
        viewer.openInventory(inventory);
        startRefreshing();
    }

    private void startRefreshing() {
        if (refresher == null || refreshTask != null) {
            return;
        }
        refreshTask = Tasks.timer(plugin, () -> {
            if (inventory.getViewers().isEmpty()) {
                refreshTask.cancel();
                refreshTask = null;
                return;
            }
            refresher.accept(this);
        }, refreshPeriodTicks, refreshPeriodTicks);
    }

    public Plugin plugin() {
        return plugin;
    }

    /** The button at {@code slot}, if any; lets tests click it without a real inventory click. */
    public Optional<Button> button(int slot) {
        return Optional.ofNullable(buttons.get(slot));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void clear(int slot) {
        inventory.setItem(slot, null);
        buttons.remove(slot);
    }

    void handleClick(InventoryClickEvent event) {
        Button button = buttons.get(event.getRawSlot());
        if (button == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        try {
            button.onClick().accept(player, event.getClick());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Menu button failed at slot " + event.getRawSlot(), e);
        }
    }
}
