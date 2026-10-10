package net.abled.loginguard;

/** Mirror of the worker's verdict classification. */
enum Verdict {
    SUCCESS, WRONG_PASSWORD, NO_ACCOUNT, UPSTREAM_TIMEOUT, UPSTREAM_DOWN;
}
