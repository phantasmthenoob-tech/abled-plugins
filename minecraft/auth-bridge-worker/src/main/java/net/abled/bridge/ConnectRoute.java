package net.abled.bridge;

import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Where the worker's headless client comes from, as the upstream will see it.
 *
 * <p>A TCP source address cannot be forged: the upstream answers the address it received, so a
 * made-up one never completes a handshake, and nothing in the Minecraft handshake can change the
 * socket's peer address on a server that reads it directly. The two settings that really change
 * what the upstream records are here:
 *
 * <ul>
 *   <li><b>bind address</b> - the local address the connection leaves from. Only useful when the
 *       host owns more than one address; the upstream then sees that one.</li>
 *   <li><b>proxy</b> - SOCKS4/SOCKS5/HTTP. Every verification leaves through the proxy, so the
 *       upstream sees the proxy's address instead of this machine's (a VPN/dedicated-IP proxy is
 *       the honest way to look like a different address).</li>
 * </ul>
 *
 * <p>A malformed proxy spec throws rather than falling back to a direct connection: a typo must
 * never silently leak the real address on a connection the operator believed was proxied.
 *
 * @param bindAddress local address to bind the outgoing socket to, or null for the OS default
 * @param proxy       proxy to reach the upstream through, or null for a direct connection
 */
record ConnectRoute(String bindAddress, ProxyInfo proxy) {

    static final ConnectRoute DIRECT = new ConnectRoute(null, null);

    /**
     * Builds a route from the config values; blank means "not set". A bind address that does not
     * resolve on this host throws here, at startup, rather than at a player's first /login.
     */
    static ConnectRoute of(String bindAddress, String proxySpec) {
        String bind = bindAddress == null || bindAddress.isBlank() ? null : bindAddress.trim();
        if (bind != null) {
            try {
                InetAddress.getByName(bind);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("bind address \"" + bind + "\" does not resolve on this host");
            }
        }
        String spec = proxySpec == null ? "" : proxySpec.trim();
        return spec.isEmpty() ? new ConnectRoute(bind, null) : new ConnectRoute(bind, parseProxy(spec));
    }

    /**
     * Console form of the route. Proxy credentials are never included: this string is printed at
     * startup and must stay safe for a server log.
     */
    String describe() {
        StringBuilder text = new StringBuilder();
        text.append(proxy == null ? "direct" : proxy.type().name().toLowerCase(Locale.ROOT)
                + " proxy " + proxy.address());
        if (bindAddress != null) {
            text.append(" from ").append(bindAddress);
        }
        return text.toString();
    }

    /**
     * Applies the route to a fresh session factory.
     *
     * <p>Uses {@code setBindSocketAddress} deliberately: MCProtocolLib's {@code setBindAddress}
     * overloads write the bind address into the <em>remote</em> address field (verified in the
     * 26.3-SNAPSHOT jar - calling it makes the client dial the bind address itself, e.g.
     * "Connection refused: /127.0.0.1:0"). The field-backed setter fills the local-address slot
     * the session passes to Netty as {@code localAddress}, while the remote address stays intact.
     */
    ClientNetworkSessionFactory apply(ClientNetworkSessionFactory factory) {
        if (bindAddress != null) {
            factory.setBindSocketAddress(new InetSocketAddress(bindAddress, 0));
        }
        if (proxy != null) {
            factory.setProxy(proxy);
        }
        return factory;
    }

    /**
     * Parses {@code socks5://[user:pass@]host:port}, {@code socks4://host:port},
     * {@code http://host:port} or a bare {@code host:port} (treated as SOCKS5).
     */
    static ProxyInfo parseProxy(String spec) {
        String rest = spec;
        ProxyInfo.Type type = ProxyInfo.Type.SOCKS5;
        int schemeEnd = rest.indexOf("://");
        if (schemeEnd > 0) {
            type = switch (rest.substring(0, schemeEnd).toLowerCase(Locale.ROOT)) {
                case "socks", "socks5", "socks5h" -> ProxyInfo.Type.SOCKS5;
                case "socks4", "socks4a" -> ProxyInfo.Type.SOCKS4;
                case "http", "https" -> ProxyInfo.Type.HTTP;
                default -> throw new IllegalArgumentException("unknown proxy scheme in \"" + masked(spec)
                        + "\": use socks5://, socks4:// or http://");
            };
            rest = rest.substring(schemeEnd + 3);
        }
        String username = null;
        String password = null;
        int at = rest.lastIndexOf('@');
        if (at > 0) {
            String userinfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = userinfo.indexOf(':');
            username = colon < 0 ? userinfo : userinfo.substring(0, colon);
            password = colon < 0 ? "" : userinfo.substring(colon + 1);
        }
        String host;
        int port;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0 || !rest.substring(close + 1).startsWith(":")) {
                throw new IllegalArgumentException("proxy \"" + masked(spec)
                        + "\" needs [ipv6]:port");
            }
            host = rest.substring(1, close);
            port = parsePort(rest.substring(close + 2), spec);
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("proxy \"" + masked(spec) + "\" needs host:port");
            }
            host = rest.substring(0, colon);
            port = parsePort(rest.substring(colon + 1), spec);
        }
        if (host.isBlank() || host.contains(" ")) {
            throw new IllegalArgumentException("proxy \"" + masked(spec) + "\" has an unusable host");
        }
        return new ProxyInfo(type, host, port, username, password);
    }

    private static int parsePort(String text, String spec) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("proxy \"" + masked(spec) + "\" has no usable port");
        }
    }

    /** The spec with any userinfo removed, so an error message cannot print a password. */
    private static String masked(String spec) {
        String rest = spec;
        int at = rest.lastIndexOf('@');
        return at < 0 ? rest : "...@" + rest.substring(at + 1);
    }
}
