package io.brace;

import java.util.*;

public class Errors {
    private final Map<String, List<String>> errors = new LinkedHashMap<>();

    public void add(String field, String message) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    public boolean hasErrors() { return !errors.isEmpty(); }
    public Map<String, List<String>> all() { return Collections.unmodifiableMap(errors); }
    public List<String> get(String field) { return errors.getOrDefault(field, List.of()); }
}
