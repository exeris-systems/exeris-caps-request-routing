package eu.exeris.caps.routing.api;

import java.util.Objects;

/**
 * Specification for transforming an HTTP header field during request or response processing.
 *
 * @since 0.1.0
 */
public final class HeaderMutation {

    public enum Action {
        /**
         * Appends the header with the specified value, preserving existing values.
         */
        ADD,
        /**
         * Sets or overwrites the header with the specified value.
         */
        SET,
        /**
         * Removes all instances of the header case-insensitively.
         */
        REMOVE,
        /**
         * Injects a value extracted from the authentication context (e.g. JWT claim "sub" as "X-User-Id").
         */
        INJECT_CLAIM
    }

    private final Action action;
    private final String headerName;
    private final String value;

    private HeaderMutation(Action action, String headerName, String value) {
        this.action = Objects.requireNonNull(action, "action");
        this.headerName = Objects.requireNonNull(headerName, "headerName");
        this.value = value;
    }

    public static HeaderMutation add(String headerName, String value) {
        Objects.requireNonNull(value, "value");
        return new HeaderMutation(Action.ADD, headerName, value);
    }

    public static HeaderMutation set(String headerName, String value) {
        Objects.requireNonNull(value, "value");
        return new HeaderMutation(Action.SET, headerName, value);
    }

    public static HeaderMutation remove(String headerName) {
        return new HeaderMutation(Action.REMOVE, headerName, null);
    }

    public static HeaderMutation injectClaim(String headerName, String claimKey) {
        Objects.requireNonNull(claimKey, "claimKey");
        return new HeaderMutation(Action.INJECT_CLAIM, headerName, claimKey);
    }

    public Action action() {
        return action;
    }

    public String headerName() {
        return headerName;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeaderMutation that)) return false;
        return action == that.action &&
                headerName.equalsIgnoreCase(that.headerName) &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, headerName.toLowerCase(java.util.Locale.ROOT), value);
    }

    @Override
    public String toString() {
        return action + " " + headerName + (value != null ? " = " + value : "");
    }
}
