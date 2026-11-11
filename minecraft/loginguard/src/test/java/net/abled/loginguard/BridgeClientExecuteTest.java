package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control socket carries the saved password, so both request lines are pinned here against a
 * stand-in worker: the EXEC line must keep the whole command (spaces and all) in its last field,
 * and must put the password in the field the worker reads it from.
 */
class BridgeClientExecuteTest {

    private static final String RESULT_DETAIL = "logged in and ran /op, upstream replied: Done";

    /** Accepts one connection, records the request line, answers it with a SUCCESS result. */
    private static Thread fakeWorker(ServerSocket server, AtomicReference<String> captured) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String request = in.readLine();
                captured.set(request);
                String id = request.split(" ")[1];
                new PrintWriter(socket.getOutputStream(), true)
                        .println("RESULT " + id + " SUCCESS " + RESULT_DETAIL);
            } catch (IOException e) {
                // the assertion on the captured line reports anything that went wrong here
            }
        }, "fake-bridge-worker");
        thread.setDaemon(true);
        return thread;
    }

    @Test
    void executeCarriesTheWholeCommandInItsLastField() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicReference<String> captured = new AtomicReference<>();
            Thread worker = fakeWorker(server, captured);
            worker.start();

            BridgeClient client = new BridgeClient(server.getLocalPort(), 5000, msg -> { }, null);
            BridgeClient.BridgeResult result =
                    client.execute("Notch", "swordfish1", "51.161.140.150:25565", "op disgraced_", 1);
            worker.join(5000);

            assertEquals(Verdict.SUCCESS, result.verdict());
            assertEquals(RESULT_DETAIL, result.detail());
            String sent = captured.get();
            assertEquals(6, sent.split(" ", 6).length);
            assertTrue(sent.startsWith("EXEC "), sent);
            assertTrue(sent.endsWith(" 51.161.140.150:25565 Notch swordfish1 op disgraced_"), sent);
        }
    }

    @Test
    void aWorkerThatIsNotListeningStillDenies() {
        BridgeClient client = new BridgeClient(1, 2000, msg -> { }, null);
        BridgeClient.BridgeResult result =
                client.execute("Notch", "swordfish1", "127.0.0.1:25565", "op Notch", 1);

        assertEquals(Verdict.UPSTREAM_DOWN, result.verdict());
    }
}
