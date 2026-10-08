package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Cooldowns;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Events;
import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Demo of every ElotecraftAPI feature: {@code /example} (subcommands) and {@code /countdown} (no subcommands).
 * Ops only, since undeclared permissions default to op. Ships as the separate ElotecraftExample plugin,
 * never inside the library jar.
 */
public final class ExampleCommand {

    private static final String PERMISSION = "elotecraftexample.example";
    private static final String ADMIN_PERMISSION = "elotecraftexample.example.admin";
    private static final long SNEAK_TIMEOUT_TICKS = 20L * 10;
    private static final int MAX_AMOUNT = 64;
    private static final int MAX_SECONDS = 60;
    private static final int DEMO_PAGE_ITEMS = 100;

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile settings;
    private final Cooldowns<UUID> giveCooldowns = new Cooldowns<>();
    private final NamespacedKey demoKey;
    private final BlockStats blockStats;
    private final ExtrasDemo extras;
    private Duration giveCooldown;

    private ExampleCommand(Plugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin, "es");
        this.settings = new ConfigFile(plugin, "example.yml");
        this.demoKey = new NamespacedKey(plugin, "demo_amount");
        this.giveCooldown = readCooldown();
        this.blockStats = new BlockStats(plugin, messages);
        this.extras = new ExtrasDemo(plugin, messages, settings);
    }

    /** Registers both demo commands; call {@link #shutdown()} from {@code onDisable}. */
    public static ExampleCommand register(Plugin plugin) {
        ExampleCommand example = new ExampleCommand(plugin);

        CommandBuilder.create("example")
                .aliases("ex")
                .description("ElotecraftAPI feature demo")
                .permission(PERMISSION)
                .messages("<red>Only operators can use the demo.", "<red>Run this in game.")
                .executes((sender, args) -> example.messages.send(sender, "example.help"))
                .playerSub("menu", null, (player, args) -> example.openMenu(player))
                .playerSub("pages", null, (player, args) -> example.openPages(player))
                .playerSub("roulette", null, (player, args) -> RouletteMenu.open(plugin, example.messages, player))
                .playerSub("shop", null, (player, args) -> example.extras.openShop(player))
                .playerSub("give", null, example::give, (sender, args) -> Args.filter(List.of("1", "16", "64"), args))
                .playerSub("sneak", null, (player, args) -> example.waitForSneak(player))
                .playerSub("input", null, (player, args) -> example.extras.askName(player))
                .playerSub("anvil", null, (player, args) -> example.extras.askNameInAnvil(player))
                .playerSub("hud", null, (player, args) -> example.extras.toggleHud(player))
                .playerSub("hologram", null, (player, args) -> example.extras.hologram(player))
                .playerSub("skull", null, (player, args) -> example.extras.giveSkull(player, Args.get(args, 0)),
                        (sender, args) -> Args.players(args))
                .sub("async", null, (sender, args) -> example.runAsync(sender))
                .sub("blocks", null, (sender, args) -> example.blockStats.show(sender,
                        args.length > 0 ? args[0] : sender.getName()), (sender, args) -> Args.players(args))
                .sub("topblocks", null, (sender, args) -> example.blockStats.showTop(sender))
                // Nested group: /example admin cooldown <duration> and /example admin reload.
                .sub(CommandBuilder.create("admin").permission(ADMIN_PERMISSION)
                        .sub("cooldown", null, example::setCooldown,
                                (sender, args) -> Args.filter(List.of("10s", "1m", "1h30m"), args))
                        .sub("reload", null, (sender, args) -> example.reload(sender)))
                .register(plugin);

        // No subcommands: the executes() handler receives every argument and its suggester completes them.
        CommandBuilder.create("countdown")
                .permission(PERMISSION)
                .executes(example::countdown, (sender, args) -> args.length == 1 ? List.of("3", "5", "10") : List.of())
                .register(plugin);

        return example;
    }

    /** Saves example.yml and block stats, then closes the database. */
    public void shutdown() {
        settings.saveNow();
        blockStats.shutdown();
    }

    private void openMenu(Player player) {
        ItemStack template = ItemBuilder.of(Material.PAPER)
                .lore(settings.get().getStringList("menu-lore"))
                .flags(ItemFlag.HIDE_ENCHANTS)
                .build();
        ItemStack info = ItemBuilder.from(template).name("<aqua>Info").build();
        ItemStack gem = ItemBuilder.from(template)
                .name(Text.mm("<gradient:green:aqua>Shiny Gem"))
                .lore(Text.mm("<gray>Paper rendered with the emerald model"), Text.mm("<yellow>Click for diamonds"))
                .itemModel(NamespacedKey.minecraft("emerald"))
                .enchant(Enchantment.UNBREAKING, 10)
                .glint(true)
                .unbreakable(true)
                .build();

        Menu menu = new Menu(plugin, 3, "<gradient:gold:red>Example Menu")
                .set(4, ItemBuilder.of(Material.BOOK).name("<white>Display only").build())
                .set(11, Button.of(info, (clicker, click) -> clicker.sendMessage(Text.mm("<gray>You <click>-clicked <white><item>",
                        Placeholder.unparsed("click", click.name().toLowerCase(Locale.ROOT)),
                        Placeholder.unparsed("item", Text.plain(info.getItemMeta().displayName()))))))
                .set(13, Button.of(gem, (clicker, click) -> {
                    clicker.closeInventory();
                    give(clicker, new String[]{click.isShiftClick() ? "64" : "1"});
                }))
                .set(15, Button.of(ItemBuilder.of(Material.BARRIER).name("<red>Close").build(),
                        (clicker, click) -> clicker.closeInventory()))
                .fill(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name(" ").build());

        menu.open(player);
    }

    private void openPages(Player player) {
        List<Button> materials = Arrays.stream(Material.values())
                .filter(material -> material.isItem() && !material.isAir() && !material.isLegacy())
                .limit(DEMO_PAGE_ITEMS)
                .map(material -> Button.of(ItemBuilder.of(material).lore("<gray>Click to get one").build(),
                        (clicker, click) -> clicker.getInventory().addItem(ItemStack.of(material))))
                .toList();

        new PaginatedMenu(plugin, 6, "<gold>Item browser")
                .items(materials)
                .set(49, Button.of(ItemBuilder.of(Material.BARRIER).name("<red>Close").build(),
                        (clicker, click) -> clicker.closeInventory()))
                .fill(ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name(" ").build())
                .open(player);
    }

    private void give(Player player, String[] args) {
        int amount = Args.integer(args.length == 0 ? "1" : args[0], 1, MAX_AMOUNT).orElse(0);
        if (amount < 1) {
            messages.send(player, "example.invalid-amount");
            return;
        }
        if (!giveCooldowns.tryUse(player.getUniqueId(), giveCooldown)) {
            messages.send(player, "example.on-cooldown",
                    Placeholder.unparsed("time", Durations.format(giveCooldowns.remaining(player.getUniqueId()))));
            return;
        }
        player.getInventory().addItem(ItemBuilder.of(Material.DIAMOND)
                .amount(amount)
                .data(demoKey, PersistentDataType.INTEGER, amount)
                .build());

        String path = "gives." + player.getUniqueId();
        int total = settings.get().getInt(path) + amount;
        settings.get().set(path, total);
        // Frequent change: many gives in a short time become one disk write.
        settings.saveLater();
        messages.send(player, "example.given",
                Placeholder.unparsed("amount", String.valueOf(amount)), Placeholder.unparsed("total", String.valueOf(total)));
    }

    private void setCooldown(CommandSender sender, String[] args) {
        String input = args.length == 0 ? "" : String.join(" ", args);
        try {
            giveCooldown = Durations.parse(input);
        } catch (IllegalArgumentException e) {
            // Player input goes in via Placeholder.unparsed, so it cannot inject MiniMessage tags.
            messages.send(sender, "example.invalid-duration", Placeholder.unparsed("input", input));
            return;
        }
        if (sender instanceof Player player) {
            giveCooldowns.clear(player.getUniqueId());
        }
        settings.get().set("give-cooldown", input);
        // Rare admin change: save right away and report failures.
        settings.save().exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not save example.yml", error);
            return null;
        });
        messages.send(sender, "example.cooldown-set", Placeholder.unparsed("time", Durations.format(giveCooldown)));
    }

    private void waitForSneak(Player player) {
        AtomicReference<Listener> listener = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        listener.set(Events.listen(plugin, PlayerToggleSneakEvent.class, EventPriority.MONITOR, true, event -> {
            if (event.getPlayer().equals(player) && event.isSneaking() && done.compareAndSet(false, true)) {
                HandlerList.unregisterAll(listener.get());
                messages.send(player, "example.sneak-done");
            }
        }));
        Tasks.later(plugin, () -> {
            if (done.compareAndSet(false, true)) {
                HandlerList.unregisterAll(listener.get());
                messages.send(player, "example.sneak-timeout");
            }
        }, SNEAK_TIMEOUT_TICKS);
        messages.send(player, "example.sneak-wait");
    }

    private void runAsync(CommandSender sender) {
        messages.send(sender, "example.async-started");

        // Manual hop: async work, then Tasks.sync back to the main thread.
        Tasks.async(plugin, () -> {
            long result = simulateSlowWork();
            Tasks.sync(plugin, () -> sendAsyncResult(sender, "async+sync " + result));
        });

        // Same thing in one call: supplyAsync completes on the main thread.
        Tasks.supplyAsync(plugin, ExampleCommand::simulateSlowWork)
                .thenAccept(result -> sendAsyncResult(sender, "supplyAsync " + result))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Async demo failed", error);
                    return null;
                });
    }

    private void reload(CommandSender sender) {
        boolean messagesOk = messages.reload();
        boolean settingsOk = settings.reload();
        giveCooldown = readCooldown();
        messages.send(sender, messagesOk && settingsOk ? "example.reloaded" : "example.reload-failed");
    }

    private void countdown(CommandSender sender, String[] args) {
        int seconds = Args.integer(Args.get(args, 0), 1, MAX_SECONDS).orElse(0);
        if (seconds < 1) {
            messages.send(sender, "example.invalid-seconds");
            return;
        }
        AtomicInteger left = new AtomicInteger(seconds);
        AtomicReference<BukkitTask> task = new AtomicReference<>();
        task.set(Tasks.timer(plugin, () -> {
            int now = left.getAndDecrement();
            if (now > 0) {
                messages.send(sender, "example.countdown", Placeholder.unparsed("seconds", String.valueOf(now)));
                return;
            }
            messages.send(sender, "example.countdown-done");
            task.get().cancel();
        }, 0L, 20L));
    }

    private void sendAsyncResult(CommandSender sender, String result) {
        messages.send(sender, "example.async-done",
                Placeholder.unparsed("result", result), Placeholder.unparsed("main", String.valueOf(Bukkit.isPrimaryThread())));
    }

    private Duration readCooldown() {
        String raw = settings.get().getString("give-cooldown", "30s");
        try {
            return Durations.parse(raw);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid give-cooldown '" + raw + "' in example.yml; using 30s");
            return Duration.ofSeconds(30);
        }
    }

    /** Stands in for a DB or HTTP call. Never do this on the main thread. */
    private static long simulateSlowWork() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.nanoTime() % 1000;
    }
}
