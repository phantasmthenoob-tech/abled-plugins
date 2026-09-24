#!/usr/bin/env bash
#
# Downloads the pieces needed for a local Medieval test server into this folder.
#
# This script is a developer convenience only: the plugin build never depends on it, and no
# server binary is committed to the repository. Run it from anywhere:
#
#   sh minecraft/test-server/fetch-dependencies.sh
#
# Requires curl. Nothing is executed automatically after downloading.

set -eu

SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGINS_DIR="${SERVER_DIR}/plugins"
MINECRAFT_VERSION="26.2"

mkdir -p "${PLUGINS_DIR}"

echo "Target directory: ${SERVER_DIR}"

# ---------------------------------------------------------------------------
# Paper 26.2 (latest stable build, resolved through the official fill API)
# ---------------------------------------------------------------------------
echo "Resolving Paper ${MINECRAFT_VERSION}..."
PAPER_META="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${MINECRAFT_VERSION}/builds/latest")"
PAPER_URL="$(printf '%s' "${PAPER_META}" | grep -o '"url": "[^"]*"' | head -1 | cut -d'"' -f4)"

if [ -z "${PAPER_URL}" ]; then
    echo "ERROR: could not resolve the Paper download URL. Download it manually from https://papermc.io/downloads/paper" >&2
    exit 1
fi

echo "Downloading Paper server jar..."
curl -fsSL -o "${SERVER_DIR}/server.jar" "${PAPER_URL}"

# ---------------------------------------------------------------------------
# Geyser + Floodgate (Bedrock clients); official download API
# ---------------------------------------------------------------------------
echo "Downloading Geyser (Spigot build)..."
curl -fsSL -o "${PLUGINS_DIR}/Geyser-Spigot.jar" \
    "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"

echo "Downloading Floodgate (Spigot build)..."
curl -fsSL -o "${PLUGINS_DIR}/floodgate.jar" \
    "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot"

# ---------------------------------------------------------------------------
# ViaVersion / ViaBackwards: cross-client support.
# Download these manually from the official project pages, then drop them in plugins/.
# ---------------------------------------------------------------------------
echo
echo "Still required (download manually, do not commit):"
echo "  ViaVersion   https://hangar.papermc.io/ViaVersion/ViaVersion"
echo "  ViaBackwards https://hangar.papermc.io/ViaVersion/ViaBackwards"

# ---------------------------------------------------------------------------
# Java 25 is required to run Paper 26.2 and to build the plugin.
# EULA: running a Minecraft server requires accepting it.
# ---------------------------------------------------------------------------
echo "eula=true" > "${SERVER_DIR}/eula.txt"

echo
echo "Next steps:"
echo "  1. Build and copy the plugin:  cd minecraft && ./gradlew build"
echo "     cp medieval-paper/build/libs/Medieval-*.jar test-server/plugins/"
echo "  2. Copy the medieval resource pack once the asset pipeline exists."
echo "  3. Start the server with a Java 25 runtime:  cd test-server && java -Xmx4G -jar server.jar --nogui"
