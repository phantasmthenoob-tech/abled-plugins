package net.abled.bridge;

import java.util.function.Function;

/**
 * Worker entrypoint. Args: {@code <control-port> <upstream-host> <upstream-port> <timeout-ms>}.
 *
 * <p>Prints {@code READY} on stdout once the control socket is listening, so the supervising
 * plugin knows the worker is live. The worker never logs passwords and never logs request lines;
 * its stdout is diagnostic only: a JOIN/annotation plus one line for every chat/dialog/reply the
 * upstream showed, so an unclassified upstream can always be tuned from the console.
 */
public final class WorkerMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("usage: <control-port> <upstream-host> <upstream-port> <timeout-ms>");
            System.exit(2);
        }
        int controlPort = Integer.parseInt(args[0]);
        String upstreamHost = args[1];
        int upstreamPort = Integer.parseInt(args[2]);
        long timeoutMs = Long.parseLong(args[3]);

        UpstreamAuthenticator authenticator = new UpstreamAuthenticator(upstreamHost, upstreamPort, timeoutMs,
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
