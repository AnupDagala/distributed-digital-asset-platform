package dev.assetplatform.worker;

import static org.assertj.core.api.Assertions.*;

import dev.assetplatform.*;
import dev.assetplatform.domain.*;
import dev.assetplatform.storage.*;
import dev.assetplatform.validation.FileValidator;
import java.io.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetInspectorTest {
  @TempDir Path root;

  private AssetMetadata inspect(byte[] bytes, String type) throws Exception {
    var props = TestProperties.at(root);
    var storage = new LocalObjectStorage(props);
    var stored = storage.put(new ByteArrayInputStream(bytes), props.maxFileBytes());
    var asset =
        new Asset(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "asset.bin",
            type,
            stored.size(),
            stored.sha256(),
            stored.key());
    return new AssetInspector(storage, props, new FileValidator()).inspect(asset);
  }

  @Test
  void extractsPngDimensions() throws Exception {
    var result = inspect(TestFiles.image("png", 12, 7), "image/png");
    assertThat(result.getWidth()).isEqualTo(12);
    assertThat(result.getHeight()).isEqualTo(7);
  }

  @Test
  void extractsJpegDimensions() throws Exception {
    var result = inspect(TestFiles.image("jpeg", 9, 5), "image/jpeg");
    assertThat(result.getWidth()).isEqualTo(9);
  }

  @Test
  void extractsPdfPageCount() throws Exception {
    assertThat(inspect(TestFiles.pdf(), "application/pdf").getPageCount()).isEqualTo(1);
  }

  @Test
  void rejectsTruncatedFileWithValidHeader() {
    assertThatThrownBy(() -> inspect("%PDF-1.7 invalid".getBytes(), "application/pdf"))
        .isInstanceOf(RejectedAssetException.class)
        .hasMessage("INVALID_PDF_EOF");
  }

  @Test
  void rejectsTamperedStoredContent() throws Exception {
    var props = TestProperties.at(root);
    var storage = new LocalObjectStorage(props);
    var bytes = TestFiles.image("png", 2, 2);
    var stored = storage.put(new ByteArrayInputStream(bytes), 10000);
    var asset =
        new Asset(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "asset.png",
            "image/png",
            stored.size(),
            "0".repeat(64),
            stored.key());
    assertThatThrownBy(() -> new AssetInspector(storage, props, new FileValidator()).inspect(asset))
        .isInstanceOf(RejectedAssetException.class)
        .hasMessage("CONTENT_INTEGRITY_MISMATCH");
  }
}
