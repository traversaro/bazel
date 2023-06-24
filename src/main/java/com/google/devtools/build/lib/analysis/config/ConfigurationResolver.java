// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.analysis.BaseDependencySpecification;
import com.google.devtools.build.lib.analysis.ConfigurationsCollector;
import com.google.devtools.build.lib.analysis.ConfigurationsResult;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyKey;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NullTransition;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PlatformMappingValue;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Turns configuration transition requests into actual configurations.
 *
 * <p>This involves:
 *
 * <ol>
 *   <li>Patching a source configuration's options with the transition.
 *   <li>Getting the destination configuration from Skyframe.
 * </ol>
 *
 * <p>For the work of determining the transition requests themselves, see {@link
 * TransitionResolver}.
 */
public final class ConfigurationResolver {

  /**
   * Determines the output ordering of each {@code <attribute, depLabel> -> [dep<config1>,
   * dep<config2>, ...]} collection produced by a split transition.
   */
  @VisibleForTesting
  public static final Comparator<Dependency> SPLIT_DEP_ORDERING =
      Comparator.comparing(
              Functions.compose(BuildConfigurationValue::getMnemonic, Dependency::getConfiguration))
          .thenComparing(
              Functions.compose(BuildConfigurationValue::checksum, Dependency::getConfiguration));

  private final SkyFunction.LookupEnvironment env;
  private final TargetAndConfiguration ctgValue;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;
  private final StarlarkTransitionCache starlarkTransitionCache;

  public ConfigurationResolver(
      SkyFunction.LookupEnvironment env,
      TargetAndConfiguration ctgValue,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      StarlarkTransitionCache starlarkTransitionCache) {
    this.env = env;
    this.ctgValue = ctgValue;
    this.configConditions = configConditions;
    this.starlarkTransitionCache = starlarkTransitionCache;
  }

  private BuildConfigurationValue getCurrentConfiguration() {
    return ctgValue.getConfiguration();
  }

  /**
   * Translates a set of {@link DependencyKey} objects with configuration transition requests to the
   * same objects with resolved configurations.
   *
   * <p>This method must preserve the original label ordering of each attribute. For example, if
   * {@code dependencyKeys.get("data")} is {@code [":a", ":b"]}, the resolved variant must also be
   * {@code [":a", ":b"]} in the same order.
   *
   * <p>For split transitions, {@code dependencyKeys.get("data") = [":a", ":b"]} can produce the
   * output {@code [":a"<config1>, ":a"<config2>, ..., ":b"<config1>, ":b"<config2>, ...]}. All
   * instances of ":a" still appear before all instances of ":b". But the {@code [":a"<config1>,
   * ":a"<config2>"]} subset may be in any (deterministic) order. In particular, this may not be the
   * same order as {@link SplitTransition#split}. If needed, this code can be modified to use that
   * order, but that involves more runtime in performance-critical code, so we won't make that
   * change without a clear need.
   *
   * <p>These configurations unconditionally include all fragments.
   *
   * <p>This method is heavily performance-optimized. Because {@link
   * com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction} calls it over every edge in
   * the configured target graph, small inefficiencies can have observable impact on analysis time.
   * Keep this in mind when making modifications and performance-test any changes you make.
   *
   * @param dependencyKeys the transition requests for each dep and each dependency kind
   * @param eventHandler the handler for events
   * @return a mapping from each dependency kind in the source target to the {@link
   *     BuildConfigurationValue}s and {@link Label}s for the deps under that dependency kind .
   *     Returns null if not all Skyframe dependencies are available.
   */
  @Nullable
  public OrderedSetMultimap<DependencyKind, Dependency> resolveConfigurations(
      OrderedSetMultimap<DependencyKind, DependencyKey> dependencyKeys,
      ExtendedEventHandler eventHandler)
      throws ConfiguredValueCreationException, InterruptedException {
    OrderedSetMultimap<DependencyKind, Dependency> resolvedDeps = OrderedSetMultimap.create();
    boolean needConfigsFromSkyframe = false;
    for (Map.Entry<DependencyKind, DependencyKey> entry : dependencyKeys.entries()) {
      DependencyKind dependencyKind = entry.getKey();
      DependencyKey dependencyKey = entry.getValue();
      ImmutableList<Dependency> depConfig =
          resolveConfiguration(dependencyKind, dependencyKey, eventHandler);
      if (depConfig == null) {
        // Instead of returning immediately, give the loop a chance to queue up every missing
        // dependency, then return all at once. That prevents re-executing this code an unnecessary
        // number of times. i.e. this is equivalent to calling env.getValuesAndExceptions() once
        // over all deps.
        needConfigsFromSkyframe = true;
      } else {
        resolvedDeps.putAll(dependencyKind, depConfig);
      }
    }
    return needConfigsFromSkyframe ? null : resolvedDeps;
  }

  /**
   * Translates a {@link DependencyKey} with configuration transition to the same objects with
   * resolved configurations.
   *
   * <p>This is the single-argument version of {@link #resolveConfigurations}, whose documentation
   * has more details.
   */
  @Nullable
  public ImmutableList<Dependency> resolveConfiguration(
      DependencyKind dependencyKind, DependencyKey dependencyKey, ExtendedEventHandler eventHandler)
      throws ConfiguredValueCreationException, InterruptedException {

    Dependency.Builder dependencyBuilder = dependencyKey.getDependencyBuilder();

    ConfigurationTransition transition = dependencyKey.getTransition();

    if (transition == NullTransition.INSTANCE) {
      Dependency resolvedDep =
          resolveNullTransition(dependencyBuilder, dependencyKind, eventHandler);
      if (resolvedDep == null) {
        return null; // Need Skyframe deps.
      }
      return ImmutableList.of(resolvedDep);
    }

    var ans = resolveGenericTransition(dependencyBuilder, dependencyKey, eventHandler);
    if (ans != null) {
      ans.stream()
          .filter(d -> d.getConfiguration() != null)
          // No need to log no-op transitions.
          .filter(d -> !d.getConfiguration().equals(ctgValue.getConfiguration()))
          .forEach(
              d ->
                  eventHandler.post(
                      new ConfigRequestedEvent(
                          d.getConfiguration(), ctgValue.getConfiguration().checksum())));
    }
    return ans;
  }

  @Nullable
  private Dependency resolveNullTransition(
      Dependency.Builder dependencyBuilder,
      DependencyKind dependencyKind,
      ExtendedEventHandler eventHandler)
      throws ConfiguredValueCreationException, InterruptedException {
    // The null configuration can be trivially computed (it's, well, null), so special-case that
    // transition here and skip the rest of the logic. A *lot* of targets have null deps, so
    // this produces real savings. Profiling tests over a simple cc_binary show this saves ~1% of
    // total analysis phase time.
    if (dependencyKind.getAttribute() != null) {
      ImmutableList<String> transitionKeys =
          collectTransitionKeys(dependencyKind.getAttribute(), eventHandler);
      if (transitionKeys == null) {
        return null; // Need Skyframe deps.
      }
      dependencyBuilder.setTransitionKeys(transitionKeys);
    }

    return dependencyBuilder.withNullConfiguration().build();
  }

  @Nullable
  private ImmutableList<Dependency> resolveGenericTransition(
      Dependency.Builder dependencyBuilder,
      DependencyKey dependencyKey,
      ExtendedEventHandler eventHandler)
      throws ConfiguredValueCreationException, InterruptedException {
    Map<String, BuildOptions> toOptions;
    try {
      toOptions = applyTransitionWithSkyframe(dependencyKey.getTransition(), eventHandler);
      if (toOptions == null) {
        return null; // Need more Skyframe deps for a Starlark transition.
      }
    } catch (TransitionException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    if (SplitTransition.equals(getCurrentConfiguration().getOptions(), toOptions.values())) {
      // The dep uses the same exact configuration. Let's re-use the current configuration and
      // skip adding a Skyframe dependency edge on it.
      return ImmutableList.of(
          dependencyBuilder
              .setConfiguration(getCurrentConfiguration())
              .setAspects(dependencyKey.getAspects())
              // Explicitly do not set the transition key, since there is only one configuration
              // and it matches the current one. This ignores the transition key set if this
              // was a split transition.
              .build());
    }

    PathFragment platformMappingPath =
        getCurrentConfiguration().getOptions().get(PlatformOptions.class).platformMappings;
    PlatformMappingValue platformMappingValue =
        (PlatformMappingValue) env.getValue(PlatformMappingValue.Key.create(platformMappingPath));
    if (platformMappingValue == null) {
      return null; // Need platform mappings from Skyframe.
    }

    Map<String, BuildConfigurationKey> configurationKeys = new HashMap<>();
    try {
      for (Map.Entry<String, BuildOptions> optionsEntry : toOptions.entrySet()) {
        String transitionKey = optionsEntry.getKey();
        BuildConfigurationKey buildConfigurationKey =
            BuildConfigurationKey.withPlatformMapping(
                platformMappingValue, optionsEntry.getValue());
        configurationKeys.put(transitionKey, buildConfigurationKey);
      }
    } catch (OptionsParsingException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    SkyframeLookupResult depConfigValues = env.getValuesAndExceptions(configurationKeys.values());
    List<Dependency> dependencies = new ArrayList<>();
    try {
      for (Map.Entry<String, BuildConfigurationKey> entry : configurationKeys.entrySet()) {
        String transitionKey = entry.getKey();
        // TODO(blaze-configurability-team): Should be able to just use BuildConfigurationKey
        BuildConfigurationValue configuration =
            (BuildConfigurationValue)
                depConfigValues.getOrThrow(entry.getValue(), InvalidConfigurationException.class);
        if (configuration == null) {
          continue;
        }
          Dependency resolvedDep =
              dependencyBuilder
                  // Copy the builder so we don't overwrite the other dependencies.
                  .copy()
                  .setConfiguration(configuration)
                  .setAspects(dependencyKey.getAspects())
                  .setTransitionKey(transitionKey)
                  .build();
          dependencies.add(resolvedDep);
      }
      if (env.valuesMissing()) {
        return null; // Need dependency configurations.
      }
    } catch (InvalidConfigurationException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    return ImmutableList.sortedCopyOf(SPLIT_DEP_ORDERING, dependencies);
  }

  @Nullable
  private ImmutableList<String> collectTransitionKeys(
      Attribute attribute, ExtendedEventHandler eventHandler)
      throws ConfiguredValueCreationException, InterruptedException {
    TransitionFactory<AttributeTransitionData> transitionFactory = attribute.getTransitionFactory();
    if (transitionFactory.isSplit()) {
      AttributeTransitionData transitionData =
          AttributeTransitionData.builder()
              .attributes(
                  ConfiguredAttributeMapper.of(
                      ctgValue.getTarget().getAssociatedRule(),
                      configConditions,
                      ctgValue.getConfiguration()))
              .build();
      ConfigurationTransition baseTransition = transitionFactory.create(transitionData);
      Map<String, BuildOptions> toOptions;
      try {
        toOptions = applyTransitionWithSkyframe(baseTransition, eventHandler);
        if (toOptions == null) {
          return null; // Need more Skyframe deps for a Starlark transition.
        }
      } catch (TransitionException e) {
        throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
      }
      if (!SplitTransition.equals(getCurrentConfiguration().getOptions(), toOptions.values())) {
        return ImmutableList.copyOf(toOptions.keySet());
      }
    }

    return ImmutableList.of();
  }

  /**
   * Applies a configuration transition over a set of build options.
   *
   * <p>This is only for callers that can't use {@link #applyTransitionWithSkyframe}. The difference
   * is {@link #applyTransitionWithSkyframe} internally computes {@code details} with Skyframe,
   * while this version requires it as a precomputed input.
   *
   * <p>prework - load all default values for reading build settings in Starlark transitions (by
   * design, {@link BuildOptions} never holds default values of build settings)
   *
   * <p>postwork - replay events/throw errors from transition implementation function and validate
   * the outputs of the transition. This only applies to Starlark transitions.
   *
   * @return the build options for the transitioned configuration.
   */
  public static Map<String, BuildOptions> applyTransitionWithoutSkyframe(
      BuildOptions fromOptions,
      ConfigurationTransition transition,
      StarlarkBuildSettingsDetailsValue details,
      ExtendedEventHandler eventHandler,
      StarlarkTransitionCache starlarkTransitionCache)
      throws TransitionException, InterruptedException {
    if (StarlarkTransition.doesStarlarkTransition(transition)) {
      return starlarkTransitionCache.computeIfAbsent(
          fromOptions, transition, details, eventHandler);
    }
    return transition.apply(TransitionUtil.restrict(transition, fromOptions), eventHandler);
  }

  /**
   * Applies a configuration transition over a set of build options.
   *
   * <p>Callers should use this over {@link #applyTransitionWithoutSkyframe}. Unlike that variation,
   * this would may return null if it needs more Skyframe deps.
   *
   * <p>postwork - replay events/throw errors from transition implementation function and validate
   * the outputs of the transition. This only applies to Starlark transitions.
   *
   * @return the build options for the transitioned configuration, or null if Skyframe dependencies
   *     for build_setting default values for Starlark transitions. These can be read from their
   *     respective packages.
   */
  @Nullable
  private Map<String, BuildOptions> applyTransitionWithSkyframe(
      ConfigurationTransition transition, ExtendedEventHandler eventHandler)
      throws TransitionException, InterruptedException {
    BuildOptions fromOptions = getCurrentConfiguration().getOptions();
    if (StarlarkTransition.doesStarlarkTransition(transition)) {
      StarlarkBuildSettingsDetailsValue details =
          getStarlarkBuildSettingsDetailsValue(transition, env);
      if (details == null) {
        return null;
      }
      return starlarkTransitionCache.computeIfAbsent(
          fromOptions, transition, details, eventHandler);
    }
    return transition.apply(TransitionUtil.restrict(transition, fromOptions), eventHandler);
  }

  /**
   * Must be in sync with {@link
   * com.google.devtools.build.lib.skyframe.SkyframeExecutor#getStarlarkBuildSettingsDetailsValue}
   */
  @Nullable
  private static StarlarkBuildSettingsDetailsValue getStarlarkBuildSettingsDetailsValue(
      ConfigurationTransition transition, SkyFunction.LookupEnvironment env)
      throws TransitionException, InterruptedException {
    ImmutableSet<Label> starlarkBuildSettings =
        StarlarkTransition.getAllStarlarkBuildSettings(transition);
    // Quick escape if transition doesn't use any Starlark build settings
    if (starlarkBuildSettings.isEmpty()) {
      return StarlarkBuildSettingsDetailsValue.EMPTY;
    }
    // Evaluate the key into StarlarkBuildSettingsDetailsValue
    return (StarlarkBuildSettingsDetailsValue)
        env.getValueOrThrow(
            StarlarkBuildSettingsDetailsValue.key(starlarkBuildSettings),
            TransitionException.class);
  }

  /**
   * This method allows resolution of configurations outside of a skyfunction call.
   *
   * <p>Unlike {@link #resolveConfigurations}, this doesn't expect the current context to be
   * evaluating dependencies of a parent target. So this method is also suitable for top-level
   * targets.
   *
   * <p>Resolution consists of applying the per-target transitions specified in {@code
   * targetsToEvaluate}. This can be used, e.g., to apply {@link
   * com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory}s over global
   * top-level configurations.
   *
   * <p>Preserves the original input order (but merges duplicate nodes that might occur due to
   * top-level configuration transitions) . Uses original (untrimmed, pre-transition) configurations
   * for targets that can't be evaluated (e.g. due to loading phase errors).
   *
   * <p>This is suitable for feeding {@link
   * com.google.devtools.build.lib.analysis.ConfiguredTargetValue} keys: as general principle {@link
   * com.google.devtools.build.lib.analysis.ConfiguredTarget}s should have exactly as much
   * information in their configurations as they need to evaluate and no more (e.g. there's no need
   * for Android settings in a C++ configured target).
   *
   * @param defaultContext the original targets and starting configurations before applying rule
   *     transitions and trimming. When actual configurations can't be evaluated, these values are
   *     returned as defaults. See TODO below.
   * @param targetsToEvaluate the inputs repackaged as dependencies, including rule-specific
   *     transitions
   * @param eventHandler the error event handler
   * @param configurationsCollector the collector which finds configurations for dependencies
   */
  // TODO(bazel-team): error out early for targets that fail - failed configuration evaluations
  //   should never make it through analysis (and especially not seed ConfiguredTargetValues)
  // TODO(gregce): merge this more with resolveConfigurations? One crucial difference is
  //   resolveConfigurations can null-return on missing deps since it executes inside Skyfunctions.
  public static TopLevelTargetsAndConfigsResult getConfigurationsFromExecutor(
      Iterable<TargetAndConfiguration> defaultContext,
      Multimap<BuildConfigurationValue, DependencyKey> targetsToEvaluate,
      ExtendedEventHandler eventHandler,
      ConfigurationsCollector configurationsCollector)
      throws InvalidConfigurationException, InterruptedException {

    Map<Label, Target> labelsToTargets = new HashMap<>();
    for (TargetAndConfiguration targetAndConfig : defaultContext) {
      labelsToTargets.put(targetAndConfig.getLabel(), targetAndConfig.getTarget());
    }

    // Maps <target, originalConfig> pairs to <target, finalConfig> pairs for targets that
    // could be successfully Skyframe-evaluated.
    Map<TargetAndConfiguration, TargetAndConfiguration> successfullyEvaluatedTargets =
        new LinkedHashMap<>();
    boolean hasError = false;
    if (!targetsToEvaluate.isEmpty()) {
      for (BuildConfigurationValue fromConfig : targetsToEvaluate.keySet()) {
        ConfigurationsResult configurationsResult =
            configurationsCollector.getConfigurations(
                eventHandler, fromConfig.getOptions(), targetsToEvaluate.get(fromConfig));
        hasError |= configurationsResult.hasError();
        for (Map.Entry<BaseDependencySpecification, BuildConfigurationValue> evaluatedTarget :
            configurationsResult.getConfigurationMap().entries()) {
          Target target = labelsToTargets.get(evaluatedTarget.getKey().getLabel());
          successfullyEvaluatedTargets.put(
              new TargetAndConfiguration(target, fromConfig),
              new TargetAndConfiguration(target, evaluatedTarget.getValue()));
        }
      }
    }

    LinkedHashSet<TargetAndConfiguration> result = new LinkedHashSet<>();
    for (TargetAndConfiguration originalInput : defaultContext) {
      // If the configuration couldn't be determined (e.g. loading phase error), use the original.
      result.add(successfullyEvaluatedTargets.getOrDefault(originalInput, originalInput));
    }
    return new TopLevelTargetsAndConfigsResult(result, hasError);
  }

  /**
   * The result of {@link #getConfigurationsFromExecutor} which also registers if an error was
   * recorded.
   */
  public static class TopLevelTargetsAndConfigsResult {
    private final Collection<TargetAndConfiguration> configurations;
    private final boolean hasError;

    public TopLevelTargetsAndConfigsResult(
        Collection<TargetAndConfiguration> configurations, boolean hasError) {
      this.configurations = configurations;
      this.hasError = hasError;
    }

    public boolean hasError() {
      return hasError;
    }

    public Collection<TargetAndConfiguration> getTargetsAndConfigs() {
      return configurations;
    }
  }
}
