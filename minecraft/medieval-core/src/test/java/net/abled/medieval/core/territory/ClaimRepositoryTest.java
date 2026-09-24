package net.abled.medieval.core.territory;

import net.abled.medieval.core.kingdom.KingdomRecord;
import net.abled.medieval.core.kingdom.KingdomRepository;
import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.StorageLog;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimRepositoryTest {

    private static final Instant CLAIMED = Instant.parse("2026-09-15T09:00:00Z");

    @TempDir
    Path tempDir;

    private StorageService storage;
    private ClaimRepository claims;
    private KingdomRepository kingdoms;
    private long avalon;
    private long camelot;

    @BeforeEach
    void setUp() {
        storage = TestDatabases.open(tempDir.resolve("claims.db"));
        claims = new ClaimRepository(storage.database(), StorageLog.noop());
        kingdoms = new KingdomRepository(storage.database(), StorageLog.noop());

        avalon = kingdoms.create("Avalon", "AVL", UUID.randomUUID(), CLAIMED).id();
        camelot = kingdoms.create("Camelot", "CAM", UUID.randomUUID(), CLAIMED).id();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void claimsAnUnclaimedChunk() {
        ChunkPosition position = new ChunkPosition("world", 0, 0);

        Optional<ClaimRecord> previous = claims.claim(position, avalon, CLAIMED);

        assertTrue(previous.isEmpty());
        assertEquals(avalon, claims.find(position).orElseThrow().kingdomId());
        assertEquals(CLAIMED, claims.find(position).orElseThrow().claimedAt());
    }

    @Test
    void reportsThePreviousOwnerWhenReclaiming() {
        ChunkPosition position = new ChunkPosition("world", 5, -7);
        claims.claim(position, avalon, CLAIMED);

        Optional<ClaimRecord> previous = claims.claim(position, camelot, CLAIMED.plusSeconds(60));

        assertEquals(avalon, previous.orElseThrow().kingdomId());
        assertEquals(camelot, claims.find(position).orElseThrow().kingdomId());
        assertEquals(1, claims.countByKingdom(camelot));
        assertEquals(0, claims.countByKingdom(avalon));
    }

    @Test
    void keepsWorldsSeparate() {
        ChunkPosition overworld = new ChunkPosition("world", 1, 2);
        ChunkPosition nether = new ChunkPosition("world_nether", 1, 2);

        claims.claim(overworld, avalon, CLAIMED);
        claims.claim(nether, camelot, CLAIMED);

        assertEquals(avalon, claims.find(overworld).orElseThrow().kingdomId());
        assertEquals(camelot, claims.find(nether).orElseThrow().kingdomId());
        assertEquals(2, claims.countByKingdom(avalon) + claims.countByKingdom(camelot));
    }

    @Test
    void listsClaimsOfAKingdom() {
        claims.claim(new ChunkPosition("world", 2, 1), avalon, CLAIMED);
        claims.claim(new ChunkPosition("world", 1, 1), avalon, CLAIMED);
        claims.claim(new ChunkPosition("world", 9, 9), camelot, CLAIMED);

        assertEquals(2, claims.byKingdom(avalon).size());
        assertEquals(new ChunkPosition("world", 1, 1), claims.byKingdom(avalon).get(0).position(),
                "claims are returned in a stable order");
    }

    @Test
    void countsClaimsPerKingdom() {
        claims.claim(new ChunkPosition("world", 0, 0), avalon, CLAIMED);
        claims.claim(new ChunkPosition("world", 1, 0), avalon, CLAIMED);

        assertEquals(2, claims.countByKingdom(avalon));
        assertEquals(0, claims.countByKingdom(camelot));
    }

    @Test
    void unclaimsChunks() {
        ChunkPosition position = new ChunkPosition("world", 4, 4);
        claims.claim(position, avalon, CLAIMED);

        assertTrue(claims.unclaim(position));
        assertFalse(claims.unclaim(position));
        assertTrue(claims.find(position).isEmpty());
    }

    @Test
    void releasesEveryClaimOfAKingdom() {
        claims.claim(new ChunkPosition("world", 0, 0), avalon, CLAIMED);
        claims.claim(new ChunkPosition("world", 1, 0), avalon, CLAIMED);

        assertEquals(2, claims.releaseAll(avalon));
        assertEquals(0, claims.countByKingdom(avalon));
    }

    @Test
    void removesClaimsWhenTheirKingdomIsDeleted() {
        ChunkPosition position = new ChunkPosition("world", 8, 8);
        claims.claim(position, avalon, CLAIMED);

        kingdoms.delete(avalon);

        assertTrue(claims.find(position).isEmpty());
    }

    @Test
    void claimsAndReleasesAreAtomic() {
        ChunkPosition position = new ChunkPosition("world", 6, 6);
        KingdomRecord kingdom = kingdoms.findByName("Avalon").orElseThrow();

        claims.claim(position, kingdom.id(), CLAIMED);

        assertEquals(1, claims.countByKingdom(kingdom.id()));
        assertEquals(kingdom.id(), claims.find(position).orElseThrow().kingdomId());
    }
}
