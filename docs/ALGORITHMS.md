# Streaming PDF end-marker matching

PDF structure checks require a final %%EOF marker close to the file's end. A marker can cross any buffer boundary. Searching each buffer independently misses it; repeatedly concatenating old bytes can introduce unnecessary copying.

StreamingPatternMatcher implements Knuth-Morris-Pratt (KMP). It builds a prefix-function array for the pattern. While bytes arrive, matched records the length of the longest pattern prefix equal to a suffix of the consumed input. On a mismatch it follows prefix links instead of rescanning input. After a match it records the absolute end offset and falls back through the prefix table, preserving overlapping matches. State survives calls to accept.

AssetInspector feeds its fixed-size hashing buffers to a matcher for %%EOF. After reading the file it requires the latest match to end within 1,024 bytes of the end, then uses PDFBox for actual parsing and page count. Marker presence alone never establishes a valid PDF. This deliberately strict policy can reject nonconforming PDFs with long trailers.

For n input bytes and a pattern of length m:

| Operation | Time | Extra space |
| --- | --- | --- |
| Prefix preprocessing | O(m) | O(m) |
| Stream scan | O(n) amortized | O(m) matcher state |
| Final proximity check | O(1) | O(1) |

Each input byte advances the scan once, and prefix fallback steps are amortized against earlier advances. With the fixed five-byte marker, matcher storage is constant. SHA-256 uses the same 8 KiB buffer; the matcher does not retain the file.

Tests cover missing/empty input, an invalid empty pattern, overlapping matches, cross-buffer matches and 250 deterministic randomized comparisons to a reference lastIndexOf implementation. There is no priority queue in front of Kafka: adding one would require a separate durable acknowledgement/ordering protocol without improving this workload.
