package net.abled.medieval.core.admin;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerGateTest {

    private static final UUID OWNER = UUID.fromString("6f6d6564-6965-7661-6c00-000000000001");
    private static final UUID STRANGER = UUID.fromString("6f6d6564-6965-7661-6c00-000000000002");

    @Test
    void matchesTheConfiguredNameRegardlessOfCase() {
        OwnerGate gate = OwnerGate.of("Disgraced_");

        assertTrue(gate.allows(OWNER, "Disgraced_"));
        assertTrue(gate.allows(OWNER, "disgraced_"));
        assertTrue(gate.allows(OWNER, "DISGRACED_"));
        assertTrue(gate.isConfigured());
    }

    @Test
    void ignoresSurroundingWhitespaceInTheConfigurationAndTheName() {
        OwnerGate gate = OwnerGate.of("  Disgraced_  ");

        assertTrue(gate.allows(OWNER, " Disgraced_ "));
    }

    @Test
    void matchesTheConfiguredUuidWithOrWithoutDashes() {
        OwnerGate dashed = OwnerGate.of(OWNER.toString());
        OwnerGate bare = OwnerGate.of(OWNER.toString().replace("-", ""));

        assertTrue(dashed.allows(OWNER, "any name at all"));
        assertTrue(bare.allows(OWNER, "any name at all"));
        // A UUID rule does not fall back to names, so a copied name cannot impersonate the owner.
        assertFalse(dashed.allows(STRANGER, "6f6d6564-6965-7661-6c00-000000000001"));
    }

    @Test
    void refusesEveryoneElse() {
        OwnerGate byName = OwnerGate.of("Disgraced_");
        OwnerGate byId = OwnerGate.of(OWNER.toString());

        assertFalse(byName.allows(STRANGER, "NotTheOwner"));
        assertFalse(byName.allows(STRANGER));
        assertFalse(byName.allows("disgraced")); // a prefix is not a match
        assertFalse(byId.allows(STRANGER, "Disgraced_"));
        assertFalse(byId.allows("Disgraced_"));
    }

    @Test
    void aBlankConfigurationDisablesTheGateInsteadOfThrowing() {
        OwnerGate gate = OwnerGate.of("   ");

        assertFalse(gate.isConfigured());
        assertFalse(gate.allows(OWNER, "Disgraced_"));
        assertFalse(gate.allows(OWNER));
    }

    @Test
    void textThatIsNotAUuidIsTreatedAsAName() {
        // 36 characters, the length of a UUID, but not one.
        String notAUuid = "Disgraced_Not_A_Uuid_At_All_0000000000";

        OwnerGate gate = OwnerGate.of(notAUuid);

        assertTrue(gate.isConfigured());
        assertTrue(gate.allows(OWNER, notAUuid));
        assertFalse(gate.allows(OWNER));
    }

    @Test
    void describeStatesWhichRuleIsInForce() {
        assertTrue(OwnerGate.of("Disgraced_").describe().contains("disgraced_"));
        assertTrue(OwnerGate.of(OWNER.toString()).describe().contains(OWNER.toString()));
        assertTrue(OwnerGate.of("").describe().contains("disabled"));
    }
}
