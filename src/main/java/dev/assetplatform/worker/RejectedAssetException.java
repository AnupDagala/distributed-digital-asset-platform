package dev.assetplatform.worker;

public class RejectedAssetException extends RuntimeException {
  private final String code;

  public RejectedAssetException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
