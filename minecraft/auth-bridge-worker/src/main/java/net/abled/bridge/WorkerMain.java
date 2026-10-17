package net.abled.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Worker entrypoint. Args: {@code <control-port> <host:port[,host:port...]> <timeout-ms>}, where
 * later endpoints are fallbacks used only when the earlier one refuses the connection. The
 * legacy {@code <control-port> <upstream-host> <upstream-port> <timeout-ms>} form still works.
 *
 * <p>Prints {@code READY} on stdout once the control socket is listening, so the supervising
 * plugin knows the worker is live. The worker never logs passwords and never logs request lines;
 * its stdout is diagnostic only: a JOIN/annotation plus one line for every chat/dialog/reply the
 * upstream showed, so an unclassified upstream can always be tuned from the console.
 */
public final class WorkerMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("usage: <control-port> <host:port[,host:port...]> <timeout-ms>");
            System.out.println("   or: <control-port> <upstream-host> <upstream-port> <timeout-ms>");
            System.exit(2);
        }
        int controlPort = Integer.parseInt(args[0]);
        long timeoutMs = Long.parseLong(args[args.length - 1]);
        List<UpstreamAuthenticator.Endpoint> endpoints = new ArrayList<>();
        if (args.length == 4) {
            endpoints.add(new UpstreamAuthenticator.Endpoint(args[1], Integer.parseInt(args[2])));
        } else {
            for (String spec : args[1].split(",")) {
                if (spec.isBlank()) {
                    continue;
                }
                int colon = spec.lastIndexOf(':');
                if (colon <= 0 || colon == spec.length() - 1) {
                    System.out.println("malformed endpoint: " + spec);
                    System.exit(2);
                }
                endpoints.add(new UpstreamAuthenticator.Endpoint(spec.substring(0, colon),
                        Integer.parseInt(spec.substring(colon + 1).trim())));
            }
        }
        if (endpoints.isEmpty()) {
            System.out.println("no upstream endpoint given");
            System.exit(2);
        }
        // Printed before READY so the operator can always see what the worker will dial.
        System.out.println("[observer] [upstream] endpoints " + endpoints);

        UpstreamAuthenticator authenticator = new UpstreamAuthenticator(endpoints, timeoutMs,
                line -> System.out.println("[observer] " + line));
        Function<Request, Result> handler = request -> {
            UpstreamAuthenticator.Outcome outcome = authenticator.verify(request);
            return new Result(request.requestId(), outcome.verdict(), outcome.detail());
        };

        BridgeServer server = new BridgeServer(controlPort, handler);
        server.start();
        System.out.println("READY");
        // The network threads are daemons; returning from main would end the JVM immediately
        // and the plugin would only ever see connection-refused. Block the main thread forever.
        Thread.currentThread().join();
    }
}
