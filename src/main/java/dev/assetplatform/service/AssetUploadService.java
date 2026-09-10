package dev.assetplatform.service;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.exception.ApiException;
import dev.assetplatform.storage.ObjectStorage;
import dev.assetplatform.validation.FileValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetUploadService {
  private final ObjectStorage storage;
  private final FileValidator validator;
  private final AssetRegistration registration;
  private final PlatformProperties props;
  private final MeterRegistry metrics;

  public AssetUploadService(
      ObjectStorage storage,
      FileValidator validator,
      AssetRegistration registration,
      PlatformProperties props,
      MeterRegistry metrics) {
    this.storage = storage;
    this.validator = validator;
    this.registration = registration;
    this.props = props;
    this.metrics = metrics;
  }

  public AssetRegistration.Result upload(UUID owner, UUID key, MultipartFile file) {
    String name = validator.filename(file.getOriginalFilename());
    if (!FileValidator.MEDIA_TYPES.contains(
        file.getContentType() == null ? "" : file.getContentType())) {
      throw new ApiException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_FILE",
          "Supported content: JPEG, PNG and PDF");
    }
    if (file.getSize() > props.maxFileBytes())
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds the configured size limit");
    ObjectStorage.StoredObject object;
    try (InputStream source = file.getInputStream()) {
      object = storage.put(source, props.maxFileBytes());
    } catch (IOException ex) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Storage temporarily unavailable");
    }
    String mediaType;
    try {
      mediaType = validator.detect(object.prefix(), file.getContentType());
    } catch (RuntimeException ex) {
      registration.cleanup(object.key());
      throw ex;
    }
    AssetRegistration.Result result = registration.register(owner, key, name, mediaType, object);
    if (result.replayed()) registration.cleanup(object.key());
    metrics
        .counter("asset.upload", "result", result.replayed() ? "replayed" : "created")
        .increment();
    return result;
  }
}
