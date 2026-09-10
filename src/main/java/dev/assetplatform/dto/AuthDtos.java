package dev.assetplatform.dto;

import jakarta.validation.constraints.*;

public final class AuthDtos {
  private AuthDtos() {}

  public record Credentials(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 128) String password) {}

  public record Token(String accessToken, String tokenType, long expiresIn) {}
}
