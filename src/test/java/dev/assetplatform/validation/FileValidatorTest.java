package dev.assetplatform.validation;

import static org.assertj.core.api.Assertions.*;

import dev.assetplatform.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileValidatorTest {
  private final FileValidator validator = new FileValidator();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../secret.png",
        "a/secret.png",
        "a\\secret.png",
        "a:stream.png",
        "%2e%2e.png",
        "bad\n.png",
        "a..png",
        "／file.png",
        "hidden."
      })
  void rejectsUnsafeNames(String name) {
    assertThatThrownBy(() -> validator.filename(name)).isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsSimpleName() {
    assertThat(validator.filename("photo (2).png")).isEqualTo("photo (2).png");
  }

  @Test
  void requiresMatchingMagicAndMime() {
    byte[] pdf = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    assertThat(validator.detect(pdf, "application/pdf")).isEqualTo("application/pdf");
    assertThatThrownBy(() -> validator.detect(pdf, "image/png")).isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsFakeImage() {
    assertThatThrownBy(() -> validator.detect("hello".getBytes(), "image/png"))
        .isInstanceOf(ApiException.class);
  }
}
