package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointProbeTest {

    @Test
    void acceptsHostPortAndStripsIpv6Brackets() {
        assertArrayEquals(new String[]{"127.0.0.1", "25565"},
                EndpointProbe.parse(" 127.0.0.1:25565 "));
        assertArrayEquals(new String[]{"snow.example.com", "50252"},
                EndpointProbe.parse("snow.example.com:50252"));
        assertArrayEquals(new String[]{"::1", "25565"},
                EndpointProbe.parse("[::1]:25565"));
    }

    @Test
    void rejectsUnusableEndpointText() {
        assertNull(EndpointProbe.parse(null));
        assertNull(EndpointProbe.parse(""));
        assertNull(EndpointProbe.parse("example.com"));
        assertNull(EndpointProbe.parse(":25565"));
        assertNull(EndpointProbe.parse("host:0"));
        assertNull(EndpointProbe.parse("host:70000"));
        assertNull(EndpointProbe.parse("host:port"));
    }

    /** /proc/net/tcp with the columns a real kernel writes, including rows that must not count. */
    @Test
    void readsOnlyListeningRowsFromTheTcpTable() throws Exception {
        String table = String.join("\n",
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
                "   0: 00000000:63DD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000 0 1",
                "   1: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000 0 2",
                "   2: 0100007F:1F90 0100007F:C350 01 00000000:00000000 00:00000000 00000000  1000 0 3",
                "   3: 0100007F:ZZZZ 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000 0 4",
                "   4: 0100007F:63DD 00000000:0000 0A");
        assertEquals(List.of(8080, 25565), // 0x1F90, 0x63DD; the ESTABLISHED row is not a listener
                EndpointProbe.parseListeningPorts(
                        new ByteArrayInputStream(table.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void probeAnswersForAListeningSocketAndForAClosedPort() throws Exception {
        int openPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            openPort = socket.getLocalPort();
            EndpointProbe.Result open = EndpointProbe.probe("127.0.0.1:" + openPort, null, 2000);
            assertTrue(open.reachable(), open.describe());
            assertEquals(openPort, open.port());
        }
        // The same port now has nothing bound to it, which is exactly the refused case the worker
        // reported for the upstream's published port.
        EndpointProbe.Result closed = EndpointProbe.probe("127.0.0.1:" + openPort, null, 2000);
        assertFalse(closed.reachable());
        assertTrue(closed.describe().contains("NOT reachable"), closed.describe());
    }
}
