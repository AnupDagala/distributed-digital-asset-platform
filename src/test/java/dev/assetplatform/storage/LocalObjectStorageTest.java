package dev.assetplatform.storage;

import static org.assertj.core.api.Assertions.*;

import dev.assetplatform.TestProperties;
import dev.assetplatform.exception.ApiException;
import java.io.*;
import java.nio.file.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {
  @TempDir Path directory;

  @Test
  void storesAndHashesStreamAndDeletesIdempotently() throws Exception {
    LocalObjectStorage storage = new LocalObjectStorage(TestProperties.at(directory));
    byte[] bytes = "stream me".getBytes();
    ObjectStorage.StoredObject object = storage.put(new ByteArrayInputStream(bytes), 100);
    assertThat(object.sha256())
        .isEqualTo(HexFormat.of().formatHex(LocalObjectStorage.sha256().digest(bytes)));
    try (InputStream in = storage.open(object.key())) {
      assertThat(in.readAllBytes()).isEqualTo(bytes);
    }
    Path materialized;
    try (var copy = storage.materialize(object.key(), 100)) {
      materialized = copy.path();
      assertThat(Files.readAllBytes(materialized)).isEqualTo(bytes);
    }
    assertThat(materialized).doesNotExist();
    storage.delete(object.key());
    storage.delete(object.key());
    try (var listing = Files.list(directory)) {
      assertThat(listing.count()).isZero();
    }
  }

  @Test
  void limitsUnknownLengthStreamAndCleansPartialObject() throws Exception {
    LocalObjectStorage storage = new LocalObjectStorage(TestProperties.at(directory));
    InputStream endless =
        new InputStream() {
          @Override
          public int read() {
            return 42;
          }
        };
    assertThatThrownBy(() -> storage.put(endless, 10000)).isInstanceOf(ApiException.class);
    try (var listing = Files.list(directory)) {
      assertThat(listing.count()).isZero();
    }
  }

  @Test
  void rejectsPathTraversalKeyAndEmptyFile() throws Exception {
    LocalObjectStorage storage = new LocalObjectStorage(TestProperties.at(directory));
    assertThatThrownBy(() -> storage.open("../outside"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.put(InputStream.nullInputStream(), 10))
        .isInstanceOf(ApiException.class);
  }
}
