package net.abled.medieval.api.kingdom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingdomRankTest {

    @Test
    void authorityIncreasesWithRank() {
        assertTrue(KingdomRank.MEMBER.authority() < KingdomRank.OFFICER.authority());
        assertTrue(KingdomRank.OFFICER.authority() < KingdomRank.LEADER.authority());
        assertTrue(KingdomRank.LEADER.authority() < KingdomRank.FOUNDER.authority());
    }

    @Test
    void atLeastComparesAuthority() {
        assertTrue(KingdomRank.FOUNDER.atLeast(KingdomRank.LEADER));
        assertTrue(KingdomRank.OFFICER.atLeast(KingdomRank.OFFICER));
        assertFalse(KingdomRank.RECRUIT.atLeast(KingdomRank.MEMBER));
    }

    @Test
    void parsesRankNamesCaseInsensitively() {
        assertEquals(KingdomRank.OFFICER, KingdomRank.byName("officer").orElseThrow());
        assertEquals(KingdomRank.LEADER, KingdomRank.byName("  Leader ").orElseThrow());
        assertTrue(KingdomRank.byName("emperor").isEmpty());
        assertTrue(KingdomRank.byName(null).isEmpty());
    }

    @Test
    void atLeastRejectsNull() {
        assertThrows(NullPointerException.class, () -> KingdomRank.MEMBER.atLeast(null));
    }
}
