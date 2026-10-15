package eu.exeris.caps.routing.api;

import java.util.List;

/**
 * High-performance L7 request router contract.
 *
 * <p>Resolves incoming HTTP requests by method and path against a pre-compiled,
 * immutable route table, returning a {@link RouteMatch}.
 *
 * <p>All implementations must be thread-safe and non-blocking: a lookup takes no locks and never waits.
 * A lookup may allocate (the match result, captured parameters and the rewritten path are new objects).
 *
 * @since 0.1.0
 */
public interface RequestRouter {

    /**
     * Resolves an inbound request by HTTP method and URI path.
     *
     * @param method HTTP method (e.g. "GET", "POST"), or {@code null} to ignore method
     * @param path   inbound request-target path; a {@code null} path does not match
     * @return the match result; never {@code null} (returns {@link RouteMatch#noMatch()} if unmatched)
     */
    RouteMatch match(String method, CharSequence path);

    /**
     * Convenience overload matching on path alone regardless of method.
     *
     * @param path inbound request-target path; a {@code null} path does not match
     * @return the match result; never {@code null}
     */
    default RouteMatch match(CharSequence path) {
        return match(null, path);
    }

    /**
     * Returns an unmodifiable list of all registered routes compiled into this router.
     *
     * @return list of routes; never {@code null}
     */
    List<Route> routes();
}
