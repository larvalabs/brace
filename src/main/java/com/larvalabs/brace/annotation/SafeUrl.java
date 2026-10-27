package com.larvalabs.brace.annotation;

import java.lang.annotation.*;

/**
 * The value, when present, must be an absolute {@code http} or {@code https} URL with a host:
 * for a link a user supplies (a profile website) that pages will render as an {@code href}.
 * {@code javascript:}, {@code data:} and relative values are rejected (M4, 2026-10 security
 * review). Templates also neutralize unsafe URLs on output; this stops them at the form.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface SafeUrl {
}
