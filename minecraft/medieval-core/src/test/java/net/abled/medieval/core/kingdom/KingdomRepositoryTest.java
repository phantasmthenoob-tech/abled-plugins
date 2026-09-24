package net.abled.medieval.core.kingdom;

import net.abled.medieval.api.kingdom.KingdomRank;
import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import net.abled.medieval.core.territory.ChunkPosition;
import net.abled.medieval.core.territory.ClaimRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingdomRepositoryTest {

    private static final UUID FOUNDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RECRUIT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant CREATED = Instant.parse("2026-09-10T12:00:00Z");
    private static final Instant JOINED = Instant.parse("2026-09-11T12:00:00Z");

    @TempDir
    Path tempDir;

    private StorageService storage;
    private TestDatabases.CollectingLog log;
    private KingdomRepository repository;
    private ClaimRepository claims;

    @BeforeEach
    void setUp() {
        log = new TestDatabases.CollectingLog();
        storage = TestDatabases.open(tempDir.resolve("kingdoms.db"));
        repository = new KingdomRepository(storage.database(), log);
        claims = new ClaimRepository(storage.database(), log);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void createsAKingdomWithItsFounderMembership() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);

        assertTrue(kingdom.id() > 0);
        assertEquals("Avalon", kingdom.name());
        assertEquals(FOUNDER, kingdom.founderId());
        assertEquals(List.of(FOUNDER), repository.members(kingdom.id()).stream()
                .map(KingdomMemberRecord::playerId).toList());
        assertEquals(KingdomRank.FOUNDER, repository.members(kingdom.id()).get(0).rank());
        assertEquals(kingdom.id(), repository.kingdomOf(FOUNDER).orElseThrow());
    }

    @Test
    void findsKingdomsByNameIgnoringCase() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);

        assertEquals(kingdom.id(), repository.findByName("avalon").orElseThrow().id());
        assertEquals(kingdom.id(), repository.findById(kingdom.id()).orElseThrow().id());
        assertTrue(repository.findByName("Camelot").isEmpty());
        assertTrue(repository.findById(9_999L).isEmpty());
    }

    @Test
    void rejectsDuplicateKingdomNames() {
        repository.create("Avalon", "AVL", FOUNDER, CREATED);

        assertThrows(DuplicateKingdomException.class,
                () -> repository.create("avalon", "OTHER", RECRUIT, CREATED));
        assertEquals(1, repository.list().size());
    }

    @Test
    void doesNotLeaveHalfCreatedKingdomsBehind() {
        repository.create("Avalon", "AVL", FOUNDER, CREATED);

        assertThrows(DuplicateKingdomException.class,
                () -> repository.create("Avalon", "AVL2", RECRUIT, CREATED));

        assertTrue(repository.kingdomOf(RECRUIT).isEmpty(),
                "the failed creation must not leave a membership row behind");
        assertEquals(1, repository.list().size());
    }

    @Test
    void updatesMemberRanksWithoutResettingTheJoinDate() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);
        repository.setMember(kingdom.id(), RECRUIT, KingdomRank.RECRUIT, JOINED);

        repository.setMember(kingdom.id(), RECRUIT, KingdomRank.OFFICER, JOINED.plusSeconds(600));

        KingdomMemberRecord member = repository.members(kingdom.id()).stream()
                .filter(record -> record.playerId().equals(RECRUIT))
                .findFirst()
                .orElseThrow();
        assertEquals(KingdomRank.OFFICER, member.rank());
        assertEquals(JOINED, member.joinedAt(), "the original join date is kept");
    }

    @Test
    void removesMembers() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);
        repository.setMember(kingdom.id(), RECRUIT, KingdomRank.RECRUIT, JOINED);

        assertTrue(repository.removeMember(kingdom.id(), RECRUIT));
        assertFalse(repository.removeMember(kingdom.id(), RECRUIT));
        assertTrue(repository.kingdomOf(RECRUIT).isEmpty());
    }

    @Test
    void deletingAKingdomRemovesMembersAndClaims() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);
        ChunkPosition position = new ChunkPosition("world", 3, -4);
        claims.claim(position, kingdom.id(), CREATED);

        assertTrue(repository.delete(kingdom.id()));

        assertTrue(repository.members(kingdom.id()).isEmpty());
        assertTrue(claims.find(position).isEmpty());
        assertFalse(repository.delete(kingdom.id()));
    }

    @Test
    void fallsBackToMemberForUnknownStoredRanks() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);
        insertMemberWithRawRank(kingdom.id(), RECRUIT, "EMPEROR");

        KingdomMemberRecord member = repository.members(kingdom.id()).stream()
                .filter(record -> record.playerId().equals(RECRUIT))
                .findFirst()
                .orElseThrow();

        assertEquals(KingdomRank.MEMBER, member.rank());
        assertFalse(log.warnings().isEmpty(), "an unexpected rank must be reported");
    }

    @Test
    void skipsMembersWithUnreadableUuids() {
        KingdomRecord kingdom = repository.create("Avalon", "AVL", FOUNDER, CREATED);
        insertMemberWithRawRank(kingdom.id(), null, "MEMBER");

        List<KingdomMemberRecord> members = repository.members(kingdom.id());

        assertEquals(1, members.size(), "only the founder remains readable");
        assertEquals(FOUNDER, members.get(0).playerId());
        assertFalse(log.errors().isEmpty(), "a malformed row must be reported");
    }

    @Test
    void listsKingdomsAlphabetically() {
        repository.create("Camelot", "CAM", FOUNDER, CREATED);
        repository.create("Avalon", "AVL", UUID.randomUUID(), CREATED);

        assertEquals(List.of("Avalon", "Camelot"), repository.list().stream().map(KingdomRecord::name).toList());
    }

    @Test
    void rejectsBlankNamesAndTags() {
        assertThrows(IllegalArgumentException.class, () -> repository.create(" ", "AVL", FOUNDER, CREATED));
        assertThrows(IllegalArgumentException.class, () -> repository.create("Avalon", "", FOUNDER, CREATED));
    }

    private void insertMemberWithRawRank(long kingdomId, UUID playerId, String rawRank) {
        String storedUuid = playerId == null ? "not-a-uuid" : playerId.toString();
        storage.database().update(
                "INSERT INTO kingdom_members (kingdom_id, player_uuid, rank, joined_at) VALUES (?, ?, ?, ?)",
                statement -> {
                    statement.setLong(1, kingdomId);
                    statement.setString(2, storedUuid);
                    statement.setString(3, rawRank);
                    statement.setLong(4, JOINED.toEpochMilli());
                });
    }
}
