package me.angylo.elotecraftAPI.hud;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-player sidebar with MiniMessage title and lines, without the red score numbers.
 * <pre>{@code
 * Sidebar sidebar = Sidebar.show(plugin, player, "<gold><bold>Elotecraft");
 * sidebar.lines("<gray>Coins: <yellow>" + coins, "", "<aqua>play.elotecraft.net");
 * }</pre>
 * Gives the player their own scoreboard, so it replaces any other plugin's sidebar or teams for them.
 * One sidebar per player; showing a new one replaces the old. Removed when the player quits or the
 * plugin disables. Main thread only.
 */
public final class Sidebar {

    public static final int MAX_LINES = 15;

    private static final Map<UUID, Sidebar> SHOWN = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final Player player;
    private final Scoreboard scoreboard;
    private final Objective objective;
    private Component title;
    /** The lines on screen, so unchanged ones are not sent again. */
    private List<Component> shown = List.of();

    private Sidebar(Plugin plugin, Player player, Component title) {
        this.plugin = plugin;
        this.player = player;
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective("sidebar", Criteria.DUMMY, title);
        this.title = title;
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
    }

    /** @param title MiniMessage */
    public static Sidebar show(Plugin plugin, Player player, String title) {
        return show(plugin, player, Text.mm(title));
    }

    public static Sidebar show(Plugin plugin, Player player, Component title) {
        Sidebar sidebar = new Sidebar(plugin, player, title);
        Sidebar replaced = SHOWN.put(player.getUniqueId(), sidebar);
        if (replaced != null) {
            replaced.objective.unregister();
        }
        player.setScoreboard(sidebar.scoreboard);
        return sidebar;
    }

    /** @param title MiniMessage */
    public Sidebar title(String title) {
        return title(Text.mm(title));
    }

    /** Sets the title; sends nothing if it is unchanged. */
    public Sidebar title(Component title) {
        if (!title.equals(this.title)) {
            objective.displayName(title);
            this.title = title;
        }
        return this;
    }

    /** MiniMessage lines, top to bottom. */
    public Sidebar lines(String... lines) {
        return lines(Arrays.stream(lines).map(Text::mm).toList());
    }

    /**
     * Replaces all lines, top to bottom. Lines equal to the ones shown are not sent again, so calling
     * this every second with mostly the same lines is cheap.
     *
     * @throws IllegalArgumentException if there are more than {@value #MAX_LINES} lines
     */
    public Sidebar lines(List<Component> lines) {
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("A sidebar shows at most " + MAX_LINES + " lines, got " + lines.size());
        }
        // Scores order the lines, so they all change when the number of lines does.
        boolean sameCount = lines.size() == shown.size();
        for (int i = 0; i < lines.size(); i++) {
            if (sameCount && lines.get(i).equals(shown.get(i))) {
                continue;
            }
            Score score = objective.getScore(entry(i));
            score.setScore(lines.size() - i);
            score.customName(lines.get(i));
        }
        for (int i = lines.size(); i < shown.size(); i++) {
            scoreboard.resetScores(entry(i));
        }
        shown = List.copyOf(lines);
        return this;
    }

    /** Removes the sidebar and gives the player the server's main scoreboard back. */
    public void hide() {
        if (SHOWN.remove(player.getUniqueId(), this)) {
            objective.unregister();
            if (player.isOnline()) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }

    /** The sidebar currently shown to {@code player} by any plugin, if any. */
    public static Optional<Sidebar> of(Player player) {
        return Optional.ofNullable(SHOWN.get(player.getUniqueId()));
    }

    /** Hides every sidebar {@code plugin} showed; called automatically when it disables. */
    public static void hideAll(Plugin plugin) {
        SHOWN.values().stream().filter(sidebar -> sidebar.plugin.equals(plugin)).toList().forEach(Sidebar::hide);
    }

    /** Drops a quitting player's sidebar; called automatically. */
    public static void forget(UUID uuid) {
        SHOWN.remove(uuid);
    }

    /** Each line needs a unique, never-shown entry name; customName is what the player sees. */
    private static String entry(int line) {
        return "line" + line;
    }
}
