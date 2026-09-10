package dev.assetplatform.validation;

import dev.assetplatform.exception.ApiException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FileValidator {
  public static final Set<String> MEDIA_TYPES =
      Set.of("image/jpeg", "image/png", "application/pdf");
  private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

  public String filename(String original) {
    if (original == null
        || original.isBlank()
        || original.length() > 180
        || original.contains("..")
        || original
            .codePoints()
            .anyMatch(
                c -> Character.isISOControl(c) || c == '/' || c == '\\' || c == ':' || c == '%')) {
      throw ApiException.invalid(
          "INVALID_FILENAME", "Filename must be a simple name of at most 180 characters");
    }
    String name = Normalizer.normalize(original, Normalizer.Form.NFKC).strip();
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._ ()-]{0,179}")
        || name.contains("..")
        || name.endsWith(".")) {
      throw ApiException.invalid("INVALID_FILENAME", "Filename contains unsupported characters");
    }
    return name;
  }

  public String detect(byte[] prefix, String declaredType) {
    String detected = null;
    if (prefix.length >= 8 && Arrays.equals(Arrays.copyOf(prefix, 8), PNG)) detected = "image/png";
    else if (prefix.length >= 3
        && prefix[0] == (byte) 255
        && prefix[1] == (byte) 216
        && prefix[2] == (byte) 255) detected = "image/jpeg";
    else if (prefix.length >= 5
        && prefix[0] == '%'
        && prefix[1] == 'P'
        && prefix[2] == 'D'
        && prefix[3] == 'F'
        && prefix[4] == '-') detected = "application/pdf";
    if (detected == null || !detected.equals(declaredType)) {
      throw new ApiException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_FILE",
          "Supported content: JPEG, PNG and PDF with matching media type");
    }
    return detected;
  }
}
