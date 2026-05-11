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

import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.ChunkingFunction;
import build.bazel.remote.execution.v2.FastCdc2020Params;
import build.bazel.remote.execution.v2.RepMaxCdcParams;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import javax.annotation.Nullable;

/** Selected content-defined chunking configuration. All sizes are in bytes. */
public interface ChunkingConfig {
  int DEFAULT_FAST_CDC_AVG_CHUNK_SIZE = 512 * 1024;
  int DEFAULT_FAST_CDC_NORMALIZATION_LEVEL = 2;
  int DEFAULT_FAST_CDC_SEED = 0;

  /** The Remote Execution API chunking function represented by this configuration. */
  ChunkingFunction.Value chunkingFunction();

  /** Blobs larger than this should be chunked. */
  long chunkingThreshold();

  /** Creates the local chunker that implements this configuration. */
  ContentDefinedChunker createChunker(DigestUtil digestUtil);

  static FastCdc fastCdcDefaults() {
    return new FastCdc(
        DEFAULT_FAST_CDC_AVG_CHUNK_SIZE,
        DEFAULT_FAST_CDC_NORMALIZATION_LEVEL,
        DEFAULT_FAST_CDC_SEED);
  }

  @Nullable
  public static ChunkingConfig fromServerCapabilities(ServerCapabilities capabilities) {
    if (!capabilities.hasCacheCapabilities()) {
      return null;
    }
    CacheCapabilities cacheCap = capabilities.getCacheCapabilities();

    FastCdc fastCdc = fastCdcFromCapabilities(cacheCap);
    if (fastCdc != null) {
      return fastCdc;
    }

    return repMaxCdcFromCapabilities(cacheCap);
  }

  @Nullable
  private static FastCdc fastCdcFromCapabilities(CacheCapabilities cacheCap) {
    if (!cacheCap.hasFastCdc2020Params()) {
      return null;
    }
    FastCdc2020Params params = cacheCap.getFastCdc2020Params();
    long configAvgSize = params.getAvgChunkSizeBytes();
    if (configAvgSize < 1024
        || configAvgSize > 1024 * 1024
        || (configAvgSize & (configAvgSize - 1)) != 0) {
      return null;
    }
    return new FastCdc(
        (int) configAvgSize, DEFAULT_FAST_CDC_NORMALIZATION_LEVEL, params.getSeed());
  }

  @Nullable
  private static RepMaxCdc repMaxCdcFromCapabilities(CacheCapabilities cacheCap) {
    if (!cacheCap.hasRepMaxCdcParams()) {
      return null;
    }
    RepMaxCdcParams params = cacheCap.getRepMaxCdcParams();
    long minSizeBytes = params.getMinChunkSizeBytes();
    long horizonSizeBytes = params.getHorizonSizeBytes();
    long peekSizeBytes = 2 * minSizeBytes + horizonSizeBytes;
    if (minSizeBytes < GearHash.WINDOW_SIZE
        || horizonSizeBytes < 0
        || peekSizeBytes < 0
        || minSizeBytes > Integer.MAX_VALUE
        || horizonSizeBytes > Integer.MAX_VALUE
        || peekSizeBytes > Integer.MAX_VALUE) {
      return null;
    }
    return new RepMaxCdc((int) minSizeBytes, (int) horizonSizeBytes);
  }

  /** Configuration for FastCDC 2020. */
  record FastCdc(int avgChunkSize, int normalizationLevel, int seed) implements ChunkingConfig {
    public int minChunkSize() {
      return avgChunkSize / 4;
    }

    public int maxChunkSize() {
      return avgChunkSize * 4;
    }

    @Override
    public long chunkingThreshold() {
      return maxChunkSize();
    }

    @Override
    public ChunkingFunction.Value chunkingFunction() {
      return ChunkingFunction.Value.FAST_CDC_2020;
    }

    @Override
    public ContentDefinedChunker createChunker(DigestUtil digestUtil) {
      return new FastCdcChunker(this, digestUtil);
    }
  }

  /** Configuration for RepMaxCDC. */
  record RepMaxCdc(int minSizeBytes, int horizonSizeBytes) implements ChunkingConfig {
    @Override
    public long chunkingThreshold() {
      return 2L * minSizeBytes - 1;
    }

    @Override
    public ChunkingFunction.Value chunkingFunction() {
      return ChunkingFunction.Value.REP_MAX_CDC;
    }

    @Override
    public ContentDefinedChunker createChunker(DigestUtil digestUtil) {
      return new RepMaxCdcChunker(this, digestUtil);
    }
  }
}
