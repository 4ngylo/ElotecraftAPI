package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Lets a party leader put each member on the red or the blue team before a split. Layout in menus.yml {@code teams}. */
public final class TeamMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;

    public TeamMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
    }

    /**
     * Shows a head per member, starting from two shuffled halves; clicking a head moves that member to the
     * other team.
     *
     * @param onStart gets the red and the blue members' ids; runs after the menu closes
     */
    public void open(Player viewer, List<Player> members, BiConsumer<List<UUID>, List<UUID>> onStart) {
        try {
            new Picker(menus.get().getConfigurationSection("teams"), settings.get().effects(), members, onStart).open(viewer);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Invalid teams menu in menus.yml: " + e.getMessage());
            messages.send(viewer, "general.menu-error");
        }
    }

    /** One open menu and the teams picked in it. */
    private final class Picker {

        private final ConfigurationSection section;
        private final Effects effects;
        private final List<Player> members;
        private final BiConsumer<List<UUID>, List<UUID>> onStart;
        private final Set<UUID> red = new HashSet<>();
        private final PaginatedMenu menu;

        Picker(ConfigurationSection section, Effects effects, List<Player> members, BiConsumer<List<UUID>, List<UUID>> onStart) {
            this.section = section;
            this.effects = effects;
            this.members = List.copyOf(members);
            this.onStart = onStart;
            this.menu = MenuLayout.frame(plugin, section);
            shuffle();
            MenuLayout.place(menu, section, "shuffle", (player, click) -> {
                effects.play(player, "menu-click");
                shuffle();
                draw();
            });
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            draw();
            MenuLayout.fill(menu, section);
        }

        void open(Player viewer) {
            menu.open(viewer);
        }

        /** Puts a random half of the members, rounded up, on red. */
        private void shuffle() {
            List<Player> shuffled = new ArrayList<>(members);
            Collections.shuffle(shuffled);
            red.clear();
            shuffled.subList(0, (shuffled.size() + 1) / 2).forEach(member -> red.add(member.getUniqueId()));
        }

        private void draw() {
            menu.items(members.stream().map(member -> Button.of(head(member), (player, click) -> {
                effects.play(player, "menu-click");
                if (!red.remove(member.getUniqueId())) {
                    red.add(member.getUniqueId());
                }
                draw();
            })).toList());
            MenuLayout.place(menu, section, "start", MenuLayout.choose(plugin, effects, player -> onStart.accept(team(true), team(false))),
                    Placeholder.unparsed("red_size", String.valueOf(team(true).size())),
                    Placeholder.unparsed("blue_size", String.valueOf(team(false).size())));
        }

        private ItemStack head(Player member) {
            boolean onRed = red.contains(member.getUniqueId());
            ItemStack head = MenuLayout.icon(Material.PLAYER_HEAD, section.getConfigurationSection(onRed ? "red" : "blue"), "lore", false,
                    Placeholder.unparsed("player", member.getName()));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(member));
            return head;
        }

        private List<UUID> team(boolean onRed) {
            return members.stream().map(Player::getUniqueId).filter(uuid -> red.contains(uuid) == onRed).toList();
        }
    }
}
