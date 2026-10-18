package net.abled.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Worker entrypoint. Args: {@code <control-port> <host:port[,host:port...]> <timeout-ms>} with
 * optional trailing {@code bind=<local-address>} and {@code proxy=<socks5|socks4|http>://[user:pass@]host:port}
 * settings; later endpoints are fallbacks used only when the earlier one refuses the connection.
 * The legacy {@code <control-port> <upstream-host> <upstream-port> <timeout-ms>} form still works.
 *
 * <p>{@code bind} and {@code proxy} decide what the upstream sees as the client's address: the
 * local source address, or the proxy's address, instead of this machine's own. {@code proxy} is
 * also accepted in the {@code AUTHBRIDGE_PROXY} environment variable, so a credential can stay out
 * of the process list; the argument wins when both are set.
 *
 * <p>Prints {@code READY} on stdout once the control socket is listening, so the supervising
 * plugin knows the worker is live. The worker never logs passwords and never logs request lines;
 * its stdout is diagnostic only: a JOIN/annotation plus one line for every chat/dialog/reply the
 * upstream showed, so an unclassified upstream can always be tuned from the console.
 */
public final class WorkerMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            return;
        }
        int controlPort;
        try {
            controlPort = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("control port is not a number: " + args[0]);
            System.exit(2);
            return;
        }
        // Flags are recognised by their `key=value` shape, so the legacy positional form
        // (<control-port> <host> <port> <timeout-ms>) keeps working unchanged.
        List<String> positional = new ArrayList<>();
        String bindAddress = null;
        String proxySpec = System.getenv("AUTHBRIDGE_PROXY");
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            int equals = arg.indexOf('=');
            if (equals > 0) {
                String key = arg.substring(0, equals);
                String value = arg.substring(equals + 1);
                switch (key) {
                    case "bind" -> bindAddress = value;
                    case "proxy" -> proxySpec = value;
                    default -> {
                        System.out.println("unknown setting: " + key);
                        System.exit(2);
                        return;
                    }
                }
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() < 2 || positional.size() > 3) {
            System.out.println("expected <endpoints> <timeout-ms>, or the legacy <host> <port> <timeout-ms>");
            System.exit(2);
            return;
        }
        long timeoutMs;
        List<UpstreamAuthenticator.Endpoint> endpoints = new ArrayList<>();
        try {
            timeoutMs = Long.parseLong(positional.get(positional.size() - 1));
            if (positional.size() == 3) {
                endpoints.add(new UpstreamAuthenticator.Endpoint(positional.get(0),
                        Integer.parseInt(positional.get(1))));
            } else {
                for (String spec : positional.get(0).split(",")) {
                    if (spec.isBlank()) {
                        continue;
                    }
                    int colon = spec.lastIndexOf(':');
                    if (colon <= 0 || colon == spec.length() - 1) {
                        System.out.println("malformed endpoint: " + spec);
                        System.exit(2);
                        return;
                    }
                    endpoints.add(new UpstreamAuthenticator.Endpoint(spec.substring(0, colon),
                            Integer.parseInt(spec.substring(colon + 1).trim())));
                }
            }
        } catch (NumberFormatException e) {
            System.out.println("malformed endpoint or timeout: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (endpoints.isEmpty()) {
            System.out.println("no upstream endpoint given");
            System.exit(2);
            return;
        }
        ConnectRoute route;
        try {
            route = ConnectRoute.of(bindAddress, proxySpec);
        } catch (IllegalArgumentException e) {
            // Fail closed and loudly: quietly going direct would leak the real address from a
            // connection the operator configured to come from somewhere else.
            System.out.println("unusable connect route: " + e.getMessage());
            System.out.println("verification will not start; fix the route or empty it to connect directly");
            System.exit(2);
            return;
        }
        // Printed before READY so the operator can always see what the worker will dial, and as
        // whom. The route never prints proxy credentials.
        System.out.println("[observer] [upstream] endpoints " + endpoints + ", connecting via " + route.describe());

        UpstreamAuthenticator authenticator = new UpstreamAuthenticator(endpoints, timeoutMs, route,
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

    private static void usage() {
        System.out.println("usage: <control-port> <host:port[,host:port...]> <timeout-ms>"
                + " [bind=<local-address>] [proxy=<socks5|socks4|http>://[user:pass@]host:port]");
        System.out.println("   or: <control-port> <upstream-host> <upstream-port> <timeout-ms>");
        System.exit(2);
    }
}
