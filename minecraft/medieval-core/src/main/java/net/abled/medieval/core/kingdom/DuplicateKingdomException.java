package net.abled.medieval.core.kingdom;

import net.abled.medieval.core.storage.StorageException;

/**
 * Raised when a kingdom name is already taken.
 *
 * <p>Uniqueness is enforced by the database (case-insensitively) so two concurrent creations
 * cannot both succeed; this exception is the domain-level translation of that constraint.
 */
public final class DuplicateKingdomException extends StorageException {

    private final String kingdomName;

    public DuplicateKingdomException(String kingdomName, Throwable cause) {
        super("a kingdom named '" + kingdomName + "' already exists", cause);
        this.kingdomName = kingdomName;
    }

    public String kingdomName() {
        return kingdomName;
    }
}
