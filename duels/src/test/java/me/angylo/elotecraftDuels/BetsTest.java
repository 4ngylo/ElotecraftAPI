package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.match.Match;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money bets on duels through a fake Vault economy. */
class BetsTest extends DuelsTestBase {

    private final Map<UUID, Double> balances = new HashMap<>();
    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void economy() {
        Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getName" -> "Fake";
                    case "format" -> String.format(Locale.ROOT, "%.2f", (double) args[0]);
                    case "getBalance" -> balance(args[0]);
                    case "has" -> balance(args[0]) >= (double) args[1];
                    case "withdrawPlayer" -> change(args[0], -(double) args[1]);
                    case "depositPlayer" -> change(args[0], (double) args[1]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        server.getServicesManager().register(Economy.class, economy, MockBukkit.createMockPlugin("Vault"), ServicePriority.Normal);
        alex = join("Alex");
        steve = join("Steve");
        // Several challenges in a row.
        alex.addAttachment(plugin, "duels.bypass.cooldown", true);
        swordKit();
        readyArena("pit");
        balances.put(alex.getUniqueId(), 500.0);
        balances.put(steve.getUniqueId(), 500.0);
    }

    private double balance(Object player) {
        return balances.getOrDefault(((OfflinePlayer) player).getUniqueId(), 0.0);
    }

    private EconomyResponse change(Object player, double amount) {
        UUID uuid = ((OfflinePlayer) player).getUniqueId();
        if (balance(player) + amount < 0) {
            return new EconomyResponse(0, balance(player), EconomyResponse.ResponseType.FAILURE, "Not enough money");
        }
        balances.merge(uuid, amount, Double::sum);
        return new EconomyResponse(Math.abs(amount), balance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    /** Alex challenges Steve for 100 each, Steve accepts: both pay at once. */
    private Match acceptedBet() {
        double alexBefore = balances.get(alex.getUniqueId());
        double steveBefore = balances.get(steve.getUniqueId());
        assertSays(alex, "duel Steve sword pit bet 100", "Bet: 100.00 each; the winner takes 200.00");
        assertSays(steve, "duel accept Alex", "Your stake of 100.00 is held");
        assertEquals(alexBefore - 100, balances.get(alex.getUniqueId()));
        assertEquals(steveBefore - 100, balances.get(steve.getUniqueId()));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        return match;
    }

    @Test
    void theWinnerTakesBothStakesLessTheTax() {
        setConfig("bets.tax", 10);
        assertSays(alex, "duel Steve sword pit bet 100", "the winner takes 180.00");
        assertSays(steve, "duel accept Alex", "held until the duel ends");
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);

        steve.simulateDamage(100, alex);
        tickUntil(() -> !duels.matches().running().contains(match));

        assertEquals(580, balances.get(alex.getUniqueId()));
        assertEquals(400, balances.get(steve.getUniqueId()));
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("You won the bet: +180.00")));
        assertTrue(messages(steve).stream().anyMatch(line -> line.contains("You lost your stake of 100.00")));
    }

    @Test
    void aForfeitLosesTheStakeAndAnAdminStopGivesBothBack() {
        Match first = acceptedBet();
        steve.performCommand("duel leave");
        tickUntil(() -> !duels.matches().running().contains(first));
        assertEquals(600, balances.get(alex.getUniqueId()));
        assertEquals(400, balances.get(steve.getUniqueId()));

        Match second = acceptedBet();
        duels.matches().stop(alex);
        tickUntil(() -> !duels.matches().running().contains(second));
        assertEquals(600, balances.get(alex.getUniqueId()));
        assertEquals(400, balances.get(steve.getUniqueId()));
        assertTrue(messages(steve).stream().anyMatch(line -> line.contains("No winner, so your stake of 100.00 was given back")));
    }

    @Test
    void stakesOfADuelARestartCutShortAreGivenBack() {
        acceptedBet();
        duels.shutdown();
        duels = Duels.start(plugin);
        await(duels.ready());
        tickUntil(() -> balances.get(alex.getUniqueId()) == 500 && balances.get(steve.getUniqueId()) == 500);

        // Given back once: a second restart pays nothing more.
        duels.shutdown();
        duels = Duels.start(plugin);
        await(duels.ready());
        ticks(40);
        assertEquals(500, balances.get(alex.getUniqueId()));
    }

    @Test
    void betsAreCheckedWhenOfferedAndWhenAccepted() {
        assertSays(alex, "duel Steve sword pit bet lots", "'lots' is not an amount of money");
        assertSays(alex, "duel Steve sword pit bet 5", "Bet from 10.00 to 100000.00");
        assertSays(alex, "duel Steve sword pit bet 600", "You don't have 600.00 to bet");

        assertSays(alex, "duel Steve sword pit bet 300", "the winner takes 600.00");
        balances.put(steve.getUniqueId(), 50.0);
        assertSays(steve, "duel accept Alex", "You don't have 300.00 to bet");
        assertEquals(500, balances.get(alex.getUniqueId()));
        assertTrue(duels.matches().matchOf(alex).isEmpty());

        // The challenge is still open once Steve has the money.
        balances.put(steve.getUniqueId(), 300.0);
        assertSays(steve, "duel accept Alex", "held until the duel ends");
        assertEquals(0, balances.get(steve.getUniqueId()));

        duels.matches().stop(alex);
        tickUntil(() -> balances.get(steve.getUniqueId()) == 300);
        setConfig("bets.enabled", false);
        ticks(20 * duels.settings().endDelaySeconds() + 1);
        assertSays(alex, "duel Steve sword pit bet 20", "Bets are off on this server");
    }
}
