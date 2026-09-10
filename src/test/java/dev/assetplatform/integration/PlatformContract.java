package dev.assetplatform.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetplatform.*;
import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.domain.*;
import dev.assetplatform.dto.*;
import dev.assetplatform.messaging.*;
import dev.assetplatform.repository.*;
import dev.assetplatform.service.*;
import dev.assetplatform.storage.ObjectStorage;
import dev.assetplatform.worker.ProcessingTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "platform.outbox-enabled=false",
      "platform.sse-poll-ms=100",
      "spring.kafka.listener.auto-startup=true",
      "platform.requests-per-minute=10000",
      "platform.auth-requests-per-minute=10000",
      "logging.level.org.apache.kafka=ERROR",
      "logging.level.kafka=ERROR",
      "logging.level.org.springframework.kafka=ERROR",
      "logging.level.org.apache.zookeeper=ERROR",
      "logging.level.state.change.logger=ERROR",
      "logging.structured.format.console=",
      "spring.main.banner-mode=off"
    })
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
abstract class PlatformContract {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired AssetUploadService uploads;
  @Autowired AssetQueryService queries;
  @Autowired AuthService auth;
  @Autowired ObjectStorage storage;
  @Autowired PlatformProperties props;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MeterRegistry metrics;
  @Autowired JwtDecoder decoder;
  @Autowired ProcessingTransactions worker;
  @Autowired EventCodec codec;

  @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
  String brokers;

  @org.springframework.boot.test.web.server.LocalServerPort int port;

  record Account(UUID id, String token) {}

  Account account() {
    var token =
        auth.register(
            new AuthDtos.Credentials(
                UUID.randomUUID() + "@example.test", UUID.randomUUID() + "Aa1!"));
    return new Account(
        UUID.fromString(decoder.decode(token.accessToken()).getSubject()), token.accessToken());
  }

  String bearer(Account account) {
    return "Bearer " + account.token();
  }

  MockMultipartFile png(String name) throws Exception {
    return new MockMultipartFile("file", name, "image/png", TestFiles.image("png", 5, 7));
  }

  UUID create(Account user, UUID key) throws Exception {
    return uploads.upload(user.id(), key, png("photo.png")).response().id();
  }

  OutboxRelay relay(KafkaTemplate<String, String> template) {
    return new OutboxRelay(jdbc, template, storage, props, transactions, metrics);
  }

  void drain() {
    relay(kafka).tick();
  }

  AssetStatus terminal(Account user, UUID id) {
    await()
        .atMost(Duration.ofSeconds(35))
        .until(() -> queries.status(user.id(), id).status().terminal());
    return queries.status(user.id(), id).status();
  }

  ProcessingRequest processingRequest(UUID asset) {
    return codec.decode(
        jdbc.queryForObject(
            "select payload from outbox_events where asset_id=? and event_type='PROCESS_ASSET'",
            String.class,
            asset));
  }

  @Test
  void registerLoginAndRejectInvalidPassword() throws Exception {
    String email = UUID.randomUUID() + "@example.test", password = UUID.randomUUID() + "Aa1!";
    String json = mapper.writeValueAsString(new AuthDtos.Credentials(email, password));
    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isString());
    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new AuthDtos.Credentials(email, "wrong-password-123"))))
        .andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "select password_hash from app_users where email=?", String.class, email))
        .doesNotContain(password);
  }

  @Test
  void rejectsUnauthenticatedInvalidTokenAndMalformedJson() throws Exception {
    mvc.perform(get("/api/v1/assets"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer invalid.token.value"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.requestId").isString())
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @Autowired org.springframework.security.oauth2.jwt.JwtEncoder tokenEncoder;

  @Test
  void rejectsExpiredAndIncorrectlyScopedTokens() throws Exception {
    Account user = account();
    for (String fault : List.of("expired", "issuer", "audience", "subject")) {
      var now = java.time.Instant.now();
      var claims =
          org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
              .subject(fault.equals("subject") ? "invalid" : user.id().toString())
              .issuer(fault.equals("issuer") ? "untrusted" : props.jwtIssuer())
              .audience(List.of(fault.equals("audience") ? "other-api" : "asset-api"))
              .issuedAt(now.minusSeconds(300))
              .expiresAt(fault.equals("expired") ? now.minusSeconds(120) : now.plusSeconds(300))
              .build();
      String token =
          tokenEncoder
              .encode(
                  org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                      org.springframework.security.oauth2.jwt.JwsHeader.with(
                              org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                          .build(),
                      claims))
              .getTokenValue();
      mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void rejectsNonCanonicalIdempotencyKey() throws Exception {
    Account user = account();
    mvc.perform(
            multipart("/api/v1/assets")
                .file(png("photo.png"))
                .header("Authorization", bearer(user))
                .header("Idempotency-Key", "1-1-1-1-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ownsAllAssetRoutesAndFiltersListing() throws Exception {
    Account a = account(), b = account();
    UUID id = create(a, null);
    for (String suffix : List.of("", "/status", "/events"))
      mvc.perform(get("/api/v1/assets/" + id + suffix).header("Authorization", bearer(b)))
          .andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/assets/" + id).header("Authorization", bearer(b)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/assets").header("Authorization", bearer(b)))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/v1/assets")
                .header("Authorization", bearer(a))
                .param("status", "QUEUED")
                .param("mediaType", "image/png")
                .param("size", "1")
                .param("sort", "fileSize,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            get("/api/v1/assets").header("Authorization", bearer(a)).param("sort", "ownerId,asc"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void maliciousFilenameMimeAndSignatureAreRejected() throws Exception {
    Account user = account();
    mvc.perform(
            multipart("/api/v1/assets")
                .file(png("../photo.png"))
                .header("Authorization", bearer(user)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            multipart("/api/v1/assets")
                .file(new MockMultipartFile("file", "evil.png", "image/png", "not png".getBytes()))
                .header("Authorization", bearer(user)))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            multipart("/api/v1/assets")
                .file(
                    new MockMultipartFile(
                        "file", "evil.exe", "application/octet-stream", new byte[] {1, 2}))
                .header("Authorization", bearer(user)))
        .andExpect(status().isUnsupportedMediaType());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from assets where owner_id=?", Integer.class, user.id()))
        .isZero();
  }

  @Test
  void concurrentIdempotencyCreatesExactlyOneAssetJobAndOutbox() throws Exception {
    Account user = account();
    UUID key = UUID.randomUUID();
    CyclicBarrier barrier = new CyclicBarrier(6);
    List<Future<UUID>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return create(user, key);
                }));
      Set<UUID> ids = new HashSet<>();
      for (Future<UUID> future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      UUID id = ids.iterator().next();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from assets where owner_id=?", Integer.class, user.id()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from asset_processing_jobs where asset_id=?", Integer.class, id))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from outbox_events where asset_id=?", Integer.class, id))
          .isEqualTo(1);
    }
  }

  @Test
  void replayIsStableAfterProcessingAndConflictingReuseReturns409() throws Exception {
    Account user = account();
    UUID key = UUID.randomUUID();
    var first = uploads.upload(user.id(), key, png("photo.png"));
    drain();
    terminal(user, first.response().id());
    var replay = uploads.upload(user.id(), key, png("photo.png"));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(first.response());
    mvc.perform(
            multipart("/api/v1/assets")
                .file(png("other.png"))
                .header("Authorization", bearer(user))
                .header("Idempotency-Key", key.toString()))
        .andExpect(status().isConflict());
  }

  @Test
  void duplicateContentIsOwnerScopedAndConcurrencySafe() throws Exception {
    Account a = account(), b = account();
    UUID first = create(a, null), second = create(a, null), foreign = create(b, null);
    drain();
    var statuses = List.of(terminal(a, first), terminal(a, second));
    assertThat(statuses).containsExactlyInAnyOrder(AssetStatus.COMPLETED, AssetStatus.DUPLICATE);
    assertThat(terminal(b, foreign)).isEqualTo(AssetStatus.COMPLETED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from content_fingerprints where owner_id=?",
                Integer.class,
                a.id()))
        .isEqualTo(1);
  }

  @Test
  void redeliveryAndInterruptedStartDoNotCreateAdditionalResults() throws Exception {
    Account user = account();
    UUID id = create(user, null);
    ProcessingRequest event = processingRequest(id);
    worker.start(event);
    drain();
    assertThat(terminal(user, id)).isEqualTo(AssetStatus.COMPLETED);
    int before = queries.events(user.id(), id, 0).size();
    kafka
        .send(props.processingTopic(), id.toString(), mapper.writeValueAsString(event))
        .get(10, TimeUnit.SECONDS);
    // Exercise the same database guard deterministically, independent of consumer scheduling.
    assertThat(worker.start(event)).isFalse();
    worker.process(event);
    assertThat(queries.events(user.id(), id, 0)).hasSize(before);
  }

  @Test
  void outboxSurvivesBrokerFailureThenPublishes() throws Exception {
    Account user = account();
    UUID id = create(user, null);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> offline = mock(KafkaTemplate.class);
    when(offline.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    relay(offline).tick();
    assertThat(
            jdbc.queryForObject(
                "select attempts from outbox_events where asset_id=? and"
                    + " event_type='PROCESS_ASSET'",
                Integer.class,
                id))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "select published_at is null from outbox_events where asset_id=? and"
                    + " event_type='PROCESS_ASSET'",
                Boolean.class,
                id))
        .isTrue();
    jdbc.update("update outbox_events set available_at=now() where published_at is null");
    drain();
    assertThat(terminal(user, id)).isEqualTo(AssetStatus.COMPLETED);
  }

  @Test
  void invalidDocumentIsRejectedWithoutRetry() throws Exception {
    Account user = account();
    UUID id =
        uploads
            .upload(
                user.id(),
                null,
                new MockMultipartFile(
                    "file", "bad.pdf", "application/pdf", "%PDF-1.7 junk".getBytes()))
            .response()
            .id();
    drain();
    assertThat(terminal(user, id)).isEqualTo(AssetStatus.REJECTED);
    assertThat(
            jdbc.queryForObject(
                "select attempts from asset_processing_jobs where asset_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void missingObjectExhaustsRetriesAndReachesDeadLetter() throws Exception {
    Account user = account();
    UUID id = create(user, null);
    String key = jdbc.queryForObject("select storage_key from assets where id=?", String.class, id);
    storage.delete(key);
    drain();
    assertThat(terminal(user, id)).isEqualTo(AssetStatus.FAILED);
    assertThat(
            jdbc.queryForObject(
                "select attempts from asset_processing_jobs where asset_id=?", Integer.class, id))
        .isEqualTo(4);
    assertThat(deadLetter(id.toString())).contains(id.toString());
  }

  @Test
  void malformedKafkaMessageDoesNotBlockSubsequentWork() throws Exception {
    String key = "malformed-" + UUID.randomUUID();
    kafka.send(props.processingTopic(), key, "{").get(10, TimeUnit.SECONDS);
    assertThat(deadLetter(key)).isEqualTo("{");
    Account user = account();
    UUID id = create(user, null);
    drain();
    assertThat(terminal(user, id)).isEqualTo(AssetStatus.COMPLETED);
  }

  @Test
  void deleteQueuesObjectCleanupAndRetainsIdempotencyReceipt() throws Exception {
    Account user = account();
    UUID key = UUID.randomUUID();
    UUID id = create(user, key);
    String stored =
        jdbc.queryForObject("select storage_key from assets where id=?", String.class, id);
    mvc.perform(delete("/api/v1/assets/" + id).header("Authorization", bearer(user)))
        .andExpect(status().isNoContent());
    drain();
    mvc.perform(get("/api/v1/assets/" + id).header("Authorization", bearer(user)))
        .andExpect(status().isNotFound());
    assertThatThrownBy(() -> storage.open(stored)).isInstanceOf(java.io.IOException.class);
    assertThat(create(user, key)).isEqualTo(id);
  }

  @Test
  void sseReplaysOnlyEventsAfterCursor() throws Exception {
    Account user = account();
    UUID id = create(user, null);
    drain();
    terminal(user, id);
    long cursor = queries.events(user.id(), id, 0).getFirst().id();
    MvcResult stream =
        mvc.perform(
                get("/api/v1/assets/" + id + "/events")
                    .header("Authorization", bearer(user))
                    .header("Last-Event-ID", cursor))
            .andExpect(request().asyncStarted())
            .andReturn();
    stream.getAsyncResult(10000);
    String content =
        mvc.perform(asyncDispatch(stream))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(content).contains("event:status", "COMPLETED").doesNotContain("ACCEPTED");
  }

  @Test
  void httpServerEnforcesMultipartAndJsonBodyLimits() throws Exception {
    Account user = account();
    var client = java.net.http.HttpClient.newHttpClient();
    String boundary = "test-boundary";
    String start =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n";
    var body =
        java.net.http.HttpRequest.BodyPublishers.concat(
            java.net.http.HttpRequest.BodyPublishers.ofString(start),
            java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                () ->
                    new java.io.InputStream() {
                      long remaining = 22L * 1024 * 1024;

                      public int read() {
                        return remaining-- > 0 ? 42 : -1;
                      }

                      public int read(byte[] b, int off, int len) {
                        if (remaining <= 0) return -1;
                        int n = (int) Math.min(len, remaining);
                        Arrays.fill(b, off, off + n, (byte) 42);
                        remaining -= n;
                        return n;
                      }
                    }),
            java.net.http.HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
    var req =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/assets"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", bearer(user))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body)
            .build();
    assertThat(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(413);
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(17000)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void persistedRetryBudgetSurvivesInterruptedDeliveries() throws Exception {
    Account user = account();
    UUID id = create(user, null);
    ProcessingRequest event = processingRequest(id);
    for (int attempt = 0; attempt < 4; attempt++) assertThat(worker.start(event)).isTrue();
    drain();
    assertThat(terminal(user, id)).isEqualTo(AssetStatus.FAILED);
    assertThat(
            jdbc.queryForObject(
                "select attempts from asset_processing_jobs where asset_id=?", Integer.class, id))
        .isEqualTo(4);
    assertThat(deadLetter(id.toString())).contains(id.toString());
  }

  private String deadLetter(String key) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(props.deadLetterTopic()));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline)
        for (var record : consumer.poll(Duration.ofMillis(500)))
          if (key.equals(record.key())) return record.value();
    }
    throw new AssertionError("Expected dead-letter record was not received");
  }
}
