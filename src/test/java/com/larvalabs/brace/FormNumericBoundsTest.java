package com.larvalabs.brace;

import com.larvalabs.brace.annotation.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** M11: {@code @Min}/{@code @Max} compare exactly, and NaN/Infinity never bind. */
class FormNumericBoundsTest {

    public record DecimalForm(@Min(0) @Max(100) BigDecimal amount) {}
    public record DoubleForm(@Min(0) @Max(100) double amount) {}
    public record BoxedDoubleForm(@Min(0) @Max(100) Double amount) {}
    public record FloatForm(@Min(0) @Max(100) float amount) {}
    public record BoxedFloatForm(@Min(0) @Max(100) Float amount) {}
    public record IntForm(@Min(0) @Max(100) int n) {}
    public record LongForm(@Min(-1) @Max(Long.MAX_VALUE) long n) {}
    public record LongMinForm(@Min(Long.MAX_VALUE) long n) {}
    public record BoxedLongForm(@Min(0) @Max(100) Long n) {}

    private static Map<String, String> p(String v) {
        return Map.of("amount", v);
    }

    private static Map<String, String> n(String v) {
        return Map.of("n", v);
    }

    private static Request jsonRequest(String body) {
        return new Request("POST", "/x", Map.of(), Map.of(),
            Map.of("content-type", "application/json"), body, Map.of(), "127.0.0.1", null);
    }

    // --- the values from the finding ---

    @Test
    void negativeFractionalBigDecimalFailsMin() {
        var form = FormBinder.bind(DecimalForm.class, p("-0.99"));
        assertFalse(form.errors("amount").isEmpty());
    }

    @Test
    void negativeFractionalDoubleFailsMin() {
        assertFalse(FormBinder.bind(DoubleForm.class, p("-0.9")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(BoxedDoubleForm.class, p("-0.9")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(FloatForm.class, p("-0.9")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(BoxedFloatForm.class, p("-0.9")).errors("amount").isEmpty());
    }

    @Test
    void fractionAboveMaxFailsMax() {
        assertFalse(FormBinder.bind(DoubleForm.class, p("100.99")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(BoxedDoubleForm.class, p("100.99")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(FloatForm.class, p("100.5")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(DecimalForm.class, p("100.01")).errors("amount").isEmpty());
    }

    @Test
    void nanIsRejected() {
        assertFalse(FormBinder.bind(DoubleForm.class, p("NaN")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(BoxedDoubleForm.class, p("NaN")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(FloatForm.class, p("NaN")).errors("amount").isEmpty());
        assertFalse(FormBinder.bind(BoxedFloatForm.class, p("NaN")).errors("amount").isEmpty());
    }

    @Test
    void infinityIsRejectedEvenWithNoBounds() {
        record Unbounded(double d, Double boxed, float f, Float boxedF) {}
        for (var v : new String[] {"Infinity", "-Infinity", "1e999"}) {
            for (var field : new String[] {"d", "boxed", "f", "boxedF"}) {
                var form = FormBinder.bind(Unbounded.class, Map.of(field, v));
                assertFalse(form.errors(field).isEmpty(), field + "=" + v);
            }
        }
    }

    @Test
    void floatOverflowIsRejected() {
        // Fine as a double, infinite once narrowed to float.
        assertFalse(FormBinder.bind(FloatForm.class, p("1e50")).errors("amount").isEmpty());
    }

    @Test
    void hugeBigDecimalFailsMaxWithoutExpanding() {
        assertFalse(FormBinder.bind(DecimalForm.class, p("1e999999999")).errors("amount").isEmpty());
        var tiny = FormBinder.bind(DecimalForm.class, p("1e-999999999"));
        assertTrue(tiny.errors("amount").isEmpty());
        assertFalse(FormBinder.bind(DecimalForm.class, p("-1e999999999")).errors("amount").isEmpty());
    }

    // --- in-range and edge values still bind ---

    @Test
    void inRangeFractionsPass() {
        assertFalse(FormBinder.bind(DecimalForm.class, p("0.01")).hasErrors());
        assertFalse(FormBinder.bind(DecimalForm.class, p("99.99")).hasErrors());
        assertFalse(FormBinder.bind(DoubleForm.class, p("0.5")).hasErrors());
        assertFalse(FormBinder.bind(BoxedFloatForm.class, p("99.5")).hasErrors());
    }

    @Test
    void boundsAreInclusive() {
        for (var v : new String[] {"0", "100", "0.0", "100.0", "-0", "-0.0"}) {
            assertFalse(FormBinder.bind(DecimalForm.class, p(v)).hasErrors(), "decimal " + v);
            assertFalse(FormBinder.bind(DoubleForm.class, p(v)).hasErrors(), "double " + v);
            assertFalse(FormBinder.bind(FloatForm.class, p(v)).hasErrors(), "float " + v);
        }
    }

    @Test
    void integralEdgesBehaveAsBefore() {
        assertFalse(FormBinder.bind(IntForm.class, n("0")).hasErrors());
        assertFalse(FormBinder.bind(IntForm.class, n("100")).hasErrors());
        assertFalse(FormBinder.bind(IntForm.class, n("-1")).errors("n").isEmpty());
        assertFalse(FormBinder.bind(IntForm.class, n("101")).errors("n").isEmpty());

        assertFalse(FormBinder.bind(LongForm.class, n("-1")).hasErrors());
        assertFalse(FormBinder.bind(LongForm.class, n("0")).hasErrors());
        assertFalse(FormBinder.bind(LongForm.class, n("-2")).errors("n").isEmpty());
        assertFalse(FormBinder.bind(LongForm.class, n(Long.toString(Long.MAX_VALUE))).hasErrors());

        assertFalse(FormBinder.bind(LongMinForm.class, n(Long.toString(Long.MAX_VALUE))).hasErrors());
        assertFalse(FormBinder.bind(LongMinForm.class, n(Long.toString(Long.MAX_VALUE - 1))).errors("n").isEmpty());

        assertFalse(FormBinder.bind(BoxedLongForm.class, n("0")).hasErrors());
        assertFalse(FormBinder.bind(BoxedLongForm.class, n("-1")).errors("n").isEmpty());
        assertFalse(FormBinder.bind(BoxedLongForm.class, n(Long.toString(Long.MAX_VALUE))).errors("n").isEmpty());
    }

    @Test
    void absentOptionalNumbersStillSkipBounds() {
        // Boxed/BigDecimal fields with no value are null, not a violation (unchanged).
        assertFalse(FormBinder.bind(DecimalForm.class, Map.of()).hasErrors());
        assertFalse(FormBinder.bind(BoxedDoubleForm.class, Map.of()).hasErrors());
    }

    // --- both entry points go through FormBinder ---

    @Test
    void jsonFormAppliesTheSameRules() {
        assertFalse(jsonRequest("{\"amount\": -0.9}").jsonForm(DoubleForm.class).errors("amount").isEmpty());
        assertFalse(jsonRequest("{\"amount\": 100.99}").jsonForm(DoubleForm.class).errors("amount").isEmpty());
        assertFalse(jsonRequest("{\"amount\": -0.99}").jsonForm(DecimalForm.class).errors("amount").isEmpty());
        assertFalse(jsonRequest("{\"amount\": \"NaN\"}").jsonForm(DoubleForm.class).errors("amount").isEmpty());
        assertFalse(jsonRequest("{\"amount\": \"1e999999999\"}").jsonForm(DecimalForm.class).errors("amount").isEmpty());
        assertFalse(jsonRequest("{\"amount\": 12.5}").jsonForm(DoubleForm.class).hasErrors());
    }

    @Test
    void reqFormAppliesTheSameRules() {
        var req = new Request("POST", "/x", Map.of(), Map.of(),
            Map.of("content-type", "application/x-www-form-urlencoded"), "amount=-0.9");
        assertFalse(req.form(DoubleForm.class).errors("amount").isEmpty());
        var nan = new Request("POST", "/x", Map.of(), Map.of(),
            Map.of("content-type", "application/x-www-form-urlencoded"), "amount=NaN");
        assertFalse(nan.form(DoubleForm.class).errors("amount").isEmpty());
    }
}
