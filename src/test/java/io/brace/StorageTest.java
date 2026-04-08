package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @Test
    void urlForAwsS3() {
        var storage = new Storage("AKID", "secret", "my-bucket", "us-east-1", null, null);
        assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com/photos/cat.jpg", storage.url("photos/cat.jpg"));
    }

    @Test
    void urlForCustomEndpoint() {
        var storage = new Storage("AKID", "secret", "my-bucket", "auto",
                "https://acct.r2.cloudflarestorage.com", null);
        assertEquals("https://acct.r2.cloudflarestorage.com/my-bucket/photos/cat.jpg", storage.url("photos/cat.jpg"));
    }

    @Test
    void urlWithPublicUrlOverride() {
        var storage = new Storage("AKID", "secret", "my-bucket", "auto",
                "https://acct.r2.cloudflarestorage.com", "https://cdn.example.com");
        assertEquals("https://cdn.example.com/photos/cat.jpg", storage.url("photos/cat.jpg"));
    }

    @Test
    void keyFromPublicUrl() {
        var storage = new Storage("AKID", "secret", "my-bucket", "auto",
                "https://acct.r2.cloudflarestorage.com", "https://cdn.example.com");
        assertEquals("photos/cat.jpg", storage.keyFromUrl("https://cdn.example.com/photos/cat.jpg"));
    }

    @Test
    void keyFromEndpointUrl() {
        var storage = new Storage("AKID", "secret", "my-bucket", "auto",
                "https://acct.r2.cloudflarestorage.com", null);
        assertEquals("photos/cat.jpg", storage.keyFromUrl("https://acct.r2.cloudflarestorage.com/my-bucket/photos/cat.jpg"));
    }

    @Test
    void keyFromAwsUrl() {
        var storage = new Storage("AKID", "secret", "my-bucket", "us-east-1", null, null);
        assertEquals("photos/cat.jpg", storage.keyFromUrl("https://my-bucket.s3.us-east-1.amazonaws.com/photos/cat.jpg"));
    }

    @Test
    void keyFromUrlReturnsNullForUnrecognized() {
        var storage = new Storage("AKID", "secret", "my-bucket", "us-east-1", null, null);
        assertNull(storage.keyFromUrl("https://other.com/photos/cat.jpg"));
    }

    @Test
    void urlEncodesSpecialCharacters() {
        var storage = new Storage("AKID", "secret", "my-bucket", "us-east-1", null, null);
        var url = storage.url("uploads/my file (1).jpg");
        assertTrue(url.contains("my%20file%20%281%29.jpg"));
        assertTrue(url.contains("uploads/"));
    }
}
