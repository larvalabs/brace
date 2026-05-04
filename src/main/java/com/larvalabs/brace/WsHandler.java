package com.larvalabs.brace;

import org.eclipse.jetty.websocket.api.Session;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Jetty 12 WebSocket endpoint adapter.
 * Creates user handler instances via a factory, dispatches events via reflection.
 */
public class WsHandler implements Session.Listener.AutoDemanding {

    private final Function<WsContext, Object> handlerFactory;
    private final com.larvalabs.brace.Session braceSession; // may be null

    private WsContext wsContext;
    private Object userHandler;
    private Method onMessageMethod;
    private Method onCloseMethod;

    WsHandler(Function<WsContext, Object> handlerFactory, com.larvalabs.brace.Session braceSession) {
        this.handlerFactory = handlerFactory;
        this.braceSession = braceSession;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        this.wsContext = new WsContext(session, braceSession);
        this.userHandler = handlerFactory.apply(wsContext);

        // Cache reflection lookups for message and close methods
        Class<?> handlerClass = userHandler.getClass();
        try {
            onMessageMethod = handlerClass.getMethod("onMessage", String.class);
        } catch (NoSuchMethodException ignored) {}
        try {
            onCloseMethod = handlerClass.getMethod("onClose", int.class, String.class);
        } catch (NoSuchMethodException ignored) {}

        // Call onConnect() if present
        try {
            Method onConnect = handlerClass.getMethod("onConnect");
            onConnect.invoke(userHandler);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            throw new RuntimeException("WebSocket onConnect failed", e);
        }
    }

    @Override
    public void onWebSocketText(String message) {
        if (onMessageMethod != null) {
            try {
                onMessageMethod.invoke(userHandler, message);
            } catch (Exception e) {
                throw new RuntimeException("WebSocket onMessage failed", e);
            }
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        if (onCloseMethod != null) {
            try {
                onCloseMethod.invoke(userHandler, statusCode, reason);
            } catch (Exception e) {
                // Log but don't throw — connection is already closing
            }
        }
        if (wsContext != null) {
            wsContext.leaveAllRooms();
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        // Clean up rooms on error as well
        if (wsContext != null) {
            wsContext.leaveAllRooms();
        }
    }
}
