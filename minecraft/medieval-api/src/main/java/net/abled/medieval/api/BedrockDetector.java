package net.abled.medieval.api;

import java.util.UUID;

/**
 * Detects Bedrock Edition players.
 *
 * <p>The Geyser module installs a real implementation once it is present; when Geyser is not
 * installed every player is treated as a Java client, which is the correct behaviour rather
 * than a placeholder.
 */
@FunctionalInterface
public interface BedrockDetector {

    boolean isBedrockPlayer(UUID playerId);

    /** Detector used when no Bedrock bridge is installed. */
    static BedrockDetector none() {
        return playerId -> false;
    }
}
