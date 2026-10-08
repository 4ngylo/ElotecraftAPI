package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.storage.Database;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.hook.VaultEconomy;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Money bets on duels. Both stakes are taken through Vault when the duel is accepted and written to
 * {@code duels_bets}; the winner gets the pot (less {@code bets.tax}) once the duel is over, and a duel that ends
 * without a winner (a draw, a cancel, {@code /duels stop}) gives both stakes back. Stakes still stored when the
 * server starts belong to duels a stop or crash cut short: they are given back once an economy is there.
 * Main thread only.
 */
public final class Bets {

    public static final String PERMISSION = "duels.bet";
    private static final double PERCENT = 100;
    private static final double CENTS = 100;
    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_bets (
                id     VARCHAR(36) NOT NULL,
                uuid   VARCHAR(36) NOT NULL,
                amount DOUBLE      NOT NULL,
                PRIMARY KEY (id, uuid)
            )""";
    private static final String INSERT = "INSERT INTO duels_bets (id, uuid, amount) VALUES (?, ?, ?)";
    private static final String DELETE_ONE = "DELETE FROM duels_bets WHERE id = ? AND uuid = ?";
    private static final String ALL = "SELECT id, uuid, amount FROM duels_bets";

    /** Both players' money for one duel, taken when it was accepted. */
    public record Stake(UUID id, UUID first, UUID second, double amount) {
    }

    /** One stored stake. */
    private record Row(UUID id, UUID player, double amount) {
    }

    private final Logger logger;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final Database db;
    private final VaultEconomy economy;
    private final CompletableFuture<Void> loaded;
    private final Map<Match, Stake> held = new HashMap<>();
    /** Stakes left from before the server started, given back once an economy is there. */
    private final List<Row> leftovers = new ArrayList<>();
    /**
     * Stakes taken since this start. The read of the leftovers runs on a database thread, so it may see a stake taken
     * meanwhile; that one is never given back as a leftover.
     */
    private final Set<UUID> takenSinceStart = new HashSet<>();

    public Bets(Logger logger, Messages messages, Supplier<Settings> settings, Database db, VaultEconomy economy) {
        this.logger = logger;
        this.messages = messages;
        this.settings = settings;
        this.db = db;
        this.economy = economy;
        this.loaded = db.update(CREATE).thenCompose(ignored -> db.query(ALL, rows ->
                        new Row(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("uuid")), rows.getDouble("amount"))))
                .thenAccept(leftovers::addAll);
    }

    /** Completes once the table exists and the stakes left from before were read. */
    public CompletableFuture<Void> ready() {
        return loaded;
    }

    /** {@code amount} rounded to cents; NaN for text that is not a positive amount. */
    public static double parse(String text) {
        try {
            double amount = Math.round(Double.parseDouble(text) * CENTS) / CENTS;
            return Double.isFinite(amount) && amount > 0 ? amount : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Whether {@code sender} may offer a bet of {@code amount}; tells them why not.
     */
    public boolean mayOffer(Player sender, double amount) {
        Settings.Bets config = settings.get().bets();
        if (!config.enabled()) {
            messages.send(sender, "bet.disabled");
        } else if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "command.no-permission");
        } else if (!economy.available()) {
            messages.send(sender, "bet.no-economy");
        } else if (Double.isNaN(amount) || amount < config.min() || amount > config.max()) {
            messages.send(sender, "bet.range", money("min", config.min()), money("max", config.max()));
        } else if (!economy.has(sender, amount)) {
            messages.send(sender, "bet.cant-afford-self", money("amount", amount));
        } else {
            return true;
        }
        return false;
    }

    /** What the winner of a bet of {@code amount} gets: both stakes less the tax. */
    public double pot(double amount) {
        return Math.round(2 * amount * (1 - settings.get().bets().tax() / PERCENT) * CENTS) / CENTS;
    }

    /** {@code <amount>} and {@code <pot>} of a bet of {@code amount}. */
    public TagResolver[] tags(double amount) {
        return new TagResolver[]{money("amount", amount), money("pot", pot(amount))};
    }

    /**
     * Takes {@code amount} from both players and stores the stakes. Checks again that both can pay: their money may
     * have changed since the challenge. Tells {@code accepter} why not.
     *
     * @return null if nothing was taken
     */
    public Stake take(Player challenger, Player accepter, double amount) {
        if (!settings.get().bets().enabled() || !economy.available()) {
            messages.send(accepter, "bet.no-economy");
            return null;
        }
        if (!economy.has(accepter, amount)) {
            messages.send(accepter, "bet.cant-afford-self", money("amount", amount));
            return null;
        }
        if (!economy.has(challenger, amount)) {
            messages.send(accepter, "bet.cant-afford", Placeholder.unparsed("player", challenger.getName()), money("amount", amount));
            return null;
        }
        if (!economy.withdraw(challenger, amount)) {
            messages.send(accepter, "bet.failed");
            return null;
        }
        if (!economy.withdraw(accepter, amount)) {
            economy.deposit(challenger, amount);
            messages.send(accepter, "bet.failed");
            return null;
        }
        Stake stake = new Stake(UUID.randomUUID(), challenger.getUniqueId(), accepter.getUniqueId(), amount);
        takenSinceStart.add(stake.id());
        // ponytail: the stakes are stored a moment after they are taken; a crash in between loses them.
        // Storing first would hand out money never taken if the server died before the withdrawals.
        store(stake);
        for (Player player : List.of(challenger, accepter)) {
            messages.send(player, "bet.taken", money("amount", amount));
        }
        return stake;
    }

    /** {@code stake} is now for {@code match}: settled when it is over. */
    public void hold(Match match, Stake stake) {
        held.put(match, stake);
    }

    /** Gives both stakes back, telling the players. */
    public void refund(Stake stake) {
        for (UUID player : List.of(stake.first(), stake.second())) {
            pay(stake, player, stake.amount(), "bet.refunded", money("amount", stake.amount()));
        }
    }

    /** A match is over: its winner gets the pot; without a winner both stakes go back. */
    public void finished(Match match) {
        Stake stake = held.remove(match);
        if (stake == null) {
            return;
        }
        if (match.winnerTeams().size() != 1) {
            refund(stake);
            return;
        }
        Player winner = match.teams().get(match.winnerTeams().getFirst()).getFirst();
        UUID loser = winner.getUniqueId().equals(stake.first()) ? stake.second() : stake.first();
        double pot = pot(stake.amount());
        if (!pay(stake, winner.getUniqueId(), pot, "bet.won", money("pot", pot))) {
            // Both stakes stay stored and go back next start.
            return;
        }
        // The loser's stake goes with the payout: the pot holds both.
        delete(stake.id(), loser);
        Player online = Bukkit.getPlayer(loser);
        if (online != null) {
            messages.send(online, "bet.lost", money("amount", stake.amount()));
        }
        logger.info(winner.getName() + " won a bet of " + stake.amount() + " each, paid " + pot);
    }

    /** Once a second: gives back the stakes left from before the server started, once an economy is there. */
    public void tick() {
        if (leftovers.isEmpty() || !economy.available()) {
            return;
        }
        List<Row> rows = leftovers.stream().filter(row -> !takenSinceStart.contains(row.id())).toList();
        leftovers.clear();
        double total = 0;
        for (Row row : rows) {
            if (economy.deposit(Bukkit.getOfflinePlayer(row.player()), row.amount()).isPresent()) {
                delete(row.id(), row.player());
                total += row.amount();
            } else {
                logger.severe("Could not give back a duel bet of " + row.amount() + " to " + row.player() + "; it is tried again next start");
            }
        }
        if (total > 0) {
            logger.info("Gave back " + total + " in duel bets a stop or crash cut short");
        }
    }

    /** Forgets the stakes of running duels without paying; for shutdown. They stay stored and go back next start. */
    public void clear() {
        held.clear();
    }

    /**
     * Pays {@code amount} of {@code stake} to {@code player}, then deletes their stake; tells them if online.
     *
     * @return false if the payment failed: their stake stays stored and is given back when the server starts again
     */
    private boolean pay(Stake stake, UUID player, double amount, String messageKey, TagResolver tag) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(player);
        if (economy.deposit(target, amount).isEmpty()) {
            logger.severe("Could not pay a duel bet of " + amount + " to " + target.getName() + "; it is given back next start");
            return false;
        }
        delete(stake.id(), player);
        if (target.getPlayer() != null) {
            messages.send(target.getPlayer(), messageKey, tag);
        }
        return true;
    }

    private void store(Stake stake) {
        db.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                for (UUID player : List.of(stake.first(), stake.second())) {
                    insert.setString(1, stake.id().toString());
                    insert.setString(2, player.toString());
                    insert.setDouble(3, stake.amount());
                    insert.executeUpdate();
                }
            }
            return null;
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Could not store a duel bet; a crash before it ends would lose the stakes", error);
            return null;
        });
    }

    private void delete(UUID id, UUID player) {
        db.update(DELETE_ONE, id.toString(), player.toString()).exceptionally(error -> {
            logger.log(Level.SEVERE, "Could not delete a settled duel bet; it may be given back again next start", error);
            return null;
        });
    }

    private TagResolver money(String tag, double amount) {
        return Placeholder.unparsed(tag, economy.format(amount));
    }
}
