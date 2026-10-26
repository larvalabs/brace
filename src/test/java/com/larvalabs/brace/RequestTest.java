package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.User;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class RequestTest {

    @Test
    void pathParams() {
        var req = new Request("GET", "/posts/42", Map.of("id", "42"), Map.of(), Map.of(), null);
        assertEquals("42", req.pathParam("id"));
        assertEquals(42, req.intPathParam("id"));
    }

    @Test
    void queryParams() {
        var req = new Request("GET", "/search", Map.of(), Map.of("q", "hello", "page", "2"), Map.of(), null);
        assertEquals("hello", req.queryParam("q"));
        assertEquals(2, req.queryInt("page"));
    }

    @Test
    void pathParamIndependent() {
        var req = new Request("GET", "/posts/42", Map.of("id", "42"), Map.of("id", "99"), Map.of(), null);
        assertEquals("42", req.pathParam("id"));
        assertEquals("99", req.queryParam("id"));
    }

    @Test
    void headers() {
        var req = new Request("GET", "/", Map.of(), Map.of(), Map.of("Accept", "text/html"), null);
        assertEquals("text/html", req.header("Accept"));
    }

    @Test
    void headersAreCaseInsensitive() {
        var req = new Request("GET", "/", Map.of(), Map.of(),
            Map.of("Content-Type", "application/json"), null);
        assertEquals("application/json", req.header("content-type"));
        assertEquals("application/json", req.header("CONTENT-TYPE"));
        assertTrue(req.hasHeader("content-type"));
        assertTrue(req.isJson());
    }

    @Test
    void caseInsensitiveHeaderMapIsAdoptedNotCopied() {
        // M3: BraceHandler hands Request a CASE_INSENSITIVE_ORDER TreeMap; Request must adopt
        // that instance rather than copy it. A write to the source after construction is
        // visible only if the instance was adopted — this caught the loss in merge b8609b6.
        var source = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        source.put("Accept", "text/html");
        var req = new Request("GET", "/", Map.of(), Map.of(), source, null);
        source.put("X-Late", "seen");
        assertEquals("seen", req.header("x-late"));
    }

    @Test
    void otherHeaderMapsAreDefensivelyCopied() {
        var source = new HashMap<String, String>();
        source.put("Accept", "text/html");
        var req = new Request("GET", "/", Map.of(), Map.of(), source, null);
        source.put("X-Late", "seen");
        assertNull(req.header("X-Late"));
        assertEquals("text/html", req.header("accept"));
    }

    @Test
    void method() {
        var req = new Request("POST", "/posts", Map.of(), Map.of(), Map.of(), null);
        assertEquals("POST", req.method());
    }

    @Test
    void path() {
        var req = new Request("GET", "/posts/42", Map.of(), Map.of(), Map.of(), null);
        assertEquals("/posts/42", req.path());
    }

    @Test
    void body() {
        var req = new Request("POST", "/posts", Map.of(), Map.of(), Map.of(), "{\"title\":\"hello\"}");
        assertEquals("{\"title\":\"hello\"}", req.body());
    }

    @Test
    void bodyAs() {
        record TestDto(String title) {}
        var req = new Request("POST", "/posts", Map.of(), Map.of(), Map.of(), "{\"title\":\"hello\"}");
        var dto = req.bodyAs(TestDto.class);
        assertEquals("hello", dto.title());
    }

    private static Request jsonRequest(String body) {
        return new Request("POST", "/users", Map.of(), Map.of(),
            Map.of("content-type", "application/json"), body);
    }

    private static final String MASS_ASSIGNMENT =
        "{\"email\":\"e@x\",\"role\":\"admin\",\"id\":1,\"passwordHash\":\"p\"}";

    @Test
    void bodyAsRefusesEntityTargets() {
        var req = jsonRequest(MASS_ASSIGNMENT);
        var ex = assertThrows(IllegalArgumentException.class, () -> req.bodyAs(User.class));
        assertTrue(ex.getMessage().contains("User"), ex.getMessage());
        assertTrue(ex.getMessage().contains("jsonForm"), ex.getMessage());
        assertTrue(ex.getMessage().contains("apply(form)"), ex.getMessage());
    }

    @Test
    void jsonAndRequireJsonRefuseEntityTargets() {
        var req = jsonRequest(MASS_ASSIGNMENT);
        assertThrows(IllegalArgumentException.class, () -> req.json(User.class));
        assertThrows(IllegalArgumentException.class, () -> req.requireJson(User.class));
    }

    @Test
    void entityRefusedBeforeBodyIsRead() {
        // Even a malformed body gets the guidance, not a parse failure.
        var req = jsonRequest("not json");
        assertThrows(IllegalArgumentException.class, () -> req.bodyAs(User.class));
    }

    public record UserWrapper(User user) {}

    public record UserList(java.util.List<User> users) {}

    @Test
    void entitiesNestedInArraysAndRecordsAreRefused() {
        // The top-level check alone let these through: Jackson bound each nested entity field
        // by field, client-chosen id and passwordHash included.
        String user = "{\"email\":\"e@x\",\"id\":1,\"passwordHash\":\"p\"}";
        var array = jsonRequest("[" + user + "]");
        var ex = assertThrows(IllegalArgumentException.class, () -> array.bodyAs(User[].class));
        assertTrue(ex.getMessage().contains("User"), ex.getMessage());
        assertTrue(ex.getMessage().contains("jsonForm"), ex.getMessage());

        var wrapped = jsonRequest("{\"user\":" + user + "}");
        assertThrows(IllegalArgumentException.class, () -> wrapped.json(UserWrapper.class));
        assertThrows(IllegalArgumentException.class, () -> wrapped.requireJson(UserWrapper.class));

        var listed = jsonRequest("{\"users\":[" + user + "]}");
        assertThrows(IllegalArgumentException.class, () -> listed.bodyAs(UserList.class));
    }

    @Test
    void entityDeserializationOutsideRequestBindingIsUnaffected() throws Exception {
        // The shared mapper also reads cached values and Http responses, where an entity is
        // legitimate: the guard only applies to reads that bodyAs marks.
        var user = Json.mapper().readValue("{\"email\":\"e@x\",\"id\":7}", User.class);
        assertEquals(7L, user.id);
        var users = Json.mapper().readValue("[{\"id\":8}]", User[].class);
        assertEquals(8L, users[0].id);
    }

    public record AccountForm(String email) {}

    public static class AccountDto {
        public String email;
        public String role;
    }

    @Test
    void recordsDtosAndMapsStillBind() {
        var req = jsonRequest("{\"email\":\"e@x\"}");
        assertEquals("e@x", req.bodyAs(AccountForm.class).email());
        assertEquals("e@x", req.json(AccountForm.class).email());
        assertEquals("e@x", req.requireJson(AccountForm.class).email());
        var withRole = jsonRequest("{\"email\":\"e@x\",\"role\":\"admin\"}");
        assertEquals("admin", withRole.bodyAs(AccountDto.class).role);
        assertEquals("admin", withRole.bodyAs(Map.class).get("role"));
    }

    @Test
    void intPathParamThrowsForMissing() {
        var req = new Request("GET", "/", Map.of(), Map.of(), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.intPathParam("missing"));
    }

    @Test
    void defaultedQueryIntReturnsDefaultOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("page", "abc"), Map.of(), null);
        assertEquals(1, req.queryInt("page", 1));
    }

    @Test
    void defaultedQueryIntStillParsesValidValue() {
        var req = new Request("GET", "/search", Map.of(), Map.of("page", "7"), Map.of(), null);
        assertEquals(7, req.queryInt("page", 1));
    }

    @Test
    void defaultedQueryIntReturnsDefaultOnMissing() {
        var req = new Request("GET", "/search", Map.of(), Map.of(), Map.of(), null);
        assertEquals(1, req.queryInt("page", 1));
    }

    @Test
    void defaultedQueryLongReturnsDefaultOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("since", "not-a-number"), Map.of(), null);
        assertEquals(99L, req.queryLong("since", 99L));
    }

    @Test
    void defaultedQueryLongStillParsesValidValue() {
        var req = new Request("GET", "/search", Map.of(), Map.of("since", "123456789012"), Map.of(), null);
        assertEquals(123456789012L, req.queryLong("since", 99L));
    }

    @Test
    void nonDefaultedQueryIntStillThrowsOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("page", "abc"), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.queryInt("page"));
    }

    @Test
    void nonDefaultedQueryLongStillThrowsOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("since", "abc"), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.queryLong("since"));
    }

    @Test
    void intPathParamStillThrowsOnUnparseable() {
        var req = new Request("GET", "/posts/abc", Map.of("id", "abc"), Map.of(), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.intPathParam("id"));
    }
}
