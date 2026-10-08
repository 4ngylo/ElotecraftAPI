package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.party.Party;
import me.angylo.elotecraftDuels.party.PartyManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code /party}: without a party, the public parties to join and a button to make one; in a party, its members
 * (the leader promotes with a left-click and kicks with shift + right-click) and buttons for invites, going
 * public, fights and leaving. Every button runs the matching {@code /party} command, so its checks apply.
 * Layouts in menus.yml {@code party} and {@code party-none}.
 */
public final class PartyMenu {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final PartyManager parties;

    public PartyMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, PartyManager parties) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.parties = parties;
    }

    public void open(Player viewer) {
        Party party = parties.partyOf(viewer.getUniqueId()).orElse(null);
        String key = party == null ? "party-none" : "party";
        ConfigurationSection section = menus.get().getConfigurationSection(key);
        try {
            PaginatedMenu menu = MenuLayout.frame(plugin, section, sizeTags(party));
            if (party == null) {
                fillNone(menu, section);
            } else {
                fillParty(menu, section, party, viewer);
            }
            MenuLayout.place(menu, section, "close", run(""));  // not in the default party layout: its bottom row is full
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, key, e);
        }
    }

    private void fillNone(PaginatedMenu menu, ConfigurationSection section) {
        menu.items(parties.openParties().stream().map(party -> {
            OfflinePlayer leader = Bukkit.getOfflinePlayer(party.leader());
            String name = Objects.requireNonNullElse(leader.getName(), "?");
            return Button.of(head(section.getConfigurationSection("public-party"), leader, "lore",
                    Placeholder.unparsed("leader", name), sizeTags(party)[0], sizeTags(party)[1]), run("join " + name));
        }).toList());
        MenuLayout.place(menu, section, "create", run("create"));
    }

    private void fillParty(PaginatedMenu menu, ConfigurationSection section, Party party, Player viewer) {
        boolean leading = party.isLeader(viewer.getUniqueId());
        menu.items(party.members().stream().map(member -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(member);
            String name = Objects.requireNonNullElse(player.getName(), "?");
            boolean isLeader = party.isLeader(member);
            String lore = isLeader ? "leader-lore" : leading ? "member-lore-leader" : "lore";
            return Button.of(head(section.getConfigurationSection(isLeader ? "leader" : "member"), player, lore,
                    Placeholder.unparsed("player", name)), leading && !isLeader ? memberClick(name) : run(null));
        }).toList());
        MenuLayout.place(menu, section, "invite", ask("party.prompt-invite", ""));
        MenuLayout.place(menu, section, party.isOpen() ? "public-on" : "public-off", run("public"));
        MenuLayout.place(menu, section, "split", run("split"));
        MenuLayout.place(menu, section, "ffa", run("ffa"));
        MenuLayout.place(menu, section, "duel", ask("party.prompt-duel", "duel "));
        MenuLayout.place(menu, section, "leave", run("leave"));
        MenuLayout.place(menu, section, "disband", (player, click) -> {
            if (click == ClickType.SHIFT_RIGHT) {
                run("disband").accept(player, click);
            }
        });
    }

    /** Left-click promotes the member, shift + right-click kicks them. */
    private BiConsumer<Player, ClickType> memberClick(String name) {
        return (player, click) -> {
            if (click == ClickType.SHIFT_RIGHT) {
                run("kick " + name).accept(player, click);
            } else if (click.isLeftClick()) {
                run("promote " + name).accept(player, click);
            }
        };
    }

    /** Closes the menu and runs {@code /party <args>}; null does nothing, an empty string only closes. */
    private BiConsumer<Player, ClickType> run(String args) {
        Effects effects = settings.get().effects();
        if (args == null) {
            return (player, click) -> { };
        }
        return MenuLayout.choose(plugin, effects, player -> {
            if (!args.isEmpty()) {
                player.performCommand("party " + args);
            }
        });
    }

    /** Asks a player name in chat, then runs {@code /party <prefix><name>}. */
    private BiConsumer<Player, ClickType> ask(String promptKey, String prefix) {
        return MenuLayout.choose(plugin, settings.get().effects(), player -> MenuLayout.ask(plugin, messages, player, promptKey,
                new TagResolver[0], answer -> {
                    String name = answer.strip();
                    if (PLAYER_NAME.matcher(name).matches()) {
                        player.performCommand("party " + prefix + name);
                    } else {
                        messages.send(player, "general.player-not-found", Placeholder.unparsed("player", name));
                    }
                }));
    }

    private static ItemStack head(ConfigurationSection template, OfflinePlayer owner, String loreKey, TagResolver... tags) {
        ItemStack head = MenuLayout.icon(Material.PLAYER_HEAD, template, loreKey, false, tags);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return head;
    }

    private TagResolver[] sizeTags(Party party) {
        return new TagResolver[]{Placeholder.unparsed("size", String.valueOf(party == null ? 0 : party.size())),
                Placeholder.unparsed("max", String.valueOf(settings.get().partyMaxSize()))};
    }
}
