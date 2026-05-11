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
import java.util.Arrays;
import java.util.List;

/**
 * RepMaxCDC content-defined chunker.
 *
 * <p>This is a Java port of buildbarn/go-cdc's optimized RepMaxCDC implementation. The state
 * machine and comments intentionally follow that implementation closely. See
 * https://github.com/buildbarn/go-cdc/blob/main/rep_max_content_defined_chunker.go.
 *
 * <p>RepMaxCDC expands on MaxCDC by repeatedly applying the chunking process until chunks fall in
 * the range {@code [minSizeBytes, 2 * minSizeBytes)}. Like MaxCDC, it has a read-ahead parameter,
 * but RepMaxCDC uses that value only as a chunking-quality horizon: {@code 0} yields uniform chunks
 * of {@code minSizeBytes}, while a positive value preserves the best cut found in the range {@code
 * [minSizeBytes, minSizeBytes + horizonSizeBytes]}. Unlike MaxCDC, increasing the horizon does not
 * reduce quality through a wider maximum/minimum chunk-size ratio, though returns diminish as the
 * horizon grows.
 */
public final class RepMaxCdcChunker implements ContentDefinedChunker {
  private final int minSizeBytes;
  private final int peekSizeBytes;
  private final DigestUtil digestUtil;

  // List of chunks for which no future data can influence their length. For each chunk, its size
  // is stored. Chunks are stored in reverse order, so that they can be popped from the end.
  private final IntList completeChunks;

  // List of cutting points that will determine the length of future chunks. The hashes at the
  // positions of the cutting points in this list will be strictly monotonically increasing.
  //
  // Cutting points are addressed relative to the first eligible position at which they may be
  // placed (i.e., the end of the last complete chunk, plus the minimum chunk size). This means that
  // the first entry is always equal to zero.
  private final IntList incompleteChunks = new IntList(32);

  // The rolling hash value corresponding to the position up to where input data has been processed.
  private long currentHash;

  // The rolling hash value corresponding to the position of last incomplete chunk. Any new
  // incomplete chunk must have a hash value that is higher than this one.
  private long bestHash;

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
    long peekSizeBytes = 2L * minSizeBytes + horizonSizeBytes;
    checkArgument(
        peekSizeBytes <= Integer.MAX_VALUE,
        "2 * minSizeBytes + horizonSizeBytes must fit in an int, got %s",
        peekSizeBytes);
    this.minSizeBytes = minSizeBytes;
    this.peekSizeBytes = (int) peekSizeBytes;
    this.digestUtil = digestUtil;
    this.completeChunks = new IntList(Math.max(1, horizonSizeBytes / minSizeBytes + 1));
  }

  @Override
  public List<Digest> chunkToDigests(InputStream input) throws IOException {
    reset();
    List<Digest> digests = new ArrayList<>();
    byte[] buf = new byte[peekSizeBytes];
    int cursor = 0;
    int end = 0;
    boolean eof = false;

    while (true) {
      int requiredBytes = completeChunks.size() > 0 ? completeChunks.getLast() : peekSizeBytes;
      int available = end - cursor;
      if (available < requiredBytes && !eof) {
        if (cursor > 0 && available > 0) {
          System.arraycopy(buf, cursor, buf, 0, available);
        }
        cursor = 0;
        end = available;

        while (end < requiredBytes) {
          int n = input.read(buf, end, buf.length - end);
          if (n == -1) {
            eof = true;
            break;
          }
          end += n;
        }
      }

      available = end - cursor;
      if (available == 0) {
        return digests;
      }

      int chunkSizeBytes = nextChunk(buf, cursor, available);
      if (chunkSizeBytes > available) {
        throw new IOException("unexpected end of input while reading RepMaxCDC chunk");
      }
      digests.add(digestUtil.compute(buf, cursor, chunkSizeBytes));
      cursor += chunkSizeBytes;
    }
  }

  private void reset() {
    completeChunks.clear();
    incompleteChunks.clear();
    currentHash = 0;
    bestHash = 0;
  }

  private int nextChunk(byte[] d, int off, int len) {
    // If the previous iteration yielded multiple chunks, we can return them without peeking the
    // full horizon. Doing so allows us to discard data as aggressively as possible. This reduces
    // the amount of data that needs to be retained (copied) when the read buffer is refilled.
    if (completeChunks.size() > 0) {
      return completeChunks.popLast();
    }

    // Gain access to the data corresponding to the next chunk(s). If we're reaching the end of the
    // input, either consume all data or leave at least minSizeBytes behind. This ensures that all
    // chunks of the file are at least minSizeBytes in size, assuming the file is as well.
    if (len < 2 * minSizeBytes) {
      return len;
    }
    int effectiveLen = len - minSizeBytes;

    // Extract the final incomplete chunk from the stack, as it denotes where the previous call
    // stopped hashing the input.
    int currentChunk;
    long currentHash = this.currentHash;
    long bestHash = this.bestHash;
    if (incompleteChunks.size() >= 2) {
      currentChunk = incompleteChunks.getLast();
      incompleteChunks.truncate(incompleteChunks.size() - 1);
    } else {
      // This is the very first chunk. We know that the first minSizeBytes positions can't contain a
      // cut. Skip them.
      incompleteChunks.clear();
      incompleteChunks.add(0);
      currentHash = 0;
      int warmupStart = off + minSizeBytes - GearHash.WINDOW_SIZE;
      int warmupEnd = off + minSizeBytes;
      for (int i = warmupStart; i < warmupEnd; i++) {
        currentHash = (currentHash << 1) + GearHash.GEAR[d[i] & 0xFF];
      }
      bestHash = currentHash;
      currentChunk = 0;
    }

    int uncompletedRegionStart = minSizeBytes + currentChunk;
    while (true) {
      // Start hashing data where the previous call left off. Stop hashing before the distance
      // between two consecutive potential cutting points becomes minSizeBytes in size, as this
      // allows us to complete a chunk.
      int hashRegionLen = effectiveLen - uncompletedRegionStart;
      int originalOldChunksCount = -1;
      int bytesBeforeMinChunkSize =
          incompleteChunks.getLast() + minSizeBytes - 1 - currentChunk;
      if (hashRegionLen > bytesBeforeMinChunkSize) {
        hashRegionLen = bytesBeforeMinChunkSize;
        originalOldChunksCount = incompleteChunks.size();
      } else if (hashRegionLen == 0) {
        break;
      }

      // Preserve all offsets at which the hash increases.
      int hashRegionStart = off + uncompletedRegionStart;
      for (int i = 0; i < hashRegionLen; i++) {
        currentHash = (currentHash << 1) + GearHash.GEAR[d[hashRegionStart + i] & 0xFF];
        if (Long.compareUnsigned(bestHash, currentHash) < 0) {
          bestHash = currentHash;
          incompleteChunks.add(currentChunk + i + 1);
        }
      }

      if (incompleteChunks.size() == originalOldChunksCount) {
        // The loop above did not yield any new cutting points, and the next byte is minSizeBytes
        // away from the last cutting point. This means we can complete all chunks up to this point.
        int previousCompleteChunksCount = completeChunks.size();
        int nextChunk = incompleteChunks.getLast();
        for (int i = incompleteChunks.size() - 3; nextChunk >= minSizeBytes && i >= 0; i--) {
          int chunk = incompleteChunks.get(i);
          int sizeBytes = nextChunk - chunk;
          if (sizeBytes >= minSizeBytes) {
            completeChunks.add(sizeBytes);
            nextChunk = chunk;
            i--;
          }
        }
        completeChunks.add(minSizeBytes + nextChunk);
        completeChunks.reverseRange(previousCompleteChunksCount, completeChunks.size());

        incompleteChunks.truncate(1);
        currentChunk = 0;
        currentHash =
            (currentHash << 1)
                + GearHash.GEAR[d[off + uncompletedRegionStart + hashRegionLen] & 0xFF];
        bestHash = currentHash;
        uncompletedRegionStart += hashRegionLen + 1;
      } else {
        currentChunk += hashRegionLen;
        uncompletedRegionStart += hashRegionLen;
      }
    }

    // Processed the full horizon. Return the first chunk.
    incompleteChunks.add(currentChunk);
    int firstChunk;
    if (completeChunks.size() > 0) {
      completeChunks.reverse();
      firstChunk = completeChunks.popLast();
    } else {
      // The process above did not yield any complete chunks, either because we reached the end of
      // the file or the horizon size wasn't large enough.
      //
      // Ensure that we pick a cutting point respecting the maximum chunk size, that still allows us
      // to pick the most optimal cutting point in the horizon later on.
      int firstChunkIndex = incompleteChunks.size() - 2;
      for (int maxChunk = incompleteChunks.get(firstChunkIndex) - minSizeBytes,
              i = firstChunkIndex - 2;
          maxChunk >= 0 && i >= 0;
          i--) {
        int chunk = incompleteChunks.get(i);
        if (chunk <= maxChunk) {
          firstChunkIndex = i;
          maxChunk = chunk - minSizeBytes;
          i--;
        }
      }
      firstChunk = minSizeBytes + incompleteChunks.get(firstChunkIndex);

      // There will be potential cutting points after the selected one that are no longer eligible,
      // as those would violate the minimum chunk size. These should be removed from the list.
      int reusableChunkIndex = firstChunkIndex + 1;
      while (true) {
        int offsetInSecondChunk = incompleteChunks.get(reusableChunkIndex) - firstChunk;
        if (offsetInSecondChunk >= 0) {
          // This cutting point and the ones after it should be kept.
          for (int i = reusableChunkIndex; i < incompleteChunks.size(); i++) {
            incompleteChunks.set(i, incompleteChunks.get(i) - firstChunk);
          }

          if (offsetInSecondChunk == 0) {
            // There is no need to recompute any cutting points.
            incompleteChunks.removePrefix(reusableChunkIndex);
          } else {
            // Because the first cutting point to keep resides at an offset beyond the minimum chunk
            // size, we may have glossed over potential cutting points before it. Recompute these.
            //
            // This should only happen rarely, especially if the horizon size is sufficiently large.
            int secondChunkStart = off + firstChunk;
            int secondChunkRecomputedRegionLen = minSizeBytes + offsetInSecondChunk - 1;
            long currentRecomputedHash = 0;
            int warmupStart = secondChunkStart + minSizeBytes - GearHash.WINDOW_SIZE;
            int warmupEnd = secondChunkStart + minSizeBytes;
            for (int i = warmupStart; i < warmupEnd; i++) {
              currentRecomputedHash =
                  (currentRecomputedHash << 1) + GearHash.GEAR[d[i] & 0xFF];
            }
            incompleteChunks.set(0, 0);
            long bestRecomputedHash = currentRecomputedHash;
            int recomputedChunkIndex = 1;
            int originalChunksCount = incompleteChunks.size();
            int recomputeStart = secondChunkStart + minSizeBytes;
            int recomputeLen = secondChunkRecomputedRegionLen - minSizeBytes;
            for (int i = 0; i < recomputeLen; i++) {
              currentRecomputedHash =
                  (currentRecomputedHash << 1) + GearHash.GEAR[d[recomputeStart + i] & 0xFF];
              if (Long.compareUnsigned(bestRecomputedHash, currentRecomputedHash) < 0) {
                bestRecomputedHash = currentRecomputedHash;
                int recomputedChunk = i + 1;
                if (recomputedChunkIndex < reusableChunkIndex) {
                  incompleteChunks.set(recomputedChunkIndex, recomputedChunk);
                  recomputedChunkIndex++;
                } else {
                  incompleteChunks.add(recomputedChunk);
                }
              }
            }
            if (recomputedChunkIndex < reusableChunkIndex) {
              // Recomputing yielded fewer cutting points than we had previously. Make the cutting
              // points contiguous again.
              incompleteChunks.removeRange(recomputedChunkIndex, reusableChunkIndex);
            } else if (incompleteChunks.size() > originalChunksCount) {
              // Recomputing yielded more cutting points than we had previously. The excess cutting
              // points were stored at the end. Rotate them into place, so that the list remains
              // sorted.
              incompleteChunks.reverseRange(reusableChunkIndex, originalChunksCount);
              incompleteChunks.reverseRange(originalChunksCount, incompleteChunks.size());
              incompleteChunks.reverseRange(reusableChunkIndex, incompleteChunks.size());
            }
          }
          break;
        }

        // The cutting point should be removed.
        reusableChunkIndex++;
        if (reusableChunkIndex == incompleteChunks.size()) {
          incompleteChunks.truncate(1);
          break;
        }
      }
    }

    this.currentHash = currentHash;
    this.bestHash = bestHash;
    return firstChunk;
  }

  private static final class IntList {
    private int[] values;
    private int size;

    IntList(int initialCapacity) {
      this.values = new int[initialCapacity];
    }

    int size() {
      return size;
    }

    int get(int index) {
      return values[index];
    }

    int getLast() {
      return values[size - 1];
    }

    void set(int index, int value) {
      values[index] = value;
    }

    void add(int value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, values.length * 2);
      }
      values[size++] = value;
    }

    int popLast() {
      return values[--size];
    }

    void clear() {
      size = 0;
    }

    void truncate(int newSize) {
      size = newSize;
    }

    void removePrefix(int count) {
      int newSize = size - count;
      System.arraycopy(values, count, values, 0, newSize);
      size = newSize;
    }

    void removeRange(int fromInclusive, int toExclusive) {
      int removed = toExclusive - fromInclusive;
      System.arraycopy(values, toExclusive, values, fromInclusive, size - toExclusive);
      size -= removed;
    }

    void reverse() {
      reverseRange(0, size);
    }

    void reverseRange(int fromInclusive, int toExclusive) {
      for (int i = fromInclusive, j = toExclusive - 1; i < j; i++, j--) {
        int tmp = values[i];
        values[i] = values[j];
        values[j] = tmp;
      }
    }
  }
}
