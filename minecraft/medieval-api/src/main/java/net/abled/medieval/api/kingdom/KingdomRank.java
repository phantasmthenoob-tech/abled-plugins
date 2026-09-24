package net.abled.medieval.api.kingdom;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Membership ranks inside a kingdom, ordered from least to most authority. */
public enum KingdomRank {

    RECRUIT(0),
    MEMBER(1),
    OFFICER(2),
    LEADER(3),
    FOUNDER(4);

    private final int authority;

    KingdomRank(int authority) {
        this.authority = authority;
    }

    /** Higher values mean more authority. */
    public int authority() {
        return authority;
    }

    /** True when this rank is allowed to perform actions requiring {@code required}. */
    public boolean atLeast(KingdomRank required) {
        Objects.requireNonNull(required, "required");
        return authority >= required.authority;
    }

    /** Parses a rank name case-insensitively, returning empty for unknown input. */
    public static Optional<KingdomRank> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.trim().toUpperCase(Locale.ROOT);
        for (KingdomRank rank : values()) {
            if (rank.name().equals(normalised)) {
                return Optional.of(rank);
            }
        }
        return Optional.empty();
    }
}
