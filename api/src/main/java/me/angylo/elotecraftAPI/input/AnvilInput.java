package me.angylo.elotecraftAPI.input;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Asks a player to type something in an anvil's rename field; clicking the result item answers.
 * <pre>{@code
 * AnvilInput.ask(plugin, player, "<gold>Rename the kit", "NAME", Duration.ofMinutes(1))
 *         .thenAccept(name -> name.ifPresent(value -> rename(kit, value)));
 * }</pre>
 * The future completes on the main thread with the text, or empty when the player closes the anvil or
 * quits, on timeout, when a newer prompt replaces this one, or when the plugin disables. Nothing can be
 * taken out of or put into the anvil, and no levels are needed. Requires {@link InputListener}
 * (registered by ElotecraftAPI). Main thread only.
 * <p>
 * Opened with {@link MenuType#ANVIL}, experimental in Paper 1.21.11; agreed as the only experimental API
 * of the library, kept in this class.
 */
public final class AnvilInput {

    /** The named item the rename field starts from, and the result slot clicked to answer. */
    static final int INPUT_SLOT = 0;
    static final int RESULT_SLOT = 2;
    private static final Material ITEM = Material.PAPER;

    private static final Map<UUID, Prompt> PROMPTS = new HashMap<>();
    /** Builds the anvil; tests swap it, as MockBukkit's {@link MenuType} builds no anvil view. */
    static BiFunction<Player, Component, AnvilView> viewFactory = (player, title) -> MenuType.ANVIL.builder().title(title).build(player);

    private static final class Prompt {
        private final Plugin plugin;
        private final AnvilView view;
        private final CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        private BukkitTask timeout;

        private Prompt(Plugin plugin, AnvilView view) {
            this.plugin = plugin;
            this.view = view;
        }
    }

    private AnvilInput() {
    }

    /** @param title MiniMessage */
    public static CompletableFuture<Optional<String>> ask(Plugin plugin, Player player, String title, String initialText, Duration timeout) {
        return ask(plugin, player, Text.mm(title), initialText, timeout);
    }

    /** @param initialText what the rename field starts with; empty for an empty field */
    public static CompletableFuture<Optional<String>> ask(Plugin plugin, Player player, Component title, String initialText, Duration timeout) {
        UUID uuid = player.getUniqueId();
        finish(uuid, Optional.empty(), false);
        AnvilView view = viewFactory.apply(player, title);
        view.getTopInventory().setItem(INPUT_SLOT, named(initialText));
        Prompt prompt = new Prompt(plugin, view);
        prompt.timeout = Tasks.later(plugin, () -> {
            if (PROMPTS.get(uuid) == prompt) {
                finish(uuid, Optional.empty(), true);
            }
        }, Math.max(1, timeout.toMillis() / 50));
        PROMPTS.put(uuid, prompt);
        player.openInventory(view);
        return prompt.result;
    }

    /** Whether {@code player} is currently being asked something in an anvil. */
    public static boolean isWaiting(Player player) {
        return PROMPTS.containsKey(player.getUniqueId());
    }

    /** Completes every prompt owned by {@code plugin} with empty and closes its anvil; called automatically when it disables. */
    public static void cancelAll(Plugin plugin) {
        for (Map.Entry<UUID, Prompt> entry : List.copyOf(PROMPTS.entrySet())) {
            if (entry.getValue().plugin.equals(plugin)) {
                finish(entry.getKey(), Optional.empty(), true);
            }
        }
    }

    /** The result follows the typed text, and costs no levels. */
    static void prepare(PrepareAnvilEvent event) {
        Prompt prompt = promptOf(event.getView());
        if (prompt == null) {
            return;
        }
        String text = typed(prompt.view);
        event.setResult(text.isEmpty() ? ItemStack.empty() : named(text));
        prompt.view.setRepairCost(0);
    }

    /** Every click in a prompt's anvil is cancelled; a click on the result answers with the typed text. */
    static void click(InventoryClickEvent event) {
        Prompt prompt = promptOf(event.getView());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = typed(prompt.view);
        if (event.getRawSlot() == RESULT_SLOT && !text.isEmpty()) {
            finish(event.getWhoClicked().getUniqueId(), Optional.of(text), true);
        }
    }

    static void drag(InventoryDragEvent event) {
        if (promptOf(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    /** Closing the anvil (Esc, quitting, another inventory) cancels; its item never goes to the player. */
    static void close(InventoryCloseEvent event) {
        if (promptOf(event.getView()) != null) {
            finish(event.getPlayer().getUniqueId(), Optional.empty(), false);
        }
    }

    static void discard(UUID uuid) {
        finish(uuid, Optional.empty(), false);
    }

    private static Prompt promptOf(InventoryView view) {
        Prompt prompt = PROMPTS.get(view.getPlayer().getUniqueId());
        return prompt != null && prompt.view == view ? prompt : null;
    }

    /**
     * Ends {@code uuid}'s prompt: its anvil is emptied first, so closing it gives nothing back.
     *
     * @param closeView whether to close the anvil (a tick later when done from a click, as Bukkit asks)
     */
    private static void finish(UUID uuid, Optional<String> answer, boolean closeView) {
        Prompt prompt = PROMPTS.remove(uuid);
        if (prompt == null) {
            return;
        }
        prompt.timeout.cancel();
        prompt.view.getTopInventory().clear();
        if (closeView && prompt.plugin.isEnabled()) {
            Tasks.sync(prompt.plugin, () -> {
                if (prompt.view.getPlayer().getOpenInventory() == prompt.view) {
                    prompt.view.close();
                }
            });
        } else if (closeView) {
            prompt.view.close();
        }
        prompt.result.complete(answer);
    }

    private static String typed(AnvilView view) {
        String text = view.getRenameText();
        return text == null ? "" : text.strip();
    }

    private static ItemStack named(String text) {
        ItemStack item = ItemStack.of(ITEM);
        if (!text.isEmpty()) {
            item.editMeta(meta -> meta.customName(Component.text(text)));
        }
        return item;
    }
}
