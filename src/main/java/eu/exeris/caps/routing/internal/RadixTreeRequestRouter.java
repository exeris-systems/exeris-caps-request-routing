package eu.exeris.caps.routing.internal;

import eu.exeris.caps.routing.api.PathPattern;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.Route;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.caps.routing.api.RouteMatch;
import eu.exeris.caps.routing.api.PathRewriteRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * High-performance, lock-free Radix Trie implementation of {@link RequestRouter}.
 *
 * <p>Supports exact, parameterized template ({@code {placeholder}}), and longest-prefix routes
 * with method-specific filtering and deterministic precedence.
 *
 * <p>Precedence: an exact route beats a template route, which beats a prefix route. Among prefix routes
 * the longest prefix wins and {@code order} breaks ties on the same prefix. Within one path pattern a
 * route naming the request method beats a method-agnostic (wildcard) route, whatever their {@code order}.
 * A template placeholder captures one non-empty segment, so {@code /a/} does not match {@code /a/{id}}.
 * A path without a leading slash is treated as if it had one, and repeated leading slashes collapse to one,
 * so the route is chosen on the same path the upstream receives.
 *
 * <p>Allocation: {@link #match(String, CharSequence, RouteLookup)} reads the path through index ranges (query
 * excluded by position, leading-slash handling by offset) over arrays frozen at construction, and records
 * the selection and any template captures in the caller's {@link RouteLookup}. In steady state it allocates
 * nothing for exact, prefix and template routes alike; the lookup's capture arrays grow once, on the first
 * match deeper than they have yet been. Reading {@code rewrittenPath()} or a captured value from the lookup
 * builds a string and allocates, as {@link RouteLookup} states.
 *
 * @since 0.1.0
 */
public final class RadixTreeRequestRouter implements RequestRouter {

    private static final Comparator<Route> ROUTE_ORDER_COMPARATOR =
            Comparator.comparingInt(Route::order).thenComparing(Route::id);

    private static final Route[] NO_ROUTES = new Route[0];
    private static final Node[] NO_NODES = new Node[0];

    private final List<Route> allRoutes;
    private final Node root;

    public RadixTreeRequestRouter(List<Route> routes) {
        Objects.requireNonNull(routes, "routes");
        this.allRoutes = List.copyOf(routes);
        this.root = new Node("");
        buildTree(this.allRoutes);
        root.freeze();
    }

    private void buildTree(List<Route> routes) {
        // Sort routes by order so insertion keeps lists sorted
        List<Route> sorted = new ArrayList<>(routes);
        sorted.sort(ROUTE_ORDER_COMPARATOR);

        for (Route route : sorted) {
            insertRoute(route);
        }
    }

    private void insertRoute(Route route) {
        String raw = route.pattern().rawPattern();
        PathPattern.Kind kind = route.pattern().kind();

        if (kind == PathPattern.Kind.TEMPLATE) {
            insertTemplateRoute(root, raw, 0, route);
        } else {
            insertLiteralRoute(root, raw, kind, route);
        }
    }

    private void insertLiteralRoute(Node current, String path, PathPattern.Kind kind, Route route) {
        // Break path into characters and traverse/split nodes
        String remaining = path;
        Node node = current;

        while (!remaining.isEmpty()) {
            Node matchingChild = null;
            for (Node child : node.childBuild) {
                if (child.paramName == null && !child.edge.isEmpty() && child.edge.charAt(0) == remaining.charAt(0)) {
                    matchingChild = child;
                    break;
                }
            }

            if (matchingChild == null) {
                // Add new child with remaining edge
                Node newChild = new Node(remaining);
                if (kind == PathPattern.Kind.EXACT) {
                    newChild.exactBuild.add(route);
                } else {
                    newChild.prefixBuild.add(route);
                }
                node.addChild(newChild);
                return;
            }

            // Find common prefix between remaining and matchingChild.edge
            int commonLen = commonPrefixLength(remaining, matchingChild.edge);

            if (commonLen < matchingChild.edge.length()) {
                // Split matchingChild
                String edgeCommon = matchingChild.edge.substring(0, commonLen);
                String edgeChildRemaining = matchingChild.edge.substring(commonLen);

                Node splitNode = new Node(edgeChildRemaining);
                splitNode.exactBuild.addAll(matchingChild.exactBuild);
                splitNode.prefixBuild.addAll(matchingChild.prefixBuild);
                splitNode.templateBuild.addAll(matchingChild.templateBuild);
                splitNode.childBuild.addAll(matchingChild.childBuild);
                splitNode.paramBuild.addAll(matchingChild.paramBuild);

                matchingChild.edge = edgeCommon;
                matchingChild.exactBuild.clear();
                matchingChild.prefixBuild.clear();
                matchingChild.templateBuild.clear();
                matchingChild.childBuild.clear();
                matchingChild.paramBuild.clear();
                matchingChild.addChild(splitNode);
            }

            remaining = remaining.substring(commonLen);
            node = matchingChild;
        }

        // Terminal node reached
        if (kind == PathPattern.Kind.EXACT) {
            node.exactBuild.add(route);
        } else {
            node.prefixBuild.add(route);
        }
    }

    private void insertTemplateRoute(Node current, String pattern, int index, Route route) {
        if (index >= pattern.length()) {
            current.templateBuild.add(route);
            return;
        }

        int braceStart = pattern.indexOf('{', index);
        if (braceStart < 0) {
            // Remainder is literal
            insertLiteralRoute(current, pattern.substring(index), PathPattern.Kind.EXACT, route);
            return;
        }

        if (braceStart > index) {
            // Literal segment before {
            String literal = pattern.substring(index, braceStart);
            Node child = findOrCreateLiteralChild(current, literal);
            insertTemplateRoute(child, pattern, braceStart, route);
            return;
        }

        // At '{'
        int braceEnd = pattern.indexOf('}', braceStart);
        if (braceEnd < 0) {
            throw new IllegalArgumentException("Malformed path template missing '}': " + pattern);
        }

        String paramName = pattern.substring(braceStart + 1, braceEnd);
        // One param node per placeholder name, so a route's captures always carry its own names
        Node paramNode = null;
        for (Node candidate : current.paramBuild) {
            if (candidate.paramName.equals(paramName)) {
                paramNode = candidate;
                break;
            }
        }
        if (paramNode == null) {
            paramNode = new Node("");
            paramNode.paramName = paramName;
            current.paramBuild.add(paramNode);
        }

        insertTemplateRoute(paramNode, pattern, braceEnd + 1, route);
    }

    private Node findOrCreateLiteralChild(Node current, String literal) {
        for (Node child : current.childBuild) {
            if (child.paramName == null && child.edge.equals(literal)) {
                return child;
            }
        }
        Node child = new Node(literal);
        current.addChild(child);
        return child;
    }

    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    @Override
    public List<Route> routes() {
        return allRoutes;
    }

    @Override
    public RouteLookup newLookup() {
        return new Lookup();
    }

    @Override
    public boolean match(String method, CharSequence path, RouteLookup into) {
        if (!(into instanceof Lookup lookup)) {
            throw new IllegalArgumentException("lookup was not created by this router");
        }
        lookup.reset(method, path);
        if (path == null || !lookup.frame() || !isCanonicalPath(lookup)) {
            lookup.clear();
            return false;
        }

        matchNode(root, 0, lookup);

        Route best = lookup.bestRoute();
        if (best == null) {
            lookup.clear();
            return false;
        }
        lookup.route = best;
        return true;
    }

    private void matchNode(Node node, int offset, Lookup ctx) {
        // A prefix route applies only at a segment boundary: /api covers /api and /api/x, not /apix
        if (node.prefixRoutes.length != 0 && atSegmentBoundary(ctx, offset)) {
            ctx.recordPrefixCandidates(node.prefixRoutes);
        }

        final int length = ctx.viewLength;
        if (offset == length) {
            // Exact node match
            if (node.exactRoutes.length != 0) {
                ctx.recordExactCandidates(node.exactRoutes);
            }
            if (node.templateRoutes.length != 0) {
                ctx.recordTemplateCandidates(node.templateRoutes);
            }
            return;
        }

        // Match literal children
        char nextChar = ctx.charAt(offset);
        Node[] children = node.children;
        for (int c = 0; c < children.length; c++) {
            Node child = children[c];
            if (child.edge.charAt(0) == nextChar && ctx.regionMatches(offset, child.edge)) {
                matchNode(child, offset + child.edge.length(), ctx);
                if (ctx.hasMatch()) {
                    return;
                }
            }
        }

        // Match param children; a placeholder captures a non-empty segment
        Node[] paramChildren = node.paramChildren;
        if (paramChildren.length != 0) {
            int nextOffset = offset;
            while (nextOffset < length && ctx.charAt(nextOffset) != '/') {
                nextOffset++;
            }
            if (nextOffset == offset) {
                return;
            }

            for (int c = 0; c < paramChildren.length; c++) {
                Node paramChild = paramChildren[c];
                ctx.pushParam(paramChild.paramName, offset, nextOffset);
                matchNode(paramChild, nextOffset, ctx);
                if (ctx.hasMatch()) {
                    return;
                }
                // Backtrack if not matched
                ctx.popParam();
            }
        }
    }

    private static boolean atSegmentBoundary(Lookup ctx, int offset) {
        return offset == ctx.viewLength
                || ctx.charAt(offset - 1) == '/'
                || ctx.charAt(offset) == '/';
    }

    /**
     * Routing and the upstream must agree on the path, so a path whose meaning depends on decoding or
     * normalisation is not routed: dot segments (literal or percent-encoded), percent-encoded or literal
     * path separators inside a segment, backslashes and control characters.
     */
    private static boolean isCanonicalPath(Lookup path) {
        int segmentStart = 0;
        int length = path.viewLength;
        for (int i = 0; i <= length; i++) {
            char c = i < length ? path.charAt(i) : '/';
            if (i < length && (c < 0x20 || c == 0x7f || c == '\\')) {
                return false;
            }
            if (c == '%' && i + 2 < length && isEncodedSeparator(path, i)) {
                return false;
            }
            if (c == '/') {
                if (isDotSegment(path, segmentStart, i)) {
                    return false;
                }
                segmentStart = i + 1;
            }
        }
        return true;
    }

    private static boolean isEncodedSeparator(Lookup path, int percentIdx) {
        char hi = path.charAt(percentIdx + 1);
        char lo = path.charAt(percentIdx + 2);
        return (hi == '2' && (lo == 'f' || lo == 'F')) || (hi == '5' && (lo == 'c' || lo == 'C'));
    }

    private static boolean isDotSegment(Lookup path, int start, int end) {
        int dots = 0;
        int i = start;
        while (i < end) {
            char c = path.charAt(i);
            if (c == '.') {
                dots++;
                i++;
            } else if (c == '%' && i + 2 < end && path.charAt(i + 1) == '2'
                    && (path.charAt(i + 2) == 'e' || path.charAt(i + 2) == 'E')) {
                dots++;
                i += 3;
            } else {
                return false;
            }
        }
        return dots == 1 || dots == 2;
    }

    private static final class Node {
        String edge;
        String paramName;
        final List<Route> exactBuild = new ArrayList<>(2);
        final List<Route> prefixBuild = new ArrayList<>(2);
        final List<Route> templateBuild = new ArrayList<>(2);
        final List<Node> childBuild = new ArrayList<>(4);
        final List<Node> paramBuild = new ArrayList<>(1);

        // Frozen at the end of construction; the match path reads only these
        Route[] exactRoutes = NO_ROUTES;
        Route[] prefixRoutes = NO_ROUTES;
        Route[] templateRoutes = NO_ROUTES;
        Node[] children = NO_NODES;
        Node[] paramChildren = NO_NODES;

        Node(String edge) {
            this.edge = Objects.requireNonNull(edge, "edge");
        }

        void addChild(Node child) {
            childBuild.add(child);
        }

        void freeze() {
            exactRoutes = exactBuild.toArray(NO_ROUTES);
            prefixRoutes = prefixBuild.toArray(NO_ROUTES);
            templateRoutes = templateBuild.toArray(NO_ROUTES);
            // Literal children, in insertion order; a literal child always has a non-empty edge
            children = childBuild.stream().filter(n -> n.paramName == null && !n.edge.isEmpty()).toArray(Node[]::new);
            paramChildren = paramBuild.toArray(NO_NODES);
            for (Node n : children) {
                n.freeze();
            }
            for (Node n : paramChildren) {
                n.freeze();
            }
        }
    }

    /** The router's {@link RouteLookup}: the match state, overwritten in place by every call. */
    private static final class Lookup implements RouteLookup {
        private static final int INITIAL_PARAMS = 4;

        // Input: the caller's sequence and the routed view of its path part
        private CharSequence source;
        private String method;
        /** Index in {@link #source} of view position 0, or -1 when the view's leading slash is not in the source. */
        private int shift;
        private int viewLength;
        /** Index in {@link #source} where the query string starts (the '?'), or the source length when none. */
        private int queryStart;

        // Selection
        private Route exactMatch;
        private Route templateMatch;
        private Route prefixMatch;
        Route route;

        // Template captures as offsets into the view, a stack so that backtracking pops
        private String[] paramNames = new String[INITIAL_PARAMS];
        private int[] paramStarts = new int[INITIAL_PARAMS];
        private int[] paramEnds = new int[INITIAL_PARAMS];
        private int paramCount;

        void reset(String method, CharSequence source) {
            this.method = method;
            this.source = source;
            this.exactMatch = null;
            this.templateMatch = null;
            this.prefixMatch = null;
            this.route = null;
            this.paramCount = 0;
        }

        void clear() {
            this.route = null;
            this.paramCount = 0;
            this.source = null;
        }

        /**
         * Frames the routed path inside the source: the query is excluded by position; a missing leading slash
         * is supplied by the view, and repeated leading slashes collapse to the last of them.
         */
        boolean frame() {
            final int len = source.length();
            int pathEnd = 0;
            while (pathEnd < len && source.charAt(pathEnd) != '?') {
                pathEnd++;
            }
            queryStart = pathEnd;
            if (pathEnd > 0 && source.charAt(0) == '/') {
                int leading = 0;
                while (leading + 1 < pathEnd && source.charAt(leading + 1) == '/') {
                    leading++;
                }
                shift = leading;
                viewLength = pathEnd - leading;
            } else {
                shift = -1;
                viewLength = pathEnd + 1;
            }
            return true;
        }

        char charAt(int i) {
            return (shift < 0 && i == 0) ? '/' : source.charAt(shift + i);
        }

        boolean regionMatches(int offset, String edge) {
            final int n = edge.length();
            if (offset + n > viewLength) {
                return false;
            }
            for (int k = 0; k < n; k++) {
                if (charAt(offset + k) != edge.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        boolean hasMatch() {
            return exactMatch != null || templateMatch != null;
        }

        Route bestRoute() {
            if (exactMatch != null) return exactMatch;
            if (templateMatch != null) return templateMatch;
            return prefixMatch;
        }

        void pushParam(String name, int start, int end) {
            if (paramCount == paramNames.length) {
                int grown = paramCount * 2;
                paramNames = java.util.Arrays.copyOf(paramNames, grown);
                paramStarts = java.util.Arrays.copyOf(paramStarts, grown);
                paramEnds = java.util.Arrays.copyOf(paramEnds, grown);
            }
            paramNames[paramCount] = name;
            paramStarts[paramCount] = start;
            paramEnds[paramCount] = end;
            paramCount++;
        }

        void popParam() {
            paramCount--;
        }

        void recordExactCandidates(Route[] routes) {
            Route chosen = selectBest(routes);
            if (chosen != null && (exactMatch == null || chosen.order() < exactMatch.order())) {
                exactMatch = chosen;
            }
        }

        void recordTemplateCandidates(Route[] routes) {
            Route chosen = selectBest(routes);
            if (chosen != null && (templateMatch == null || chosen.order() < templateMatch.order())) {
                templateMatch = chosen;
            }
        }

        void recordPrefixCandidates(Route[] routes) {
            // Candidates arrive shallowest first, so a later one is a longer prefix and always wins;
            // order only decides among routes registered on the same prefix (see selectBest)
            Route chosen = selectBest(routes);
            if (chosen != null) {
                prefixMatch = chosen;
            }
        }

        private Route selectBest(Route[] routes) {
            Route wildcard = null;
            for (int i = 0; i < routes.length; i++) {
                Route r = routes[i];
                if (r.method() == null) {
                    if (wildcard == null || r.order() < wildcard.order()) {
                        wildcard = r;
                    }
                } else if (method != null && r.method().equalsIgnoreCase(method)) {
                    return r; // Specific method match takes immediate precedence
                }
            }
            return wildcard;
        }

        // ---- RouteLookup ----

        @Override
        public boolean isMatched() {
            return route != null;
        }

        @Override
        public Route route() {
            return route;
        }

        @Override
        public String rewrittenPath() {
            if (route == null) {
                return null;
            }
            PathRewriteRule rule = route.rewriteRule();
            // The request already is the routed path: one leading slash, and the query kept as received
            if (rule.type() == PathRewriteRule.RewriteType.NONE && source instanceof String s
                    && shift == 0 && viewLength > 0) {
                return s;
            }
            // Rewrite the normalised path, so a request without a leading slash is rewritten like one with it
            StringBuilder normalised = new StringBuilder(viewLength + source.length() - queryStart);
            for (int i = 0; i < viewLength; i++) {
                normalised.append(charAt(i));
            }
            normalised.append(source, queryStart, source.length());
            return ZeroAllocPathRewriter.rewrite(normalised, rule);
        }

        @Override
        public int pathParamCount() {
            return route == null ? 0 : paramCount;
        }

        @Override
        public String pathParamName(int index) {
            checkIndex(index);
            return paramNames[index];
        }

        @Override
        public String pathParamValue(int index) {
            checkIndex(index);
            StringBuilder value = new StringBuilder(paramEnds[index] - paramStarts[index]);
            for (int i = paramStarts[index]; i < paramEnds[index]; i++) {
                value.append(charAt(i));
            }
            return value.toString();
        }

        private void checkIndex(int index) {
            if (index < 0 || index >= pathParamCount()) {
                throw new IndexOutOfBoundsException("capture index " + index + ", count " + pathParamCount());
            }
        }

        @Override
        public String pathParam(String name) {
            for (int i = pathParamCount() - 1; i >= 0; i--) {
                if (paramNames[i].equals(name)) {
                    return pathParamValue(i);
                }
            }
            return null;
        }

        @Override
        public Map<String, String> pathParams() {
            int n = pathParamCount();
            if (n == 0) {
                return Map.of();
            }
            Map<String, String> params = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                params.put(paramNames[i], pathParamValue(i));
            }
            return Map.copyOf(params);
        }

        @Override
        public RouteMatch toRouteMatch() {
            return route == null ? RouteMatch.noMatch() : RouteMatch.matched(route, rewrittenPath(), pathParams());
        }
    }
}
