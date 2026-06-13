package benchmark.jmh;

import com.larvalabs.brace.Errors;
import com.larvalabs.brace.Form;
import com.larvalabs.brace.FormBinder;
import com.larvalabs.brace.annotation.*;
import org.openjdk.jmh.annotations.*;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * M4 form-bind benchmark (gap #3): reports {@code gc.alloc.rate.norm} (bytes/op) and time for binding a
 * request-param map to a validated record, two ways:
 * <ul>
 *   <li><b>post</b> — the shipped {@link FormBinder#bind}, which caches per-record reflection.</li>
 *   <li><b>pre</b> — {@link #legacyBind}, a frozen verbatim copy of the pre-M4 FormBinder that re-reflected
 *       on every call: {@code getRecordComponents()}, {@code getAnnotations()} per component, a
 *       {@code getDeclaredConstructor} scan + {@code setAccessible}, and a {@code getDeclaredMethod("validate")}
 *       that <em>constructs and throws</em> {@code NoSuchMethodException} when no custom validator exists.</li>
 * </ul>
 * Two record shapes isolate the two effects: {@code SignupForm} has no {@code validate(Errors)} (the common
 * case — pre-M4 throws on every bind), {@code SignupFormValidated} has one (so the pre/post gap there is the
 * reflection-lookup caching alone, without the thrown exception).
 *
 * <p>The legacy copy is deliberately frozen — it represents pre-M4 behavior and must never track FormBinder.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class FormBindBench {

    public record SignupForm(
            @Required @MinLength(3) String username,
            @Required @Email String email,
            @Required @MinLength(8) String password,
            @Min(18) int age) {}

    public record SignupFormValidated(
            @Required @MinLength(3) String username,
            @Required @Email String email,
            @Min(18) int age) {
        public void validate(Errors errors) {
            // representative custom validator; body irrelevant to the bind cost being measured
            if (username != null && username.equals(email)) {
                errors.add("username", "must differ from email");
            }
        }
    }

    private Map<String, String> params;
    private Map<String, String> paramsValidated;

    @Setup
    public void setup() {
        params = new LinkedHashMap<>();
        params.put("username", "alice");
        params.put("email", "alice@example.com");
        params.put("password", "hunter2!");
        params.put("age", "30");

        paramsValidated = new LinkedHashMap<>();
        paramsValidated.put("username", "alice");
        paramsValidated.put("email", "alice@example.com");
        paramsValidated.put("age", "30");
    }

    @Benchmark
    public Form<SignupForm> bind_post_noValidate() {
        return FormBinder.bind(SignupForm.class, params);
    }

    @Benchmark
    public Form<SignupForm> bind_pre_noValidate() {
        return legacyBind(SignupForm.class, params);
    }

    @Benchmark
    public Form<SignupFormValidated> bind_post_withValidate() {
        return FormBinder.bind(SignupFormValidated.class, paramsValidated);
    }

    @Benchmark
    public Form<SignupFormValidated> bind_pre_withValidate() {
        return legacyBind(SignupFormValidated.class, paramsValidated);
    }

    // ---------------------------------------------------------------------------------------------
    // Frozen verbatim copy of pre-M4 FormBinder (reflects on every bind). Do not "improve" — it is the
    // baseline the shipped FormBinder is measured against.
    // ---------------------------------------------------------------------------------------------

    static <T> Form<T> legacyBind(Class<T> recordClass, Map<String, String> params) {
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
            values[i] = convert(raw, type, name, errors);
            validate(comp, raw, values[i], errors);
        }
        T instance = construct(recordClass, types, values);
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
                if (raw == null || raw.trim().isEmpty()) errors.add(name, "is required");
            } else if (ann instanceof MinLength ml) {
                if (raw != null && !raw.isEmpty() && raw.length() < ml.value())
                    errors.add(name, "must be at least " + ml.value() + " characters");
            } else if (ann instanceof MaxLength ml) {
                if (raw != null && raw.length() > ml.value())
                    errors.add(name, "must be at most " + ml.value() + " characters");
            } else if (ann instanceof Min min) {
                if (value instanceof Number n && n.longValue() < min.value())
                    errors.add(name, "must be at least " + min.value());
            } else if (ann instanceof Max max) {
                if (value instanceof Number n && n.longValue() > max.value())
                    errors.add(name, "must be at most " + max.value());
            } else if (ann instanceof Email) {
                if (raw != null && !raw.isEmpty()) {
                    int at = raw.indexOf('@');
                    if (at < 1 || raw.indexOf('.', at) < 0) errors.add(name, "must be a valid email");
                }
            } else if (ann instanceof In in) {
                if (raw != null && !raw.isEmpty() && !Arrays.asList(in.value()).contains(raw))
                    errors.add(name, "must be one of: " + String.join(", ", in.value()));
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
            // No custom validation — pre-M4 reached this by constructing + throwing on every bind.
        } catch (Exception e) {
            throw new RuntimeException("Failed to call validate() on " + instance.getClass().getSimpleName(), e);
        }
    }
}
