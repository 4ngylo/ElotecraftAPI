package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.storage.Database;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstances;
import me.angylo.elotecraftDuels.arena.ArenaPregen;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.arena.ArenaWorld;
import me.angylo.elotecraftDuels.command.AdminCommand;
import me.angylo.elotecraftDuels.command.DuelCommand;
import me.angylo.elotecraftDuels.command.EventCommand;
import me.angylo.elotecraftDuels.event.EventManager;
import me.angylo.elotecraftDuels.command.PartyCommand;
import me.angylo.elotecraftDuels.hook.PlaceholderHook;
import me.angylo.elotecraftDuels.hook.WorldEditHook;
import me.angylo.elotecraftDuels.hud.DuelsSidebar;
import me.angylo.elotecraftDuels.hud.LeaderboardHolograms;
import me.angylo.elotecraftDuels.hud.LobbyItems;
import me.angylo.elotecraftDuels.hud.WatchItem;
import me.angylo.elotecraftDuels.hud.LobbyVisibility;
import me.angylo.elotecraftDuels.kit.CustomKits;
import me.angylo.elotecraftDuels.kit.KitEditor;
import me.angylo.elotecraftDuels.kit.KitLayouts;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.listener.BuildListener;
import me.angylo.elotecraftDuels.listener.CombatListener;
import me.angylo.elotecraftDuels.listener.ProtectionListener;
import me.angylo.elotecraftDuels.listener.SessionListener;
import me.angylo.elotecraftDuels.hook.VaultEconomy;
import me.angylo.elotecraftDuels.match.Bets;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.match.RequestManager;
import me.angylo.elotecraftDuels.match.Rewards;
import me.angylo.elotecraftDuels.menu.ArenaMenu;
import me.angylo.elotecraftDuels.menu.CosmeticsMenu;
import me.angylo.elotecraftDuels.menu.FightInventoryMenu;
import me.angylo.elotecraftDuels.menu.HistoryMenu;
import me.angylo.elotecraftDuels.menu.ArenaAdminMenu;
import me.angylo.elotecraftDuels.menu.EventMenu;
import me.angylo.elotecraftDuels.menu.KitAdminMenu;
import me.angylo.elotecraftDuels.menu.KitMenu;
import me.angylo.elotecraftDuels.menu.OptionsMenu;
import me.angylo.elotecraftDuels.menu.PartyMenu;
import me.angylo.elotecraftDuels.menu.CustomKitMenu;
import me.angylo.elotecraftDuels.menu.SpectateMenu;
import me.angylo.elotecraftDuels.menu.TeamMenu;
import me.angylo.elotecraftDuels.party.PartyFights;
import me.angylo.elotecraftDuels.party.PartyManager;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import me.angylo.elotecraftDuels.stats.MatchHistory;
import me.angylo.elotecraftDuels.stats.Seasons;
import me.angylo.elotecraftDuels.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Builds every part of the plugin in dependency order, runs the once-a-second housekeeping, reloads
 * the files and shuts down in reverse. Takes a plain {@link Plugin} so tests can run it on a mock plugin.
 */
public final class Duels {

    private static final long SECOND_TICKS = 20;
    private static final int SECONDS_PER_RETRY = 60;

    private final Plugin plugin;
    private final ConfigFile config;
    private final ConfigFile menus;
    private final Messages messages;
    private final Database database;
    private final ArenaRegistry arenas;
    private final KitRegistry kits;
    private final StatsService stats;
    private final MatchHistory history;
    private final Seasons seasons;
    private final SnapshotStore snapshots;
    private final KitLayouts layouts;
    private final KitEditor editor;
    private final CustomKits customKits;
    private final ArenaInstances instances;
    private final WorldEditHook worldEdit;
    private final ArenaPregen pregen;
    private final MatchManager matches;
    private final RequestManager requests;
    private final Bets bets;
    private final QueueManager queues;
    private final PartyManager parties;
    private final PartyFights partyFights;
    private final EventManager events;
    private final SessionListener sessions;
    private final DuelsSidebar sidebar;
    private final LobbyItems lobbyItems;
    private final WatchItem watchItem;
    private final LeaderboardHolograms holograms;
    private final LobbyVisibility visibility;
    private final PlaceholderHook placeholders;
    private final BukkitTask ticker;
    private volatile Settings settings;
    private int seconds;

    private Duels(Plugin plugin, ConfigFile config, Database database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.settings = Settings.load(config.get(), plugin.getLogger());
        this.menus = new ConfigFile(plugin, "menus.yml");
        this.messages = new Messages(plugin);
        this.arenas = new ArenaRegistry(plugin);
        this.kits = new KitRegistry(plugin);
        this.stats = new StatsService(plugin, database);
        this.history = new MatchHistory(plugin, database);
        this.seasons = new Seasons(plugin, database, stats.ready());
        this.snapshots = new SnapshotStore(plugin, database);
        this.layouts = new KitLayouts(plugin, database);
        this.customKits = new CustomKits(this::settings, kits, layouts);
        // Loaded before arenas are rebuilt after a crash: pregen copies live there.
        World arenasWorld = ArenaWorld.load(plugin, settings.arenasWorld()).orElse(null);
        this.instances = new ArenaInstances(plugin, this::settings, arenas);
        this.worldEdit = WorldEditHook.detect(plugin).orElse(null);
        this.pregen = new ArenaPregen(plugin, this::settings, arenas, arenasWorld, worldEdit);
        this.matches = new MatchManager(plugin, messages, this::settings, arenas, instances, snapshots, stats, history, layouts);
        this.editor = new KitEditor(plugin, messages, this::settings, snapshots, layouts, customKits, matches::isBusy);
        this.bets = new Bets(plugin.getLogger(), messages, this::settings, database, new VaultEconomy(plugin.getLogger()));
        matches.onFinish(bets::finished);
        this.requests = new RequestManager(messages, this::settings, kits, arenas, matches, bets);
        this.queues = new QueueManager(messages, this::settings, kits, matches, stats);
        this.events = new EventManager(plugin.getLogger(), messages, this::settings, kits, arenas, matches, queues, snapshots,
                new Rewards(plugin, messages, this::settings));
        matches.busyElsewhere(editor::isEditing);
        matches.waitingElsewhere(events::isWaiting);
        KitMenu kitMenu = new KitMenu(plugin, messages, menus, this::settings, kits, matches, queues, stats);
        ArenaMenu arenaMenu = new ArenaMenu(plugin, messages, menus, this::settings, arenas, matches);

        this.parties = new PartyManager(plugin, messages, this::settings);
        TeamMenu teamMenu = new TeamMenu(plugin, messages, menus, this::settings);
        this.partyFights = new PartyFights(messages, this::settings, kits, arenas, matches, queues, parties, teamMenu);
        SpectateMenu spectateMenu = new SpectateMenu(plugin, messages, menus, this::settings, matches);
        CustomKitMenu customKitMenu = new CustomKitMenu(plugin, messages, menus, this::settings, customKits, editor);
        Command duel = new DuelCommand(this, kitMenu, arenaMenu, new FightInventoryMenu(plugin, messages, menus),
                new HistoryMenu(plugin, messages, menus, this::settings, kits),
                new OptionsMenu(plugin, messages, menus, this::settings),
                new CosmeticsMenu(plugin, messages, menus, this::settings),
                spectateMenu, customKitMenu).register();
        new AdminCommand(this, new ArenaAdminMenu(plugin, messages, menus, this::settings, arenas),
                new KitAdminMenu(plugin, messages, menus, this::settings, kits)).register();
        new PartyCommand(this, kitMenu, new PartyMenu(plugin, messages, menus, this::settings, parties)).register();
        new EventCommand(this, new EventMenu(plugin, messages, menus, this::settings, events, kits, arenas, matches,
                kitMenu, arenaMenu, teamMenu)).register();
        this.sessions = new SessionListener(plugin, messages, stats, snapshots, matches, requests, queues);
        this.lobbyItems = new LobbyItems(plugin, this::settings, menus, matches, queues);
        this.watchItem = new WatchItem(plugin, menus, matches, spectateMenu);
        for (Listener listener : List.of(sessions, parties, layouts, editor, events, lobbyItems, watchItem, customKitMenu,
                new CombatListener(plugin, messages, this::settings, matches, snapshots),
                new ProtectionListener(messages, this::settings, matches, duel),
                new BuildListener(this::settings, matches, instances, arenas))) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
        // After a reload the players are already online: load them as if they had just joined.
        Bukkit.getOnlinePlayers().forEach(sessions::load);
        layouts.loadOnline();
        this.placeholders = PlaceholderHook.register(this);
        this.sidebar = new DuelsSidebar(plugin, messages, this::settings, stats, kits, matches, queues, parties);
        plugin.getServer().getPluginManager().registerEvents(sidebar, plugin);
        this.holograms = new LeaderboardHolograms(plugin, messages, this::settings, stats, kits);
        this.visibility = new LobbyVisibility(plugin, this::settings, matches, parties);
        this.ticker = Tasks.timer(plugin, this::tick, SECOND_TICKS, SECOND_TICKS);
        warnMissingKillMessages();
        plugin.getLogger().info("Loaded " + arenas.all().size() + " arenas (" + arenas.all().stream().filter(Arena::isReady).count()
                + " ready) and " + kits.all().size() + " kits" + (placeholders.registered() ? "; PlaceholderAPI hooked" : "")
                + (worldEdit != null ? "; " + worldEdit.name() + " pastes arena copies" : ""));
    }

    /**
     * Opens the database and starts everything. Call from {@code onEnable}; pair with {@link #shutdown()}.
     *
     * @throws IllegalStateException if the database cannot be reached
     */
    public static Duels start(Plugin plugin) {
        ConfigFile config = new ConfigFile(plugin, "config.yml");
        Database database = Database.fromConfig(plugin, config.get().getConfigurationSection("database"));
        try {
            return new Duels(plugin, config, database);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    public Plugin plugin() {
        return plugin;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public ArenaRegistry arenas() {
        return arenas;
    }

    public KitRegistry kits() {
        return kits;
    }

    public CustomKits customKits() {
        return customKits;
    }

    public MatchHistory history() {
        return history;
    }

    public StatsService stats() {
        return stats;
    }

    public Seasons seasons() {
        return seasons;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public ArenaInstances instances() {
        return instances;
    }

    public ArenaPregen pregen() {
        return pregen;
    }

    /** FastAsyncWorldEdit or WorldEdit, when enabled. */
    public Optional<WorldEditHook> worldEdit() {
        return Optional.ofNullable(worldEdit);
    }

    public MatchManager matches() {
        return matches;
    }

    public RequestManager requests() {
        return requests;
    }

    public Bets bets() {
        return bets;
    }

    public QueueManager queues() {
        return queues;
    }

    public KitEditor editor() {
        return editor;
    }

    public PartyManager parties() {
        return parties;
    }

    public PartyFights partyFights() {
        return partyFights;
    }

    public EventManager events() {
        return events;
    }

    public DuelsSidebar sidebar() {
        return sidebar;
    }

    public LeaderboardHolograms holograms() {
        return holograms;
    }

    /** Completes once the database tables exist. */
    public CompletableFuture<Void> ready() {
        return CompletableFuture.allOf(stats.ready(), history.ready(), snapshots.ready(), layouts.ready(), seasons.ready(),
                bets.ready());
    }

    /**
     * Reloads config.yml, messages, menus.yml, arenas.yml and kits.yml; running duels keep the arena and
     * kit they started with. The database and {@code arenas.world} need a restart.
     *
     * @return false if a file failed to load (its previous contents are kept)
     */
    public boolean reload() {
        boolean ok = config.reload();
        ok &= messages.reload();
        ok &= menus.reload();
        ok &= arenas.reload();
        ok &= kits.reload();
        settings = Settings.load(config.get(), plugin.getLogger());
        warnMissingKillMessages();
        return ok;
    }

    /** Puts every player back, saves pending data and closes the database. Call from {@code onDisable}. */
    public void shutdown() {
        ticker.cancel();
        placeholders.unregister();
        sidebar.hideAll();
        holograms.shutdown();
        visibility.showAll();
        editor.shutdown();
        matches.shutdown();
        // After the matches put players' own inventories back, lobby items included.
        lobbyItems.stripAll();
        instances.shutdown();
        requests.clear();
        // After the matches: their stakes stay stored and are given back next start.
        bets.clear();
        queues.clear();
        parties.clear();
        partyFights.clear();
        events.clear();
        stats.retryFailed();
        snapshots.retryFailedDeletes();
        arenas.saveNow();
        kits.saveNow();
        database.close();
    }

    /** Kill messages of config.yml without a text in messages.yml are not offered. */
    private void warnMissingKillMessages() {
        settings.cosmetics().killMessages().stream().map(Cosmetics.Cosmetic::id)
                .filter(id -> !messages.has("kill-messages." + id))
                .forEach(id -> plugin.getLogger().warning("config.yml cosmetics.kill-messages." + id
                        + " has no text in messages.yml kill-messages." + id + "; it is not offered"));
    }

    private void tick() {
        matches.purgeExpired();
        requests.tick();
        bets.tick();
        queues.tick();
        events.tick();
        sidebar.tick();
        holograms.tick();
        visibility.tick();
        lobbyItems.tick();
        watchItem.tick();
        if (++seconds % SECONDS_PER_RETRY == 0) {
            stats.retryFailed();
            snapshots.retryFailedDeletes();
            sessions.purgeStale();
        }
    }
}
