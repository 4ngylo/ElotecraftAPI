package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureUtilsTest {

    @Test
    void durationsParseCombinedUnits() {
        assertEquals(Duration.ofSeconds(93_784), Durations.parse("1d2h3m4s"));
        assertEquals(Duration.ofMinutes(90), Durations.parse("1H 30m"));
        assertEquals(Duration.ofSeconds(45), Durations.parse("45s"));
    }

    @Test
    void durationsRejectBadInput() {
        for (String bad : new String[]{"", "  ", "abc", "10", "5x", "1h-2m", "h1", "99999999999999999999d"}) {
            assertThrows(IllegalArgumentException.class, () -> Durations.parse(bad), bad);
        }
    }

    @Test
    void durationsFormatDropsZeroParts() {
        assertEquals("1d 2h 4s", Durations.format(Duration.ofSeconds(93_604)));
        assertEquals("0s", Durations.format(Duration.ZERO));
        assertEquals("0s", Durations.format(Duration.ofSeconds(-5)));
    }

    @Test
    void cooldownBlocksUntilExpiry() {
        AtomicLong now = new AtomicLong();
        Cooldowns<String> cooldowns = new Cooldowns<>(now::get);
        Duration fiveSeconds = Duration.ofSeconds(5);

        assertTrue(cooldowns.tryUse("a", fiveSeconds));
        assertFalse(cooldowns.tryUse("a", fiveSeconds));
        assertTrue(cooldowns.tryUse("b", fiveSeconds));

        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertEquals(Duration.ofSeconds(3), cooldowns.remaining("a"));

        now.addAndGet(Duration.ofSeconds(3).toNanos());
        assertEquals(Duration.ZERO, cooldowns.remaining("a"));
        assertTrue(cooldowns.tryUse("a", fiveSeconds));

        cooldowns.clear("a");
        assertTrue(cooldowns.tryUse("a", fiveSeconds));
    }

    @Test
    void textParsesMiniMessage() {
        assertEquals("Hello world", Text.plain(Text.mm("<red>Hello <bold>world")));
        assertEquals(NamedTextColor.RED, Text.mm("<red>Hi").color());
    }
}
