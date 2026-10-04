package me.angylo.elotecraftAPI.input;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a player to type something in chat.
 * <pre>{@code
 * ChatInput.ask(plugin, player, "<yellow>Type a name for your shop, or 'cancel'", Duration.ofSeconds(30))
 *         .thenAccept(name -> name.ifPresent(value -> createShop(player, value)));
 * }</pre>
 * The answer is hidden from public chat. The future completes on the main thread with the text,
 * or empty on timeout, when the player types {@value #CANCEL_WORD} or quits, when a newer prompt
 * replaces this one, or when the plugin disables. Requires {@link InputListener} (registered by ElotecraftAPI).
 */
public final class ChatInput {

    public static final String CANCEL_WORD = "cancel";

    private static final Map<UUID, Prompt> PROMPTS = new ConcurrentHashMap<>();

    private static final class Prompt {
        private final Plugin plugin;
        private final CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        private BukkitTask timeout;

        private Prompt(Plugin plugin) {
            this.plugin = plugin;
        }
    }

    private ChatInput() {
    }

    /** @param prompt MiniMessage shown to the player */
    public static CompletableFuture<Optional<String>> ask(Plugin plugin, Player player, String prompt, Duration timeout) {
        return ask(plugin, player, Text.mm(prompt), timeout);
    }

    /** Main thread only. */
    public static CompletableFuture<Optional<String>> ask(Plugin plugin, Player player, Component prompt, Duration timeout) {
        UUID uuid = player.getUniqueId();
        Prompt created = new Prompt(plugin);
        created.timeout = Tasks.later(plugin, () -> {
            if (PROMPTS.remove(uuid, created)) {
                created.result.complete(Optional.empty());
            }
        }, Math.max(1, timeout.toMillis() / 50));
        Prompt replaced = PROMPTS.put(uuid, created);
        if (replaced != null) {
            replaced.timeout.cancel();
            replaced.result.complete(Optional.empty());
        }
        player.sendMessage(prompt);
        return created.result;
    }

    /** Whether {@code player} is currently being asked something. */
    public static boolean isWaiting(Player player) {
        return PROMPTS.containsKey(player.getUniqueId());
    }

    /** Consumes a chat message if its sender has a prompt; may be called off the main thread. */
    static boolean answer(UUID uuid, String message) {
        Prompt prompt = PROMPTS.remove(uuid);
        if (prompt == null) {
            return false;
        }
        prompt.timeout.cancel();
        String text = message.strip();
        Optional<String> answer = text.isEmpty() || text.equalsIgnoreCase(CANCEL_WORD) ? Optional.empty() : Optional.of(text);
        if (prompt.plugin.isEnabled()) {
            Tasks.sync(prompt.plugin, () -> prompt.result.complete(answer));
        } else {
            prompt.result.complete(answer);
        }
        return true;
    }

    static void discard(UUID uuid) {
        Prompt prompt = PROMPTS.remove(uuid);
        if (prompt != null) {
            prompt.timeout.cancel();
            prompt.result.complete(Optional.empty());
        }
    }

    /** Completes every prompt owned by {@code plugin} with empty; called automatically when it disables. */
    public static void cancelAll(Plugin plugin) {
        PROMPTS.forEach((uuid, prompt) -> {
            if (prompt.plugin.equals(plugin) && PROMPTS.remove(uuid, prompt)) {
                prompt.timeout.cancel();
                prompt.result.complete(Optional.empty());
            }
        });
    }
}
