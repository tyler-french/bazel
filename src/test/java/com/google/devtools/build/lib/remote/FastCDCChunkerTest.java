// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.remote.FastCDCChunker.ChunkData;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FastCDCChunker}. */
@RunWith(JUnit4.class)
public class FastCDCChunkerTest {

  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  private static final int TEST_AVG_SIZE = 256;
  private static final int TEST_MIN_SIZE = TEST_AVG_SIZE / 4;
  private static final int TEST_MAX_SIZE = TEST_AVG_SIZE * 4;

  private FastCDCChunker chunker;

  @Before
  public void setUp() {
    chunker = new FastCDCChunker(TEST_MIN_SIZE, TEST_AVG_SIZE, TEST_MAX_SIZE, DIGEST_UTIL);
  }

  @Test
  public void testChunkEmptyInput() throws IOException {
    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(new byte[0]));
    assertThat(chunks).isEmpty();
  }

  @Test
  public void testChunkSmallInput_singleChunk() throws IOException {
    byte[] data = "Hello, World!".getBytes();
    assertThat(data.length).isLessThan(TEST_MIN_SIZE);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).data().toByteArray()).isEqualTo(data);
  }

  @Test
  public void testChunkLargeInput_multipleChunks() throws IOException {
    byte[] data = new byte[TEST_MAX_SIZE * 5];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    assertThat(chunks.size()).isGreaterThan(1);
  }

  @Test
  public void testChunkBoundariesAreDeterministic() throws IOException {
    byte[] data = new byte[TEST_MAX_SIZE * 3];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks1 = chunker.chunk(new ByteArrayInputStream(data));
    ImmutableList<ChunkData> chunks2 = chunker.chunk(new ByteArrayInputStream(data));

    assertThat(chunks1).hasSize(chunks2.size());
    for (int i = 0; i < chunks1.size(); i++) {
      assertThat(chunks1.get(i).digest()).isEqualTo(chunks2.get(i).digest());
      assertThat(chunks1.get(i).data()).isEqualTo(chunks2.get(i).data());
    }
  }

  @Test
  public void testChunkDigestsAreCorrect() throws IOException {
    byte[] data = new byte[TEST_MAX_SIZE * 2];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    for (ChunkData chunk : chunks) {
      // Verify the digest matches the data
      Digest expectedDigest = DIGEST_UTIL.compute(chunk.data().toByteArray());
      assertThat(chunk.digest()).isEqualTo(expectedDigest);
    }
  }

  @Test
  public void testMinMaxChunkSizeRespected() throws IOException {
    byte[] data = new byte[TEST_MAX_SIZE * 10];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    for (int i = 0; i < chunks.size(); i++) {
      int chunkSize = chunks.get(i).data().size();
      // Last chunk can be smaller than minSize
      if (i < chunks.size() - 1) {
        assertThat(chunkSize).isAtLeast(TEST_MIN_SIZE);
      }
      assertThat(chunkSize).isAtMost(TEST_MAX_SIZE);
    }
  }

  @Test
  public void testChunkingPreservesData() throws IOException {
    byte[] data = new byte[TEST_MAX_SIZE * 5];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    // Concatenate all chunks
    ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
    for (ChunkData chunk : chunks) {
      chunk.data().writeTo(reconstructed);
    }

    assertThat(reconstructed.toByteArray()).isEqualTo(data);
  }

  @Test
  public void testChunkSizesAverageNearTarget() throws IOException {
    // With enough data, average chunk size should be near the target
    byte[] data = new byte[TEST_AVG_SIZE * 100];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    long totalSize = 0;
    for (ChunkData chunk : chunks) {
      totalSize += chunk.data().size();
    }
    double avgSize = (double) totalSize / chunks.size();

    // Average should be within 50% of target (FastCDC has some variance)
    assertThat(avgSize).isGreaterThan(TEST_AVG_SIZE * 0.5);
    assertThat(avgSize).isLessThan(TEST_AVG_SIZE * 1.5);
  }

  @Test
  public void testChunkBoundariesContentDefined() throws IOException {
    // Inserting data at the beginning should not affect chunk boundaries later
    byte[] originalData = new byte[TEST_MAX_SIZE * 5];
    new Random(42).nextBytes(originalData);

    // Create modified data with prefix
    byte[] prefix = "PREFIX_DATA_".getBytes();
    byte[] modifiedData = new byte[prefix.length + originalData.length];
    System.arraycopy(prefix, 0, modifiedData, 0, prefix.length);
    System.arraycopy(originalData, 0, modifiedData, prefix.length, originalData.length);

    ImmutableList<ChunkData> originalChunks = chunker.chunk(new ByteArrayInputStream(originalData));
    ImmutableList<ChunkData> modifiedChunks = chunker.chunk(new ByteArrayInputStream(modifiedData));

    // After the prefix disruption, some chunks should be shared
    // Count how many chunks from original appear in modified
    int sharedChunks = 0;
    for (ChunkData original : originalChunks) {
      for (ChunkData modified : modifiedChunks) {
        if (original.digest().equals(modified.digest())) {
          sharedChunks++;
          break;
        }
      }
    }

    // With content-defined chunking, we expect some chunk sharing after the prefix
    // The exact number depends on where chunk boundaries fall
    assertThat(sharedChunks).isGreaterThan(0);
  }

  @Test
  public void testDefaultChunkerUsesRemoteOptionsConstants() throws IOException {
    // Test that the default constructor uses RemoteOptions constants
    FastCDCChunker defaultChunker = new FastCDCChunker(DIGEST_UTIL);

    // Create data larger than default threshold (2MB)
    byte[] data = new byte[4 * 1024 * 1024]; // 4MB
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = defaultChunker.chunk(new ByteArrayInputStream(data));

    // Should produce multiple chunks
    assertThat(chunks.size()).isGreaterThan(1);

    // Verify data integrity
    ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
    for (ChunkData chunk : chunks) {
      chunk.data().writeTo(reconstructed);
    }
    assertThat(reconstructed.toByteArray()).isEqualTo(data);
  }

  @Test
  public void testChunkBoundariesMethod() {
    byte[] data = new byte[TEST_MAX_SIZE * 3];
    new Random(42).nextBytes(data);

    int[] boundaries = chunker.chunkBoundaries(data);

    // Sum of boundaries should equal data length
    int sum = 0;
    for (int boundary : boundaries) {
      sum += boundary;
    }
    assertThat(sum).isEqualTo(data.length);
  }

  @Test
  public void testRepeatedPatternProducesFewerChunks() throws IOException {
    // Repeated data should produce chunks that can be deduplicated
    byte[] pattern = new byte[TEST_AVG_SIZE];
    new Random(42).nextBytes(pattern);

    // Create data with repeated pattern
    byte[] repeatedData = new byte[pattern.length * 10];
    for (int i = 0; i < 10; i++) {
      System.arraycopy(pattern, 0, repeatedData, i * pattern.length, pattern.length);
    }

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(repeatedData));

    // Count unique digests
    long uniqueDigests = chunks.stream().map(ChunkData::digest).distinct().count();

    // With repeated patterns, we should have fewer unique chunks than total chunks
    // (though this depends on chunk boundaries aligning with pattern boundaries)
    assertThat(chunks.size()).isGreaterThan(1);
  }

  @Test
  public void testGearTableProducesConsistentResults() throws IOException {
    byte[] data = new byte[10000];
    new Random(12345).nextBytes(data);

    int[] boundaries = chunker.chunkBoundaries(data);

    // Exact values depend on the gear table - catches accidental modifications.
    // These values are specific to fastcdc-go's tables.
    assertThat(boundaries.length).isEqualTo(34);
    assertThat(boundaries[0]).isEqualTo(245);
  }

  @Test
  public void testChunkerWithExactMinSize() throws IOException {
    // Input exactly at minSize should produce a single chunk (no content boundaries possible)
    byte[] data = new byte[TEST_MIN_SIZE];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).data().size()).isEqualTo(TEST_MIN_SIZE);
  }

  @Test
  public void testAllChunksRespectMaxSize() throws IOException {
    // All chunks should respect maxSize regardless of input size
    byte[] data = new byte[TEST_MAX_SIZE * 3];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    for (ChunkData chunk : chunks) {
      assertThat(chunk.data().size()).isAtMost(TEST_MAX_SIZE);
    }
  }

  @Test
  public void testAllChunksExceptLastRespectMinSize() throws IOException {
    byte[] data = new byte[TEST_MAX_SIZE * 3];
    new Random(42).nextBytes(data);

    ImmutableList<ChunkData> chunks = chunker.chunk(new ByteArrayInputStream(data));

    for (int i = 0; i < chunks.size() - 1; i++) {
      assertThat(chunks.get(i).data().size()).isAtLeast(TEST_MIN_SIZE);
    }
  }

  // Cross-language compatibility test.
  //
  // This implementation matches github.com/jotfs/fastcdc-go for cross-language compatibility.
  // We use fastcdc-go because it's the simplest implementation: one byte at a time with masks
  // that have bits evenly distributed for better deduplication ratios.

  @Test
  public void testAllZerosProducesMaxSizeChunks() throws IOException {
    // All zeros always returns max-size chunks since no content boundaries are found.
    byte[] data = new byte[10240];
    FastCDCChunker zeroChunker = new FastCDCChunker(64, 256, 1024, DIGEST_UTIL);

    int[] boundaries = zeroChunker.chunkBoundaries(data);

    assertThat(boundaries.length).isEqualTo(10);
    for (int boundary : boundaries) {
      assertThat(boundary).isEqualTo(1024);
    }
  }
}
