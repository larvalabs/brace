package com.larvalabs.brace;

public class Middleware {

    @FunctionalInterface
    public interface Before {
        Result handle(Request req);
    }

    @FunctionalInterface
    public interface After {
        Result handle(Request req, Result result);
    }

    public record BoundBefore(PathPattern pattern, Before handler) {
        public Result apply(Request req) {
            if (pattern == null || pattern.matches(req.path())) {
                return handler.handle(req);
            }
            return null;
        }
    }

    public record BoundAfter(PathPattern pattern, After handler) {
        public Result apply(Request req, Result result) {
            if (pattern == null || pattern.matches(req.path())) {
                return handler.handle(req, result);
            }
            return result;
        }
    }

    public static class PathPattern {
        // The only supported shapes are exact and trailing-/* prefix, so matching is plain
        // string comparison — running a regex (and allocating a Matcher) per request per
        // middleware was pure overhead. Semantics match the previous ^prefix(/.*)?$ regex.
        private final String exact;            // the exact path, or the prefix without "/*"
        private final String prefixWithSlash;  // precomputed "prefix/" for prefix patterns; null for exact

        private PathPattern(String exact, String prefixWithSlash) {
            this.exact = exact;
            this.prefixWithSlash = prefixWithSlash;
        }

        public static PathPattern compile(String pattern) {
            // Reject interior wildcards at registration time.
            // Valid patterns are: /path/exact or /path/* (trailing only).
            if (pattern.contains("*")) {
                if (!pattern.endsWith("/*")) {
                    throw new IllegalArgumentException(
                        "Interior wildcards are not supported; only a trailing /* is allowed: " + pattern
                    );
                }
            }

            if (pattern.endsWith("/*")) {
                var prefix = pattern.substring(0, pattern.length() - 2);
                // Matches bare prefix, prefix/, and prefix/anything
                return new PathPattern(prefix, prefix + "/");
            }
            return new PathPattern(pattern, null);
        }

        public boolean matches(String path) {
            if (prefixWithSlash == null) {
                return path.equals(exact);
            }
            return path.equals(exact) || path.startsWith(prefixWithSlash);
        }
    }
}
