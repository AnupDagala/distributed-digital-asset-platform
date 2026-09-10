package dev.assetplatform.service;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.domain.User;
import dev.assetplatform.dto.AuthDtos.*;
import dev.assetplatform.exception.ApiException;
import dev.assetplatform.repository.UserRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JwtEncoder tokens;
  private final PlatformProperties props;
  private final String dummyHash;

  public AuthService(
      UserRepository users,
      PasswordEncoder passwords,
      JwtEncoder tokens,
      PlatformProperties props) {
    this.users = users;
    this.passwords = passwords;
    this.tokens = tokens;
    this.props = props;
    dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public Token register(Credentials request) {
    User user =
        users.saveAndFlush(
            new User(normalize(request.email()), passwords.encode(request.password())));
    return token(user);
  }

  @Transactional(readOnly = true)
  public Token login(Credentials request) {
    Optional<User> user = users.findByEmail(normalize(request.email()));
    boolean matches =
        passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
    if (user.isEmpty() || !matches)
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
    return token(user.get());
  }

  private Token token(User user) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(props.jwtIssuer())
            .subject(user.getId().toString())
            .audience(List.of("asset-api"))
            .issuedAt(now)
            .expiresAt(now.plus(props.tokenTtl()))
            .id(UUID.randomUUID().toString())
            .build();
    String value =
        tokens
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new Token(value, "Bearer", props.tokenTtl().toSeconds());
  }

  private String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
