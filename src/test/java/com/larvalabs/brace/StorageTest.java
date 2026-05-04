package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void buildAuthHeaderProducesValidFormat() {
        var storage = new Storage("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "examplebucket", "us-east-1", null, null);
        var auth = storage.buildAuthHeader("PUT", "test-key", "text/plain",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "20130524T000000Z", "20130524");
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request"));
        assertTrue(auth.contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"));
        assertTrue(auth.contains("Signature="));
    }

    @Test
    void urlEncodesSpecialCharacters() {
        var storage = new Storage("AKID", "secret", "my-bucket", "us-east-1", null, null);
        var url = storage.url("uploads/my file (1).jpg");
        assertTrue(url.contains("my%20file%20%281%29.jpg"));
        assertTrue(url.contains("uploads/"));
    }

    @Test
    void keyFromUrlRoundtripsSpecialCharacters() {
        var storage = new Storage("AKID", "secret", "my-bucket", "us-east-1", null, null);
        var key = "uploads/my file (1).jpg";
        assertEquals(key, storage.keyFromUrl(storage.url(key)));
    }

    @Test
    void s3FactoryReadsConfig(@TempDir Path dir) throws Exception {
        var confFile = dir.resolve("app.conf");
        Files.writeString(confFile, "s3.accessKeyId=AKID\ns3.secretKey=secret\ns3.bucket=my-bucket\ns3.region=us-west-2\ns3.endpoint=https://r2.example.com\ns3.publicUrl=https://cdn.example.com\n");
        var config = Config.load(confFile, "production");
        var storage = Storage.s3(config);
        assertEquals("https://cdn.example.com/test.jpg", storage.url("test.jpg"));
    }

    @Test
    void s3FactoryThrowsOnMissingConfig(@TempDir Path dir) throws Exception {
        var confFile = dir.resolve("app.conf");
        Files.writeString(confFile, "s3.accessKeyId=AKID\n");
        var config = Config.load(confFile, "production");
        assertThrows(RuntimeException.class, () -> Storage.s3(config));
    }
}
