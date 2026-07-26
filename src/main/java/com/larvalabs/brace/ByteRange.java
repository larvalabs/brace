package com.larvalabs.brace;

/**
 * A single resolved byte range from a {@code Range} request header (RFC 9110 §14).
 *
 * <p>Only single ranges are resolved. A multi-range request ({@code bytes=0-99,200-299}) is
 * reported as {@link #UNSUPPORTED}, and the caller serves the whole resource with a 200 — which is
 * legal, is what most servers do, and avoids the {@code multipart/byteranges} machinery for a case
 * essentially no real client sends. What real clients do send is a single range: video players
 * seeking, download managers resuming, and CDNs fetching slices.
 *
 * @param first first byte position, inclusive
 * @param last last byte position, inclusive
 */
record ByteRange(long first, long last) {

    /** Not a range request, or one this server does not resolve — serve the whole resource. */
    static final ByteRange UNSUPPORTED = new ByteRange(-1, -1);

    /** The range cannot be satisfied against the resource's actual size — the caller sends 416. */
    static final ByteRange UNSATISFIABLE = new ByteRange(-2, -2);

    long length() {
        return last - first + 1;
    }

    /**
     * Parses a {@code Range} header against a known resource size.
     *
     * @param header the raw header value, may be null
     * @param size   the total size of the resource; must be known to resolve a suffix range
     */
    static ByteRange parse(String header, long size) {
        if (header == null || size < 0) return UNSUPPORTED;
        String value = header.strip();
        // "Range: bytes=..." is the only unit anything sends; an unknown unit means "ignore".
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) return UNSUPPORTED;
        String spec = value.substring(6).strip();
        if (spec.indexOf(',') >= 0) return UNSUPPORTED; // multi-range: serve the whole thing

        int dash = spec.indexOf('-');
        if (dash < 0) return UNSUPPORTED;
        String fromText = spec.substring(0, dash).strip();
        String toText = spec.substring(dash + 1).strip();

        try {
            if (fromText.isEmpty()) {
                // Suffix form: "-500" means the final 500 bytes.
                if (toText.isEmpty()) return UNSUPPORTED;
                long suffix = Long.parseLong(toText);
                if (suffix <= 0) return UNSATISFIABLE;
                // A suffix longer than the resource is not an error — it means the whole resource.
                long first = Math.max(0, size - suffix);
                return size == 0 ? UNSATISFIABLE : new ByteRange(first, size - 1);
            }
            long first = Long.parseLong(fromText);
            if (first < 0) return UNSUPPORTED;
            // A start at or past the end is the one case RFC 9110 requires a 416 for.
            if (first >= size) return UNSATISFIABLE;
            long last = toText.isEmpty() ? size - 1 : Long.parseLong(toText);
            if (last < first) return UNSUPPORTED;
            // Clamp rather than reject: asking past the end of the file is normal for a client
            // that does not know the size yet.
            return new ByteRange(first, Math.min(last, size - 1));
        } catch (NumberFormatException e) {
            // Includes values too large for a long — malformed, so ignore the header entirely
            // rather than guessing what was meant.
            return UNSUPPORTED;
        }
    }
}
