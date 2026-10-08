package me.angylo.elotecraftDuels.event;

import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An event a player hosts while players gather for it: its settings and who joined. Once it starts it
 * becomes a match and this is dropped. Main thread only.
 */
public final class HostedEvent {

    /**
     * Everyone for themselves, two teams, or a single-elimination tournament of 1v1 fights: all fights of a
     * round at once, or (sumo) one fight at a time while the others watch.
     */
    public enum Mode {
        FFA, TEAMS, TOURNAMENT, SUMO;

        /** Lower case, for config.yml, messages.yml ({@code event.mode-<key>}) and menus.yml. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isTournament() {
            return this == TOURNAMENT || this == SUMO;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** The host of events config.yml {@code events.schedule} starts: nobody plays for it. */
    public static final UUID SERVER = new UUID(0, 0);

    private final UUID host;
    private final String hostName;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> invited = new HashSet<>();
    /** Game rules the host changed from the kit's, for this event only. */
    private final Map<KitRule, Boolean> rules = new EnumMap<>(KitRule.class);
    private String kit;
    /** Null for a random arena. */
    private String arena;
    private Mode mode = Mode.FFA;
    private int winners = 1;
    private boolean open = true;
    private boolean spectatable = true;
    private boolean border;
    private int secondsLeft;
    private int secondsToAnnounce;

    /** @param host {@link #SERVER} for a scheduled event, which the host does not join */
    HostedEvent(UUID host, String hostName, String kit, int secondsLeft, int secondsToAnnounce) {
        this.host = host;
        this.hostName = hostName;
        this.kit = kit;
        this.secondsLeft = secondsLeft;
        this.secondsToAnnounce = secondsToAnnounce;
        if (!host.equals(SERVER)) {
            players.add(host);
        }
    }

    public UUID host() {
        return host;
    }

    public String hostName() {
        return hostName;
    }

    public boolean isHost(UUID player) {
        return host.equals(player);
    }

    /** Everyone who joined, the host first. */
    public List<UUID> players() {
        return List.copyOf(players);
    }

    public int size() {
        return players.size();
    }

    public boolean isInvited(UUID player) {
        return invited.contains(player);
    }

    /** The kit's name. */
    public String kit() {
        return kit;
    }

    /** The arena's name, or null for a random one. */
    public String arena() {
        return arena;
    }

    public Mode mode() {
        return mode;
    }

    /** How many players win a free-for-all; other events have one winning team or champion. */
    public int winners() {
        return mode == Mode.FFA ? winners : 1;
    }

    /** Public: announced and listed in {@code /event}; private: invited players only. */
    public boolean isOpen() {
        return open;
    }

    public boolean isSpectatable() {
        return spectatable;
    }

    public boolean hasBorder() {
        return border;
    }

    /** Seconds until it starts on its own. */
    public int secondsLeft() {
        return secondsLeft;
    }

    /** {@code rule} in this event: the host's choice, else the kit's. */
    public boolean flag(Kit base, KitRule rule, Settings settings) {
        return rules.getOrDefault(rule, base.flag(rule, settings));
    }

    /** Whether the host changed {@code rule} from the kit's. */
    public boolean changed(KitRule rule) {
        return rules.containsKey(rule);
    }

    /** {@code base} with the rules the host changed. */
    public Kit kitFor(Kit base) {
        Kit kit = base;
        for (Map.Entry<KitRule, Boolean> rule : rules.entrySet()) {
            kit = kit.withRule(rule.getKey(), rule.getValue());
        }
        return kit;
    }

    boolean add(UUID player) {
        return players.add(player);
    }

    boolean remove(UUID player) {
        return players.remove(player);
    }

    void invite(UUID player) {
        invited.add(player);
    }

    /** A new kit drops the rule changes, which were made against the old one. */
    void kit(String newKit) {
        if (!newKit.equals(kit)) {
            rules.clear();
        }
        kit = newKit;
    }

    void arena(String newArena) {
        arena = newArena;
    }

    void mode(Mode newMode) {
        mode = newMode;
    }

    void winners(int newWinners) {
        winners = newWinners;
    }

    void open(boolean newOpen) {
        open = newOpen;
    }

    void spectatable(boolean newSpectatable) {
        spectatable = newSpectatable;
    }

    void border(boolean newBorder) {
        border = newBorder;
    }

    /** @param value null for the kit's own */
    void rule(KitRule rule, Boolean value) {
        if (value == null) {
            rules.remove(rule);
        } else {
            rules.put(rule, value);
        }
    }

    /** Counts a second down; returns the seconds left. */
    int countDown() {
        return --secondsLeft;
    }

    /** Whether it is time to announce it again; counts a second down. */
    boolean announceDue(int intervalSeconds) {
        if (--secondsToAnnounce > 0) {
            return false;
        }
        secondsToAnnounce = intervalSeconds;
        return true;
    }
}
