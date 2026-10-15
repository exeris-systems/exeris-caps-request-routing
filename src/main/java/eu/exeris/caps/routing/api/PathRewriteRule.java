package eu.exeris.caps.routing.api;

import java.util.Objects;

/**
 * Rule governing how an inbound request path is transformed before dispatch to an upstream service.
 *
 * @since 0.1.0
 */
public final class PathRewriteRule {

    public enum RewriteType {
        NONE,
        STRIP_PREFIX,
        PREFIX_REWRITE,
        EXACT_REWRITE
    }

    private static final PathRewriteRule NO_REWRITE = new PathRewriteRule(RewriteType.NONE, null, null);

    private final RewriteType type;
    private final String matchPrefix;
    private final String replacement;

    private PathRewriteRule(RewriteType type, String matchPrefix, String replacement) {
        this.type = Objects.requireNonNull(type, "type");
        this.matchPrefix = matchPrefix;
        this.replacement = replacement;
    }

    public static PathRewriteRule none() {
        return NO_REWRITE;
    }

    public static PathRewriteRule stripPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return new PathRewriteRule(RewriteType.STRIP_PREFIX, prefix, null);
    }

    public static PathRewriteRule prefixRewrite(String matchPrefix, String replacement) {
        Objects.requireNonNull(matchPrefix, "matchPrefix");
        Objects.requireNonNull(replacement, "replacement");
        return new PathRewriteRule(RewriteType.PREFIX_REWRITE, matchPrefix, replacement);
    }

    public static PathRewriteRule exactRewrite(String targetPath) {
        Objects.requireNonNull(targetPath, "targetPath");
        return new PathRewriteRule(RewriteType.EXACT_REWRITE, null, targetPath);
    }

    public RewriteType type() {
        return type;
    }

    public String matchPrefix() {
        return matchPrefix;
    }

    public String replacement() {
        return replacement;
    }

    /**
     * Applies this rewrite rule to the given request path.
     *
     * @param path inbound request path; a {@code null} path yields {@code "/"}
     * @return rewritten path; non-null
     */
    public String rewrite(CharSequence path) {
        if (path == null) {
            return "/";
        }
        String pathStr = path.toString();
        return switch (type) {
            case NONE -> pathStr;
            case EXACT_REWRITE -> replacement;
            case STRIP_PREFIX -> {
                if (matchesPrefix(pathStr)) {
                    String sub = pathStr.substring(matchPrefix.length());
                    yield singleLeadingSlash(sub.startsWith("/") ? sub : "/" + sub);
                }
                yield pathStr;
            }
            case PREFIX_REWRITE -> {
                if (matchesPrefix(pathStr)) {
                    String sub = pathStr.substring(matchPrefix.length());
                    if (sub.startsWith("/")) {
                        yield singleLeadingSlash(replacement.endsWith("/")
                                ? replacement.substring(0, replacement.length() - 1) + sub
                                : replacement + sub);
                    } else if (sub.isEmpty()) {
                        yield replacement;
                    } else {
                        yield singleLeadingSlash(replacement.endsWith("/")
                                ? replacement + sub
                                : replacement + "/" + sub);
                    }
                }
                yield pathStr;
            }
        };
    }

    /** The prefix applies only at a segment boundary: {@code /api} covers {@code /api/x}, not {@code /apix}. */
    private boolean matchesPrefix(String pathStr) {
        if (matchPrefix == null || !pathStr.startsWith(matchPrefix)) {
            return false;
        }
        return pathStr.length() == matchPrefix.length()
                || matchPrefix.endsWith("/")
                || pathStr.charAt(matchPrefix.length()) == '/';
    }

    /**
     * A path beginning with two slashes reads as a network-path reference (an authority) to a consumer that
     * assembles a URI, so a rewritten path begins with exactly one.
     */
    private static String singleLeadingSlash(String rewritten) {
        int i = 0;
        while (i + 1 < rewritten.length() && rewritten.charAt(i) == '/' && rewritten.charAt(i + 1) == '/') {
            i++;
        }
        return i == 0 ? rewritten : rewritten.substring(i);
    }
}
