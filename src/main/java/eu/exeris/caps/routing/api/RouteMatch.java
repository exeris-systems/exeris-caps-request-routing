package eu.exeris.caps.routing.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Result of matching an incoming HTTP request against the routing table.
 *
 * @since 0.1.0
 */
public final class RouteMatch {

    private static final RouteMatch NO_MATCH = new RouteMatch(false, null, null, Map.of());

    private final boolean matched;
    private final Route route;
    private final String rewrittenPath;
    private final Map<String, String> pathParams;

    public RouteMatch(boolean matched, Route route, String rewrittenPath, Map<String, String> pathParams) {
        this.matched = matched;
        this.route = route;
        this.rewrittenPath = rewrittenPath;
        this.pathParams = pathParams != null ? Map.copyOf(pathParams) : Map.of();
    }

    public static RouteMatch noMatch() {
        return NO_MATCH;
    }

    public static RouteMatch matched(Route route, String rewrittenPath, Map<String, String> pathParams) {
        Objects.requireNonNull(route, "route");
        return new RouteMatch(true, route, rewrittenPath, pathParams);
    }

    public boolean isMatched() {
        return matched;
    }

    public Route route() {
        return route;
    }

    public String rewrittenPath() {
        return rewrittenPath;
    }

    public Map<String, String> pathParams() {
        return pathParams;
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    /**
     * Transforms inbound request headers according to the route's mutations and context claims.
     *
     * @param inboundHeaders original inbound headers; non-null
     * @param contextClaims  claims from auth/identity context (e.g. JWT "sub", "tid"); nullable
     * @return transformed header list; non-null
     */
    public List<HeaderEntry> transformRequestHeaders(List<HeaderEntry> inboundHeaders, Map<String, String> contextClaims) {
        if (!matched || route.requestHeaderMutations().isEmpty()) {
            return inboundHeaders != null ? inboundHeaders : List.of();
        }

        List<HeaderMutation> mutations = route.requestHeaderMutations();
        List<HeaderEntry> result = new ArrayList<>(
                (inboundHeaders != null ? inboundHeaders.size() : 0) + mutations.size()
        );

        // Track header names to remove or set
        List<String> toRemove = null;
        List<HeaderMutation> toSet = null;
        List<HeaderMutation> toAdd = null;
        List<HeaderMutation> toInject = null;

        for (int i = 0; i < mutations.size(); i++) {
            HeaderMutation m = mutations.get(i);
            switch (m.action()) {
                case REMOVE -> {
                    if (toRemove == null) toRemove = new ArrayList<>(2);
                    toRemove.add(m.headerName().toLowerCase(Locale.ROOT));
                }
                case SET -> {
                    if (toSet == null) toSet = new ArrayList<>(2);
                    toSet.add(m);
                }
                case ADD -> {
                    if (toAdd == null) toAdd = new ArrayList<>(2);
                    toAdd.add(m);
                }
                case INJECT_CLAIM -> {
                    if (toInject == null) toInject = new ArrayList<>(2);
                    toInject.add(m);
                }
            }
        }

        // 1. Process inbound headers (filtering out REMOVE and SET)
        if (inboundHeaders != null) {
            for (int i = 0; i < inboundHeaders.size(); i++) {
                HeaderEntry h = inboundHeaders.get(i);
                String lowerName = h.name().toLowerCase(Locale.ROOT);
                if (toRemove != null && toRemove.contains(lowerName)) {
                    continue;
                }
                if (toInject != null && isInjected(toInject, h.name())) {
                    // The header is owned by the gateway: a client-supplied value never reaches the upstream
                    continue;
                }
                if (toSet != null) {
                    boolean beingOverwritten = false;
                    for (int j = 0; j < toSet.size(); j++) {
                        if (toSet.get(j).headerName().equalsIgnoreCase(h.name())) {
                            beingOverwritten = true;
                            break;
                        }
                    }
                    if (beingOverwritten) {
                        continue;
                    }
                }
                result.add(h);
            }
        }

        // 2. Append SET headers
        if (toSet != null) {
            for (int i = 0; i < toSet.size(); i++) {
                HeaderMutation m = toSet.get(i);
                result.add(new HeaderEntry(m.headerName(), m.value()));
            }
        }

        // 3. Append ADD headers
        if (toAdd != null) {
            for (int i = 0; i < toAdd.size(); i++) {
                HeaderMutation m = toAdd.get(i);
                result.add(new HeaderEntry(m.headerName(), m.value()));
            }
        }

        // 4. Append INJECT_CLAIM headers if available
        if (toInject != null && contextClaims != null) {
            for (int i = 0; i < toInject.size(); i++) {
                HeaderMutation m = toInject.get(i);
                String claimVal = contextClaims.get(m.value());
                if (claimVal != null && !claimVal.isBlank() && isSafeHeaderValue(claimVal)) {
                    result.add(new HeaderEntry(m.headerName(), claimVal));
                }
            }
        }

        return List.copyOf(result);
    }

    /**
     * Transforms upstream response headers according to the route's response mutations.
     *
     * @param responseHeaders original upstream response headers; non-null
     * @return transformed header list; non-null
     */
    public List<HeaderEntry> transformResponseHeaders(List<HeaderEntry> responseHeaders) {
        if (!matched || route.responseHeaderMutations().isEmpty()) {
            return responseHeaders != null ? responseHeaders : List.of();
        }

        List<HeaderMutation> mutations = route.responseHeaderMutations();
        List<HeaderEntry> result = new ArrayList<>(
                (responseHeaders != null ? responseHeaders.size() : 0) + mutations.size()
        );

        List<String> toRemove = null;
        List<HeaderMutation> toSet = null;
        List<HeaderMutation> toAdd = null;

        for (int i = 0; i < mutations.size(); i++) {
            HeaderMutation m = mutations.get(i);
            switch (m.action()) {
                case REMOVE -> {
                    if (toRemove == null) toRemove = new ArrayList<>(2);
                    toRemove.add(m.headerName().toLowerCase(Locale.ROOT));
                }
                case SET -> {
                    if (toSet == null) toSet = new ArrayList<>(2);
                    toSet.add(m);
                }
                case ADD -> {
                    if (toAdd == null) toAdd = new ArrayList<>(2);
                    toAdd.add(m);
                }
                case INJECT_CLAIM -> {
                    // response header claim injection not applicable
                }
            }
        }

        if (responseHeaders != null) {
            for (int i = 0; i < responseHeaders.size(); i++) {
                HeaderEntry h = responseHeaders.get(i);
                String lowerName = h.name().toLowerCase(Locale.ROOT);
                if (toRemove != null && toRemove.contains(lowerName)) {
                    continue;
                }
                if (toSet != null) {
                    boolean beingOverwritten = false;
                    for (int j = 0; j < toSet.size(); j++) {
                        if (toSet.get(j).headerName().equalsIgnoreCase(h.name())) {
                            beingOverwritten = true;
                            break;
                        }
                    }
                    if (beingOverwritten) {
                        continue;
                    }
                }
                result.add(h);
            }
        }

        if (toSet != null) {
            for (int i = 0; i < toSet.size(); i++) {
                HeaderMutation m = toSet.get(i);
                result.add(new HeaderEntry(m.headerName(), m.value()));
            }
        }

        if (toAdd != null) {
            for (int i = 0; i < toAdd.size(); i++) {
                HeaderMutation m = toAdd.get(i);
                result.add(new HeaderEntry(m.headerName(), m.value()));
            }
        }

        return List.copyOf(result);
    }

    private static boolean isInjected(List<HeaderMutation> injections, String headerName) {
        for (int i = 0; i < injections.size(); i++) {
            if (injections.get(i).headerName().equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        return false;
    }

    /** A value carrying CR, LF or NUL could end the header line and start another. */
    private static boolean isSafeHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return false;
            }
        }
        return true;
    }
}
