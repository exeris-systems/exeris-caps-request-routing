package eu.exeris.caps.routing.internal;

import eu.exeris.caps.routing.api.PathPattern;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.Route;
import eu.exeris.caps.routing.api.RouteMatch;

import java.util.ArrayList;
import java.util.Collections;
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
 * @since 0.1.0
 */
public final class RadixTreeRequestRouter implements RequestRouter {

    private static final Comparator<Route> ROUTE_ORDER_COMPARATOR =
            Comparator.comparingInt(Route::order).thenComparing(Route::id);

    private final List<Route> allRoutes;
    private final Node root;

    public RadixTreeRequestRouter(List<Route> routes) {
        Objects.requireNonNull(routes, "routes");
        this.allRoutes = List.copyOf(routes);
        this.root = new Node("");
        buildTree(this.allRoutes);
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
            for (Node child : node.children) {
                if (child.paramName == null && !child.edge.isEmpty() && child.edge.charAt(0) == remaining.charAt(0)) {
                    matchingChild = child;
                    break;
                }
            }

            if (matchingChild == null) {
                // Add new child with remaining edge
                Node newChild = new Node(remaining);
                if (kind == PathPattern.Kind.EXACT) {
                    newChild.exactRoutes.add(route);
                } else {
                    newChild.prefixRoutes.add(route);
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
                splitNode.exactRoutes.addAll(matchingChild.exactRoutes);
                splitNode.prefixRoutes.addAll(matchingChild.prefixRoutes);
                splitNode.templateRoutes.addAll(matchingChild.templateRoutes);
                splitNode.children.addAll(matchingChild.children);
                splitNode.paramChildren.addAll(matchingChild.paramChildren);

                matchingChild.edge = edgeCommon;
                matchingChild.exactRoutes.clear();
                matchingChild.prefixRoutes.clear();
                matchingChild.templateRoutes.clear();
                matchingChild.children.clear();
                matchingChild.paramChildren.clear();
                matchingChild.addChild(splitNode);
            }

            remaining = remaining.substring(commonLen);
            node = matchingChild;
        }

        // Terminal node reached
        if (kind == PathPattern.Kind.EXACT) {
            node.exactRoutes.add(route);
        } else {
            node.prefixRoutes.add(route);
        }
    }

    private void insertTemplateRoute(Node current, String pattern, int index, Route route) {
        if (index >= pattern.length()) {
            current.templateRoutes.add(route);
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
        for (Node candidate : current.paramChildren) {
            if (candidate.paramName.equals(paramName)) {
                paramNode = candidate;
                break;
            }
        }
        if (paramNode == null) {
            paramNode = new Node("");
            paramNode.paramName = paramName;
            current.paramChildren.add(paramNode);
        }

        insertTemplateRoute(paramNode, pattern, braceEnd + 1, route);
    }

    private Node findOrCreateLiteralChild(Node current, String literal) {
        for (Node child : current.children) {
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
    public RouteMatch match(String method, CharSequence path) {
        if (path == null) {
            return RouteMatch.noMatch();
        }

        String fullPath = path.toString();
        int queryIdx = fullPath.indexOf('?');
        String searchPath = queryIdx < 0 ? fullPath : fullPath.substring(0, queryIdx);
        if (!searchPath.startsWith("/")) {
            searchPath = "/" + searchPath;
        }
        // Routing and the upstream must see one path: leading slashes collapse to one before matching,
        // the same way the rewriter collapses them, so //admin/x routes as /admin/x
        int leading = 0;
        while (leading + 1 < searchPath.length() && searchPath.charAt(leading + 1) == '/') {
            leading++;
        }
        if (leading > 0) {
            searchPath = searchPath.substring(leading);
        }

        if (!isCanonicalPath(searchPath)) {
            return RouteMatch.noMatch();
        }

        MatchContext ctx = new MatchContext(method, searchPath);
        matchNode(root, searchPath, 0, ctx);

        Route best = ctx.bestRoute();
        if (best == null) {
            return RouteMatch.noMatch();
        }

        // Rewrite the normalised path, so a request without a leading slash is rewritten like one with it
        String normalised = queryIdx < 0 ? searchPath : searchPath + fullPath.substring(queryIdx);
        String rewritten = ZeroAllocPathRewriter.rewrite(normalised, best.rewriteRule());
        return RouteMatch.matched(best, rewritten, ctx.params);
    }

    private void matchNode(Node node, String path, int offset, MatchContext ctx) {
        // A prefix route applies only at a segment boundary: /api covers /api and /api/x, not /apix
        if (!node.prefixRoutes.isEmpty() && atSegmentBoundary(path, offset)) {
            ctx.recordPrefixCandidates(node.prefixRoutes);
        }

        if (offset == path.length()) {
            // Exact node match
            if (!node.exactRoutes.isEmpty()) {
                ctx.recordExactCandidates(node.exactRoutes);
            }
            if (!node.templateRoutes.isEmpty()) {
                ctx.recordTemplateCandidates(node.templateRoutes);
            }
            return;
        }

        // Match literal children
        char nextChar = path.charAt(offset);
        for (Node child : node.children) {
            if (child.paramName == null && !child.edge.isEmpty() && child.edge.charAt(0) == nextChar) {
                if (path.startsWith(child.edge, offset)) {
                    matchNode(child, path, offset + child.edge.length(), ctx);
                    if (ctx.hasMatch()) {
                        return;
                    }
                }
            }
        }

        // Match param children; a placeholder captures a non-empty segment
        if (!node.paramChildren.isEmpty()) {
            int slashIdx = path.indexOf('/', offset);
            int nextOffset = slashIdx < 0 ? path.length() : slashIdx;
            if (nextOffset == offset) {
                return;
            }
            String paramVal = path.substring(offset, nextOffset);

            for (Node paramChild : node.paramChildren) {
                ctx.addParam(paramChild.paramName, paramVal);
                matchNode(paramChild, path, nextOffset, ctx);
                if (ctx.hasMatch()) {
                    return;
                }
                // Backtrack if not matched
                ctx.removeParam(paramChild.paramName);
            }
        }
    }

    private static boolean atSegmentBoundary(String path, int offset) {
        return offset == path.length()
                || path.charAt(offset - 1) == '/'
                || path.charAt(offset) == '/';
    }

    /**
     * Routing and the upstream must agree on the path, so a path whose meaning depends on decoding or
     * normalisation is not routed: dot segments (literal or percent-encoded), percent-encoded or literal
     * path separators inside a segment, backslashes and control characters.
     */
    private static boolean isCanonicalPath(String path) {
        int segmentStart = 0;
        int length = path.length();
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

    private static boolean isEncodedSeparator(String path, int percentIdx) {
        char hi = path.charAt(percentIdx + 1);
        char lo = path.charAt(percentIdx + 2);
        return (hi == '2' && (lo == 'f' || lo == 'F')) || (hi == '5' && (lo == 'c' || lo == 'C'));
    }

    private static boolean isDotSegment(String path, int start, int end) {
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
        final List<Route> exactRoutes = new ArrayList<>(2);
        final List<Route> prefixRoutes = new ArrayList<>(2);
        final List<Route> templateRoutes = new ArrayList<>(2);
        final List<Node> children = new ArrayList<>(4);
        final List<Node> paramChildren = new ArrayList<>(1);

        Node(String edge) {
            this.edge = Objects.requireNonNull(edge, "edge");
        }

        void addChild(Node child) {
            children.add(child);
        }
    }

    private static final class MatchContext {
        final String method;
        final String path;
        Map<String, String> params;

        Route exactMatch;
        Route templateMatch;
        Route prefixMatch;

        MatchContext(String method, String path) {
            this.method = method;
            this.path = path;
        }

        boolean hasExactMatch() {
            return exactMatch != null;
        }

        boolean hasMatch() {
            return exactMatch != null || templateMatch != null;
        }

        void addParam(String name, String value) {
            if (params == null) {
                params = new HashMap<>(4);
            }
            params.put(name, value);
        }

        void removeParam(String name) {
            if (params != null) {
                params.remove(name);
            }
        }

        void recordExactCandidates(List<Route> routes) {
            Route chosen = selectBest(routes);
            if (chosen != null && (exactMatch == null || chosen.order() < exactMatch.order())) {
                exactMatch = chosen;
            }
        }

        void recordTemplateCandidates(List<Route> routes) {
            Route chosen = selectBest(routes);
            if (chosen != null && (templateMatch == null || chosen.order() < templateMatch.order())) {
                templateMatch = chosen;
            }
        }

        void recordPrefixCandidates(List<Route> routes) {
            // Candidates arrive shallowest first, so a later one is a longer prefix and always wins;
            // order only decides among routes registered on the same prefix (see selectBest)
            Route chosen = selectBest(routes);
            if (chosen != null) {
                prefixMatch = chosen;
            }
        }

        private Route selectBest(List<Route> routes) {
            Route wildcard = null;
            for (int i = 0; i < routes.size(); i++) {
                Route r = routes.get(i);
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

        Route bestRoute() {
            if (exactMatch != null) return exactMatch;
            if (templateMatch != null) return templateMatch;
            return prefixMatch;
        }
    }
}
