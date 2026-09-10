package dev.assetplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class User {
  @Id private UUID id;
  private String email;
  private String passwordHash;
  private Instant createdAt;

  protected User() {}

  public User(String email, String passwordHash) {
    this.id = UUID.randomUUID();
    this.email = email;
    this.passwordHash = passwordHash;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }
}
