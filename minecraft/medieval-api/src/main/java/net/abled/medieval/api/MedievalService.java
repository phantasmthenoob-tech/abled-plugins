package net.abled.medieval.api;

/** A lifecycle-managed unit of the Medieval system. */
public interface MedievalService {

    /** Stable identifier used in logs and diagnostics. */
    String name();

    default void onEnable() {
    }

    default void onDisable() {
    }
}
