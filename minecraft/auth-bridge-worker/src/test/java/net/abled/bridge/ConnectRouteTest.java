package net.abled.bridge;

import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The connect route decides what address the upstream sees, so its parsing is pinned here. */
class ConnectRouteTest {

    @Test
    void blankSettingsMeanADirectConnection() {
        ConnectRoute route = ConnectRoute.of("", "  ");
        assertNull(route.proxy());
        assertNull(route.bindAddress());
        assertEquals("direct", route.describe());
    }

    @Test
    void bareHostPortIsSocks5() {
        ProxyInfo proxy = ConnectRoute.parseProxy("203.0.113.9:1080");
        assertEquals(ProxyInfo.Type.SOCKS5, proxy.type());
        assertEquals(new InetSocketAddress("203.0.113.9", 1080), proxy.address());
        assertNull(proxy.username());
    }

    @Test
    void schemeAndCredentialsAreParsed() {
        ProxyInfo proxy = ConnectRoute.parseProxy("socks5://user:secret@proxy.example:1080");
        assertEquals(ProxyInfo.Type.SOCKS5, proxy.type());
        assertEquals("user", proxy.username());
        assertEquals("secret", proxy.password());
        assertEquals("proxy.example", ((InetSocketAddress) proxy.address()).getHostString());
        assertEquals(1080, ((InetSocketAddress) proxy.address()).getPort());
    }

    @Test
    void everySupportedSchemeMapsToItsTransport() {
        assertEquals(ProxyInfo.Type.SOCKS4, ConnectRoute.parseProxy("socks4://10.0.0.1:9050").type());
        assertEquals(ProxyInfo.Type.HTTP, ConnectRoute.parseProxy("http://10.0.0.1:3128").type());
    }

    @Test
    void bracketedIpv6IsAccepted() {
        ProxyInfo proxy = ConnectRoute.parseProxy("socks5://[::1]:1080");
        assertEquals(1080, ((InetSocketAddress) proxy.address()).getPort());
    }

    @Test
    void bindAddressIsKeptAndShown() {
        ConnectRoute route = ConnectRoute.of(" 192.0.2.7 ", "socks5://proxy.example:1080");
        assertEquals("192.0.2.7", route.bindAddress());
        assertEquals(ProxyInfo.Type.SOCKS5, route.proxy().type());
        assertTrue(route.describe().startsWith("socks5 proxy "));
        assertTrue(route.describe().endsWith(" from 192.0.2.7"));
    }

    @Test
    void applyingARouteKeepsTheUpstreamAndSetsTheLocalAddress() throws Exception {
        ClientNetworkSessionFactory factory = ClientNetworkSessionFactory.factory()
                .setAddress("upstream.example", 25565);
        ConnectRoute.of("127.0.0.1", "").apply(factory);
        String remote = String.valueOf(field(factory, "remoteSocketAddress"));
        String local = String.valueOf(field(factory, "bindSocketAddress"));
        // The library's setBindAddress overloads would have overwritten the remote with the bind
        // address here; the upstream must survive and the local address must be the one we set.
        assertTrue(remote.contains("upstream.example"), remote);
        assertTrue(remote.contains("25565"), remote);
        assertTrue(local.contains("127.0.0.1"), local);
        assertTrue(local.endsWith(":0"), local);
        assertTrue(field(factory, "proxy") == null);
    }

    @Test
    void aBindAddressThatDoesNotResolveIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectRoute.of("no-such-host.invalid.", ""));
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void unusableSpecsThrowInsteadOfFallingBackToDirect() {
        assertThrows(IllegalArgumentException.class, () -> ConnectRoute.parseProxy("socks9://host:1080"));
        assertThrows(IllegalArgumentException.class, () -> ConnectRoute.parseProxy("host-without-port"));
        assertThrows(IllegalArgumentException.class, () -> ConnectRoute.parseProxy("host:notaport"));
        assertThrows(IllegalArgumentException.class, () -> ConnectRoute.parseProxy("host:0"));
        assertThrows(IllegalArgumentException.class, () -> ConnectRoute.parseProxy("socks5://[::1]1080"));
    }

    @Test
    void errorMessagesNeverCarryThePassword() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ConnectRoute.parseProxy("socks5://user:hunter2@bad host:1080"));
        assertFalse(failure.getMessage().contains("hunter2"));
    }
}
