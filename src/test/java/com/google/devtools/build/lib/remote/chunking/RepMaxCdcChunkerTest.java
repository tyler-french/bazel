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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.Digest;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepMaxCdcChunker}. */
@RunWith(JUnit4.class)
public class RepMaxCdcChunkerTest {
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  @Test
  public void chunkToDigests_emptyInput_returnsEmptyList() throws IOException {
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(256, 1024, DIGEST_UTIL);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(new byte[0]));

    assertThat(digests).isEmpty();
  }

  @Test
  public void chunkToDigests_smallInput_returnsSingleChunk() throws IOException {
    int minSizeBytes = 256;
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(minSizeBytes, 1024, DIGEST_UTIL);
    byte[] data = new byte[100];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(1);
    assertThat(digests.get(0).getSizeBytes()).isEqualTo(100);
  }

  @Test
  public void chunkToDigests_inputSmallerThanTwoMinSize_returnsSingleChunk() throws IOException {
    int minSizeBytes = 256;
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(minSizeBytes, 1024, DIGEST_UTIL);
    byte[] data = new byte[2 * minSizeBytes - 1];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(1);
    assertThat(digests.get(0).getSizeBytes()).isEqualTo(data.length);
  }

  @Test
  public void chunkToDigests_inputExactlyTwoMinSize_producesTwoChunks() throws IOException {
    int minSizeBytes = 256;
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(minSizeBytes, 1024, DIGEST_UTIL);
    byte[] data = new byte[2 * minSizeBytes];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(2);
    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }

  @Test
  public void chunkToDigests_largeInput_producesMultipleChunks() throws IOException {
    int minSizeBytes = 256;
    int horizonSizeBytes = 2048;
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(minSizeBytes, horizonSizeBytes, DIGEST_UTIL);
    byte[] data = new byte[minSizeBytes * 20];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests.size()).isGreaterThan(1);
    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }

  @Test
  public void chunkToDigests_allChunkSizesInRange() throws IOException {
    int minSizeBytes = 256;
    int horizonSizeBytes = 2048;
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(minSizeBytes, horizonSizeBytes, DIGEST_UTIL);
    byte[] data = new byte[minSizeBytes * 40];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests.size()).isGreaterThan(1);
    // All chunks except possibly the last must be in [minSizeBytes, 2*minSizeBytes)
    for (int i = 0; i < digests.size() - 1; i++) {
      long size = digests.get(i).getSizeBytes();
      assertThat(size).isAtLeast(minSizeBytes);
      assertThat(size).isLessThan(2 * minSizeBytes);
    }
    // Last chunk can be smaller
    assertThat(digests.get(digests.size() - 1).getSizeBytes()).isGreaterThan(0);
  }

  @Test
  public void chunkToDigests_sameInputProducesSameChunks() throws IOException {
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(256, 2048, DIGEST_UTIL);
    byte[] data = new byte[8192];
    new Random(123).nextBytes(data);

    List<Digest> digests1 = chunker.chunkToDigests(new ByteArrayInputStream(data));
    List<Digest> digests2 = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests1).isEqualTo(digests2);
  }

  @Test
  public void chunkToDigests_matchesSimpleReferenceImplementation() throws IOException {
    int minSizeBytes = 256;
    byte[] data = new byte[minSizeBytes * 100];
    new Random(123).nextBytes(data);

    for (int horizonSizeBytes = 0; horizonSizeBytes <= 4096; horizonSizeBytes += 512) {
      RepMaxCdcChunker chunker =
          new RepMaxCdcChunker(minSizeBytes, horizonSizeBytes, DIGEST_UTIL);
      List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

      assertThat(chunkSizes(digests))
          .isEqualTo(simpleRepMaxChunkSizes(data, minSizeBytes, horizonSizeBytes));
    }
  }

  @Test
  public void chunkToDigests_digestsAreCorrect() throws IOException {
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(256, 2048, DIGEST_UTIL);
    byte[] data = new byte[100];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(1);
    Digest expected = DIGEST_UTIL.compute(data);
    assertThat(digests.get(0)).isEqualTo(expected);
  }

  @Test
  public void chunkToDigests_zeroHorizon_producesUniformChunks() throws IOException {
    int minSizeBytes = 256;
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(minSizeBytes, 0, DIGEST_UTIL);
    byte[] data = new byte[minSizeBytes * 10];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    // With horizon=0, all chunks should be exactly minSizeBytes
    for (Digest digest : digests) {
      assertThat(digest.getSizeBytes()).isEqualTo(minSizeBytes);
    }
    assertThat(digests).hasSize(10);
  }

  @Test
  public void chunkToDigests_preservesTotalSize() throws IOException {
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(1024, 8192, DIGEST_UTIL);
    byte[] data = new byte[100_000];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }

  @Test
  public void constructor_minSizeTooSmall_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new RepMaxCdcChunker(32, 1024, DIGEST_UTIL));
  }

  @Test
  public void constructor_negativeHorizon_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new RepMaxCdcChunker(256, -1, DIGEST_UTIL));
  }

  @Test
  public void chunkToDigests_fromConfig() throws IOException {
    ChunkingConfig.RepMaxCdc config = new ChunkingConfig.RepMaxCdc(256, 2048);
    RepMaxCdcChunker chunker = new RepMaxCdcChunker(config, DIGEST_UTIL);
    byte[] data = new byte[4096];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests.size()).isGreaterThan(1);
    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }

  private static List<Long> chunkSizes(List<Digest> digests) {
    List<Long> sizes = new ArrayList<>();
    for (Digest digest : digests) {
      sizes.add(digest.getSizeBytes());
    }
    return sizes;
  }

  private static List<Long> simpleRepMaxChunkSizes(
      byte[] data, int minSizeBytes, int horizonSizeBytes) {
    List<Long> chunks = new ArrayList<>();
    int peekSizeBytes = 2 * minSizeBytes + horizonSizeBytes;
    int cursor = 0;
    while (cursor < data.length) {
      int len = Math.min(peekSizeBytes, data.length - cursor);
      if (len < 2 * minSizeBytes) {
        chunks.add((long) len);
        cursor += len;
        continue;
      }

      int searchLen = len - minSizeBytes;
      long initialHash = 0;
      for (int i = cursor + minSizeBytes - GearHash.WINDOW_SIZE;
          i < cursor + minSizeBytes;
          i++) {
        initialHash = (initialHash << 1) + GearHash.GEAR[data[i] & 0xFF];
      }

      while (true) {
        long hash = initialHash;
        long bestHash = hash;
        int bestCutOffsetBytes = 0;
        for (int i = 0; i < searchLen - minSizeBytes; i++) {
          hash = (hash << 1) + GearHash.GEAR[data[cursor + minSizeBytes + i] & 0xFF];
          if (Long.compareUnsigned(bestHash, hash) < 0) {
            bestHash = hash;
            bestCutOffsetBytes = i + 1;
          }
        }
        if (bestCutOffsetBytes < minSizeBytes) {
          int chunkSizeBytes = minSizeBytes + bestCutOffsetBytes;
          chunks.add((long) chunkSizeBytes);
          cursor += chunkSizeBytes;
          break;
        }

        searchLen = bestCutOffsetBytes;
      }
    }
    return chunks;
  }
}
