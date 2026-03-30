package io.brace;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InvokerTest {

    static class TestController {
        public Result noParams() {
            return Result.text("no params");
        }

        public Result requestOnly(Request req) {
            return Result.text("path: " + req.path());
        }
    }

    @Test
    void invokesMethodWithNoParams() throws Exception {
        var controller = new TestController();
        var method = TestController.class.getMethod("noParams");
        var invoker = Invoker.build(controller, method);

        var req = new Request("GET", "/", Map.of(), Map.of(), Map.of(), null);
        var result = invoker.invoke(req, null, null);

        assertEquals("no params", result.body());
    }

    @Test
    void invokesMethodWithRequest() throws Exception {
        var controller = new TestController();
        var method = TestController.class.getMethod("requestOnly", Request.class);
        var invoker = Invoker.build(controller, method);

        var req = new Request("GET", "/hello", Map.of(), Map.of(), Map.of(), null);
        var result = invoker.invoke(req, null, null);

        assertEquals("path: /hello", result.body());
    }

    @Test
    void reportsNeedsDatabase() throws Exception {
        var controller = new TestController();
        var method = TestController.class.getMethod("noParams");
        var invoker = Invoker.build(controller, method);
        assertFalse(invoker.needsDatabase());

        var method2 = TestController.class.getMethod("requestOnly", Request.class);
        var invoker2 = Invoker.build(controller, method2);
        assertFalse(invoker2.needsDatabase());
    }
}
