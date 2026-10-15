package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.caps.routing.api.RouteMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RouteLookup")
class RouteLookupTest {

    private static final RequestRouter ROUTER = RequestRouters.builder()
            .route("health").pathExact("/health").targetUri("http://u").and()
            .route("orders").pathPrefix("/api/v1/orders").targetUri("http://u").stripPrefix("/api/v1").and()
            .route("api").pathPrefix("/api").targetUri("http://u").rewritePrefix("/api", "/internal").and()
            .route("fixed").pathExact("/legacy").targetUri("http://u").exactRewrite("/v2/new").and()
            .route("user").pathTemplate("/users/{id}").targetUri("http://u").and()
            .route("user-post").method("POST").pathTemplate("/users/{id}/posts/{post}").targetUri("http://u").and()
            .route("tenant").pathTemplate("/t/{tenant}/orders/{id}/lines").targetUri("http://u").and()
            .build();

    @Test
    @DisplayName("a reused lookup agrees with the immutable result on every kind of path")
    void agreesWithTheImmutableResult() {
        String[] paths = {
                "/health", "/health?x=1", "health", "//health", "///health?q", "/", "", "?q=1", "/healthy",
                "/api/v1/orders/9/items?x=y", "/api/v1/orders", "/api/other", "/api", "/apix",
                "/legacy", "/legacy?a=b", "/users/42", "/users/42?x", "/users/", "/users",
                "/users/7/posts/3", "/t/acme/orders/5/lines", "/t/acme/orders/5/lines/", "/nope",
                "/a/../health", "/a/%2e%2e/health", "/a%2fb", "/a\\b", "/a\u0001b"
        };
        for (String method : new String[] {null, "GET", "POST"}) {
            RouteLookup lookup = ROUTER.newLookup();
            for (String path : paths) {
                RouteMatch expected = ROUTER.match(method, path);
                boolean matched = ROUTER.match(method, path, lookup);
                String label = method + " " + path;
                assertThat(matched).as(label).isEqualTo(expected.isMatched());
                assertThat(lookup.isMatched()).as(label).isEqualTo(expected.isMatched());
                if (matched) {
                    assertThat(lookup.route()).as(label).isSameAs(expected.route());
                    assertThat(lookup.rewrittenPath()).as(label).isEqualTo(expected.rewrittenPath());
                    assertThat(lookup.pathParams()).as(label).isEqualTo(expected.pathParams());
                    assertThat(lookup.toRouteMatch().rewrittenPath()).as(label).isEqualTo(expected.rewrittenPath());
                } else {
                    assertThat(lookup.route()).as(label).isNull();
                    assertThat(lookup.rewrittenPath()).as(label).isNull();
                    assertThat(lookup.pathParamCount()).as(label).isZero();
                }
            }
        }
    }

    @Test
    @DisplayName("captures are exposed by index and by name, and a repeated lookup replaces them")
    void capturesByIndexAndName() {
        RouteLookup lookup = ROUTER.newLookup();

        assertThat(ROUTER.match("POST", "/users/7/posts/3?x=1", lookup)).isTrue();
        assertThat(lookup.route().id()).isEqualTo("user-post");
        assertThat(lookup.pathParamCount()).isEqualTo(2);
        assertThat(lookup.pathParamName(0)).isEqualTo("id");
        assertThat(lookup.pathParamValue(0)).isEqualTo("7");
        assertThat(lookup.pathParamName(1)).isEqualTo("post");
        assertThat(lookup.pathParamValue(1)).isEqualTo("3");
        assertThat(lookup.pathParam("post")).isEqualTo("3");
        assertThat(lookup.pathParam("absent")).isNull();

        assertThat(ROUTER.match("GET", "/health", lookup)).isTrue();
        assertThat(lookup.pathParamCount()).isZero();
        assertThat(lookup.pathParams()).isEmpty();
        assertThatThrownBy(() -> lookup.pathParamName(0)).isInstanceOf(IndexOutOfBoundsException.class);

        assertThat(ROUTER.match("GET", "/nope", lookup)).isFalse();
        assertThat(lookup.pathParamCount()).isZero();
    }

    @Test
    @DisplayName("an identity rewrite of a routed String returns that String itself")
    void identityRewriteReturnsTheRequestString() {
        RouteLookup lookup = ROUTER.newLookup();
        String path = "/users/42?x=1";
        assertThat(ROUTER.match("GET", path, lookup)).isTrue();
        assertThat(lookup.rewrittenPath()).isSameAs(path);
    }

    @Test
    @DisplayName("captures read from a path that is not a String")
    void captureFromStringBuilder() {
        RouteLookup lookup = ROUTER.newLookup();
        StringBuilder path = new StringBuilder("users/42?x");
        assertThat(ROUTER.match("GET", path, lookup)).isTrue();
        assertThat(lookup.pathParam("id")).isEqualTo("42");
        assertThat(lookup.rewrittenPath()).isEqualTo("/users/42?x");
    }

    @Test
    @DisplayName("a snapshot survives the reuse of the lookup")
    void snapshotOutlivesReuse() {
        RouteLookup lookup = ROUTER.newLookup();
        ROUTER.match("GET", "/users/42?x", lookup);
        RouteMatch snapshot = lookup.toRouteMatch();
        ROUTER.match("GET", "/health", lookup);

        assertThat(snapshot.route().id()).isEqualTo("user");
        assertThat(snapshot.pathParams()).isEqualTo(Map.of("id", "42"));
        assertThat(snapshot.rewrittenPath()).isEqualTo("/users/42?x");
    }

    @Test
    @DisplayName("a null path does not match and a foreign lookup is refused")
    void nullPathAndForeignLookup() {
        RouteLookup lookup = ROUTER.newLookup();
        assertThat(ROUTER.match("GET", null, lookup)).isFalse();
        assertThat(lookup.isMatched()).isFalse();

        RouteLookup foreign = new RouteLookup() {
            public boolean isMatched() { return false; }
            public eu.exeris.caps.routing.api.Route route() { return null; }
            public String rewrittenPath() { return null; }
            public int pathParamCount() { return 0; }
            public String pathParamName(int index) { return null; }
            public String pathParamValue(int index) { return null; }
            public String pathParam(String name) { return null; }
            public Map<String, String> pathParams() { return Map.of(); }
            public RouteMatch toRouteMatch() { return RouteMatch.noMatch(); }
        };
        assertThatThrownBy(() -> ROUTER.match("GET", "/health", foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("header transformations through the lookup are those of the selected route")
    void headerTransformsDelegate() {
        RequestRouter router = RequestRouters.builder()
                .route("r").pathExact("/h").targetUri("http://u")
                    .setRequestHeader("X-Gw", "1").and().build();
        RouteLookup lookup = router.newLookup();
        assertThat(router.match("GET", "/h", lookup)).isTrue();
        assertThat(lookup.transformRequestHeaders(List.of(), null)).hasSize(1);
        assertThat(router.match("GET", "/zzz", lookup)).isFalse();
        assertThat(lookup.transformRequestHeaders(List.of(), null)).isEmpty();
    }
}
