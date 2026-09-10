package dev.assetplatform.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "asset_metadata")
public class AssetMetadata {
  @Id private UUID assetId;
  private Integer width;
  private Integer height;
  private Integer pageCount;

  protected AssetMetadata() {}

  public AssetMetadata(UUID assetId, Integer width, Integer height, Integer pageCount) {
    this.assetId = assetId;
    this.width = width;
    this.height = height;
    this.pageCount = pageCount;
  }

  public Integer getWidth() {
    return width;
  }

  public Integer getHeight() {
    return height;
  }

  public Integer getPageCount() {
    return pageCount;
  }
}
