package eu.exeris.caps.routing.internal;

import eu.exeris.caps.routing.api.PathRewriteRule;

/**
 * Utility for applying path rewrites while minimizing allocations.
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
        if (rewriteRule == null || rewriteRule.type() == PathRewriteRule.RewriteType.NONE) {
            return fullPath.toString();
        }

        String pathStr = fullPath.toString();
        int queryIdx = pathStr.indexOf('?');
        String pathOnly = queryIdx < 0 ? pathStr : pathStr.substring(0, queryIdx);
        String queryOnly = queryIdx < 0 ? "" : pathStr.substring(queryIdx);

        String rewrittenBase = rewriteRule.rewrite(pathOnly);
        if (queryOnly.isEmpty()) {
            return rewrittenBase;
        }
        return rewrittenBase + queryOnly;
    }
}
