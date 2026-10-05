package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.storage.Database;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstances;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.command.AdminCommand;
import me.angylo.elotecraftDuels.command.DuelCommand;
import me.angylo.elotecraftDuels.hook.PlaceholderHook;
import me.angylo.elotecraftDuels.hook.SlimeWorlds;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.listener.BuildListener;
import me.angylo.elotecraftDuels.listener.CombatListener;
import me.angylo.elotecraftDuels.listener.ProtectionListener;
import me.angylo.elotecraftDuels.listener.SessionListener;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.match.RequestManager;
import me.angylo.elotecraftDuels.menu.ArenaMenu;
import me.angylo.elotecraftDuels.menu.KitMenu;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import me.angylo.elotecraftDuels.stats.StatsService;
import org.bukkit.Bukkit;
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
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final ConfigFile config;
    private final ConfigFile menus;
    private final Messages messages;
    private final Database database;
    private final ArenaRegistry arenas;
    private final KitRegistry kits;
    private final StatsService stats;
    private final SnapshotStore snapshots;
    private final SlimeWorlds slime;
    private final ArenaInstances instances;
    private final MatchManager matches;
    private final RequestManager requests;
    private final QueueManager queues;
    private final SessionListener sessions;
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
        this.snapshots = new SnapshotStore(plugin, database);
        this.slime = settings.slimeEnabled() ? SlimeWorlds.detect(plugin).orElse(null) : null;
        this.instances = new ArenaInstances(plugin, this::settings, arenas, slime);
        this.matches = new MatchManager(plugin, messages, this::settings, arenas, instances, snapshots, stats);
        this.requests = new RequestManager(messages, this::settings, kits, arenas, matches);
        this.queues = new QueueManager(messages, this::settings, kits, matches, stats);
        KitMenu kitMenu = new KitMenu(plugin, messages, menus, this::settings, kits, matches, queues);
        ArenaMenu arenaMenu = new ArenaMenu(plugin, messages, menus, this::settings, arenas, matches);

        Command duel = new DuelCommand(this, kitMenu, arenaMenu).register();
        new AdminCommand(this).register();
        this.sessions = new SessionListener(plugin, messages, stats, snapshots, matches, requests, queues);
        for (Listener listener : List.of(sessions,
                new CombatListener(plugin, messages, this::settings, matches, snapshots),
                new ProtectionListener(messages, this::settings, matches, duel),
                new BuildListener(this::settings, matches, instances, arenas))) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
        // After a reload the players are already online: load them as if they had just joined.
        Bukkit.getOnlinePlayers().forEach(sessions::load);
        this.placeholders = PlaceholderHook.register(this);
        if (slime != null) {
            slime.loadTemplates();
        }
        this.ticker = Tasks.timer(plugin, this::tick, SECOND_TICKS, SECOND_TICKS);
        plugin.getLogger().info("Loaded " + arenas.all().size() + " arenas (" + arenas.all().stream().filter(Arena::isReady).count()
                + " ready) and " + kits.all().size() + " kits" + (placeholders.registered() ? "; PlaceholderAPI hooked" : "")
                + (slime != null ? "; AdvancedSlimePaper found, arenas in its worlds are copied for each duel" : ""));
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

    public StatsService stats() {
        return stats;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public ArenaInstances instances() {
        return instances;
    }

    /** AdvancedSlimePaper support, when the server runs it and {@code slime.enabled} is on. */
    public Optional<SlimeWorlds> slime() {
        return Optional.ofNullable(slime);
    }

    public MatchManager matches() {
        return matches;
    }

    public RequestManager requests() {
        return requests;
    }

    public QueueManager queues() {
        return queues;
    }

    /** Completes once the database tables exist. */
    public CompletableFuture<Void> ready() {
        return CompletableFuture.allOf(stats.ready(), snapshots.ready());
    }

    /**
     * Reloads config.yml, messages, menus.yml, arenas.yml and kits.yml; running duels keep the arena and
     * kit they started with. The database and {@code slime.enabled} need a restart.
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
        return ok;
    }

    /** Puts every player back, saves pending data and closes the database. Call from {@code onDisable}. */
    public void shutdown() {
        ticker.cancel();
        placeholders.unregister();
        matches.shutdown();
        instances.shutdown();
        snapshots.awaitSaves(SHUTDOWN_WAIT_SECONDS);
        requests.clear();
        queues.clear();
        stats.retryFailed();
        snapshots.retryFailedDeletes();
        arenas.saveNow();
        kits.saveNow();
        database.close();
    }

    private void tick() {
        matches.purgeExpiredRematches();
        requests.tick();
        queues.tick();
        if (++seconds % SECONDS_PER_RETRY == 0) {
            stats.retryFailed();
            snapshots.retryFailedDeletes();
            sessions.purgeStale();
        }
    }
}
