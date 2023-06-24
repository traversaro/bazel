// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.java;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap;
import com.google.devtools.build.lib.starlarkbuildapi.core.ContextAndFlagGuardedValue;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi.JavaInfoProviderApi;
import net.starlark.java.eval.FlagGuardedValue;

/** {@link Bootstrap} for Starlark objects related to the java language. */
public class JavaBootstrap implements Bootstrap {

  private final JavaCommonApi<?, ?, ?, ?, ?, ?, ?> javaCommonApi;
  private final JavaInfoProviderApi javaInfoProviderApi;
  private final JavaPluginInfoApi.Provider<?> javaPluginInfoProviderApi;
  private final ProguardSpecProviderApi.Provider<?> proguardSpecProvider;
  private static final ImmutableSet<PackageIdentifier> allowedRepositories =
      ImmutableSet.of(
          PackageIdentifier.createUnchecked("_builtins", ""),
          PackageIdentifier.createUnchecked("bazel_tools", ""),
          PackageIdentifier.createUnchecked("rules_java", ""),
          PackageIdentifier.createUnchecked("", "tools/build_defs/java"));

  public JavaBootstrap(
      JavaCommonApi<?, ?, ?, ?, ?, ?, ?> javaCommonApi,
      JavaInfoProviderApi javaInfoProviderApi,
      JavaPluginInfoApi.Provider<?> javaPluginInfoProviderApi,
      ProguardSpecProviderApi.Provider<?> proguardSpecProvider) {
    this.javaCommonApi = javaCommonApi;
    this.javaInfoProviderApi = javaInfoProviderApi;
    this.javaPluginInfoProviderApi = javaPluginInfoProviderApi;
    this.proguardSpecProvider = proguardSpecProvider;
  }

  @Override
  public void addBindingsToBuilder(ImmutableMap.Builder<String, Object> builder) {
    builder.put(
        "java_common",
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            javaCommonApi,
            allowedRepositories));
    builder.put(
        "JavaInfo",
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            javaInfoProviderApi,
            allowedRepositories));
    builder.put(
        "JavaPluginInfo",
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            javaPluginInfoProviderApi,
            allowedRepositories));

    builder.put(
        ProguardSpecProviderApi.NAME,
        FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
            BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API, proguardSpecProvider));
  }
}
