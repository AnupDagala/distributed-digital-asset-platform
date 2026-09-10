# Streaming PDF end-marker matching

PDF structure checks require a final %%EOF marker close to the file's end. A marker can cross any buffer boundary. Searching each buffer independently misses it; repeatedly concatenating old bytes can introduce unnecessary copying.

StreamingPatternMatcher implements Knuth-Morris-Pratt (KMP). It builds a prefix-function array for the pattern. While bytes arrive, matched records the length of the longest pattern prefix equal to a suffix of the consumed input. On a mismatch it follows prefix links instead of rescanning input. After a match it records the absolute end offset and falls back through the prefix table, preserving overlapping matches. State survives calls to accept.

AssetInspector feeds its fixed-size hashing buffers to a matcher for %%EOF only for PDF content; image uploads do not perform this unused marker scan. After reading the file it requires the latest match to end within 1,024 bytes of the end, then uses PDFBox for actual parsing and page count. Marker presence alone never establishes a valid PDF. This deliberately strict policy can reject nonconforming PDFs with long trailers. A small tail buffer would also solve this fixed-marker problem; KMP makes chunk/overlap behavior explicit without retaining the trailer and remains general for arbitrary binary patterns.

For n input bytes and a pattern of length m:

| Operation | Time | Extra space |
| --- | --- | --- |
| Prefix preprocessing | O(m) | O(m) |
| Stream scan | O(n) amortized | O(m) matcher state |
| Final proximity check | O(1) | O(1) |

Each input byte advances the scan once, and prefix fallback steps are amortized against earlier advances. With the fixed five-byte marker, matcher storage is constant. SHA-256 uses the same 8 KiB buffer; the matcher does not retain the file.

Tests cover missing/empty input, an invalid empty pattern, overlaps, cross-buffer matches, zero-length chunks, invalid offset/length, defensive copying and the exact 1,024/1,025-byte trailer boundary. In addition to 250 seeded text comparisons with lastIndexOf, 300 seeded binary cases vary pattern length, unsigned byte values and chunk partitions against an independent reference search. The matcher is per inspection and mutable, so callers must not share it between threads.

This algorithm validates a byte-stream precondition; it does not schedule jobs, establish PDF safety or replace the parser. Kafka owns durable event ordering/delivery. No in-memory priority queue is interposed in front of that protocol. SHA-256 equality is the fingerprint key; the worker's database constraint, not KMP or an in-memory set, resolves concurrent content duplicates.
