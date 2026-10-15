package eu.exeris.caps.routing.api;

import java.util.Objects;

/**
 * Lightweight, immutable HTTP header key-value pair carrier.
 *
 * <p>Free of transport framing, HPACK tables, and kernel wire dependencies.
 *
 * @param name  header field name; non-null
 * @param value header field value; non-null
 * @since 0.1.0
 */
public record HeaderEntry(String name, String value) {

    public HeaderEntry {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }

    public boolean nameEqualsIgnoreCase(String other) {
        return name.equalsIgnoreCase(other);
    }
}
