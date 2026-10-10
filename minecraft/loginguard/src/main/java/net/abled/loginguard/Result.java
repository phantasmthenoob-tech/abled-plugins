package net.abled.loginguard;

import java.util.UUID;

/** Mirror of the worker's {@code RESULT} line parser. */
record Result(UUID requestId, Verdict verdict, String detail) {

    static Result parse(String line) {
        String[] parts = line.trim().split(" ", 4);
        if (parts.length < 3 || !parts[0].equals("RESULT")) {
            throw new IllegalArgumentException("not a result line: " + line);
        }
        Verdict v = Verdict.valueOf(parts[2]);
        return new Result(UUID.fromString(parts[1]), v, parts.length == 4 ? parts[3] : "");
    }
}
