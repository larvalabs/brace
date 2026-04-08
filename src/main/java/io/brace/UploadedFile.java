package io.brace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class UploadedFile {

    private final String filename;
    private final String contentType;
    private final byte[] bytes;

    public UploadedFile(String filename, String contentType, byte[] bytes) {
        this.filename = filename;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    public String filename() { return filename; }
    public String contentType() { return contentType; }
    public byte[] bytes() { return bytes; }
    public long size() { return bytes.length; }

    public void saveTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
}
