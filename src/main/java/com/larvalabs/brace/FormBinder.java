package com.larvalabs.brace;

import com.larvalabs.brace.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FormBinder {

    /**
     * Per-record reflection, resolved once and cached (M4). Without this every bind re-ran
     * {@code getRecordComponents()} (a fresh cloned array), {@code getAnnotations()} per component,
     * a {@code getDeclaredConstructor} scan + {@code setAccessible}, and — for every form lacking a
     * custom validator — a {@code getDeclaredMethod("validate", …)} that *constructed and threw* a
     * {@code NoSuchMethodException} (stack-trace fill) as control flow. The cache key cardinality is
     * the app's form record types (code-defined, small, not request-controlled), so it never needs
     * eviction.
     */
    private record FieldMeta(String name, Class<?> type, Annotation[] annotations) {}

    /** {@code validate} is null when the record declares no {@code validate(Errors)} — an absence
     *  marker so the lookup (and its exception) never runs again for that class. */
    private record FormMeta(FieldMeta[] fields, Constructor<?> constructor, Method validate) {}

    private static final Map<Class<?>, FormMeta> META_CACHE = new ConcurrentHashMap<>();

    public static <T> Form<T> bind(Class<T> recordClass, Map<String, String> params) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException(recordClass.getName() + " is not a record");
        }

        FormMeta meta = META_CACHE.computeIfAbsent(recordClass, FormBinder::buildMeta);
        var fields = meta.fields();

        var errors = new Errors();
        var rawValues = new LinkedHashMap<String, String>();
        var values = new Object[fields.length];

        for (int i = 0; i < fields.length; i++) {
            var field = fields[i];
            var raw = params.get(field.name());
            rawValues.put(field.name(), raw);

            // Convert to target type
            values[i] = convert(raw, field.type(), field.name(), errors);

            // Run annotation validations
            validate(field, raw, values[i], errors);
        }

        // Construct the record
        T instance = construct(recordClass, meta.constructor(), values);

        // Call custom validate(Errors) if present
        callCustomValidate(instance, meta.validate(), errors);

        return new Form<>(instance, errors, rawValues);
    }

    private static FormMeta buildMeta(Class<?> recordClass) {
        var components = recordClass.getRecordComponents();
        var fields = new FieldMeta[components.length];
        var paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            var comp = components[i];
            fields[i] = new FieldMeta(comp.getName(), comp.getType(), comp.getAnnotations());
            paramTypes[i] = comp.getType();
        }

        Constructor<?> constructor;
        try {
            constructor = recordClass.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            // A record always has a canonical constructor matching its component types, so this is
            // effectively unreachable — surface it loudly rather than swallowing if it ever isn't.
            throw new RuntimeException(
                "Record " + recordClass.getSimpleName() + " has no canonical constructor", e);
        }

        Method validate;
        try {
            validate = recordClass.getDeclaredMethod("validate", Errors.class);
            validate.setAccessible(true);
        } catch (NoSuchMethodException e) {
            validate = null; // no custom validation — resolved once, not re-thrown per bind
        }

        return new FormMeta(fields, constructor, validate);
    }

    private static Object convert(String raw, Class<?> type, String name, Errors errors) {
        if (raw == null || raw.isEmpty()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            if (type == boolean.class) return false;
            return null;
        }

        if (type.isEnum()) {
            return convertEnum(raw, type, name, errors);
        }
        if (type == java.time.LocalDate.class) {
            try {
                return java.time.LocalDate.parse(raw);
            } catch (java.time.format.DateTimeParseException e) {
                errors.add(name, "must be a date (yyyy-MM-dd)");
                return null;
            }
        }
        if (type == java.time.Instant.class) {
            try {
                return java.time.Instant.parse(raw);
            } catch (java.time.format.DateTimeParseException e) {
                errors.add(name, "must be an ISO-8601 timestamp (e.g. 2026-06-11T12:00:00Z)");
                return null;
            }
        }

        try {
            if (type == String.class) return raw;
            if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
            if (type == long.class || type == Long.class) return Long.parseLong(raw);
            if (type == double.class || type == Double.class) {
                double d = Double.parseDouble(raw);
                if (!Double.isFinite(d)) return notFinite(type, name, errors);
                return d;
            }
            if (type == float.class || type == Float.class) {
                float f = Float.parseFloat(raw);
                if (!Float.isFinite(f)) return notFinite(type, name, errors);
                return f;
            }
            if (type == boolean.class || type == Boolean.class) return parseCheckbox(raw);
            if (type == java.math.BigDecimal.class) return new java.math.BigDecimal(raw);
            return raw;
        } catch (NumberFormatException e) {
            errors.add(name, "invalid number");
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            return null;
        }
    }

    /**
     * NaN and ±Infinity (including overflow, e.g. {@code 1e999}) are not numbers a form can mean:
     * NaN compares false against every bound, so it slipped past {@code @Min}/{@code @Max}. Same
     * fallback values as an unparseable number.
     */
    private static Object notFinite(Class<?> type, String name, Errors errors) {
        errors.add(name, "must be a finite number");
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        return null;
    }

    /**
     * Exact comparison of a bound against a bound-typed value, so a fraction below/above the
     * bound is not truncated away ({@code long} truncation passed {@code -0.99} for
     * {@code @Min(0)}). Integral types compare as {@code long}; {@code BigDecimal} and
     * {@code BigInteger} compare exactly (no expansion, so {@code 1e999999999} is safe);
     * {@code double}/{@code float} use primitive comparison so {@code -0.0} equals {@code 0}.
     * NaN (already rejected in {@link #convert}) compares as out of range on both sides.
     */
    private static boolean belowMin(Number n, long min) {
        if (n instanceof java.math.BigDecimal bd) return bd.compareTo(java.math.BigDecimal.valueOf(min)) < 0;
        if (n instanceof java.math.BigInteger bi) return bi.compareTo(java.math.BigInteger.valueOf(min)) < 0;
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            return Double.isNaN(d) || d < (double) min;
        }
        return n.longValue() < min;
    }

    private static boolean aboveMax(Number n, long max) {
        if (n instanceof java.math.BigDecimal bd) return bd.compareTo(java.math.BigDecimal.valueOf(max)) > 0;
        if (n instanceof java.math.BigInteger bi) return bi.compareTo(java.math.BigInteger.valueOf(max)) > 0;
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            return Double.isNaN(d) || d > (double) max;
        }
        return n.longValue() > max;
    }

    /**
     * Truthy values accepted for a {@code boolean} form field (M2). An HTML checkbox submits
     * {@code name=on} when checked and nothing at all when unchecked, so
     * {@code Boolean.parseBoolean} — true for the literal string "true" and nothing else — bound a
     * CHECKED box to {@code false}, making the control silently inert. Absence still means false
     * (handled in {@link #convert}'s empty branch), which is what an unchecked box relies on.
     *
     * <p>{@code "on"} is the HTML default; {@code "1"}/{@code "yes"}/{@code "checked"} cover the
     * common hand-rolled and JSON-ish variants ({@code jsonForm} runs through the same converter,
     * where a real JSON {@code true} arrives as {@code "true"}).
     */
    private static final Set<String> TRUTHY = Set.of("true", "on", "1", "yes", "checked");

    private static boolean parseCheckbox(String raw) {
        return TRUTHY.contains(raw.trim().toLowerCase());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertEnum(String raw, Class<?> type, String name, Errors errors) {
        try {
            return Enum.valueOf((Class<Enum>) type, raw);
        } catch (IllegalArgumentException e) {
            var names = new ArrayList<String>();
            for (var constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            errors.add(name, "must be one of: " + String.join(", ", names));
            return null;
        }
    }

    private static void validate(FieldMeta field, String raw, Object value, Errors errors) {
        var name = field.name();

        for (var ann : field.annotations()) {
            if (ann instanceof Required) {
                if (raw == null || raw.trim().isEmpty()) {
                    errors.add(name, "is required");
                }
            } else if (ann instanceof MinLength ml) {
                if (raw != null && !raw.isEmpty() && raw.length() < ml.value()) {
                    errors.add(name, "must be at least " + ml.value() + " characters");
                }
            } else if (ann instanceof MaxLength ml) {
                if (raw != null && raw.length() > ml.value()) {
                    errors.add(name, "must be at most " + ml.value() + " characters");
                }
            } else if (ann instanceof Min min) {
                if (value instanceof Number n && belowMin(n, min.value())) {
                    errors.add(name, "must be at least " + min.value());
                }
            } else if (ann instanceof Max max) {
                if (value instanceof Number n && aboveMax(n, max.value())) {
                    errors.add(name, "must be at most " + max.value());
                }
            } else if (ann instanceof Email) {
                if (raw != null && !raw.isEmpty()) {
                    int at = raw.indexOf('@');
                    if (at < 1 || raw.indexOf('.', at) < 0) {
                        errors.add(name, "must be a valid email");
                    }
                }
            } else if (ann instanceof com.larvalabs.brace.annotation.SafeUrl) {
                if (raw != null && !raw.isEmpty() && !Url.isAbsoluteHttp(raw)) {
                    errors.add(name, "must be an http or https URL");
                }
            } else if (ann instanceof In in) {
                if (raw != null && !raw.isEmpty()) {
                    if (!Arrays.asList(in.value()).contains(raw)) {
                        errors.add(name, "must be one of: " + String.join(", ", in.value()));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T construct(Class<T> recordClass, Constructor<?> constructor, Object[] values) {
        try {
            return (T) constructor.newInstance(values);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct record " + recordClass.getSimpleName(), e);
        }
    }

    private static void callCustomValidate(Object instance, Method validate, Errors errors) {
        if (instance == null || validate == null) return;
        try {
            validate.invoke(instance, errors);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call validate() on " + instance.getClass().getSimpleName(), e);
        }
    }
}
