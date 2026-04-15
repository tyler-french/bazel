// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote.chunking;

import static com.google.common.base.Preconditions.checkArgument;

import build.bazel.remote.execution.v2.Digest;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * RepMaxCDC content-defined chunker.
 *
 * <p>RepMaxCDC (Repeated Maximum Content-Defined Chunking) applies a maximum-hash chunking
 * strategy iteratively to produce chunks in the range [minSizeBytes, 2*minSizeBytes). This gives a
 * tight 2:1 ratio between the maximum and minimum chunk sizes, yielding better deduplication than
 * FastCDC for many workloads.
 *
 * <p>Algorithm: within each window of [minSizeBytes, minSizeBytes+horizonSizeBytes) bytes, the Gear
 * rolling hash is computed at every position. The position with the maximum hash value is selected
 * as a candidate cut point. If the resulting chunk would be >= 2*minSizeBytes, the algorithm narrows
 * the window to the candidate position and repeats, guaranteeing termination with a chunk in
 * [minSizeBytes, 2*minSizeBytes).
 *
 * <p>This implementation uses a staircase optimization that avoids repeated scanning: during a
 * single left-to-right pass, we track the last position with a new-maximum hash that falls within
 * [minSizeBytes, 2*minSizeBytes). This is equivalent to the iterative narrowing but runs in O(n)
 * time with O(1) extra space.
 *
 * @see <a href="https://github.com/buildbarn/go-cdc">go-cdc reference implementation</a>
 */
public class RepMaxCdcChunker implements ContentDefinedChunker {
  private final int minSizeBytes;
  private final int horizonSizeBytes;
  private final DigestUtil digestUtil;

  public RepMaxCdcChunker(ChunkingConfig.RepMaxCdc config, DigestUtil digestUtil) {
    this(config.minSizeBytes(), config.horizonSizeBytes(), digestUtil);
  }

  public RepMaxCdcChunker(int minSizeBytes, int horizonSizeBytes, DigestUtil digestUtil) {
    checkArgument(
        minSizeBytes >= GearHash.WINDOW_SIZE,
        "minSizeBytes must be >= %s, got %s",
        GearHash.WINDOW_SIZE,
        minSizeBytes);
    checkArgument(
        horizonSizeBytes >= 0, "horizonSizeBytes must be non-negative, got %s", horizonSizeBytes);
    this.minSizeBytes = minSizeBytes;
    this.horizonSizeBytes = horizonSizeBytes;
    this.digestUtil = digestUtil;
  }

  @Override
  public List<Digest> chunkToDigests(InputStream input) throws IOException {
    List<Digest> digests = new ArrayList<>();
    int peekSizeBytes = 2 * minSizeBytes + horizonSizeBytes;
    byte[] buf = new byte[peekSizeBytes];
    int cursor = 0;
    int end = 0;
    boolean eof = false;

    while (true) {
      int available = end - cursor;

      // Refill buffer when we have less than a full peek window
      if (available < peekSizeBytes && !eof) {
        if (cursor > 0 && available > 0) {
          System.arraycopy(buf, cursor, buf, 0, available);
        }
        cursor = 0;
        end = available;
        while (end < peekSizeBytes) {
          int bytesRead = input.read(buf, end, peekSizeBytes - end);
          if (bytesRead == -1) {
            eof = true;
            break;
          }
          end += bytesRead;
        }
        available = end - cursor;
      }

      if (available == 0) {
        break;
      }

      int chunkSizeBytes = nextChunk(buf, cursor, available);
      digests.add(digestUtil.compute(buf, cursor, chunkSizeBytes));
      cursor += chunkSizeBytes;
    }

    return digests;
  }

  /**
   * Finds the next chunk boundary using the RepMaxCDC algorithm.
   *
   * <p>The key insight: scanning left-to-right while tracking positions where the hash reaches a
   * new maximum produces a "staircase" of monotonically increasing hash values. The iterative
   * narrowing of RepMaxCDC is equivalent to walking back through this staircase to find the last
   * entry whose offset from the start is less than minSizeBytes. We only need to track that single
   * entry, making this O(1) extra space.
   */
  int nextChunk(byte[] buf, int off, int len) {
    // Final chunk: if remaining data is less than 2*minSizeBytes, return it all
    if (len < 2 * minSizeBytes) {
      return len;
    }

    // Reserve minSizeBytes at the end to guarantee the next chunk has at least minSizeBytes bytes
    int searchEnd = len - minSizeBytes;

    // Warm up the Gear hash over the gearHashWindowSizeBytes bytes preceding the first candidate
    // cut position
    long gearHash = 0;
    for (int i = minSizeBytes - GearHash.WINDOW_SIZE; i < minSizeBytes; i++) {
      gearHash = (gearHash << 1) + GearHash.GEAR[buf[off + i] & 0xFF];
    }

    // Scan from minSizeBytes to searchEnd, tracking the last position with a new-maximum hash
    // that would produce a valid chunk (size < 2*minSizeBytes).
    long bestGearHash = 0;
    int bestCutOffsetBytes = 0; // relative to minSizeBytes

    for (int i = minSizeBytes; i < searchEnd; i++) {
      gearHash = (gearHash << 1) + GearHash.GEAR[buf[off + i] & 0xFF];
      if (Long.compareUnsigned(gearHash, bestGearHash) > 0) {
        bestGearHash = gearHash;
        int offsetBytes = i - minSizeBytes;
        if (offsetBytes < minSizeBytes) {
          bestCutOffsetBytes = offsetBytes;
        }
      }
    }

    return minSizeBytes + bestCutOffsetBytes;
  }
}
