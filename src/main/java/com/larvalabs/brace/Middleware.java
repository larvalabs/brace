package com.larvalabs.brace;

import java.util.regex.Pattern;

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
        private final Pattern regex;

        private PathPattern(Pattern regex) {
            this.regex = regex;
        }

        public static PathPattern compile(String pattern) {
            String regex;
            if (pattern.endsWith("/*")) {
                var prefix = pattern.substring(0, pattern.length() - 2);
                regex = "^" + Pattern.quote(prefix) + "/.+$";
            } else {
                regex = "^" + Pattern.quote(pattern) + "$";
            }
            return new PathPattern(Pattern.compile(regex));
        }

        public boolean matches(String path) {
            return regex.matcher(path).matches();
        }
    }
}
