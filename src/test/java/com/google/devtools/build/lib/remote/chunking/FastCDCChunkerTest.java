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
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FastCDCChunker}. */
@RunWith(JUnit4.class)
public class FastCDCChunkerTest {
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  @Test
  public void chunkToDigests_emptyInput_returnsEmptyList() throws IOException {
    FastCDCChunker chunker = new FastCDCChunker(DIGEST_UTIL);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(new byte[0]));

    assertThat(digests).isEmpty();
  }

  @Test
  public void chunkToDigests_smallInput_returnsSingleChunk() throws IOException {
    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    FastCDCChunker chunker = new FastCDCChunker(config, DIGEST_UTIL);
    byte[] data = new byte[100];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(1);
    assertThat(digests.get(0).getSizeBytes()).isEqualTo(100);
  }

  @Test
  public void chunkToDigests_dataAtMinSize_returnsSingleChunk() throws IOException {
    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    FastCDCChunker chunker = new FastCDCChunker(config, DIGEST_UTIL);
    byte[] data = new byte[config.minChunkSize()];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(1);
    assertThat(digests.get(0).getSizeBytes()).isEqualTo(config.minChunkSize());
  }

  @Test
  public void chunkToDigests_largeInput_producesMultipleChunks() throws IOException {
    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    FastCDCChunker chunker = new FastCDCChunker(config, DIGEST_UTIL);
    byte[] data = new byte[config.maxChunkSize() * 3];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests.size()).isGreaterThan(1);
    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }

  @Test
  public void chunkToDigests_sameInputProducesSameChunks() throws IOException {
    FastCDCChunker chunker = new FastCDCChunker(DIGEST_UTIL);
    byte[] data = new byte[2 * 1024 * 1024];
    new Random(123).nextBytes(data);

    List<Digest> digests1 = chunker.chunkToDigests(new ByteArrayInputStream(data));
    List<Digest> digests2 = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests1).isEqualTo(digests2);
  }

  @Test
  public void chunkToDigests_chunkSizesWithinBounds() throws IOException {
    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    FastCDCChunker chunker = new FastCDCChunker(config, DIGEST_UTIL);
    byte[] data = new byte[config.maxChunkSize() * 10];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    for (int i = 0; i < digests.size() - 1; i++) {
      long size = digests.get(i).getSizeBytes();
      assertThat(size).isAtLeast(config.minChunkSize());
      assertThat(size).isAtMost(config.maxChunkSize());
    }
  }

  @Test
  public void chunkToDigests_lastChunkCanBeSmallerThanMin() throws IOException {
    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    FastCDCChunker chunker = new FastCDCChunker(config, DIGEST_UTIL);
    int dataSize = config.maxChunkSize() + config.minChunkSize() / 2;
    byte[] data = new byte[dataSize];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests.size()).isAtLeast(1);
    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(dataSize);
  }

  @Test
  public void chunkToDigests_digestsAreCorrect() throws IOException {
    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    FastCDCChunker chunker = new FastCDCChunker(config, DIGEST_UTIL);
    byte[] data = new byte[500];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests).hasSize(1);
    Digest expected = DIGEST_UTIL.compute(data);
    assertThat(digests.get(0)).isEqualTo(expected);
  }

  @Test
  public void constructor_invalidMinSize_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FastCDCChunker(0, 1024, 4096, 2, DIGEST_UTIL));
  }

  @Test
  public void constructor_avgSizeLessThanMinSize_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FastCDCChunker(1024, 512, 4096, 2, DIGEST_UTIL));
  }

  @Test
  public void constructor_maxSizeLessThanAvgSize_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FastCDCChunker(256, 1024, 512, 2, DIGEST_UTIL));
  }

  @Test
  public void constructor_avgSizeNotPowerOfTwo_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FastCDCChunker(256, 1000, 4096, 2, DIGEST_UTIL));
  }

  @Test
  public void constructor_invalidNormalization_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FastCDCChunker(256, 1024, 4096, 4, DIGEST_UTIL));
  }

  @Test
  public void chunkToDigests_withDefaultConfig() throws IOException {
    FastCDCChunker chunker = new FastCDCChunker(DIGEST_UTIL);
    byte[] data = new byte[4 * 1024 * 1024];
    new Random(42).nextBytes(data);

    List<Digest> digests = chunker.chunkToDigests(new ByteArrayInputStream(data));

    assertThat(digests.size()).isGreaterThan(1);
    long totalSize = digests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }
}
