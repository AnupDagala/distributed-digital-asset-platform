package dev.assetplatform.worker;

import static dev.assetplatform.storage.LocalObjectStorage.sha256;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.domain.*;
import dev.assetplatform.storage.ObjectStorage;
import dev.assetplatform.validation.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

@Component
public class AssetInspector {
  private final ObjectStorage storage;
  private final PlatformProperties props;
  private final FileValidator validator;

  public AssetInspector(ObjectStorage storage, PlatformProperties props, FileValidator validator) {
    this.storage = storage;
    this.props = props;
    this.validator = validator;
  }

  public AssetMetadata inspect(Asset asset) throws IOException {
    try (var object = storage.materialize(asset.getStorageKey(), props.maxFileBytes())) {
      Path path = object.path();
      StreamingPatternMatcher eof =
          asset.getMediaType().equals("application/pdf")
              ? new StreamingPatternMatcher("%%EOF".getBytes(StandardCharsets.US_ASCII))
              : null;
      MessageDigest digest = sha256();
      try (InputStream input = Files.newInputStream(path)) {
        byte[] first = input.readNBytes(16);
        try {
          validator.detect(first, asset.getMediaType());
        } catch (dev.assetplatform.exception.ApiException ex) {
          throw new RejectedAssetException("SIGNATURE_MISMATCH");
        }
        digest.update(first);
        if (eof != null) eof.accept(first, 0, first.length);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          digest.update(buffer, 0, count);
          if (eof != null) eof.accept(buffer, 0, count);
        }
      }
      if (Files.size(path) != asset.getFileSize()
          || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(asset.getSha256()))) {
        throw new RejectedAssetException("CONTENT_INTEGRITY_MISMATCH");
      }
      if (eof != null) {
        if (!eof.endsWithin(1024)) throw new RejectedAssetException("INVALID_PDF_EOF");
        return pdf(asset.getId(), path);
      }
      return image(asset.getId(), path);
    }
  }

  private AssetMetadata pdf(UUID id, Path path) {
    try (PDDocument document =
        Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
      if (document.isEncrypted()) throw new RejectedAssetException("ENCRYPTED_PDF");
      int pages = document.getNumberOfPages();
      if (pages < 1 || pages > 2000) throw new RejectedAssetException("PDF_PAGE_LIMIT");
      return new AssetMetadata(id, null, null, pages);
    } catch (IOException | IllegalArgumentException ex) {
      throw new RejectedAssetException("INVALID_PDF");
    }
  }

  private AssetMetadata image(UUID id, Path path) {
    try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new RejectedAssetException("INVALID_IMAGE");
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > props.maxImagePixels())
          throw new RejectedAssetException("IMAGE_PIXEL_LIMIT");
        BufferedImage decoded = reader.read(0);
        if (decoded == null) throw new RejectedAssetException("INVALID_IMAGE");
        decoded.flush();
        return new AssetMetadata(id, width, height, null);
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException ex) {
      throw new RejectedAssetException("INVALID_IMAGE");
    }
  }
}
