package eu.exeris.caps.routing.api;

import eu.exeris.caps.routing.internal.RadixTreeRequestRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent factory and builder for creating {@link RequestRouter} instances.
 *
 * @since 0.1.0
 */
public final class RequestRouters {

    private RequestRouters() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Route> routes = new ArrayList<>();

        private Builder() {
        }

        public RouteBuilder route(String id) {
            return new RouteBuilder(this, id);
        }

        public Builder addRoute(Route route) {
            Objects.requireNonNull(route, "route");
            routes.add(route);
            return this;
        }

        public RequestRouter build() {
            return new RadixTreeRequestRouter(routes);
        }
    }

    public static final class RouteBuilder {
        private final Builder parent;
        private final String id;
        private String method;
        private PathPattern pattern;
        private String targetUri;
        private PathRewriteRule rewriteRule = PathRewriteRule.none();
        private final List<HeaderMutation> reqMutations = new ArrayList<>();
        private final List<HeaderMutation> respMutations = new ArrayList<>();
        private int order = 0;

        RouteBuilder(Builder parent, String id) {
            this.parent = Objects.requireNonNull(parent, "parent");
            this.id = Objects.requireNonNull(id, "id");
        }

        public RouteBuilder method(String method) {
            this.method = method != null ? method.toUpperCase(java.util.Locale.ROOT) : null;
            return this;
        }

        public RouteBuilder pathExact(String path) {
            this.pattern = PathPattern.exact(path);
            return this;
        }

        public RouteBuilder pathPrefix(String prefix) {
            this.pattern = PathPattern.prefix(prefix);
            return this;
        }

        public RouteBuilder pathTemplate(String template) {
            this.pattern = PathPattern.template(template);
            return this;
        }

        public RouteBuilder targetUri(String targetUri) {
            this.targetUri = targetUri;
            return this;
        }

        public RouteBuilder stripPrefix(String prefix) {
            this.rewriteRule = PathRewriteRule.stripPrefix(prefix);
            return this;
        }

        public RouteBuilder rewritePrefix(String matchPrefix, String replacement) {
            this.rewriteRule = PathRewriteRule.prefixRewrite(matchPrefix, replacement);
            return this;
        }

        public RouteBuilder exactRewrite(String targetPath) {
            this.rewriteRule = PathRewriteRule.exactRewrite(targetPath);
            return this;
        }

        public RouteBuilder addRequestHeader(String name, String value) {
            this.reqMutations.add(HeaderMutation.add(name, value));
            return this;
        }

        public RouteBuilder setRequestHeader(String name, String value) {
            this.reqMutations.add(HeaderMutation.set(name, value));
            return this;
        }

        public RouteBuilder removeRequestHeader(String name) {
            this.reqMutations.add(HeaderMutation.remove(name));
            return this;
        }

        public RouteBuilder injectClaimHeader(String headerName, String claimKey) {
            this.reqMutations.add(HeaderMutation.injectClaim(headerName, claimKey));
            return this;
        }

        public RouteBuilder addResponseHeader(String name, String value) {
            this.respMutations.add(HeaderMutation.add(name, value));
            return this;
        }

        public RouteBuilder setResponseHeader(String name, String value) {
            this.respMutations.add(HeaderMutation.set(name, value));
            return this;
        }

        public RouteBuilder removeResponseHeader(String name) {
            this.respMutations.add(HeaderMutation.remove(name));
            return this;
        }

        public RouteBuilder order(int order) {
            this.order = order;
            return this;
        }

        public Builder and() {
            if (pattern == null) {
                throw new IllegalStateException("Route '" + id + "' has no path pattern specified");
            }
            if (targetUri == null) {
                throw new IllegalStateException("Route '" + id + "' has no targetUri specified");
            }
            Route r = new Route(
                    id, method, pattern, targetUri, rewriteRule,
                    List.copyOf(reqMutations), List.copyOf(respMutations), order
            );
            return parent.addRoute(r);
        }
    }
}
