package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UrlTest {

    @Test
    void staticPath() {
        assertEquals("/users", Url.to("/users"));
    }

    @Test
    void rootPath() {
        assertEquals("/", Url.to("/"));
    }

    @Test
    void singleParam() {
        assertEquals("/users/42", Url.to("/users/{id}", 42));
    }

    @Test
    void multipleParams() {
        assertEquals("/users/42/posts/7", Url.to("/users/{id}/posts/{postId}", 42, 7));
    }

    @Test
    void stringParam() {
        assertEquals("/teams/rockets", Url.to("/teams/{slug}", "rockets"));
    }

    @Test
    void tooFewParamsThrows() {
        assertThrows(IllegalArgumentException.class, () -> Url.to("/users/{id}"));
    }

    @Test
    void mixedStaticAndDynamic() {
        assertEquals("/api/v1/users/42/profile", Url.to("/api/v1/users/{id}/profile", 42));
    }
}
