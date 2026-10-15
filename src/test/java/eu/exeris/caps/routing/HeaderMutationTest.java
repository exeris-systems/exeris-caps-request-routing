package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.HeaderEntry;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.RouteMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HeaderMutation and Transformation Tests")
class HeaderMutationTest {

    @Test
    @DisplayName("transforms inbound request headers: removes auth, injects claims, adds metadata")
    void transformsRequestHeaders() {
        RequestRouter router = RequestRouters.builder()
                .route("secure-api")
                    .pathPrefix("/api/secure")
                    .targetUri("http://secure-svc:8080")
                    .removeRequestHeader("Authorization")
                    .injectClaimHeader("X-User-Id", "sub")
                    .injectClaimHeader("X-Tenant-Id", "tenant_id")
                    .addRequestHeader("X-Gateway-Route", "secure-api")
                    .setRequestHeader("User-Agent", "ExerisGateway/0.2")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/api/secure/data");
        assertThat(match.isMatched()).isTrue();

        List<HeaderEntry> inbound = List.of(
                new HeaderEntry("Host", "gateway.example.com"),
                new HeaderEntry("Authorization", "Bearer sensitive-token-xyz"),
                new HeaderEntry("User-Agent", "Mozilla/5.0"),
                new HeaderEntry("Accept", "application/json")
        );

        Map<String, String> claims = Map.of(
                "sub", "user_alice_42",
                "tenant_id", "corp_acme_100"
        );

        List<HeaderEntry> transformed = match.transformRequestHeaders(inbound, claims);

        // Verify Authorization was removed
        assertThat(transformed).noneMatch(h -> h.nameEqualsIgnoreCase("Authorization"));

        // Verify User-Agent was overridden
        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("User-Agent"))
                .extracting(HeaderEntry::value)
                .containsExactly("ExerisGateway/0.2");

        // Verify claims were injected
        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("X-User-Id"))
                .extracting(HeaderEntry::value)
                .containsExactly("user_alice_42");

        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("X-Tenant-Id"))
                .extracting(HeaderEntry::value)
                .containsExactly("corp_acme_100");

        // Verify static header was added
        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("X-Gateway-Route"))
                .extracting(HeaderEntry::value)
                .containsExactly("secure-api");

        // Verify untargeted headers were preserved
        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("Accept"))
                .extracting(HeaderEntry::value)
                .containsExactly("application/json");
    }

    @Test
    @DisplayName("transforms response headers: removes sensitive headers, adds security headers")
    void transformsResponseHeaders() {
        RequestRouter router = RequestRouters.builder()
                .route("public-api")
                    .pathPrefix("/public")
                    .targetUri("http://public-svc:8080")
                    .removeResponseHeader("Server")
                    .addResponseHeader("X-Content-Type-Options", "nosniff")
                    .setResponseHeader("Cache-Control", "public, max-age=3600")
                    .and()
                .build();

        RouteMatch match = router.match("GET", "/public/catalog");
        assertThat(match.isMatched()).isTrue();

        List<HeaderEntry> upstreamHeaders = List.of(
                new HeaderEntry("Content-Type", "application/json"),
                new HeaderEntry("Server", "InternalMicroservice/1.0"),
                new HeaderEntry("Cache-Control", "no-cache")
        );

        List<HeaderEntry> transformed = match.transformResponseHeaders(upstreamHeaders);

        // Server header removed
        assertThat(transformed).noneMatch(h -> h.nameEqualsIgnoreCase("Server"));

        // Cache-Control overridden
        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("Cache-Control"))
                .extracting(HeaderEntry::value)
                .containsExactly("public, max-age=3600");

        // X-Content-Type-Options added
        assertThat(transformed).filteredOn(h -> h.nameEqualsIgnoreCase("X-Content-Type-Options"))
                .extracting(HeaderEntry::value)
                .containsExactly("nosniff");
    }
}
