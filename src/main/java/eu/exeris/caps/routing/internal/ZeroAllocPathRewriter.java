package eu.exeris.caps.routing.internal;

import eu.exeris.caps.routing.api.PathRewriteRule;

/**
 * Applies a route's path rewrite and preserves the query string; the result never begins with {@code //}.
 *
 * <p>Each call allocates the result string (and the intermediate strings of the rewrite).
 */
public final class ZeroAllocPathRewriter {

    private ZeroAllocPathRewriter() {
    }

    /**
     * Rewrites the given path and preserves any query string present.
     *
     * @param fullPath    the complete request path (may include query string)
     * @param rewriteRule the rewrite rule to apply
     * @return the rewritten path
     */
    public static String rewrite(CharSequence fullPath, PathRewriteRule rewriteRule) {
        if (fullPath == null || fullPath.length() == 0) {
            return "/";
        }
        String pathStr = fullPath.toString();
        int queryIdx = pathStr.indexOf('?');
        String pathOnly = queryIdx < 0 ? pathStr : pathStr.substring(0, queryIdx);
        String queryOnly = queryIdx < 0 ? "" : pathStr.substring(queryIdx);

        String rewrittenBase = rewriteRule == null ? pathOnly : rewriteRule.rewrite(pathOnly);
        return singleLeadingSlash(rewrittenBase) + queryOnly;
    }

    /**
     * Whatever the rewrite type, the result begins with at most one slash: a leading {@code //} reads as a
     * network-path reference (an authority) to a consumer that assembles a URI.
     */
    private static String singleLeadingSlash(String path) {
        int i = 0;
        while (i + 1 < path.length() && path.charAt(i) == '/' && path.charAt(i + 1) == '/') {
            i++;
        }
        return i == 0 ? path : path.substring(i);
    }
}
