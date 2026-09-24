package net.abled.medieval.api;

import java.util.UUID;

/**
 * Everything the shared core needs from the host server, expressed without any
 * server-specific types. Implemented by the Paper module.
 */
public interface MedievalPlatform {

    /** Implementation name reported by the server, for example {@code CraftBukkit}. */
    String serverName();

    /** Full server build string, for example {@code git-Paper-129 (MC: 26.2)}. */
    String serverVersion();

    /** True when a plugin with this exact name is installed and currently enabled. */
    boolean isPluginPresent(String pluginName);

    /** True when the player connects through Geyser or Floodgate. */
    boolean isBedrockPlayer(UUID playerId);

    void logInfo(String message);

    void logWarning(String message);

    void logError(String message, Throwable cause);
}
