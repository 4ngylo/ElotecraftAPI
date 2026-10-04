package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Example: a case-opening roulette. The reel scrolls through the middle row, slows down and stops
 * on the prize under the hopper. The prize is picked (weighted) before the spin starts, so closing
 * the menu does not change the result; it is still given when the spin ends.
 */
final class RouletteMenu {

    private static final int REEL_START = 9;
    private static final int REEL_WIDTH = 9;
    private static final int POINTER_SLOT = 4;
    private static final int CENTER_SLOT = 13;
    private static final int STATUS_SLOT = 22;
    private static final int CLOSE_SLOT = 26;
    private static final int REEL_LENGTH = 48;
    private static final int STEPS = REEL_LENGTH - REEL_WIDTH;
    /** The reel item shown in the center slot after the last step. */
    private static final int WINNER_INDEX = STEPS + REEL_WIDTH / 2;
    private static final int MAX_EXTRA_DELAY_TICKS = 9;
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600));
    private static final List<Material> BORDER_COLORS = List.of(
            Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE);
    // ponytail: server-wide guard against double spins; per-plugin state if several plugins ever run their own roulette
    private static final Set<UUID> SPINNING = ConcurrentHashMap.newKeySet();

    private record Prize(String name, ItemStack reward, int weight, boolean rare) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Player player;
    private final RandomGenerator random;
    private final List<Prize> prizes = List.of(
            new Prize("<gray>Coal", ItemStack.of(Material.COAL, 8), 30, false),
            new Prize("<white>Iron Ingots", ItemStack.of(Material.IRON_INGOT, 6), 25, false),
            new Prize("<gold>Gold Ingots", ItemStack.of(Material.GOLD_INGOT, 4), 20, false),
            new Prize("<green>Emeralds", ItemStack.of(Material.EMERALD, 3), 12, false),
            new Prize("<aqua>Diamonds", ItemStack.of(Material.DIAMOND, 2), 8, false),
            new Prize("<light_purple>Enchanted Golden Apple", ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE), 4, true),
            new Prize("<gradient:#ff8a00:#e52e71>Netherite Ingot", ItemStack.of(Material.NETHERITE_INGOT), 1, true));
    private final int totalWeight = prizes.stream().mapToInt(Prize::weight).sum();
    private final Menu menu;
    private final Prize winner;
    private final List<Prize> reel;
    private int step;

    RouletteMenu(Plugin plugin, Messages messages, Player player, RandomGenerator random) {
        this.plugin = plugin;
        this.messages = messages;
        this.player = player;
        this.random = random;
        this.menu = new Menu(plugin, 3, "<gradient:gold:red><bold>✦ Roulette ✦");
        this.winner = pick();
        this.reel = buildReel();
    }

    /** Opens a new roulette for {@code player}, unless one is already spinning. */
    static void open(Plugin plugin, Messages messages, Player player) {
        new RouletteMenu(plugin, messages, player, ThreadLocalRandom.current()).start();
    }

    void start() {
        if (!SPINNING.add(player.getUniqueId())) {
            messages.send(player, "example.roulette-busy");
            player.playSound(sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f));
            return;
        }
        render();
        menu.open(player);
        messages.send(player, "example.roulette-start");
        scheduleNext();
    }

    private void scheduleNext() {
        if (step == STEPS) {
            finish();
            return;
        }
        double progress = (double) step / STEPS;
        long delay = 1 + Math.round(MAX_EXTRA_DELAY_TICKS * progress * progress * progress);
        Tasks.later(plugin, this::advance, delay);
    }

    private void advance() {
        step++;
        render();
        float pitch = 0.6f + 1.4f * step / STEPS;
        player.playSound(sound(org.bukkit.Sound.UI_BUTTON_CLICK, pitch));
        scheduleNext();
    }

    private void render() {
        for (int i = 0; i < REEL_WIDTH; i++) {
            menu.set(REEL_START + i, display(reel.get(step + i)));
        }
        ItemStack border = pane(BORDER_COLORS.get(step % BORDER_COLORS.size()));
        for (int slot = 0; slot < REEL_WIDTH; slot++) {
            menu.set(slot, border);
            menu.set(slot + 18, border);
        }
        menu.set(POINTER_SLOT, pointer());
        menu.set(STATUS_SLOT, ItemBuilder.of(Material.CLOCK).name("<yellow>Spinning...").build());
    }

    private void finish() {
        SPINNING.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        ItemStack gold = pane(Material.YELLOW_STAINED_GLASS_PANE);
        for (int slot = 0; slot < REEL_WIDTH; slot++) {
            menu.set(slot, gold);
            menu.set(slot + 18, gold);
        }
        menu.set(POINTER_SLOT, pointer());
        menu.set(CENTER_SLOT, ItemBuilder.from(display(winner)).glint(true).build());
        menu.set(STATUS_SLOT, Button.of(ItemBuilder.of(Material.NETHER_STAR).name("<green><bold>Spin again").build(),
                (clicker, click) -> open(plugin, messages, clicker)));
        menu.set(CLOSE_SLOT, Button.of(ItemBuilder.of(Material.BARRIER).name("<red>Close").build(),
                (clicker, click) -> clicker.closeInventory()));

        player.getInventory().addItem(winner.reward().clone()).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));

        TagResolver prize = Placeholder.parsed("prize", winner.name());
        player.showTitle(Title.title(
                messages.get(winner.rare() ? "example.roulette-title-rare" : "example.roulette-title"),
                messages.get("example.roulette-subtitle", prize),
                TITLE_TIMES));
        messages.send(player, "example.roulette-win", prize);

        if (winner.rare()) {
            player.playSound(sound(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f));
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 80, 0.6, 1, 0.6, 0.4);
            Bukkit.broadcast(messages.get("example.roulette-broadcast",
                    Placeholder.unparsed("player", player.getName()), prize));
        } else {
            player.playSound(sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.2f));
            player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 25, 0.5, 0.8, 0.5, 0);
        }
    }

    private Prize pick() {
        int roll = random.nextInt(totalWeight);
        for (Prize prize : prizes) {
            roll -= prize.weight();
            if (roll < 0) {
                return prize;
            }
        }
        throw new IllegalStateException("Weights do not add up");
    }

    private List<Prize> buildReel() {
        List<Prize> items = new ArrayList<>(REEL_LENGTH);
        for (int i = 0; i < REEL_LENGTH; i++) {
            items.add(pick());
        }
        items.set(WINNER_INDEX, winner);
        if (!winner.rare()) {
            // Near miss: the rarest prize stops just past the pointer.
            items.set(WINNER_INDEX + 1, prizes.getLast());
        }
        return items;
    }

    private ItemStack display(Prize prize) {
        String chance = String.format(Locale.ROOT, "%.0f%%", 100.0 * prize.weight() / totalWeight);
        return ItemBuilder.from(prize.reward())
                .name(prize.name())
                .lore(prize.rare() ? "<gold>★ Rare ★" : "<gray>Common", "<gray>Chance: <white>" + chance)
                .build();
    }

    private static ItemStack pointer() {
        return ItemBuilder.of(Material.HOPPER).name("<yellow>▼ Your prize ▼").build();
    }

    private static ItemStack pane(Material material) {
        return ItemBuilder.of(material).name(" ").build();
    }

    private static Sound sound(org.bukkit.Sound type, float pitch) {
        return Sound.sound(type, Sound.Source.MASTER, 1f, pitch);
    }
}
