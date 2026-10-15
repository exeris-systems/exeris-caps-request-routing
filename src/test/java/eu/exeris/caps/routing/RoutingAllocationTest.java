package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Contract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Measures the steady-state allocation of {@link RequestRouter#match(String, CharSequence, RouteLookup)} with
 * the kernel TCK's {@link JfrAllocationMonitor}: JFR allocation events for {@code eu.exeris.*} types plus the
 * exact per-thread allocated-bytes delta, which the monitor requires to stay below one byte per iteration.
 * The router, the lookup and the paths are built before the measurement; only {@code match} runs inside it.
 * Reading a rewritten path or a captured value from the lookup is not measured: it allocates, and says so.
 */
@DisplayName("RequestRouter allocation")
class RoutingAllocationTest {

    private static final RequestRouter ROUTER = RequestRouters.builder()
            .route("health").pathExact("/health").targetUri("http://u").and()
            .route("orders").pathPrefix("/api/v1/orders").targetUri("http://u").stripPrefix("/api/v1").and()
            .route("api").pathPrefix("/api").targetUri("http://u").and()
            .route("user").pathTemplate("/users/{id}").targetUri("http://u").and()
            .route("tenant").pathTemplate("/t/{tenant}/orders/{id}/lines").targetUri("http://u").and()
            .route("get-only").method("GET").pathExact("/g").targetUri("http://u").and()
            .build();

    private static void assertZeroAllocation(String what, String method, CharSequence[] paths, boolean expectMatch)
            throws IOException {
        RouteLookup lookup = ROUTER.newLookup();
        Config config = Config.ofDefaults("RequestRouting", "RoutingAllocationTest", Contract.zero());
        int[] matched = new int[1];
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            int hits = 0;
            for (int i = 0; i < iterations; i++) {
                if (ROUTER.match(method, paths[i % paths.length], lookup)) {
                    hits++;
                }
            }
            matched[0] = hits;
        });
        assertThat(matched[0] > 0).as("workload ran with matches=%s", expectMatch).isEqualTo(expectMatch);
        JfrAllocationMonitor.assertContract(config, result, what);
    }

    @Test
    @DisplayName("exact routes, with and without a query and a leading slash, allocate nothing")
    void exactRoutes() throws IOException {
        assertZeroAllocation("match(exact)", "GET",
                new CharSequence[] {"/health", "/health?x=1&y=2", "health", "//health?q", "/g"}, true);
    }

    @Test
    @DisplayName("prefix routes, shallow and deep, allocate nothing")
    void prefixRoutes() throws IOException {
        assertZeroAllocation("match(prefix)", "GET",
                new CharSequence[] {"/api/v1/orders/9/items?x=y", "/api/v1/orders", "/api/other", "/api"}, true);
    }

    @Test
    @DisplayName("template routes allocate nothing, with or without captures read")
    void templateRoutes() throws IOException {
        assertZeroAllocation("match(template)", "GET",
                new CharSequence[] {"/users/42", "/users/7?x", "/t/acme/orders/5/lines"}, true);
    }

    @Test
    @DisplayName("a path that is not a String and a null method allocate nothing")
    void nonStringPathAndNullMethod() throws IOException {
        assertZeroAllocation("match(StringBuilder, null method)", null,
                new CharSequence[] {new StringBuilder("/health?q"), new StringBuilder("/api/v1/orders/1"),
                        new StringBuilder("/users/9")}, true);
    }

    @Test
    @DisplayName("requests that match nothing, or that are not canonical, allocate nothing")
    void unmatchedRequests() throws IOException {
        assertZeroAllocation("match(no route)", "GET",
                new CharSequence[] {"/nope", "/users/", "/a/../health", "/a%2fb", "/x\\y", "/healthy"}, false);
    }

    @Test
    @DisplayName("the measurement fails for a workload that allocates, so a pass above is not vacuous")
    void measurementDetectsAllocation() throws IOException {
        RouteLookup lookup = ROUTER.newLookup();
        Config config = Config.ofDefaults("RequestRouting", "RoutingAllocationTest", Contract.zero());
        Object[] sink = new Object[1];
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                ROUTER.match("GET", "/users/42", lookup);
                sink[0] = lookup.pathParam("id");
            }
        });
        assertThatThrownBy(() -> JfrAllocationMonitor.assertContract(config, result, "control"))
                .isInstanceOf(AssertionError.class);
    }
}
