package eu.exeris.caps.routing.api;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of an L7 gateway route.
 *
 * @param id                      route identifier; non-null; equal-order routes are ordered by id
 * @param method                  HTTP method constraint, or {@code null} to match any method
 * @param pattern                 path matching pattern; non-null
 * @param targetUri               destination upstream URI or cluster authority; non-null
 * @param rewriteRule             path rewriting rule; non-null
 * @param requestHeaderMutations  transformations to apply to inbound request headers; {@code null} is read as empty
 * @param responseHeaderMutations transformations to apply to upstream response headers; {@code null} is read as empty
 * @param order                   tie-break (lower is preferred) between routes of the same kind that compete
 *                                for one request: routes on the same pattern, and template routes on
 *                                different patterns. The route builder defaults it to 0. It never lifts a
 *                                route over a better kind (exact, then template, then prefix), a longer
 *                                prefix, or, on one pattern, a route that names the request method
 * @since 0.1.0
 */
public record Route(
        String id,
        String method,
        PathPattern pattern,
        String targetUri,
        PathRewriteRule rewriteRule,
        List<HeaderMutation> requestHeaderMutations,
        List<HeaderMutation> responseHeaderMutations,
        int order
) {

    public Route {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(pattern, "pattern must not be null");
        Objects.requireNonNull(targetUri, "targetUri must not be null");
        Objects.requireNonNull(rewriteRule, "rewriteRule must not be null");
        requestHeaderMutations = requestHeaderMutations != null ? List.copyOf(requestHeaderMutations) : List.of();
        responseHeaderMutations = responseHeaderMutations != null ? List.copyOf(responseHeaderMutations) : List.of();
    }
}
