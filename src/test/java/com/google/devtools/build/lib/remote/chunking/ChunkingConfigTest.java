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

import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.ChunkingFunction;
import build.bazel.remote.execution.v2.FastCdc2020Params;
import build.bazel.remote.execution.v2.RepMaxCdcParams;
import build.bazel.remote.execution.v2.ServerCapabilities;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ChunkingConfig}. */
@RunWith(JUnit4.class)
public class ChunkingConfigTest {

  @Test
  public void fastCdcDefaults_returnsExpectedValues() {
    ChunkingConfig.FastCdc config = ChunkingConfig.fastCdcDefaults();

    assertThat(config.avgChunkSize()).isEqualTo(512 * 1024);
    assertThat(config.normalizationLevel()).isEqualTo(2);
    assertThat(config.seed()).isEqualTo(0);
    assertThat(config.chunkingThreshold()).isEqualTo(512 * 1024 * 4);
    assertThat(config.chunkingFunction()).isEqualTo(ChunkingFunction.Value.FAST_CDC_2020);
  }

  @Test
  public void fastCdc_minChunkSize_returnsQuarterOfAvg() {
    ChunkingConfig.FastCdc config = new ChunkingConfig.FastCdc(1024, 2, 0);

    assertThat(config.minChunkSize()).isEqualTo(256);
  }

  @Test
  public void fastCdc_maxChunkSize_returnsFourTimesAvg() {
    ChunkingConfig.FastCdc config = new ChunkingConfig.FastCdc(1024, 2, 0);

    assertThat(config.maxChunkSize()).isEqualTo(4096);
  }

  @Test
  public void fastCdc_chunkingThreshold_equalsMaxChunkSize() {
    ChunkingConfig.FastCdc config = new ChunkingConfig.FastCdc(1024, 2, 0);

    assertThat(config.chunkingThreshold()).isEqualTo(config.maxChunkSize());
  }

  @Test
  public void repMaxCdc_chunkingThreshold_chunksAtTwoMinSize() {
    ChunkingConfig.RepMaxCdc config = new ChunkingConfig.RepMaxCdc(1024, 8192);

    assertThat(config.chunkingThreshold()).isEqualTo(2047);
    assertThat(config.chunkingFunction()).isEqualTo(ChunkingFunction.Value.REP_MAX_CDC);
  }

  @Test
  public void fromServerCapabilities_withoutCacheCapabilities_returnsNull() {
    ServerCapabilities capabilities = ServerCapabilities.getDefaultInstance();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isNull();
  }

  @Test
  public void fromServerCapabilities_withoutChunkingParams_returnsNull() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(CacheCapabilities.getDefaultInstance())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isNull();
  }

  @Test
  public void fromServerCapabilities_withFastCdcParams_returnsFastCdcConfig() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .setFastCdc2020Params(
                        FastCdc2020Params.newBuilder()
                            .setAvgChunkSizeBytes(256 * 1024)
                            .setSeed(42)
                            .build())
                    .build())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isEqualTo(new ChunkingConfig.FastCdc(256 * 1024, 2, 42));
  }

  @Test
  public void fromServerCapabilities_withBothAlgorithms_returnsFastCdcConfig() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .setFastCdc2020Params(
                        FastCdc2020Params.newBuilder()
                            .setAvgChunkSizeBytes(256 * 1024)
                            .setSeed(42)
                            .build())
                    .setRepMaxCdcParams(
                        RepMaxCdcParams.newBuilder()
                            .setMinChunkSizeBytes(128 * 1024)
                            .setHorizonSizeBytes(1024 * 1024)
                            .build())
                    .build())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isEqualTo(new ChunkingConfig.FastCdc(256 * 1024, 2, 42));
  }

  @Test
  public void fromServerCapabilities_withRepMaxCdcParams_returnsRepMaxCdcConfig() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .setRepMaxCdcParams(
                        RepMaxCdcParams.newBuilder()
                            .setMinChunkSizeBytes(128 * 1024)
                            .setHorizonSizeBytes(1024 * 1024)
                            .build())
                    .build())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isEqualTo(new ChunkingConfig.RepMaxCdc(128 * 1024, 1024 * 1024));
  }

  @Test
  public void fromServerCapabilities_invalidFastCdcParams_ignoresFastCdc() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .setFastCdc2020Params(
                        FastCdc2020Params.newBuilder().setAvgChunkSizeBytes(300 * 1024).build())
                    .build())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isNull();
  }

  @Test
  public void fromServerCapabilities_invalidFastCdcWithValidRepMaxCdc_returnsRepMaxCdcConfig() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .setFastCdc2020Params(
                        FastCdc2020Params.newBuilder().setAvgChunkSizeBytes(300 * 1024).build())
                    .setRepMaxCdcParams(
                        RepMaxCdcParams.newBuilder()
                            .setMinChunkSizeBytes(128 * 1024)
                            .setHorizonSizeBytes(1024 * 1024)
                            .build())
                    .build())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isEqualTo(new ChunkingConfig.RepMaxCdc(128 * 1024, 1024 * 1024));
  }

  @Test
  public void fromServerCapabilities_invalidRepMaxCdcParams_ignoresRepMaxCdc() {
    ServerCapabilities capabilities =
        ServerCapabilities.newBuilder()
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .setRepMaxCdcParams(
                        RepMaxCdcParams.newBuilder()
                            .setMinChunkSizeBytes(GearHash.WINDOW_SIZE - 1)
                            .setHorizonSizeBytes(1024)
                            .build())
                    .build())
            .build();

    ChunkingConfig config = ChunkingConfig.fromServerCapabilities(capabilities);

    assertThat(config).isNull();
  }
}
