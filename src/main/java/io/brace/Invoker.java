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
    private final boolean needsSession;

    enum ParamType {
        REQUEST, DATABASE, SESSION
    }

    private Invoker(Object target, Method method, List<ParamType> paramTypes) {
        this.target = target;
        this.method = method;
        this.paramTypes = paramTypes;
        this.needsDatabase = paramTypes.contains(ParamType.DATABASE);
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
            } else if (type.getSimpleName().equals("Session")) {
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
}
