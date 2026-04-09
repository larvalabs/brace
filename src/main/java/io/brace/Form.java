package io.brace;

import java.util.*;

public class Form<T> {
    private final T value;
    private final Errors errors;
    private final Map<String, String> rawValues;

    public Form(T value, Errors errors, Map<String, String> rawValues) {
        this.value = value;
        this.errors = errors;
        this.rawValues = rawValues;
    }

    public boolean hasErrors() { return errors.hasErrors(); }
    public T value() { return value; }
    public Errors errors() { return errors; }
    public Map<String, List<String>> allErrors() { return errors.all(); }
    public List<String> errors(String field) { return errors.get(field); }
    public String raw(String field) { return rawValues.get(field); }
}
