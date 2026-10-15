package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.HeaderEntry;
import eu.exeris.caps.routing.api.HeaderMutation;
import eu.exeris.caps.routing.api.PathPattern;
import eu.exeris.caps.routing.api.PathRewriteRule;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.Route;
import eu.exeris.caps.routing.api.RouteMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RadixTreeRequestRouter Unit Tests")
class RadixTreeRequestRouterTest {

    @Test
    @DisplayName("resolves exact route successfully")
    void resolvesExactRoute() {
        RequestRouter router = RequestRouters.builder()
                .route("health-check")
                    .pathExact("/health")
                    .targetUri("http://mock-upstream:9000")
                    .and()
                .route("bench-exact")
                    .pathExact("/api/bench")
                    .targetUri("http://mock-upstream:9000")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/health");
        assertThat(match.isMatched()).isTrue();
        assertThat(match.route().id()).isEqualTo("health-check");
        assertThat(match.rewrittenPath()).isEqualTo("/health");

        RouteMatch benchMatch = router.match("POST", "/api/bench");
        assertThat(benchMatch.isMatched()).isTrue();
        assertThat(benchMatch.route().id()).isEqualTo("bench-exact");
    }

    @Test
    @DisplayName("resolves prefix route and preserves sub-paths")
    void resolvesPrefixRoute() {
        RequestRouter router = RequestRouters.builder()
                .route("orders-prefix")
                    .pathPrefix("/api/v1/orders")
                    .targetUri("http://orders-service:8081")
                    .stripPrefix("/api/v1")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/api/v1/orders/123/items");
        assertThat(match.isMatched()).isTrue();
        assertThat(match.route().id()).isEqualTo("orders-prefix");
        assertThat(match.rewrittenPath()).isEqualTo("/orders/123/items");
    }

    @Test
    @DisplayName("exact match wins over prefix match regardless of registration order")
    void exactWinsOverPrefix() {
        RequestRouter router = RequestRouters.builder()
                .route("generic-api")
                    .pathPrefix("/api")
                    .targetUri("http://generic:8000")
                    .and()
                .route("specific-bench")
                    .pathExact("/api/bench")
                    .targetUri("http://bench:9000")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/api/bench");
        assertThat(match.isMatched()).isTrue();
        assertThat(match.route().id()).isEqualTo("specific-bench");

        RouteMatch genericMatch = router.match("GET", "/api/other");
        assertThat(genericMatch.isMatched()).isTrue();
        assertThat(genericMatch.route().id()).isEqualTo("generic-api");
    }

    @Test
    @DisplayName("specific HTTP method match takes precedence over wildcard method")
    void specificMethodWinsOverWildcard() {
        RequestRouter router = RequestRouters.builder()
                .route("any-method")
                    .pathExact("/api/resource")
                    .targetUri("http://wildcard:8000")
                    .and()
                .route("post-only")
                    .method("POST")
                    .pathExact("/api/resource")
                    .targetUri("http://post-svc:8000")
                    .and()
                .build();

        RouteMatch postMatch = router.match("POST", "/api/resource");
        assertThat(postMatch.isMatched()).isTrue();
        assertThat(postMatch.route().id()).isEqualTo("post-only");

        RouteMatch getMatch = router.match("GET", "/api/resource");
        assertThat(getMatch.isMatched()).isTrue();
        assertThat(getMatch.route().id()).isEqualTo("any-method");
    }

    @Test
    @DisplayName("captures parameters in templated routes")
    void capturesPathParameters() {
        RequestRouter router = RequestRouters.builder()
                .route("user-orders")
                    .pathTemplate("/api/users/{userId}/orders/{orderId}")
                    .targetUri("http://orders-svc:8081")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/api/users/alice-99/orders/ord-456");
        assertThat(match.isMatched()).isTrue();
        assertThat(match.route().id()).isEqualTo("user-orders");
        assertThat(match.pathParam("userId")).isEqualTo("alice-99");
        assertThat(match.pathParam("orderId")).isEqualTo("ord-456");
    }

    @Test
    @DisplayName("strips query string for route resolution but preserves it in rewritten path")
    void queryStrippingAndPreservation() {
        RequestRouter router = RequestRouters.builder()
                .route("search-route")
                    .pathPrefix("/search")
                    .stripPrefix("/search")
                    .targetUri("http://search-svc:8082")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/search/items?q=laptop&sort=asc");
        assertThat(match.isMatched()).isTrue();
        assertThat(match.rewrittenPath()).isEqualTo("/items?q=laptop&sort=asc");
    }

    @Test
    @DisplayName("returns unmatched for non-existent routes")
    void returnsUnmatched() {
        RequestRouter router = RequestRouters.builder()
                .route("only-route")
                    .pathExact("/health")
                    .targetUri("http://upstream:8080")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/unknown/path");
        assertThat(match.isMatched()).isFalse();
        assertThat(match.route()).isNull();
    }
}
