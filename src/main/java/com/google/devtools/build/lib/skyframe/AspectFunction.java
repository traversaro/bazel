// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.analysis.AspectResolutionHelpers.aspectMatchesConfiguredTarget;
import static com.google.devtools.build.lib.skyframe.PrerequisiteProducer.createDefaultToolchainContextKey;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment.MissingDepException;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DuplicateException;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget;
import com.google.devtools.build.lib.analysis.producers.DependencyContext;
import com.google.devtools.build.lib.analysis.producers.DependencyContextProducer;
import com.google.devtools.build.lib.analysis.producers.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsInputs;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.memory.CurrentRuleTracker;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.BuildViewProvider;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.build.skyframe.state.Driver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * The Skyframe function that generates aspects.
 *
 * <p>This class, together with {@link ConfiguredTargetFunction} drives the analysis phase. For more
 * information, see {@link com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 *
 * <p>{@link AspectFunction} takes a SkyKey containing an {@link AspectKey} [a tuple of (target
 * label, configurations, aspect class and aspect parameters)], loads an {@link Aspect} from aspect
 * class and aspect parameters, gets a {@link ConfiguredTarget} for label and configurations, and
 * then creates a {@link ConfiguredAspect} for a given {@link AspectKey}.
 *
 * <p>See {@link com.google.devtools.build.lib.packages.AspectClass} documentation for an overview
 * of aspect-related classes
 *
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 * @see com.google.devtools.build.lib.packages.AspectClass
 */
final class AspectFunction implements SkyFunction {
  private final BuildViewProvider buildViewProvider;
  private final RuleClassProvider ruleClassProvider;
  /**
   * Indicates whether the set of packages transitively loaded for a given {@link AspectValue} will
   * be needed later (see {@link
   * com.google.devtools.build.lib.analysis.ConfiguredObjectValue#getTransitivePackages}). If not,
   * they are not collected and stored.
   */
  private final boolean storeTransitivePackages;

  AspectFunction(
      BuildViewProvider buildViewProvider,
      RuleClassProvider ruleClassProvider,
      boolean storeTransitivePackages) {
    this.buildViewProvider = buildViewProvider;
    this.ruleClassProvider = ruleClassProvider;
    this.storeTransitivePackages = storeTransitivePackages;
  }

  static class State implements SkyKeyComputeState {
    /** Null if AspectFuncton is not storing this information. */
    @Nullable NestedSetBuilder<Package> transitivePackages;

    NestedSetBuilder<Cause> transitiveRootCauses = NestedSetBuilder.stableOrder();

    @Nullable InitialValues initialValues;

    PrerequisiteProducer.State computeDependenciesState = new PrerequisiteProducer.State();

    State(boolean storeTransitivePackages) {
      this.transitivePackages = storeTransitivePackages ? NestedSetBuilder.stableOrder() : null;
    }
  }

  private static class InitialValues {
    @Nullable private final Aspect aspect;
    @Nullable private final ConfiguredAspectFactory aspectFactory;
    private final ConfiguredTarget baseConfiguredTarget;

    private InitialValues(
        @Nullable Aspect aspect,
        @Nullable ConfiguredAspectFactory aspectFactory,
        ConfiguredTarget baseConfiguredTarget) {
      this.aspect = aspect;
      this.aspectFactory = aspectFactory;
      this.baseConfiguredTarget = baseConfiguredTarget;
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws AspectFunctionException, InterruptedException {
    AspectKey key = (AspectKey) skyKey.argument();
    State state = env.getState(() -> new State(storeTransitivePackages));

    PrerequisiteProducer.State computeDependenciesState = state.computeDependenciesState;
    if (state.initialValues == null) {
      InitialValues initialValues = getInitialValues(computeDependenciesState, key, env);
      if (initialValues == null) {
        return null;
      }
      state.initialValues = initialValues;
    }
    Aspect aspect = state.initialValues.aspect;
    ConfiguredAspectFactory aspectFactory = state.initialValues.aspectFactory;
    ConfiguredTarget associatedTarget = state.initialValues.baseConfiguredTarget;
    TargetAndConfiguration targetAndConfiguration = computeDependenciesState.targetAndConfiguration;
    Target target = targetAndConfiguration.getTarget();
    BuildConfigurationValue configuration = targetAndConfiguration.getConfiguration();

    // If the target is incompatible, then there's not much to do. The intent here is to create an
    // AspectValue that doesn't trigger any of the associated target's dependencies to be evaluated
    // against this aspect.
    if (associatedTarget.get(IncompatiblePlatformProvider.PROVIDER) != null
        ||
        // Similarly, aspects that propagate into post-NoConfigTransition targets can't access
        // most flags or dependencies and are likely to be unsound. So make aspects propagating to
        // these configurations no-ops.
        (configuration != null && configuration.getOptions().hasNoConfig())) {
      return new AspectValue(
          key,
          aspect,
          target.getLocation(),
          ConfiguredAspect.forNonapplicableTarget(),
          state.transitivePackages == null ? null : state.transitivePackages.build());
    }

    if (AliasProvider.isAlias(associatedTarget)) {
      return createAliasAspect(env, targetAndConfiguration, aspect, key, associatedTarget);
    }
    // If we get here, label should match original label, and therefore the target we looked up
    // above indeed corresponds to associatedTarget.getLabel().
    Preconditions.checkState(
        associatedTarget.getOriginalLabel().equals(associatedTarget.getLabel()),
        "Non-alias %s should have matching label but found %s",
        associatedTarget.getOriginalLabel(),
        associatedTarget.getLabel());

    // If the incompatible flag is set, the top-level aspect should not be applied on top-level
    // targets whose rules do not advertise the aspect's required providers. The aspect should not
    // also propagate to these targets dependencies.
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    boolean checkRuleAdvertisedProviders =
        starlarkSemantics.getBool(
            BuildLanguageOptions.INCOMPATIBLE_TOP_LEVEL_ASPECTS_REQUIRE_PROVIDERS);
    if (checkRuleAdvertisedProviders) {
      if (target instanceof Rule) {
        if (!aspect
            .getDefinition()
            .getRequiredProviders()
            .isSatisfiedBy(((Rule) target).getRuleClassObject().getAdvertisedProviders())) {
          return new AspectValue(
              key,
              aspect,
              target.getLocation(),
              ConfiguredAspect.forNonapplicableTarget(),
              /*transitivePackages=*/ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
        }
      }
    }

    ImmutableList<Aspect> topologicalAspectPath;
    if (key.getBaseKeys().isEmpty()) {
      topologicalAspectPath = ImmutableList.of(aspect);
    } else {
      LinkedHashSet<AspectKey> orderedKeys = new LinkedHashSet<>();
      collectAspectKeysInTopologicalOrder(key.getBaseKeys(), orderedKeys);
      SkyframeLookupResult aspectValues = env.getValuesAndExceptions(orderedKeys);
      if (env.valuesMissing()) {
        return null;
      }
      ImmutableList.Builder<Aspect> topologicalAspectPathBuilder =
          ImmutableList.builderWithExpectedSize(orderedKeys.size() + 1);
      for (AspectKey aspectKey : orderedKeys) {
        AspectValue aspectValue = (AspectValue) aspectValues.get(aspectKey);
        if (aspectValue == null) {
          BugReport.logUnexpected(
              "aspectValue for: '%s' was missing, this should never happen", aspectKey);
          return null;
        }
        topologicalAspectPathBuilder.add(aspectValue.getAspect());
      }
      topologicalAspectPath = topologicalAspectPathBuilder.add(aspect).build();

      List<ConfiguredAspect> directlyRequiredAspects =
          Lists.transform(
              key.getBaseKeys(), k -> ((AspectValue) aspectValues.get(k)).getConfiguredAspect());
      try {
        associatedTarget = MergedConfiguredTarget.of(associatedTarget, directlyRequiredAspects);
      } catch (DuplicateException e) {
        env.getListener().handle(Event.error(target.getLocation(), e.getMessage()));
        throw new AspectFunctionException(
            new AspectCreationException(e.getMessage(), target.getLabel(), configuration));
      }
    }

    try {
      var transitiveState =
          new TransitiveDependencyState(
              state.transitiveRootCauses,
              state.transitivePackages,
              /* prerequisitePackages= */ null);
      var dependencyContext =
          getDependencyContext(computeDependenciesState, key, aspect, transitiveState, env);
      if (dependencyContext == null) {
        return null;
      }

      ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts =
          dependencyContext.unloadedToolchainContexts();
      ConfigConditions configConditions = dependencyContext.configConditions();

      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap;
      try {
        depValueMap =
            PrerequisiteProducer.computeDependencies(
                computeDependenciesState,
                topologicalAspectPath,
                configConditions.asProviders(),
                unloadedToolchainContexts == null
                    ? null
                    : unloadedToolchainContexts.asToolchainContexts(),
                ruleClassProvider,
                buildViewProvider.getSkyframeBuildView(),
                transitiveState,
                env);
      } catch (ConfiguredValueCreationException e) {
        throw new AspectCreationException(
            e.getMessage(), key.getLabel(), configuration, e.getDetailedExitCode());
      }
      if (depValueMap == null) {
        return null;
      }
      if (!state.transitiveRootCauses.isEmpty()) {
        NestedSet<Cause> causes = state.transitiveRootCauses.build();
        throw new AspectFunctionException(
            new AspectCreationException(
                "Loading failed",
                causes,
                ConfiguredTargetFunction.getPrioritizedDetailedExitCode(causes)));
      }

      // Load the requested toolchains into the ToolchainContext, now that we have dependencies.
      ToolchainCollection<ResolvedToolchainContext> toolchainContexts = null;
      if (unloadedToolchainContexts != null) {
        String targetDescription =
            "aspect " + aspect.getDescriptor().getDescription() + " applied to " + target;
        ToolchainCollection.Builder<ResolvedToolchainContext> contextsBuilder =
            ToolchainCollection.builder();
        for (Map.Entry<String, UnloadedToolchainContext> unloadedContext :
            unloadedToolchainContexts.getContextMap().entrySet()) {
          ImmutableSet<ConfiguredTargetAndData> toolchainDependencies =
              ImmutableSet.copyOf(
                  depValueMap.get(DependencyKind.forExecGroup(unloadedContext.getKey())));
          contextsBuilder.addContext(
              unloadedContext.getKey(),
              ResolvedToolchainContext.load(
                  unloadedContext.getValue(), targetDescription, toolchainDependencies));
        }
        toolchainContexts = contextsBuilder.build();
      }

      return createAspect(
          env,
          key,
          topologicalAspectPath,
          aspect,
          aspectFactory,
          target,
          associatedTarget,
          configuration,
          configConditions,
          toolchainContexts,
          computeDependenciesState.execGroupCollectionBuilder,
          depValueMap,
          state.transitivePackages);
    } catch (DependencyEvaluationException e) {
      // TODO(bazel-team): consolidate all env.getListener().handle() calls in this method, like in
      // ConfiguredTargetFunction. This encourages clear, consistent user messages (ideally without
      // the programmer having to think about it).
      if (!e.depReportedOwnError()) {
        env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      }
      if (e.getCause() instanceof ConfiguredValueCreationException) {
        ConfiguredValueCreationException cause = (ConfiguredValueCreationException) e.getCause();
        throw new AspectFunctionException(
            new AspectCreationException(
                cause.getMessage(), cause.getRootCauses(), cause.getDetailedExitCode()));
      } else if (e.getCause() instanceof InconsistentAspectOrderException) {
        InconsistentAspectOrderException cause = (InconsistentAspectOrderException) e.getCause();
        env.getListener().handle(Event.error(cause.getLocation(), cause.getMessage()));
        throw new AspectFunctionException(
            new AspectCreationException(cause.getMessage(), key.getLabel(), configuration));
      } else if (e.getCause() instanceof TransitionException) {
        TransitionException cause = (TransitionException) e.getCause();
        throw new AspectFunctionException(
            new AspectCreationException(cause.getMessage(), key.getLabel(), configuration));
      } else {
        // Cast to InvalidConfigurationException as a consistency check. If you add any
        // DependencyEvaluationException constructors, you may need to change this code, too.
        InvalidConfigurationException cause = (InvalidConfigurationException) e.getCause();
        throw new AspectFunctionException(
            new AspectCreationException(
                cause.getMessage(), key.getLabel(), configuration, cause.getDetailedExitCode()));
      }
    } catch (AspectCreationException e) {
      throw new AspectFunctionException(e);
    } catch (ConfiguredValueCreationException e) {
      throw new AspectFunctionException(e);
    } catch (ToolchainException e) {
      throw new AspectFunctionException(
          new AspectCreationException(
              e.getMessage(), new LabelCause(key.getLabel(), e.getDetailedExitCode())));
    }
  }

  /** Populates {@code state.execGroupCollection} as a side effect. */
  @Nullable // Null if a Skyframe restart is needed.
  private static DependencyContext getDependencyContext(
      PrerequisiteProducer.State state,
      AspectKey key,
      Aspect aspect,
      TransitiveDependencyState transitiveState,
      Environment env)
      throws InterruptedException, ConfiguredValueCreationException, ToolchainException {
    if (state.dependencyContext != null) {
      return state.dependencyContext;
    }
    if (state.dependencyContextProducer == null) {
      TargetAndConfiguration targetAndConfiguration = state.targetAndConfiguration;
      UnloadedToolchainContextsInputs unloadedToolchainContextsInputs =
          getUnloadedToolchainContextsInputs(
              aspect.getDefinition(),
              key.getConfigurationKey(),
              targetAndConfiguration.getConfiguration());
      state.execGroupCollectionBuilder = unloadedToolchainContextsInputs;
      state.dependencyContextProducer =
          new Driver(
              new DependencyContextProducer(
                  unloadedToolchainContextsInputs,
                  targetAndConfiguration,
                  transitiveState,
                  (DependencyContextProducer.ResultSink) state));
    }
    if (state.dependencyContextProducer.drive(env, env.getListener())) {
      state.dependencyContextProducer = null;
    }

    // During error bubbling, the state machine might not be done, but still emit an error.
    var error = state.dependencyContextError;
    if (error != null) {
      switch (error.kind()) {
        case TOOLCHAIN:
          throw error.toolchain();
        case CONFIGURED_VALUE_CREATION:
          throw error.configuredValueCreation();
        case INCOMPATIBLE_TARGET:
          throw new IllegalStateException("Unexpected error: " + error.incompatibleTarget());
        case VALIDATION:
          throw new IllegalStateException("Unexpected error: " + error.validation());
      }
      throw new IllegalStateException("unreachable");
    }

    return state.dependencyContext; // Null if not yet done.
  }

  static BzlLoadValue.Key bzlLoadKeyForStarlarkAspect(StarlarkAspectClass starlarkAspectClass) {
    Label extensionLabel = starlarkAspectClass.getExtensionLabel();
    return StarlarkBuiltinsValue.isBuiltinsRepo(extensionLabel.getRepository())
        ? BzlLoadValue.keyForBuiltins(extensionLabel)
        : BzlLoadValue.keyForBuild(extensionLabel);
  }

  @Nullable
  private static InitialValues getInitialValues(
      PrerequisiteProducer.State state, AspectKey key, Environment env)
      throws AspectFunctionException, InterruptedException {
    ActionLookupKey configuredTargetLookupKey = key.getBaseConfiguredTargetKey().toKey();
    PackageIdentifier basePackageKey =
        key.getBaseConfiguredTargetKey().getLabel().getPackageIdentifier();
    var initialKeys =
        ImmutableSet.<SkyKey>builder().add(configuredTargetLookupKey).add(basePackageKey);

    BuildConfigurationKey configurationKey = key.getConfigurationKey();
    if (configurationKey != null) {
      initialKeys.add(configurationKey);
    }

    StarlarkAspectClass starlarkAspectClass;
    BzlLoadValue.Key bzlLoadKey;
    if (key.getAspectClass() instanceof NativeAspectClass) {
      starlarkAspectClass = null;
      bzlLoadKey = null;
    } else {
      Preconditions.checkState(
          key.getAspectClass() instanceof StarlarkAspectClass, "Unknown aspect class: %s", key);
      starlarkAspectClass = (StarlarkAspectClass) key.getAspectClass();
      initialKeys.add(bzlLoadKey = bzlLoadKeyForStarlarkAspect(starlarkAspectClass));
    }

    SkyframeLookupResult initialValues = env.getValuesAndExceptions(initialKeys.build());
    if (env.valuesMissing()) {
      return null;
    }

    ConfiguredTarget baseConfiguredTarget;
    try {
      var baseConfiguredTargetValue =
          (ConfiguredTargetValue)
              initialValues.getOrThrow(
                  configuredTargetLookupKey, ConfiguredValueCreationException.class);
      if (baseConfiguredTargetValue == null) {
        BugReport.logUnexpected(
            "Unexpected exception with %s and AspectKey %s", key.getBaseConfiguredTargetKey(), key);
        return null;
      }
      baseConfiguredTarget = baseConfiguredTargetValue.getConfiguredTarget();
    } catch (ConfiguredValueCreationException e) {
      throw new AspectFunctionException(
          new AspectCreationException(e.getMessage(), e.getRootCauses(), e.getDetailedExitCode()));
    }
    Preconditions.checkState(
        Objects.equals(key.getConfigurationKey(), baseConfiguredTarget.getConfigurationKey()),
        "Aspect not in same configuration as base configured target: %s, %s",
        key,
        baseConfiguredTarget);

    // Keep this in sync with the same code in ConfiguredTargetFunction.
    Package basePackage = ((PackageValue) initialValues.get(basePackageKey)).getPackage();
    if (basePackage.containsErrors()) {
      throw new AspectFunctionException(
          new BuildFileContainsErrorsException(key.getLabel().getPackageIdentifier()));
    }
    Target target;
    try {
      target = basePackage.getTarget(baseConfiguredTarget.getOriginalLabel().getName());
    } catch (NoSuchTargetException e) {
      throw new IllegalStateException("Name already verified", e);
    }

    BuildConfigurationValue configuration =
        configurationKey == null
            ? null
            : (BuildConfigurationValue) initialValues.get(configurationKey);

    state.targetAndConfiguration = new TargetAndConfiguration(target, configuration);

    ConfiguredAspectFactory aspectFactory;
    Aspect aspect;
    if (bzlLoadKey == null) {
      NativeAspectClass nativeAspectClass = (NativeAspectClass) key.getAspectClass();
      aspectFactory = (ConfiguredAspectFactory) nativeAspectClass;
      aspect = Aspect.forNative(nativeAspectClass, key.getParameters());
    } else {
      StarlarkDefinedAspect starlarkAspect;
      try {
        BzlLoadValue bzlLoadvalue;
        try {
          bzlLoadvalue =
              (BzlLoadValue) initialValues.getOrThrow(bzlLoadKey, BzlLoadFailedException.class);
          if (bzlLoadvalue == null) {
            BugReport.logUnexpected(
                "Unexpected exception with %s and AspectKey %s", bzlLoadKey, key);
            return null;
          }
        } catch (BzlLoadFailedException e) {
          throw new AspectCreationException(
              e.getMessage(), starlarkAspectClass.getExtensionLabel(), e.getDetailedExitCode());
        }
        starlarkAspect = loadAspectFromBzl(starlarkAspectClass, bzlLoadvalue);
      } catch (AspectCreationException e) {
        env.getListener().handle(Event.error(e.getMessage()));
        throw new AspectFunctionException(e);
      }
      aspectFactory = new StarlarkAspectFactory(starlarkAspect);
      aspect =
          Aspect.forStarlark(
              starlarkAspect.getAspectClass(),
              starlarkAspect.getDefinition(key.getParameters()),
              key.getParameters());
    }

    return new InitialValues(aspect, aspectFactory, baseConfiguredTarget);
  }

  /**
   * Loads a Starlark-defined aspect from an extension file.
   *
   * @throws AspectCreationException if the value loaded is not a {@link StarlarkDefinedAspect}
   */
  static StarlarkDefinedAspect loadAspectFromBzl(
      StarlarkAspectClass starlarkAspectClass, BzlLoadValue bzlLoadValue)
      throws AspectCreationException {
    Label extensionLabel = starlarkAspectClass.getExtensionLabel();
    String starlarkValueName = starlarkAspectClass.getExportedName();
    Object starlarkValue = bzlLoadValue.getModule().getGlobal(starlarkValueName);
    if (!(starlarkValue instanceof StarlarkDefinedAspect)) {
      throw new AspectCreationException(
          String.format(
              starlarkValue == null ? "%s is not exported from %s" : "%s from %s is not an aspect",
              starlarkValueName,
              extensionLabel),
          extensionLabel);
    }
    return (StarlarkDefinedAspect) starlarkValue;
  }

  @Nullable
  private static UnloadedToolchainContextsInputs getUnloadedToolchainContextsInputs(
      AspectDefinition aspectDefinition,
      @Nullable BuildConfigurationKey configurationKey,
      @Nullable BuildConfigurationValue configuration) {
    if (configuration == null) {
      // Configuration can be null in the case of aspects applied to input files. In this case,
      // there are no toolchains being used.
      return UnloadedToolchainContextsInputs.empty();
    }

    boolean useAutoExecGroups = shouldUseAutoExecGroups(aspectDefinition, configuration);
    var processedExecGroups =
        ExecGroupCollection.process(
            aspectDefinition.execGroups(),
            aspectDefinition.execCompatibleWith(),
            aspectDefinition.getToolchainTypes(),
            useAutoExecGroups);
    // Note: `configuration.getOptions().hasNoConfig()` is handled early in #compute.
    return UnloadedToolchainContextsInputs.create(
        processedExecGroups,
        createDefaultToolchainContextKey(
            configurationKey,
            aspectDefinition.execCompatibleWith(),
            /* debugTarget= */ false,
            /* useAutoExecGroups= */ useAutoExecGroups,
            aspectDefinition.getToolchainTypes(),
            /* parentExecutionPlatformLabel= */ null));
  }

  private static boolean shouldUseAutoExecGroups(
      AspectDefinition aspectDefinition, BuildConfigurationValue configuration) {
    ImmutableMap<String, Attribute> aspectAttributes = aspectDefinition.getAttributes();
    if (aspectAttributes.containsKey("$use_auto_exec_groups")) {
      return (boolean) aspectAttributes.get("$use_auto_exec_groups").getDefaultValueUnchecked();
    }
    return configuration.useAutoExecGroups();
  }

  /**
   * Collects {@link AspectKey} dependencies by performing a postorder traversal over {@link
   * AspectKey#getBaseKeys}.
   *
   * <p>The resulting set of {@code orderedKeys} is topologically ordered: each aspect key appears
   * after all of its dependencies.
   */
  private static void collectAspectKeysInTopologicalOrder(
      List<AspectKey> baseKeys, LinkedHashSet<AspectKey> orderedKeys) {
    for (AspectKey key : baseKeys) {
      if (!orderedKeys.contains(key)) {
        collectAspectKeysInTopologicalOrder(key.getBaseKeys(), orderedKeys);
        orderedKeys.add(key);
      }
    }
  }

  /**
   * Computes the given aspectKey of an alias-like target, by depending on the corresponding key of
   * the next target in the alias chain (if there are more), or the "real" configured target.
   */
  @Nullable
  private AspectValue createAliasAspect(
      Environment env,
      TargetAndConfiguration targetAndConfiguration,
      Aspect aspect,
      AspectKey originalKey,
      ConfiguredTarget baseConfiguredTarget)
      throws InterruptedException {
    ImmutableList<Label> aliasChain =
        baseConfiguredTarget.getProvider(AliasProvider.class).getAliasChain();

    AspectKey actualKey;
    if (aliasChain.size() > 1) {
      // If there is another alias in the chain, follows it, creating the next alias aspect.
      actualKey =
          buildAliasAspectKey(
              originalKey, aliasChain.get(1), baseConfiguredTarget.getConfigurationKey());
    } else {
      // Otherwise, creates an aspect of the real configured target using its real configuration key
      // which includes any transitions.
      actualKey =
          buildAliasAspectKey(
              originalKey,
              baseConfiguredTarget.getLabel(),
              baseConfiguredTarget.getActual().getConfigurationKey());
    }

    return createAliasAspect(
        env,
        targetAndConfiguration.getTarget(),
        originalKey,
        aspect,
        actualKey,
        storeTransitivePackages ? NestedSetBuilder.stableOrder() : null);
  }

  @Nullable
  private static AspectValue createAliasAspect(
      Environment env,
      Target originalTarget,
      AspectKey originalKey,
      Aspect aspect,
      AspectKey depKey,
      @Nullable NestedSetBuilder<Package> transitivePackages)
      throws InterruptedException {
    // Compute the AspectValue of the target the alias refers to (which can itself be either an
    // alias or a real target)
    AspectValue real = (AspectValue) env.getValue(depKey);
    if (env.valuesMissing()) {
      return null;
    }

    NestedSet<Package> finalTransitivePackages = null;
    if (transitivePackages != null) {
      finalTransitivePackages =
          transitivePackages
              .addTransitive(Preconditions.checkNotNull(real.getTransitivePackages()))
              .add(originalTarget.getPackage())
              .build();
    }
    return new AspectValue(
        originalKey,
        aspect,
        originalTarget.getLocation(),
        ConfiguredAspect.forAlias(real.getConfiguredAspect()),
        finalTransitivePackages);
  }

  private static AspectKey buildAliasAspectKey(
      AspectKey originalKey, Label aliasLabel, BuildConfigurationKey configurationKey) {
    ImmutableList<AspectKey> aliasedBaseKeys =
        originalKey.getBaseKeys().stream()
            .map(baseKey -> buildAliasAspectKey(baseKey, aliasLabel, configurationKey))
            .collect(toImmutableList());
    return AspectKeyCreator.createAspectKey(
        originalKey.getAspectDescriptor(),
        aliasedBaseKeys,
        ConfiguredTargetKey.builder()
            .setLabel(aliasLabel)
            .setConfigurationKey(configurationKey)
            .build());
  }

  @Nullable
  private AspectValue createAspect(
      Environment env,
      AspectKey key,
      ImmutableList<Aspect> topologicalAspectPath,
      Aspect aspect,
      ConfiguredAspectFactory aspectFactory,
      Target associatedTarget,
      ConfiguredTarget associatedConfiguredTarget,
      BuildConfigurationValue configuration,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      @Nullable ExecGroupCollection.Builder execGroupCollectionBuilder,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> directDeps,
      @Nullable NestedSetBuilder<Package> transitivePackages)
      throws AspectFunctionException, InterruptedException {
    // Should be successfully evaluated and cached from the loading phase.
    StarlarkBuiltinsValue starlarkBuiltinsValue =
        (StarlarkBuiltinsValue) env.getValue(StarlarkBuiltinsValue.key());
    if (env.valuesMissing()) {
      return null;
    }

    SkyframeBuildView view = buildViewProvider.getSkyframeBuildView();

    StoredEventHandler events = new StoredEventHandler();
    CachingAnalysisEnvironment analysisEnvironment =
        view.createAnalysisEnvironment(key, events, env, configuration, starlarkBuiltinsValue);

    ConfiguredAspect configuredAspect;
    if (aspect.getDefinition().applyToGeneratingRules() && associatedTarget instanceof OutputFile) {
      OutputFile outputFile = (OutputFile) associatedTarget;
      Label label = outputFile.getGeneratingRule().getLabel();
      return createAliasAspect(
          env, associatedTarget, key, aspect, key.withLabel(label), transitivePackages);
    } else if (aspectMatchesConfiguredTarget(
        associatedConfiguredTarget, associatedTarget instanceof Rule, aspect)) {
      try {
        CurrentRuleTracker.beginConfiguredAspect(aspect.getAspectClass());
        configuredAspect =
            view.getConfiguredTargetFactory()
                .createAspect(
                    analysisEnvironment,
                    associatedTarget,
                    associatedConfiguredTarget,
                    topologicalAspectPath,
                    aspectFactory,
                    aspect,
                    directDeps,
                    configConditions,
                    toolchainContexts,
                    execGroupCollectionBuilder,
                    configuration,
                    transitivePackages == null ? null : transitivePackages.build(),
                    key);
      } catch (MissingDepException e) {
        Preconditions.checkState(env.valuesMissing());
        return null;
      } catch (ActionConflictException e) {
        throw new AspectFunctionException(e);
      } catch (InvalidExecGroupException e) {
        throw new AspectFunctionException(e);
      } finally {
        CurrentRuleTracker.endConfiguredAspect();
      }
    } else {
      configuredAspect = ConfiguredAspect.forNonapplicableTarget();
    }

    events.replayOn(env.getListener());
    if (events.hasErrors()) {
      analysisEnvironment.disable(associatedTarget);
      String msg = "Analysis of target '" + associatedTarget.getLabel() + "' failed";
      throw new AspectFunctionException(
          new AspectCreationException(msg, key.getLabel(), configuration));
    }
    Preconditions.checkState(!analysisEnvironment.hasErrors(),
        "Analysis environment hasError() but no errors reported");

    if (env.valuesMissing()) {
      return null;
    }

    analysisEnvironment.disable(associatedTarget);
    Preconditions.checkNotNull(configuredAspect);

    return new AspectValue(
        key,
        aspect,
        associatedTarget.getLocation(),
        configuredAspect,
        transitivePackages == null ? null : transitivePackages.build());
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    AspectKey aspectKey = (AspectKey) skyKey.argument();
    return Label.print(aspectKey.getLabel());
  }

  /** Used to indicate errors during the computation of an {@link AspectValue}. */
  public static final class AspectFunctionException extends SkyFunctionException {
    public AspectFunctionException(NoSuchThingException e) {
      super(e, Transience.PERSISTENT);
    }

    public AspectFunctionException(AspectCreationException e) {
      super(e, Transience.PERSISTENT);
    }

    public AspectFunctionException(ConfiguredValueCreationException e) {
      super(e, Transience.PERSISTENT);
    }

    public AspectFunctionException(InvalidExecGroupException e) {
      super(e, Transience.PERSISTENT);
    }

    public AspectFunctionException(ActionConflictException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }
}
