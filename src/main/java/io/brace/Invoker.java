package io.brace;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

public class Invoker {

    private final Object target;
    private final Method method;
    private final List<ParamType> paramTypes;
    private final boolean needsDatabase;
    private final boolean needsReadOnlyDatabase;
    private final boolean needsSession;

    enum ParamType {
        REQUEST, DATABASE, SESSION
    }

    private Invoker(Object target, Method method, List<ParamType> paramTypes) {
        this(target, method, paramTypes, false);
    }

    private Invoker(Object target, Method method, List<ParamType> paramTypes, boolean readOnlyDatabase) {
        this.target = target;
        this.method = method;
        this.paramTypes = paramTypes;
        this.needsDatabase = paramTypes.contains(ParamType.DATABASE);
        this.needsReadOnlyDatabase = readOnlyDatabase;
        this.needsSession = paramTypes.contains(ParamType.SESSION);
    }

    public static Invoker build(Object target, Method method) {
        var paramTypes = new ArrayList<ParamType>();
        for (Parameter param : method.getParameters()) {
            var type = param.getType();
            if (type == Request.class) {
                paramTypes.add(ParamType.REQUEST);
            } else if (type == Database.class) {
                paramTypes.add(ParamType.DATABASE);
            } else if (type == Session.class) {
                paramTypes.add(ParamType.SESSION);
            } else {
                throw new IllegalArgumentException(
                    "Unsupported parameter type: " + type.getName() +
                    " in method " + method.getName() +
                    ". Supported types: Request, Database, Session");
            }
        }
        return new Invoker(target, method, paramTypes);
    }

    public Result invoke(Request req, Object database, Object session) throws Exception {
        var args = new Object[paramTypes.size()];
        for (int i = 0; i < paramTypes.size(); i++) {
            args[i] = switch (paramTypes.get(i)) {
                case REQUEST -> req;
                case DATABASE -> database;
                case SESSION -> session;
            };
        }
        return (Result) method.invoke(target, args);
    }

    public boolean needsDatabase() { return needsDatabase; }
    public boolean needsReadOnlyDatabase() { return needsReadOnlyDatabase; }
    public boolean needsSession() { return needsSession; }

    // Wraps a Handler (functional interface) for use with Brace.get() etc.
    public static Invoker fromFunction(Handler handler) {
        return new Invoker(null, null, List.of()) {
            @Override
            public Result invoke(Request req, Object database, Object session) {
                return handler.apply(req);
            }
            @Override
            public boolean needsDatabase() { return false; }
            @Override
            public boolean needsSession() { return false; }
        };
    }

    // Wraps a DbHandler (Request + Database) for use with Brace.get() etc.
    public static Invoker fromDbFunction(DbHandler handler) {
        return new Invoker(null, null, List.of(ParamType.DATABASE)) {
            @Override
            public Result invoke(Request req, Object database, Object session) {
                return handler.apply(req, (Database) database);
            }
        };
    }

    // Wraps a SessionHandler (Request + Session) for use with Brace.get() etc.
    public static Invoker fromSessionFunction(SessionHandler handler) {
        return new Invoker(null, null, List.of(ParamType.SESSION)) {
            @Override
            public Result invoke(Request req, Object database, Object session) {
                return handler.apply(req, (Session) session);
            }
        };
    }

    // Wraps a FullHandler (Request + Database + Session) for use with Brace.get() etc.
    public static Invoker fromFullFunction(FullHandler handler) {
        return new Invoker(null, null, List.of(ParamType.DATABASE, ParamType.SESSION)) {
            @Override
            public Result invoke(Request req, Object database, Object session) {
                return handler.apply(req, (Database) database, (Session) session);
            }
        };
    }

    // Wraps a ReadDbHandler (Request + Database, no transaction) for use with Brace.get() etc.
    public static Invoker fromReadDbFunction(ReadDbHandler handler) {
        return new Invoker(null, null, List.of(ParamType.DATABASE), true) {
            @Override
            public Result invoke(Request req, Object database, Object session) {
                return handler.apply(req, (Database) database);
            }
        };
    }

    // Wraps a ReadFullHandler (Request + Database + Session, no transaction) for use with Brace.get() etc.
    public static Invoker fromReadFullFunction(ReadFullHandler handler) {
        return new Invoker(null, null, List.of(ParamType.DATABASE, ParamType.SESSION), true) {
            @Override
            public Result invoke(Request req, Object database, Object session) {
                return handler.apply(req, (Database) database, (Session) session);
            }
        };
    }
}
