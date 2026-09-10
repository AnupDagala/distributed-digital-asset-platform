package dev.assetplatform.validation;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreamingPatternMatcherTest {
  private byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void handlesChunkBoundariesAndOverlaps() {
    StreamingPatternMatcher matcher = new StreamingPatternMatcher(bytes("ababa"));
    matcher.accept(bytes("xxaba"), 0, 5);
    matcher.accept(bytes("baba!"), 0, 5);
    assertThat(matcher.lastMatchEnd()).isEqualTo(9);
    assertThat(matcher.endsWithin(1)).isTrue();
    assertThat(matcher.endsWithin(0)).isFalse();
  }

  @Test
  void handlesMissingAndEmptyInput() {
    StreamingPatternMatcher matcher = new StreamingPatternMatcher(bytes("%%EOF"));
    matcher.accept(new byte[0], 0, 0);
    assertThat(matcher.endsWithin(1024)).isFalse();
    assertThatThrownBy(() -> new StreamingPatternMatcher(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void matchesReferenceSearchAcrossRandomChunking() {
    Random random = new Random(702);
    for (int sample = 0; sample < 250; sample++) {
      StringBuilder input = new StringBuilder();
      for (int i = 0; i < 200; i++) input.append((char) ('a' + random.nextInt(3)));
      byte[] data = bytes(input.toString());
      String pattern = sample % 2 == 0 ? "ababa" : "cc";
      StreamingPatternMatcher matcher = new StreamingPatternMatcher(bytes(pattern));
      for (int i = 0; i < data.length; ) {
        int n = Math.min(1 + random.nextInt(13), data.length - i);
        matcher.accept(data, i, n);
        i += n;
      }
      int match = input.lastIndexOf(pattern);
      assertThat(matcher.lastMatchEnd()).isEqualTo(match < 0 ? -1 : match + pattern.length());
    }
  }
}
