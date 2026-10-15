package eu.exeris.caps.routing.api;

import java.util.List;

/**
 * High-performance L7 request router contract.
 *
 * <p>Resolves incoming HTTP requests by method and path against a pre-compiled,
 * immutable route table, returning a {@link RouteMatch}.
 *
 * <p>All implementations must be thread-safe and non-blocking: a lookup takes no locks and never waits.
 * The router is immutable; the per-request state lives in a caller-owned {@link RouteLookup}.
 *
 * <p>Allocation: {@link #match(String, CharSequence, RouteLookup)} with a reused lookup allocates nothing in
 * steady state when the route is exact, prefix or template, whether or not the template captures; see
 * {@link RouteLookup} for what is built only when the caller reads it. The two-argument forms return an
 * immutable {@link RouteMatch} and allocate it, its rewritten path and its parameter map on every call.
 *
 * @since 0.1.0
 */
public interface RequestRouter {

    /**
     * Creates a result holder for {@link #match(String, CharSequence, RouteLookup)}. Call once per caller
     * (a thread, a connection, a request scope) and reuse it.
     *
     * @return a new, unmatched lookup bound to this router implementation
     */
    RouteLookup newLookup();

    /**
     * Resolves an inbound request into a caller-owned, reused lookup.
     *
     * <p>Works on index ranges of {@code path}: the query string is excluded by position and the path is not
     * copied, so nothing is allocated once the lookup's capture arrays have grown to the deepest template
     * (a one-time cost on its first deep match). Other parameters are as for
     * {@link #match(String, CharSequence)}.
     *
     * @param method HTTP method; a {@code null} method matches only routes with no method constraint
     * @param path   inbound request-target path; a {@code null} path does not match
     * @param into   the lookup to overwrite, from {@link #newLookup()} of this router; non-null
     * @return {@code true} when a route was selected, which {@code into} then describes
     * @throws IllegalArgumentException if {@code into} was not created by this router
     */
    boolean match(String method, CharSequence path, RouteLookup into);

    /**
     * Resolves an inbound request by HTTP method and URI path, returning an immutable result that allocates.
     * A hot path uses {@link #match(String, CharSequence, RouteLookup)}.
     *
     * @param method HTTP method (e.g. "GET", "POST"); a {@code null} method matches only routes with no method constraint
     * @param path   inbound request-target path; a {@code null} path does not match, a missing leading slash is
     *               added, a query string is ignored for matching, and a path whose meaning depends on decoding
     *               or normalisation (dot segments, encoded or literal backslashes and slashes inside a segment,
     *               control characters) is never routed
     * @return the match result; never {@code null} (returns {@link RouteMatch#noMatch()} if unmatched)
     */
    default RouteMatch match(String method, CharSequence path) {
        RouteLookup lookup = newLookup();
        return match(method, path, lookup) ? lookup.toRouteMatch() : RouteMatch.noMatch();
    }

    /**
     * Convenience overload for a request without a method: only routes with no method constraint can match.
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
