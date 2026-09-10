package dev.assetplatform.validation;

import java.util.Objects;

/** KMP keeps the longest matching prefix across chunks, including overlapping matches. */
public final class StreamingPatternMatcher {
  private final byte[] pattern;
  private final int[] prefix;
  private int matched;
  private long consumed;
  private long lastMatchEnd = -1;

  public StreamingPatternMatcher(byte[] pattern) {
    if (pattern.length == 0) throw new IllegalArgumentException("Pattern cannot be empty");
    this.pattern = pattern.clone();
    prefix = new int[pattern.length];
    for (int i = 1, j = 0; i < pattern.length; i++) {
      while (j > 0 && pattern[i] != pattern[j]) j = prefix[j - 1];
      if (pattern[i] == pattern[j]) j++;
      prefix[i] = j;
    }
  }

  public void accept(byte[] bytes, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    for (int i = offset; i < offset + length; i++) {
      while (matched > 0 && bytes[i] != pattern[matched]) matched = prefix[matched - 1];
      if (bytes[i] == pattern[matched]) matched++;
      consumed++;
      if (matched == pattern.length) {
        lastMatchEnd = consumed;
        matched = prefix[matched - 1];
      }
    }
  }

  public boolean endsWithin(long trailingBytes) {
    return lastMatchEnd >= 0 && trailingBytes >= 0 && consumed - lastMatchEnd <= trailingBytes;
  }

  public long lastMatchEnd() {
    return lastMatchEnd;
  }
}
