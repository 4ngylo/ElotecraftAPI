package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.command.DuelCommand;
import me.angylo.elotecraftDuels.event.EventManager;
import me.angylo.elotecraftDuels.event.HostedEvent;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static me.angylo.elotecraftDuels.menu.MenuLayout.value;
import static me.angylo.elotecraftDuels.menu.MenuLayout.with;

/**
 * The menus of hosted events: every event to join or watch ({@code /event}), the host's settings
 * ({@code /event settings}) and the event's game rules. Buttons call {@link EventManager}, which checks
 * that the clicker hosts the event. Live menus are redrawn every second. Layouts in menus.yml
 * {@code events}, {@code event-settings} and {@code event-rules}.
 */
public final class EventMenu {

    private static final long REFRESH_TICKS = 20;
    private static final int MAX_ROWS = 6;

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final EventManager events;
    private final KitRegistry kits;
    private final ArenaRegistry arenas;
    private final MatchManager matches;
    private final KitMenu kitMenu;
    private final ArenaMenu arenaMenu;
    private final TeamMenu teamMenu;

    public EventMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, EventManager events,
                     KitRegistry kits, ArenaRegistry arenas, MatchManager matches, KitMenu kitMenu, ArenaMenu arenaMenu,
                     TeamMenu teamMenu) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.events = events;
        this.kits = kits;
        this.arenas = arenas;
        this.matches = matches;
        this.kitMenu = kitMenu;
        this.arenaMenu = arenaMenu;
        this.teamMenu = teamMenu;
    }

    /** Starts gathering players, after picking a kit if none is given; then shows the settings. */
    public void host(Player host, Kit kit) {
        if (kit == null) {
            kitMenu.open(host, KitMenu.Mode.CHALLENGE, chosen -> host(host, chosen));
        } else if (events.host(host, kit)) {
            openSettings(host);
        }
    }

    /** Starts the host's event: a team event first lets them pick the teams. */
    public void start(Player host) {
        HostedEvent event = events.hostedBy(host).orElse(null);
        if (event == null || event.mode() == HostedEvent.Mode.FFA) {
            events.start(host, null, null);
            return;
        }
        List<Player> players = event.players().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        teamMenu.open(host, players, (red, blue) -> events.start(host, red, blue));
    }

    /** Every public event gathering players, and every event fight that may be watched. */
    public void openList(Player viewer) {
        ConfigurationSection section = menus.get().getConfigurationSection("events");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(listEntries(viewer, section, effects));
            menu.refresh(REFRESH_TICKS, open -> menu.items(listEntries(viewer, section, effects)));
            if (viewer.hasPermission(EventManager.HOST)) {
                MenuLayout.place(menu, section, "host", MenuLayout.choose(plugin, effects, player -> host(player, null)));
            }
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "events", e);
        }
    }

    /** The settings of the event {@code host} hosts. */
    public void openSettings(Player host) {
        HostedEvent event = events.hostedBy(host).orElse(null);
        if (event == null) {
            messages.send(host, "event.not-hosting");
            return;
        }
        try {
            new SettingsMenu(menus.get().getConfigurationSection("event-settings"), settings.get().effects(), host).open();
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, host, "event-settings", e);
        }
    }

    /** The game rules of the event {@code host} hosts: the kit's, or the host's choice. */
    public void openRules(Player host) {
        HostedEvent event = events.hostedBy(host).orElse(null);
        Kit kit = event == null ? null : kits.get(event.kit()).orElse(null);
        if (kit == null) {
            openSettings(host);
            return;
        }
        ConfigurationSection section = menus.get().getConfigurationSection("event-rules");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            Consumer<HostedEvent> draw = current -> menu.items(Arrays.stream(KitRule.values()).filter(KitRule::isFlag)
                    .map(rule -> ruleButton(section, effects, current, kit, rule, menu)).toList());
            draw.accept(event);
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, this::openSettings));
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            MenuLayout.fill(menu, section);
            menu.open(host);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, host, "event-rules", e);
        }
    }

    private List<Button> listEntries(Player viewer, ConfigurationSection section, Effects effects) {
        List<Button> entries = new ArrayList<>();
        for (HostedEvent event : events.openEvents()) {
            ItemStack head = MenuLayout.icon(Material.PLAYER_HEAD, section.getConfigurationSection("event"), "lore", false,
                    eventTags(viewer, event));
            Player host = Bukkit.getPlayer(event.host());
            if (host != null) {
                head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(host));
            }
            entries.add(Button.of(head, MenuLayout.choose(plugin, effects, player -> events.join(player, event))));
        }
        for (Match match : matches.running()) {
            if (match.type() != Match.Type.EVENT || !match.options().spectatable() || match.state() == Match.State.ENDING) {
                continue;
            }
            Optional<Player> fighter = match.fighters().stream()
                    .filter(player -> matches.matchOf(player).orElse(null) == match).findFirst();
            fighter.ifPresent(target -> entries.add(Button.of(
                    MenuLayout.icon(match.kit().icon(), section.getConfigurationSection("running"), "lore", false,
                            Placeholder.unparsed("host", match.options().host()),
                            Placeholder.component("kit", Text.mm(match.kit().displayName())),
                            Placeholder.component("arena", Text.mm(match.arena().displayName())),
                            Placeholder.unparsed("alive", String.valueOf(match.fighters().stream().filter(match::isAlive).count()))),
                    MenuLayout.choose(plugin, effects, player -> player.performCommand("duel spectate " + target.getName())))));
        }
        return entries;
    }

    private Button ruleButton(ConfigurationSection section, Effects effects, HostedEvent event, Kit kit, KitRule rule, PaginatedMenu menu) {
        ConfigurationSection template = section.getConfigurationSection("rule");
        boolean on = event.flag(kit, rule, settings.get());
        boolean changed = event.changed(rule);
        ItemStack icon = MenuLayout.icon(material(template, on ? "flag-on" : "flag-off"), template, "lore", changed,
                Placeholder.unparsed("rule", rule.key()),
                Placeholder.component("value", value(section, on ? "on" : "off")),
                Placeholder.component("state", value(section, changed ? "set" : "kit")));
        return Button.of(icon, (player, click) -> {
            effects.play(player, "menu-click");
            events.rule(player, rule, click.isRightClick() ? null : !on);
            events.hostedBy(player).ifPresent(current -> menu.items(Arrays.stream(KitRule.values()).filter(KitRule::isFlag)
                    .map(other -> ruleButton(section, effects, current, kit, other, menu)).toList()));
        });
    }

    /** One open settings menu; redrawn after each change and every second, closed once the event is gone. */
    private final class SettingsMenu {

        private final ConfigurationSection section;
        private final Effects effects;
        private final Player host;
        private final Menu menu;

        SettingsMenu(ConfigurationSection section, Effects effects, Player host) {
            if (section == null) {
                throw new IllegalArgumentException("Missing event-settings menu section in menus.yml");
            }
            int rows = section.getInt("rows", 5);
            if (rows < 1 || rows > MAX_ROWS) {
                throw new IllegalArgumentException("event-settings.rows must be 1 to " + MAX_ROWS);
            }
            this.section = section;
            this.effects = effects;
            this.host = host;
            this.menu = new Menu(plugin, rows, Text.mm(section.getString("title", "")));
            if (!draw()) {
                throw new IllegalStateException("No event to show");
            }
            if (section.isConfigurationSection("filler")) {
                menu.fill(MenuConfig.item(section.getConfigurationSection("filler")));
            }
            menu.refresh(REFRESH_TICKS, open -> {
                if (!draw()) {
                    List.copyOf(menu.getInventory().getViewers()).forEach(HumanEntity::closeInventory);
                }
            });
        }

        void open() {
            menu.open(host);
        }

        /** @return false if the event started or was cancelled */
        private boolean draw() {
            HostedEvent event = events.hostedBy(host).orElse(null);
            Kit kit = event == null ? null : kits.get(event.kit()).orElse(null);
            if (kit == null) {
                return false;
            }
            Settings.Events config = settings.get().events();
            Arena arena = event.arena() == null ? null : arenas.get(event.arena()).orElse(null);
            TagResolver[] tags = {
                    Placeholder.component("kit", Text.mm(kit.displayName())),
                    Placeholder.component("arena", arena == null ? messages.get(host, "general.random-arena") : Text.mm(arena.displayName())),
                    Placeholder.component("mode", messages.get(host, event.mode() == HostedEvent.Mode.FFA ? "event.mode-ffa" : "event.mode-teams")),
                    Placeholder.unparsed("winners", String.valueOf(event.winners())),
                    Placeholder.unparsed("players", String.valueOf(event.size())),
                    Placeholder.unparsed("min", String.valueOf(config.minPlayers())),
                    Placeholder.unparsed("max", String.valueOf(config.maxPlayers())),
                    Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(Math.max(0, event.secondsLeft())))),
                    Placeholder.unparsed("rules", String.valueOf(Arrays.stream(KitRule.values()).filter(event::changed).count()))};
            put("kit", kit.icon(), false, tags, MenuLayout.choose(plugin, effects, player ->
                    kitMenu.open(player, KitMenu.Mode.CHALLENGE, chosen -> {
                        events.kit(player, chosen);
                        openSettings(player);
                    })));
            put("arena", null, false, tags, (player, click) -> {
                if (!player.hasPermission(DuelCommand.SELECT_ARENA)) {
                    effects.play(player, "denied");
                    messages.send(player, "command.no-permission");
                    return;
                }
                MenuLayout.choose(plugin, effects, clicker -> arenaMenu.open(clicker, kit, chosen -> {
                    events.arena(clicker, chosen.orElse(null));
                    openSettings(clicker);
                })).accept(player, click);
            });
            put("rules", null, false, tags, MenuLayout.choose(plugin, effects, EventMenu.this::openRules));
            put("start", null, false, tags, MenuLayout.choose(plugin, effects, EventMenu.this::start));
            put("mode", material(section.getConfigurationSection("mode"), event.mode() == HostedEvent.Mode.FFA ? "ffa" : "teams"),
                    false, tags, change(events::toggleMode));
            put("winners", null, false, tags, change(player -> { }, (player, click) ->
                    events.changeWinners(player, click.isRightClick() ? -1 : 1)));
            toggle("border", event.hasBorder(), tags, events::toggleBorder);
            toggle("public", event.isOpen(), tags, events::toggleOpen);
            toggle("spectate", event.isSpectatable(), tags, events::toggleSpectating);
            put("invite", null, false, tags, MenuLayout.choose(plugin, effects, player ->
                    MenuLayout.ask(plugin, messages, player, "event.prompt-invite", tags, name ->
                            player.performCommand("event invite " + name))));
            put("cancel", null, false, tags, MenuLayout.choose(plugin, effects, events::cancel));
            return true;
        }

        private void toggle(String key, boolean on, TagResolver[] tags, Consumer<Player> flip) {
            put(key, material(section.getConfigurationSection(key), on ? "on" : "off"), on,
                    with(tags, Placeholder.component("value", value(section, on ? "on" : "off"))), change(flip));
        }

        private BiConsumer<Player, ClickType> change(Consumer<Player> action) {
            return change(action, (player, click) -> { });
        }

        /** Plays the click, runs {@code action} (then {@code clickAction}) and redraws. */
        private BiConsumer<Player, ClickType> change(Consumer<Player> action, BiConsumer<Player, ClickType> clickAction) {
            return (player, click) -> {
                effects.play(player, "menu-click");
                action.accept(player);
                clickAction.accept(player, click);
                draw();
            };
        }

        /** The button {@code key} at its {@code slot}, if configured; {@code material} null for the one it names. */
        private void put(String key, Material material, boolean glint, TagResolver[] tags, BiConsumer<Player, ClickType> action) {
            ConfigurationSection button = section.getConfigurationSection(key);
            if (button == null) {
                return;
            }
            int slot = button.getInt("slot", -1);
            if (slot < 0 || slot >= menu.getInventory().getSize()) {
                throw new IllegalArgumentException(button.getCurrentPath() + ".slot must be 0 to " + (menu.getInventory().getSize() - 1));
            }
            menu.set(slot, Button.of(MenuLayout.icon(material == null ? material(button, "material") : material, button, "lore", glint, tags), action));
        }
    }

    private TagResolver[] eventTags(Player viewer, HostedEvent event) {
        Kit kit = kits.get(event.kit()).orElse(null);
        Arena arena = event.arena() == null ? null : arenas.get(event.arena()).orElse(null);
        Settings.Events config = settings.get().events();
        return new TagResolver[]{
                Placeholder.unparsed("host", event.hostName()),
                Placeholder.component("kit", kit == null ? Component.text(event.kit()) : Text.mm(kit.displayName())),
                Placeholder.component("arena", arena == null ? messages.get(viewer, "general.random-arena") : Text.mm(arena.displayName())),
                Placeholder.component("mode", messages.get(viewer, event.mode() == HostedEvent.Mode.FFA ? "event.mode-ffa" : "event.mode-teams")),
                Placeholder.unparsed("winners", String.valueOf(event.winners())),
                Placeholder.unparsed("players", String.valueOf(event.size())),
                Placeholder.unparsed("max", String.valueOf(config.maxPlayers())),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(Math.max(0, event.secondsLeft()))))};
    }

    private static Material material(ConfigurationSection template, String key) {
        String raw = template == null ? "" : template.getString(key, "");
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException((template == null ? key : template.getCurrentPath() + "." + key) + " is not an item: '" + raw + "'");
        }
        return material;
    }
}
