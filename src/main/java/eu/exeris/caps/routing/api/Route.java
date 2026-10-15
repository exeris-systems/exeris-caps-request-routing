package eu.exeris.caps.routing.api;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of an L7 gateway route.
 *
 * @param id                      unique route identifier; non-null
 * @param method                  HTTP method constraint, or {@code null} to match any method
 * @param pattern                 path matching pattern; non-null
 * @param targetUri               destination upstream URI or cluster authority; non-null
 * @param rewriteRule             path rewriting rule; non-null
 * @param requestHeaderMutations  transformations to apply to inbound request headers; non-null
 * @param responseHeaderMutations transformations to apply to upstream response headers; non-null
 * @param order                   precedence order (lower executes earlier); default 0
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
