package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.HeaderEntry;
import eu.exeris.caps.routing.api.PathRewriteRule;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.RouteMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Routing: path agreement and header ownership")
class RoutingPathSafetyTest {

    private static RequestRouter router() {
        return RequestRouters.builder()
                .route("api").pathPrefix("/api").targetUri("http://api:8080").stripPrefix("/api").and()
                .route("health").pathExact("/health").targetUri("http://health:8080").and()
                .route("users").pathTemplate("/users/{id}").targetUri("http://users:8080").and()
                .build();
    }

    @Test
    @DisplayName("a prefix route covers its own segment and nothing that merely starts with the same characters")
    void prefixStopsAtSegmentBoundary() {
        RequestRouter router = router();

        assertThat(router.match("GET", "/api").isMatched()).isTrue();
        assertThat(router.match("GET", "/api/orders").isMatched()).isTrue();
        assertThat(router.match("GET", "/apix").isMatched()).isFalse();
        assertThat(router.match("GET", "/api-internal/orders").isMatched()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/../health", "/api/./orders", "/api/..", "/api/%2e%2e/health", "/api/%2E./x", "/api/.%2e/x",
            "/api/%2e/x", "/api/a%2fb", "/api/a%2Fb", "/api/a%5cb", "/api/a\\b", "/api/a\u0000b", "/api/a\nb"
    })
    @DisplayName("a path whose meaning depends on decoding or normalisation is not routed")
    void nonCanonicalPathIsNotRouted(String path) {
        assertThat(router().match("GET", path).isMatched()).isFalse();
    }

    @Test
    @DisplayName("a query string does not make a path non-canonical, and dots inside a segment name are kept")
    void queryAndDottedNamesAreRouted() {
        RequestRouter router = router();

        assertThat(router.match("GET", "/api/orders?next=../x").isMatched()).isTrue();
        assertThat(router.match("GET", "/api/file.v1.json").isMatched()).isTrue();
        assertThat(router.match("GET", "/api/...").isMatched()).isTrue();
        assertThat(router.match("GET", "/users/42").pathParam("id")).isEqualTo("42");
    }

    @Test
    @DisplayName("a strip or rewrite never yields a path that begins with two slashes")
    void rewriteDoesNotProduceNetworkPathReference() {
        RouteMatch match = router().match("GET", "/api//evil.example/x");

        assertThat(match.isMatched()).isTrue();
        assertThat(match.rewrittenPath()).isEqualTo("/evil.example/x");
        assertThat(PathRewriteRule.prefixRewrite("/old", "/").rewrite("/old//evil.example/x"))
                .isEqualTo("/evil.example/x");
    }

    @Test
    @DisplayName("a rewrite prefix applies only at a segment boundary")
    void rewriteStopsAtSegmentBoundary() {
        assertThat(PathRewriteRule.stripPrefix("/api").rewrite("/apix/y")).isEqualTo("/apix/y");
        assertThat(PathRewriteRule.prefixRewrite("/api", "/v2").rewrite("/apix")).isEqualTo("/apix");
        assertThat(PathRewriteRule.stripPrefix("/api").rewrite("/api")).isEqualTo("/");
        assertThat(PathRewriteRule.stripPrefix("/api").rewrite("/api/x")).isEqualTo("/x");
    }

    @Test
    @DisplayName("an injected claim header replaces whatever the client sent, and is dropped when the claim is absent")
    void injectedHeaderIsOwnedByTheGateway() {
        RequestRouter router = RequestRouters.builder()
                .route("secure").pathPrefix("/secure").targetUri("http://s:8080")
                    .injectClaimHeader("X-User-Id", "sub").and()
                .build();
        RouteMatch match = router.match("GET", "/secure/data");
        List<HeaderEntry> inbound = List.of(
                new HeaderEntry("x-user-id", "admin"),
                new HeaderEntry("Accept", "application/json"));

        List<HeaderEntry> withClaim = match.transformRequestHeaders(inbound, Map.of("sub", "u-1"));
        List<HeaderEntry> withoutClaim = match.transformRequestHeaders(inbound, Map.of());
        List<HeaderEntry> noClaims = match.transformRequestHeaders(inbound, null);

        assertThat(withClaim).extracting(HeaderEntry::value).containsExactly("application/json", "u-1");
        assertThat(withoutClaim).extracting(HeaderEntry::name).containsExactly("Accept");
        assertThat(noClaims).extracting(HeaderEntry::name).containsExactly("Accept");
    }

    @Test
    @DisplayName("a claim value that could end the header line is not injected")
    void claimValueWithLineBreakIsNotInjected() {
        RequestRouter router = RequestRouters.builder()
                .route("secure").pathPrefix("/secure").targetUri("http://s:8080")
                    .injectClaimHeader("X-User-Id", "sub").and()
                .build();
        RouteMatch match = router.match("GET", "/secure/data");

        List<HeaderEntry> out = match.transformRequestHeaders(List.of(),
                Map.of("sub", "u-1\r\nX-Admin: true"));

        assertThat(out).isEmpty();
    }
}
