// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.analysis.AspectResolutionHelpers.computeAspectCollection;
import static com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData.SPLIT_DEP_ORDERING;
import static java.util.Arrays.sort;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.AspectCollection;
import com.google.devtools.build.lib.analysis.DuplicateException;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.build.skyframe.state.StateMachine.Tasks;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Computes requested prerequisite(s), applying any requested aspects.
 *
 * <p>A dependency is specified by a {@link Label} and an execution platform {@link Label} if it is
 * a toolchain.
 *
 * <p>Its configuration is determined by an {@link AttributeConfiguration}, which may be split and
 * result in multiple outputs.
 *
 * <p>Computes any specified aspects, applying the appropriate filtering, and merges them into the
 * resulting values.
 */
final class PrerequisitesProducer
    implements StateMachine,
        ConfiguredTargetAndDataProducer.ResultSink,
        ConfiguredAspectProducer.ResultSink {
  interface ResultSink {
    void acceptPrerequisitesValue(ConfiguredTargetAndData[] prerequisites);

    void acceptPrerequisitesError(InvalidVisibilityDependencyException error);

    void acceptPrerequisitesCreationError(ConfiguredValueCreationException error);

    void acceptPrerequisitesAspectError(DependencyEvaluationException error);
  }

  // -------------------- Input --------------------
  private final PrerequisiteParameters parameters;
  private final Label label;
  @Nullable // Non-null for toolchain prerequisites.
  private final Label executionPlatformLabel;
  private final AttributeConfiguration configuration;
  private final ImmutableList<Aspect> propagatingAspects;

  // -------------------- Output --------------------
  private final ResultSink sink;

  // -------------------- Internal State --------------------
  private ConfiguredTargetAndData[] configuredTargets;
  private boolean hasError;

  PrerequisitesProducer(
      PrerequisiteParameters parameters,
      Label label,
      @Nullable Label executionPlatformLabel,
      AttributeConfiguration configuration,
      ImmutableList<Aspect> propagatingAspects,
      ResultSink sink) {
    this.parameters = parameters;
    this.label = label;
    this.executionPlatformLabel = executionPlatformLabel;
    this.configuration = configuration;
    this.propagatingAspects = propagatingAspects;
    this.sink = sink;

    // size > 0 guaranteed by contract of SplitTransition.
    int size = configuration.count();
    this.configuredTargets = new ConfiguredTargetAndData[size];
  }

  @Override
  public StateMachine step(Tasks tasks, ExtendedEventHandler listener) {
    switch (configuration.kind()) {
      case VISIBILITY:
        tasks.enqueue(
            new ConfiguredTargetAndDataProducer(
                getPrerequisiteKey(/* configurationKey= */ null),
                /* transitionKey= */ null,
                parameters.transitiveState(),
                (ConfiguredTargetAndDataProducer.ResultSink) this,
                /* outputIndex= */ 0));
        break;
      case UNARY:
        tasks.enqueue(
            new ConfiguredTargetAndDataProducer(
                getPrerequisiteKey(configuration.unary()),
                /* transitionKey= */ null,
                parameters.transitiveState(),
                (ConfiguredTargetAndDataProducer.ResultSink) this,
                /* outputIndex= */ 0));
        break;
      case SPLIT:
        int index = 0;
        for (Map.Entry<String, BuildConfigurationKey> entry : configuration.split().entrySet()) {
          tasks.enqueue(
              new ConfiguredTargetAndDataProducer(
                  getPrerequisiteKey(entry.getValue()),
                  /* transitionKey= */ entry.getKey(),
                  parameters.transitiveState(),
                  (ConfiguredTargetAndDataProducer.ResultSink) this,
                  index));
          ++index;
        }
        break;
    }
    return this::computeConfiguredAspects;
  }

  @Override
  public void acceptConfiguredTargetAndData(ConfiguredTargetAndData value, int index) {
    configuredTargets[index] = value;
  }

  @Override
  public void acceptConfiguredTargetAndDataError(InvalidVisibilityDependencyException error) {
    hasError = true;
    sink.acceptPrerequisitesError(error);
  }

  @Override
  public void acceptConfiguredTargetAndDataError(ConfiguredValueCreationException error) {
    hasError = true;
    sink.acceptPrerequisitesCreationError(error);
  }

  private StateMachine computeConfiguredAspects(Tasks tasks, ExtendedEventHandler listener) {
    if (hasError) {
      return DONE;
    }

    cleanupValues();

    AspectCollection aspects;
    try {
      // All configured targets in the set have the same underlying target so using an arbitrary one
      // for aspect filtering is safe.
      aspects = computeAspectCollection(configuredTargets[0], propagatingAspects);
    } catch (InconsistentAspectOrderException e) {
      sink.acceptPrerequisitesAspectError(new DependencyEvaluationException(e));
      return DONE;
    }

    if (aspects.isEmpty()) { // Short circuits if there are no aspects.
      sink.acceptPrerequisitesValue(configuredTargets);
      return DONE;
    }

    NestedSetBuilder<Package> transitivePackages =
        parameters.transitiveState().transitivePackages();
    for (int i = 0; i < configuredTargets.length; ++i) {
      ConfiguredTargetAndData target = configuredTargets[i];
      configuredTargets[i] = null;
      tasks.enqueue(
          new ConfiguredAspectProducer(
              aspects, target, (ConfiguredAspectProducer.ResultSink) this, i, transitivePackages));
    }
    return this::emitMergedTargets;
  }

  @Override
  public void acceptConfiguredAspectMergedTarget(
      int outputIndex, ConfiguredTargetAndData mergedTarget) {
    configuredTargets[outputIndex] = mergedTarget;
  }

  @Override
  public void acceptConfiguredAspectError(DuplicateException error) {
    hasError = true;
    sink.acceptPrerequisitesAspectError(
        new DependencyEvaluationException(
            new ConfiguredValueCreationException(
                parameters.location(),
                error.getMessage(),
                parameters.label(),
                parameters.eventId(),
                /* rootCauses= */ null,
                /* detailedExitCode= */ null),
            /* depReportedOwnError= */ false));
  }

  private StateMachine emitMergedTargets(Tasks tasks, ExtendedEventHandler listener) {
    if (!hasError) {
      sink.acceptPrerequisitesValue(configuredTargets);
    }
    return DONE;
  }

  private ConfiguredTargetKey getPrerequisiteKey(@Nullable BuildConfigurationKey configurationKey) {
    var key = ConfiguredTargetKey.builder().setLabel(label).setConfigurationKey(configurationKey);
    if (executionPlatformLabel != null) {
      key.setExecutionPlatformLabel(executionPlatformLabel);
    }
    return key.build();
  }

  private void cleanupValues() {
    if (configuredTargets.length == 1) {
      // Clears the transition keys if there was no effective transition.
      BuildConfigurationKey fromConfigurationKey = parameters.configurationKey();
      BuildConfigurationValue transitionedConfiguration = configuredTargets[0].getConfiguration();
      // `fromConfigurationKey` == null implies `transitionedConfiguration` == null.
      if (fromConfigurationKey == null
          || (transitionedConfiguration != null
              && fromConfigurationKey
                  .getOptions()
                  .equals(transitionedConfiguration.getOptions()))) {
        configuredTargets[0] = configuredTargets[0].copyWithClearedTransitionKeys();
      }
      return;
    }
    // Otherwise, there was a split transition. Aggregates the transition keys if the resulting
    // configurations are null.
    if (configuredTargets[0].getConfiguration() == null) {
      var keys = new ImmutableList.Builder<String>();
      keys.addAll(configuredTargets[0].getTransitionKeys());
      for (int i = 1; i < configuredTargets.length; ++i) {
        checkState(
            configuredTargets[i].getConfiguration() == null,
            "inconsistent split transition result from %s to %s",
            parameters.label(),
            label);
        keys.addAll(configuredTargets[i].getTransitionKeys());
      }
      configuredTargets =
          new ConfiguredTargetAndData[] {configuredTargets[0].copyWithTransitionKeys(keys.build())};
      return;
    }
    sort(configuredTargets, SPLIT_DEP_ORDERING);
  }
}
