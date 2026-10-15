package eu.exeris.caps.routing.api;

import java.util.List;
import java.util.Map;

/**
 * A reusable result holder for {@link RequestRouter#match(String, CharSequence, RouteLookup)}.
 *
 * <p>Obtain one per caller from {@link RequestRouter#newLookup()} and reuse it for every request that caller
 * routes. The router overwrites it in place, so a lookup that selects a static, prefix or template route
 * allocates nothing, including a template's captures, which are recorded as offsets into the path.
 *
 * <p>Contract:
 * <ul>
 *   <li><b>Confinement.</b> A lookup is not thread-safe. Use it from one thread at a time, and not for two
 *       routing calls whose results are both still needed.</li>
 *   <li><b>Validity.</b> Its content describes the most recent {@code match} call that was given it and is
 *       replaced by the next. It refers to the {@code CharSequence} passed to that call, so the caller must not
 *       change that sequence while it reads the lookup. Take {@link #toRouteMatch()} to keep a result.</li>
 *   <li><b>Allocation.</b> {@link #isMatched()}, {@link #route()}, {@link #pathParamCount()} and
 *       {@link #pathParamName(int)} allocate nothing. {@link #rewrittenPath()}, {@link #pathParamValue(int)},
 *       {@link #pathParam(String)}, {@link #pathParams()} and {@link #toRouteMatch()} build strings or maps on
 *       access and so allocate, except that {@code rewrittenPath()} returns the request's own {@code String}
 *       when the route's rule is {@link PathRewriteRule#none()} and the path was passed as a {@code String}
 *       that is already in its routed form.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface RouteLookup {

    /** @return {@code true} when the last call selected a route */
    boolean isMatched();

    /** @return the selected route, or {@code null} when nothing matched */
    Route route();

    /**
     * @return the request path after the route's rewrite rule, with the request's query string preserved;
     *         {@code null} when nothing matched. Built on each call, see the class contract.
     */
    String rewrittenPath();

    /** @return the number of template placeholders captured; zero for an exact or prefix route */
    int pathParamCount();

    /** @return the name of the placeholder at {@code index}, from {@code 0} to {@code pathParamCount() - 1} */
    String pathParamName(int index);

    /** @return the captured value at {@code index}, as a new string */
    String pathParamValue(int index);

    /**
     * @return the value captured for the placeholder {@code name} (the last one when a template repeats a
     *         name), or {@code null} when there is none
     */
    String pathParam(String name);

    /** @return the captured values keyed by placeholder name, as a new unmodifiable map */
    Map<String, String> pathParams();

    /** @return an immutable snapshot of this result, safe to keep after the lookup is reused */
    RouteMatch toRouteMatch();

    /**
     * Request-header transformation of the selected route; see
     * {@link RouteMatch#transformRequestHeaders(List, Map)}. Returns the inbound list unchanged when nothing
     * matched.
     */
    default List<HeaderEntry> transformRequestHeaders(List<HeaderEntry> inboundHeaders, Map<String, String> contextClaims) {
        return RouteMatch.transformRequestHeaders(route(), inboundHeaders, contextClaims);
    }

    /**
     * Response-header transformation of the selected route; see
     * {@link RouteMatch#transformResponseHeaders(List)}. Returns the given list unchanged when nothing matched.
     */
    default List<HeaderEntry> transformResponseHeaders(List<HeaderEntry> responseHeaders) {
        return RouteMatch.transformResponseHeaders(route(), responseHeaders);
    }
}
