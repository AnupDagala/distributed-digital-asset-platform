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
  void pdfTrailerBoundaryIsInclusiveAndPatternIsDefensivelyCopied() {
    byte[] pattern = bytes("%%EOF");
    StreamingPatternMatcher matcher = new StreamingPatternMatcher(pattern);
    pattern[0] = '!';
    byte[] marker = bytes("x%%EOF");
    matcher.accept(marker, 1, 5);
    matcher.accept(new byte[1024], 0, 1024);
    assertThat(matcher.endsWithin(1024)).isTrue();
    matcher.accept(new byte[1], 0, 1);
    assertThat(matcher.endsWithin(1024)).isFalse();
    assertThat(matcher.endsWithin(-1)).isFalse();
    assertThatThrownBy(() -> matcher.accept(marker, -1, 2))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> matcher.accept(marker, 1, marker.length))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void binaryPatternsMatchReferenceAcrossArbitraryPartitions() {
    Random random = new Random(90910);
    for (int sample = 0; sample < 300; sample++) {
      byte[] pattern = new byte[1 + random.nextInt(16)], data = new byte[random.nextInt(300)];
      for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (128 + random.nextInt(4));
      for (int i = 0; i < data.length; i++) data[i] = (byte) (128 + random.nextInt(4));
      if (data.length >= pattern.length && sample % 2 == 0)
        System.arraycopy(
            pattern, 0, data, random.nextInt(data.length - pattern.length + 1), pattern.length);
      long expected = -1;
      for (int i = 0; i <= data.length - pattern.length; i++) {
        if (java.util.Arrays.equals(pattern, 0, pattern.length, data, i, i + pattern.length))
          expected = i + pattern.length;
      }
      StreamingPatternMatcher matcher = new StreamingPatternMatcher(pattern);
      for (int offset = 0; offset < data.length; ) {
        int length = Math.min(1 + random.nextInt(19), data.length - offset);
        matcher.accept(data, offset, length);
        offset += length;
      }
      assertThat(matcher.lastMatchEnd()).as("sample %s", sample).isEqualTo(expected);
    }
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
