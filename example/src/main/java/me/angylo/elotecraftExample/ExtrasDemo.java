package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.hologram.Hologram;
import me.angylo.elotecraftAPI.hud.Bossbars;
import me.angylo.elotecraftAPI.hud.Sidebar;
import me.angylo.elotecraftAPI.input.ChatInput;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Example: chat input, HUD, holograms, skulls and a YAML-defined live menu. */
final class     ExtrasDemo {

    private static final Duration INPUT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BOSSBAR_DURATION = Duration.ofSeconds(10);
    private static final int HOLOGRAM_SECONDS = 15;
    private static final int CLOCK_SLOT = 4;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile settings;

    ExtrasDemo(Plugin plugin, Messages messages, ConfigFile settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
    }

    /** Asks for a nickname in chat; the answer never shows in public chat. */
    void askName(Player player) {
        ChatInput.ask(plugin, player, messages.get(player, "example.input-ask"), INPUT_TIMEOUT)
                .thenAccept(name -> name.ifPresentOrElse(
                        value -> messages.send(player, "example.input-done", Placeholder.unparsed("name", value)),
                        () -> messages.send(player, "example.input-cancelled")));
    }

    /** Toggles a sidebar and shows a draining boss bar. */
    void toggleHud(Player player) {
        if (Sidebar.of(player).isPresent()) {
            Sidebar.of(player).get().hide();
            messages.send(player, "example.hud-hidden");
            return;
        }
        Sidebar.show(plugin, player, "<gradient:gold:yellow><bold>Elotecraft").lines(
                "<gray>Player: <white>" + player.getName(),
                "<gray>Online: <white>" + Bukkit.getOnlinePlayers().size(),
                "",
                "<yellow>/example hud <gray>to hide");
        Bossbars.timed(plugin, player, "<gold>HUD demo <gray>(10s)", BossBar.Color.YELLOW, BOSSBAR_DURATION);
        messages.send(player, "example.hud-shown");
    }

    /** Spawns a hologram in front of the player that counts down, then removes itself. */
    void hologram(Player player) {
        Location location = player.getEyeLocation().add(player.getLocation().getDirection().multiply(2));
        Hologram hologram = Hologram.spawn(plugin, location, countdownText(HOLOGRAM_SECONDS));
        AtomicInteger secondsLeft = new AtomicInteger(HOLOGRAM_SECONDS);
        AtomicReference<BukkitTask> task = new AtomicReference<>();
        task.set(Tasks.timer(plugin, () -> {
            int left = secondsLeft.decrementAndGet();
            if (left <= 0 || !hologram.isValid()) {
                hologram.remove();
                task.get().cancel();
                return;
            }
            hologram.text(countdownText(left));
        }, 20L, 20L));
        messages.send(player, "example.hologram-spawned");
    }

    /** Gives the head of an online player (yourself by default). */
    void giveSkull(Player player, String targetName) {
        Optional<Player> found = targetName.isEmpty() ? Optional.of(player) : Args.player(targetName);
        if (found.isEmpty()) {
            messages.send(player, "example.skull-unknown", Placeholder.unparsed("player", targetName));
            return;
        }
        Player target = found.get();
        player.getInventory().addItem(ItemBuilder.of(Material.PLAYER_HEAD)
                .name("<yellow>" + target.getName() + "'s head")
                .skull(target)
                .build());
        messages.send(player, "example.skull-given", Placeholder.unparsed("player", target.getName()));
    }

    /** Opens the shop defined under {@code shop:} in example.yml, with a clock refreshed every second. */
    void openShop(Player player) {
        Map<String, BiConsumer<Player, ClickType>> actions = Map.of(
                "take-diamond", (clicker, click) -> clicker.getInventory().addItem(ItemStack.of(Material.DIAMOND)),
                "take-emerald", (clicker, click) -> clicker.getInventory().addItem(ItemStack.of(Material.EMERALD)),
                "close", (clicker, click) -> clicker.closeInventory());
        Menu menu = MenuConfig.load(plugin, settings.get().getConfigurationSection("shop"), actions);
        menu.set(CLOCK_SLOT, clock())
                .refresh(20, live -> live.set(CLOCK_SLOT, clock()))
                .open(player);
    }

    private static ItemStack clock() {
        return ItemBuilder.of(Material.CLOCK)
                .name("<yellow>Server time: <white>" + LocalTime.now().format(CLOCK))
                .lore("<gray>Updated every second by Menu.refresh")
                .build();
    }

    private static String countdownText(int seconds) {
        return "<gradient:gold:yellow><bold>Hologram</bold></gradient>\n<gray>Disappears in <white>" + seconds + "s";
    }
}
