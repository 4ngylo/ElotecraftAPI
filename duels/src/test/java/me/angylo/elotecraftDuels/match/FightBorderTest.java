package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftDuels.Settings;
import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event border's size over the fight. MockBukkit cannot show world borders, so only the math runs here. */
class FightBorderTest {

    private final FightBorder border = FightBorder.around(new BoundingBox(0, 60, 0, 20, 80, 30),
            new Settings.Border(Duration.ofSeconds(10), Duration.ofSeconds(20), 5, 1));

    @Test
    void itWaitsThenClosesInEvenlyToItsSmallestSize() {
        assertEquals(31, border.size(0));
        assertEquals(31, border.size(10));
        assertEquals(18, border.size(20));
        assertEquals(5, border.size(30));
        assertEquals(5, border.size(500));
    }

    @Test
    void fightersFarFromTheCenterAreOutsideOnceItClosesIn() {
        Location nearEdge = new Location(null, 1, 64, 15);

        assertFalse(border.isOutside(nearEdge, 0));
        assertTrue(border.isOutside(nearEdge, 30));
        assertFalse(border.isOutside(new Location(null, 10, 64, 15), 30));
    }
}
