package net.abled.bridge;

import java.util.UUID;

/**
 * The verdict line the worker writes back over the control socket, one per request. The password
 * never appears here; only the correlator and the classified outcome.
 */
public record Result(UUID requestId, Verdict verdict, String detail) {

    /** Single-line wire format: {@code RESULT <uuid> <VERDICT> [detail without whitespace-crash chars]}. */
    public String wire() {
        String safe = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
        return "RESULT " + requestId + " " + verdict + " " + safe;
    }

    public static Result parse(String line) {
        String[] parts = line.trim().split(" ", 4);
        if (parts.length < 3 || !parts[0].equals("RESULT")) {
            throw new IllegalArgumentException("not a result line: " + line);
        }
        Verdict verdict = Verdict.valueOf(parts[2]);
        return new Result(java.util.UUID.fromString(parts[1]), verdict,
                parts.length == 4 ? parts[3] : "");
    }
}
