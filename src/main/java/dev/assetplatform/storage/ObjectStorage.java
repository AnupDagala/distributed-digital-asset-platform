package dev.assetplatform.storage;

import java.io.*;
import java.nio.file.Path;

public interface ObjectStorage {
  record StoredObject(String key, long size, String sha256, byte[] prefix) {}

  StoredObject put(InputStream source, long maxBytes) throws IOException;

  InputStream open(String key) throws IOException;

  void delete(String key) throws IOException;

  /** Caller owns and must close the temporary materialization. */
  MaterializedObject materialize(String key, long maxBytes) throws IOException;

  record MaterializedObject(Path path) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      java.nio.file.Files.deleteIfExists(path);
    }
  }
}
