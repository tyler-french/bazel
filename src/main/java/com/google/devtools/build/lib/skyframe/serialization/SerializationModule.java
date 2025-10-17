// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A {@link BlazeModule} to store Skyframe serialization lifecycle hooks. */
public class SerializationModule extends BlazeModule {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private RemoteAnalysisCachingServicesSupplier remoteAnalysisCachingServicesSupplier;
  private BlazeDirectories directories;

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    if (!directories.inWorkspace()) {
      // Serialization only works when the Bazel server is invoked from a workspace.
      // Counter-example: invoking the Bazel server outside of a workspace to generate/dump
      // documentation HTML.
      return;
    }
    this.directories = directories;

    // This is injected as a callback instead of evaluated eagerly to avoid forcing the somewhat
    // expensive AutoRegistry.get call on clients that don't require it.
    builder.setAnalysisCodecRegistrySupplier(
        getAnalysisCodecRegistrySupplier(runtime, directories));

    remoteAnalysisCachingServicesSupplier = getAnalysisCachingServicesSupplier();
    builder.setRemoteAnalysisCachingServicesSupplier(remoteAnalysisCachingServicesSupplier);
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    // Check if we should use disk cache based on options
    if (env.getOptions() != null) {
      RemoteAnalysisCachingOptions options =
          env.getOptions().getOptions(RemoteAnalysisCachingOptions.class);
      if (options != null && options.diskAnalysisCache && directories != null) {
        // Replace the supplier with disk-backed version
        remoteAnalysisCachingServicesSupplier =
            createDiskBackedServicesSupplier(directories, options);
      }
    }
  }

  @Override
  public void blazeShutdown() {
    shutdownAnalysisCacheClient();
  }

  @Override
  public void blazeShutdownOnCrash(DetailedExitCode exitCode) {
    shutdownAnalysisCacheClient();
  }

  private void shutdownAnalysisCacheClient() {
    @Nullable
    ListenableFuture<RemoteAnalysisCacheClient> analysisCacheClient =
        remoteAnalysisCachingServicesSupplier == null
            ? null
            : remoteAnalysisCachingServicesSupplier.getAnalysisCacheClient();
    if (analysisCacheClient != null) {
      analysisCacheClient.addListener(
          new Runnable() {
            @Override
            public void run() {
              try {
                analysisCacheClient
                    .get(RemoteAnalysisCacheClient.SHUTDOWN_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)
                    .shutdown();
              } catch (ExecutionException | TimeoutException | InterruptedException e) {
                // There is no analysisCacheClient to shutdown.
                analysisCacheClient.cancel(/* mayInterruptIfRunning= */ false);
              }
            }
          },
          directExecutor());
    }
  }

  @ForOverride
  protected Supplier<ObjectCodecRegistry> getAnalysisCodecRegistrySupplier(
      BlazeRuntime runtime, BlazeDirectories directories) {
    return () ->
        SerializationRegistrySetupHelpers.initializeAnalysisCodecRegistryBuilder(
                runtime.getRuleClassProvider(),
                SerializationRegistrySetupHelpers.makeReferenceConstants(
                    directories,
                    runtime.getRuleClassProvider(),
                    directories.getWorkspace().getBaseName()))
            .build();
  }

  @ForOverride
  protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier() {
    return InMemoryRemoteAnalysisCachingServicesSupplier.INSTANCE;
  }

  /**
   * Creates a disk-backed services supplier.
   *
   * <p>This creates a persistent analysis cache in the specified directory, or in the output base
   * directory if no custom directory is specified.
   */
  private RemoteAnalysisCachingServicesSupplier createDiskBackedServicesSupplier(
      BlazeDirectories directories, RemoteAnalysisCachingOptions options) {
    Path cacheDir;
    if (options.diskAnalysisCacheDir != null && !options.diskAnalysisCacheDir.isEmpty()) {
      // Use custom directory specified by user
      cacheDir = directories.getOutputBase().getFileSystem().getPath(options.diskAnalysisCacheDir);
    } else {
      // Use default location in output base
      cacheDir = directories.getOutputBase().getRelative("analysis-cache");
    }

    try {
      DiskFingerprintValueStore diskStore = new DiskFingerprintValueStore(cacheDir);
      logger.atInfo().log("Using disk-based analysis cache at: %s", cacheDir);
      return new DiskBackedServicesSupplier(diskStore);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to create disk analysis cache at %s, falling back to in-memory", cacheDir);
      return InMemoryRemoteAnalysisCachingServicesSupplier.INSTANCE;
    }
  }

  /** A supplier that uses an in-memory fingerprint value service. */
  private static final class InMemoryRemoteAnalysisCachingServicesSupplier
      implements RemoteAnalysisCachingServicesSupplier {
    private static final InMemoryRemoteAnalysisCachingServicesSupplier INSTANCE =
        new InMemoryRemoteAnalysisCachingServicesSupplier();

    private static final FingerprintValueService SERVICE_INSTANCE =
        new FingerprintValueService(
            commonPool(),
            FingerprintValueStore.inMemoryStore(),
            new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
            FingerprintValueService.NONPROD_FINGERPRINTER,
            /* jsonLogWriter= */ null);

    private static final ListenableFuture<FingerprintValueService> WRAPPED_SERVICE_INSTANCE =
        immediateFuture(SERVICE_INSTANCE);

    @Override
    public ListenableFuture<FingerprintValueService> getFingerprintValueService() {
      return WRAPPED_SERVICE_INSTANCE;
    }
  }

  /** A supplier that uses a disk-based fingerprint value service. */
  private static final class DiskBackedServicesSupplier
      implements RemoteAnalysisCachingServicesSupplier {
    private final FingerprintValueService serviceInstance;
    private final ListenableFuture<FingerprintValueService> wrappedServiceInstance;

    DiskBackedServicesSupplier(DiskFingerprintValueStore diskStore) {
      this.serviceInstance =
          new FingerprintValueService(
              commonPool(),
              diskStore,
              new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
              FingerprintValueService.NONPROD_FINGERPRINTER,
              /* jsonLogWriter= */ null);
      this.wrappedServiceInstance = immediateFuture(serviceInstance);
    }

    @Override
    public ListenableFuture<FingerprintValueService> getFingerprintValueService() {
      return wrappedServiceInstance;
    }
  }
}
