package com.larvalabs.brace;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.persistence.Entity;

import java.io.IOException;

/**
 * Refuses to deserialize request JSON into a JPA entity anywhere in the target type's graph (M9).
 *
 * <p>{@link Request#bodyAs} checks the top-level class, but an entity nested in an array or in a
 * record/DTO component ({@code Account[]}, {@code record Wrapper(Account account)}) was still
 * bound field by field, so the client chose its {@code id}, {@code role} and {@code passwordHash}.
 * This module wraps the deserializer of every {@code @Entity} class in a guard that throws
 * {@link Refused} — but only for reads carrying the {@link #REFUSE} attribute, which
 * {@code bodyAs} sets. The same shared mapper also deserializes cached values and outbound
 * {@code Http} responses, where an entity is legitimate, so the guard is per-read rather than
 * global.
 */
final class EntityBindingGuard extends SimpleModule {

    /** Reader attribute that turns the guard on for one read. */
    static final Object REFUSE = EntityBindingGuard.class;

    EntityBindingGuard() {
        super("brace-entity-binding-guard");
        setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                          BeanDescription beanDesc,
                                                          JsonDeserializer<?> deserializer) {
                Class<?> type = beanDesc.getBeanClass();
                return type.isAnnotationPresent(Entity.class) ? new Guard(deserializer, type) : deserializer;
            }
        });
    }

    /** The message both the top-level check and the nested guard use. */
    static String message(Class<?> entity) {
        return "Refusing to bind request JSON into JPA entity " + entity.getSimpleName()
            + " — a client could set any field (id, role, passwordHash). Bind a record with "
            + "req.jsonForm(" + entity.getSimpleName() + "Form.class) and copy the fields with "
            + "the entity's apply(form) (see BRACE-AGENTS.md § Forms & Validation).";
    }

    /** Thrown from inside Jackson; {@code bodyAs} digs it out of whatever Jackson wraps it in. */
    static final class Refused extends IllegalArgumentException {
        Refused(Class<?> entity) {
            super(message(entity));
        }
    }

    private static final class Guard extends DelegatingDeserializer {
        private final Class<?> entity;

        Guard(JsonDeserializer<?> delegate, Class<?> entity) {
            super(delegate);
            this.entity = entity;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            return new Guard(newDelegatee, entity);
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (ctxt.getAttribute(REFUSE) != null) throw new Refused(entity);
            return super.deserialize(p, ctxt);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object deserialize(JsonParser p, DeserializationContext ctxt, Object intoValue) throws IOException {
            if (ctxt.getAttribute(REFUSE) != null) throw new Refused(entity);
            return ((JsonDeserializer<Object>) _delegatee).deserialize(p, ctxt, intoValue);
        }
    }
}
