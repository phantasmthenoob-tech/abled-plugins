package net.abled.medieval.core.world;

import net.abled.medieval.api.MedievalEvent;

import java.util.Objects;

/**
 * Published when a dimension is opened or closed.
 *
 * <p>Other systems (event schedules, GUIs, announcements) subscribe to this instead of calling the
 * gate directly, so a new gate consumer never has to edit the gate itself.
 *
 * @param dimension the gate that changed
 * @param open      its new state
 * @param actor     who changed it, for logging; {@code console} when no player did
 */
public record DimensionAccessChanged(Dimension dimension, boolean open, String actor) implements MedievalEvent {

    public DimensionAccessChanged {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(actor, "actor");
    }
}
