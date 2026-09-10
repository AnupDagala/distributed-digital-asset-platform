package dev.assetplatform.storage;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.exception.ApiException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalObjectStorage implements ObjectStorage {
  private final Path root;

  public LocalObjectStorage(PlatformProperties props) throws IOException {
    root = props.storageRoot().toAbsolutePath().normalize();
    Files.createDirectories(root);
    if (Files.isSymbolicLink(root)) throw new IOException("Storage root cannot be a symbolic link");
  }

  @Override
  public StoredObject put(InputStream source, long maxBytes) throws IOException {
    String key = UUID.randomUUID().toString();
    Path target = resolve(key);
    MessageDigest digest = sha256();
    ByteArrayOutputStream prefix = new ByteArrayOutputStream(16);
    long size = 0;
    try (OutputStream out =
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = source.read(buffer)) != -1) {
        size += read;
        requireSize(size, maxBytes);
        if (prefix.size() < 16) prefix.write(buffer, 0, Math.min(read, 16 - prefix.size()));
        digest.update(buffer, 0, read);
        out.write(buffer, 0, read);
      }
      if (size == 0) throw ApiException.invalid("EMPTY_FILE", "File must not be empty");
    } catch (IOException | RuntimeException ex) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanup) {
        ex.addSuppressed(cleanup);
      }
      throw ex;
    }
    return new StoredObject(
        key, size, HexFormat.of().formatHex(digest.digest()), prefix.toByteArray());
  }

  @Override
  public InputStream open(String key) throws IOException {
    return Files.newInputStream(resolve(key), LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(resolve(key));
  }

  @Override
  public MaterializedObject materialize(String key, long maxBytes) throws IOException {
    Path temp = Files.createTempFile("asset-parse-", ".bin");
    try (InputStream in = open(key);
        OutputStream out = Files.newOutputStream(temp)) {
      byte[] buffer = new byte[8192];
      long size = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        size += read;
        requireSize(size, maxBytes);
        out.write(buffer, 0, read);
      }
      return new MaterializedObject(temp);
    } catch (IOException | RuntimeException ex) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanup) {
        ex.addSuppressed(cleanup);
      }
      throw ex;
    }
  }

  private Path resolve(String key) {
    if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid storage key");
    return root.resolve(key);
  }

  private static void requireSize(long actual, long maximum) {
    if (actual > maximum)
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds the configured size limit");
  }

  public static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
