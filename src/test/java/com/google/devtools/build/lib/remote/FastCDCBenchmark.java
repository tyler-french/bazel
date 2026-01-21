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

import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Benchmark for FastCDCChunker performance.
 *
 * <p>Run with: bazel run //src/test/java/com/google/devtools/build/lib/remote:fastcdc_benchmark
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class FastCDCBenchmark {

  // Test with different data sizes
  @Param({"1048576", "10485760", "104857600"}) // 1MB, 10MB, 100MB
  public int size;

  // Test with different data patterns
  @Param({"random", "zeros", "text"})
  public String pattern;

  private byte[] data;
  private FastCDCChunker chunker;
  private DigestUtil digestUtil;

  @Setup
  public void setup() {
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
    chunker = new FastCDCChunker(digestUtil);

    data = new byte[size];
    switch (pattern) {
      case "random":
        new Random(42).nextBytes(data);
        break;
      case "zeros":
        // Already zeros
        break;
      case "text":
        // Simulated text with repeated patterns (good for dedup)
        byte[] line = "The quick brown fox jumps over the lazy dog.\n".getBytes();
        for (int i = 0; i < size; i++) {
          data[i] = line[i % line.length];
        }
        break;
      default:
        throw new IllegalArgumentException("Unknown pattern: " + pattern);
    }
  }

  /**
   * Benchmark chunk boundary detection only (no digest computation). This measures raw chunking
   * throughput in MB/s.
   */
  @Benchmark
  public int[] chunkBoundariesOnly() {
    return chunker.chunkBoundaries(data);
  }

  /**
   * Benchmark full chunking with digest computation. This is the realistic workload for production
   * use.
   */
  @Benchmark
  public int chunkWithDigests() throws Exception {
    try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
      return chunker.chunk(input).size();
    }
  }

  /**
   * Main method for running outside of JMH runner. Useful for quick sanity checks.
   */
  public static void main(String[] args) throws Exception {
    FastCDCBenchmark bench = new FastCDCBenchmark();
    bench.size = 10_485_760; // 10MB
    bench.pattern = "random";
    bench.setup();

    // Warmup
    for (int i = 0; i < 5; i++) {
      bench.chunkBoundariesOnly();
      bench.chunkWithDigests();
    }

    // Benchmark chunk boundaries only
    int iterations = 100;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      bench.chunkBoundariesOnly();
    }
    long boundariesNs = System.nanoTime() - start;
    double boundariesMbps =
        (double) bench.size * iterations / (boundariesNs / 1_000_000_000.0) / (1024 * 1024);

    // Benchmark full chunking
    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      bench.chunkWithDigests();
    }
    long fullNs = System.nanoTime() - start;
    double fullMbps =
        (double) bench.size * iterations / (fullNs / 1_000_000_000.0) / (1024 * 1024);

    System.out.printf("Chunk boundaries only: %.1f MB/s%n", boundariesMbps);
    System.out.printf("Full chunking (with digests): %.1f MB/s%n", fullMbps);
    System.out.printf("Data pattern: %s, size: %d bytes%n", bench.pattern, bench.size);
  }
}
