package net.abled.bridge;

/**
 * The only values the worker is allowed to answer with. Any outcome not explicitly listed here
 * (crash, protocol surprise, timeout) reaches the plugin as UPSTREAM_TIMEOUT or UPSTREAM_DOWN by
 * the supervising timeout, never as a success.
 */
public enum Verdict {
    /** Upstream accepted the credentials. */
    SUCCESS,
    /** Upstream recognized the account and rejected the password. */
    WRONG_PASSWORD,
    /** Upstream has no registration for this username. */
    NO_ACCOUNT,
    /** The upstream did not answer an authentication step inside its budget. */
    UPSTREAM_TIMEOUT,
    /** The upstream connection could not be established or dropped mid-flow. */
    UPSTREAM_DOWN
}
