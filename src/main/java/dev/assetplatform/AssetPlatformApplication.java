package dev.assetplatform;

import dev.assetplatform.config.PlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PlatformProperties.class)
public class AssetPlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssetPlatformApplication.class, args);
  }
}
