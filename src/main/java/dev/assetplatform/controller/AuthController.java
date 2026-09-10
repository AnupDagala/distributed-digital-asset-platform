package dev.assetplatform.controller;

import dev.assetplatform.dto.AuthDtos.*;
import dev.assetplatform.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/register")
  public ResponseEntity<Token> register(@Valid @RequestBody Credentials request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(auth.register(request));
  }

  @PostMapping("/login")
  public ResponseEntity<Token> login(@Valid @RequestBody Credentials request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(request));
  }
}
