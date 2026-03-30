package io.brace;

import io.brace.annotation.*;

import java.lang.reflect.*;
import java.util.*;

public class FormBinder {

    public static <T> Form<T> bind(Class<T> recordClass, Map<String, String> params) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException(recordClass.getName() + " is not a record");
        }

        var components = recordClass.getRecordComponents();
        var errors = new Errors();
        var rawValues = new LinkedHashMap<String, String>();
        var values = new Object[components.length];
        var types = new Class<?>[components.length];

        for (int i = 0; i < components.length; i++) {
            var comp = components[i];
            var name = comp.getName();
            var type = comp.getType();
            types[i] = type;
            var raw = params.get(name);
            rawValues.put(name, raw);

            // Convert to target type
            values[i] = convert(raw, type, name, errors);

            // Run annotation validations
            validate(comp, raw, values[i], errors);
        }

        // Construct the record
        T instance = construct(recordClass, types, values);

        // Call custom validate(Errors) if present
        callCustomValidate(instance, errors);

        return new Form<>(instance, errors, rawValues);
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

        try {
            if (type == String.class) return raw;
            if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
            if (type == long.class || type == Long.class) return Long.parseLong(raw);
            if (type == double.class || type == Double.class) return Double.parseDouble(raw);
            if (type == float.class || type == Float.class) return Float.parseFloat(raw);
            if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
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

    private static void validate(RecordComponent comp, String raw, Object value, Errors errors) {
        var name = comp.getName();
        var annotations = comp.getAnnotations();

        for (var ann : annotations) {
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
                if (value instanceof Number n && n.longValue() < min.value()) {
                    errors.add(name, "must be at least " + min.value());
                }
            } else if (ann instanceof Max max) {
                if (value instanceof Number n && n.longValue() > max.value()) {
                    errors.add(name, "must be at most " + max.value());
                }
            } else if (ann instanceof Email) {
                if (raw != null && !raw.isEmpty()) {
                    int at = raw.indexOf('@');
                    if (at < 1 || raw.indexOf('.', at) < 0) {
                        errors.add(name, "must be a valid email");
                    }
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
    private static <T> T construct(Class<T> recordClass, Class<?>[] types, Object[] values) {
        try {
            var constructor = recordClass.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct record " + recordClass.getSimpleName(), e);
        }
    }

    private static <T> void callCustomValidate(T instance, Errors errors) {
        if (instance == null) return;
        try {
            var method = instance.getClass().getDeclaredMethod("validate", Errors.class);
            method.setAccessible(true);
            method.invoke(instance, errors);
        } catch (NoSuchMethodException e) {
            // No custom validation — that's fine
        } catch (Exception e) {
            throw new RuntimeException("Failed to call validate() on " + instance.getClass().getSimpleName(), e);
        }
    }
}
