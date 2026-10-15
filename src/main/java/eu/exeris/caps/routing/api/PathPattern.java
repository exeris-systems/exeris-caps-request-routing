package eu.exeris.caps.routing.api;

import java.util.Objects;

/**
 * Path matching specification for an L7 route.
 *
 * <p>Supported pattern kinds:
 * <ul>
 *   <li>{@link Kind#EXACT}: Matches the full request path exactly (e.g. {@code /health}).</li>
 *   <li>{@link Kind#PREFIX}: Matches paths starting with the specified prefix (e.g. {@code /api/v1/orders}).</li>
 *   <li>{@link Kind#TEMPLATE}: Matches paths containing {@code {placeholder}} segments and captures their values.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class PathPattern {

    public enum Kind {
        EXACT,
        PREFIX,
        TEMPLATE
    }

    private final Kind kind;
    private final String rawPattern;

    private PathPattern(Kind kind, String rawPattern) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.rawPattern = Objects.requireNonNull(rawPattern, "rawPattern");
    }

    public static PathPattern exact(String path) {
        Objects.requireNonNull(path, "path");
        return new PathPattern(Kind.EXACT, normalize(path));
    }

    public static PathPattern prefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return new PathPattern(Kind.PREFIX, normalize(prefix));
    }

    public static PathPattern template(String template) {
        Objects.requireNonNull(template, "template");
        String norm = normalize(template);
        if (!norm.contains("{") || !norm.contains("}")) {
            return exact(norm);
        }
        return new PathPattern(Kind.TEMPLATE, norm);
    }

    public Kind kind() {
        return kind;
    }

    public String rawPattern() {
        return rawPattern;
    }

    private static String normalize(String p) {
        String trimmed = p.trim();
        if (trimmed.isEmpty()) {
            return "/";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathPattern that)) return false;
        return kind == that.kind && Objects.equals(rawPattern, that.rawPattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, rawPattern);
    }

    @Override
    public String toString() {
        return kind + ":" + rawPattern;
    }
}
