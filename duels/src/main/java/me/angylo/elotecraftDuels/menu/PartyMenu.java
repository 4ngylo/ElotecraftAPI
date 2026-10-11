package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.LocalizedFile;
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
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code /party}: without a party, the public parties to join and a button to make one; in a party, its members
 * (the leader opens a member's menu to promote or kick them) and buttons for invites, fights and settings, each
 * opening its own menu. Every button runs the matching {@code /party} command, so its checks apply.
 * Layouts in menus.yml {@code party}, {@code party-member}, {@code party-fights}, {@code party-settings} and
 * {@code party-none}.
 */
public final class PartyMenu {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final PartyManager parties;

    public PartyMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, PartyManager parties) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.parties = parties;
    }

    public void open(Player viewer) {
        Party party = parties.partyOf(viewer.getUniqueId()).orElse(null);
        String key = party == null ? "party-none" : "party";
        ConfigurationSection section = menus.get(viewer).getConfigurationSection(key);
        try {
            PaginatedMenu menu = MenuLayout.frame(plugin, section, sizeTags(party));
            if (party == null) {
                fillNone(menu, section);
            } else {
                fillParty(menu, section, party, viewer);
            }
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects(), section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects()));
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
        TagResolver[] sizes = sizeTags(party);
        menu.items(party.members().stream().map(member -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(member);
            boolean isLeader = party.isLeader(member);
            String lore = isLeader ? "leader-lore" : leading ? "member-lore-leader" : "lore";
            return Button.of(head(section.getConfigurationSection(isLeader ? "leader" : "member"), player, lore,
                    Placeholder.unparsed("player", name(player))), leading && !isLeader
                    ? MenuLayout.choose(plugin, effects(), clicker -> openMember(clicker, member)) : (clicker, click) -> { });
        }).toList());
        MenuLayout.place(menu, section, "invite", ask("party.prompt-invite", ""), sizes);
        MenuLayout.place(menu, section, "fights", MenuLayout.choose(plugin, effects(), this::openFights), sizes);
        MenuLayout.place(menu, section, "settings", MenuLayout.choose(plugin, effects(), this::openSettings), sizes);
    }

    /** A member of the viewer's party, to promote or kick; the party menu if they are no longer in it. */
    private void openMember(Player viewer, UUID member) {
        Party party = parties.partyOf(viewer.getUniqueId()).filter(found -> found.members().contains(member)).orElse(null);
        if (party == null) {
            open(viewer);
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(member);
        String name = name(player);
        TagResolver[] tags = MenuLayout.with(sizeTags(party), Placeholder.unparsed("player", name));
        submenu(viewer, "party-member", tags, (menu, section) -> {
            MenuLayout.put(menu, section, "promote", run("promote " + name), tags);
            MenuLayout.put(menu, section, "head", head(section.getConfigurationSection("head"), player, "lore", tags), (clicker, click) -> { });
            MenuLayout.put(menu, section, "kick", MenuLayout.confirm(plugin, messages, menus, effects(), MenuLayout.name(section, "kick", tags),
                    command("kick " + name).andThen(this::open), clicker -> openMember(clicker, member)), tags);
        });
    }

    private void openFights(Player viewer) {
        submenu(viewer, "party-fights", null, (menu, section) -> {
            MenuLayout.put(menu, section, "split", run("split"));
            MenuLayout.put(menu, section, "ffa", run("ffa"));
            MenuLayout.put(menu, section, "duel", ask("party.prompt-duel", "duel "));
        });
    }

    private void openSettings(Player viewer) {
        submenu(viewer, "party-settings", null, (menu, section) -> {
            boolean open = parties.partyOf(viewer.getUniqueId()).map(Party::isOpen).orElse(false);
            MenuLayout.put(menu, section, open ? "public-on" : "public-off", run("public"));
            MenuLayout.put(menu, section, "leave", run("leave"));
            MenuLayout.put(menu, section, "disband", MenuLayout.confirm(plugin, messages, menus, effects(),
                    MenuLayout.name(section, "disband"), command("disband"), this::openSettings));
        });
    }

    /**
     * Opens the party submenu {@code key}, filled by {@code fill}, with back (to the party menu) and close; the party
     * menu instead if the viewer is no longer in a party. {@code tags} (the party's size if null) fill the title.
     */
    private void submenu(Player viewer, String key, TagResolver[] tags, BiConsumer<Menu, ConfigurationSection> fill) {
        Party party = parties.partyOf(viewer.getUniqueId()).orElse(null);
        if (party == null) {
            open(viewer);
            return;
        }
        ConfigurationSection section = menus.get(viewer).getConfigurationSection(key);
        try {
            Menu menu = MenuLayout.fixed(plugin, section, tags == null ? sizeTags(party) : tags);
            fill.accept(menu, section);
            MenuLayout.put(menu, section, "back", MenuLayout.choose(plugin, effects(), this::open));
            MenuLayout.put(menu, section, "close", MenuLayout.close(plugin, effects()));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, key, e);
        }
    }

    /** Closes the menu and runs {@code /party <args>}. */
    private BiConsumer<Player, ClickType> run(String args) {
        return MenuLayout.choose(plugin, effects(), command(args));
    }

    private static Consumer<Player> command(String args) {
        return player -> player.performCommand("party " + args);
    }

    /** Asks a player name in chat, then runs {@code /party <prefix><name>}. */
    private BiConsumer<Player, ClickType> ask(String promptKey, String prefix) {
        return MenuLayout.choose(plugin, effects(), player -> MenuLayout.ask(plugin, messages, player, promptKey,
                new TagResolver[0], answer -> {
                    String name = answer.strip();
                    if (PLAYER_NAME.matcher(name).matches()) {
                        player.performCommand("party " + prefix + name);
                    } else {
                        messages.send(player, "general.player-not-found", Placeholder.unparsed("player", name));
                    }
                    // A party duel may open the kit menu; an invite goes back to the party.
                    if (prefix.isEmpty()) {
                        open(player);
                    }
                }));
    }

    private Effects effects() {
        return settings.get().effects();
    }

    private static String name(OfflinePlayer player) {
        return Objects.requireNonNullElse(player.getName(), "?");
    }

    private static ItemStack head(ConfigurationSection template, OfflinePlayer owner, String loreKey, TagResolver... tags) {
        ItemStack head = MenuLayout.icon(Material.PLAYER_HEAD, template, loreKey, false, tags);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return head;
    }

    private TagResolver[] sizeTags(Party party) {
        return new TagResolver[]{Placeholder.unparsed("size", String.valueOf(party == null ? 0 : party.size())),
                Placeholder.unparsed("max", String.valueOf(parties.maxSize(party)))};
    }
}
