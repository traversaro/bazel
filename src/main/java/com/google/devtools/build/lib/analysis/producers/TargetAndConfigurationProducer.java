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

import static com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId;

import com.google.auto.value.AutoOneOf;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.InconsistentNullConfigException;
import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Computes the target and configuration for a configured target key.
 *
 * <p>If the key has a configuration and the target is configurable, attempts to apply a rule side
 * transition. If the target is not configurable, directly transitions to the null configuration. If
 * the resulting configuration already has an owner, delegates to the owner instead of recomputing
 * the configured target.
 *
 * <p>If the key does not have a configuration, it was requested as a visibility dependency.
 * Verifies that the {@link Target} is a {@link PackageGroup}, throwing {@link
 * InvalidVisibilityDependencyException} if that's not the case.
 */
public final class TargetAndConfigurationProducer
    implements StateMachine, Consumer<SkyValue>, TargetProducer.ResultSink {
  /** Accepts results of this producer. */
  public interface ResultSink {
    void acceptTargetAndConfiguration(TargetAndConfiguration value, ConfiguredTargetKey fullKey);

    void acceptTargetAndConfigurationDelegatedValue(ConfiguredTargetValue value);

    void acceptTargetAndConfigurationError(TargetAndConfigurationError error);
  }

  /** Tagged union of possible errors. */
  @AutoOneOf(TargetAndConfigurationError.Kind.class)
  public abstract static class TargetAndConfigurationError {
    /** Tags the error type. */
    public enum Kind {
      CONFIGURED_VALUE_CREATION,
      INVALID_VISIBILITY_DEPENDENCY,
      INCONSISTENT_NULL_CONFIG
    }

    public abstract Kind kind();

    public abstract ConfiguredValueCreationException configuredValueCreation();

    public abstract InvalidVisibilityDependencyException invalidVisibilityDependency();

    public abstract InconsistentNullConfigException inconsistentNullConfig();

    private static TargetAndConfigurationError of(ConfiguredValueCreationException e) {
      return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
          .configuredValueCreation(e);
    }

    // TODO(b/261521010): enable this error once Rule transitions are removed from dependency
    // resolution.
    // private static TargetAndConfigurationError of(InvalidVisibilityDependencyException e) {
    // return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
    // .invalidVisibilityDependency(e);
    // }

    // TODO(b/261521010): delete this error once Rule transitions are removed from dependency
    // resolution.
    private static TargetAndConfigurationError of(InconsistentNullConfigException e) {
      return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
          .inconsistentNullConfig(e);
    }
  }

  // -------------------- Input --------------------
  private final ConfiguredTargetKey preRuleTransitionKey;
  @Nullable private final TransitionFactory<RuleTransitionData> trimmingTransitionFactory;
  private final StarlarkTransitionCache transitionCache;

  private final TransitiveDependencyState transitiveState;

  // -------------------- Output --------------------
  private final ResultSink sink;

  // -------------------- Internal State --------------------
  private Target target;

  public TargetAndConfigurationProducer(
      ConfiguredTargetKey preRuleTransitionKey,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory,
      StarlarkTransitionCache transitionCache,
      TransitiveDependencyState transitiveState,
      ResultSink sink) {
    this.preRuleTransitionKey = preRuleTransitionKey;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.transitionCache = transitionCache;
    this.transitiveState = transitiveState;
    this.sink = sink;
  }

  @Override
  public StateMachine step(Tasks tasks, ExtendedEventHandler listener) {
    return new TargetProducer(
        preRuleTransitionKey.getLabel(),
        transitiveState,
        (TargetProducer.ResultSink) this,
        /* runAfter= */ this::determineConfiguration);
  }

  @Override
  public void acceptTarget(Target target) {
    this.target = target;
  }

  @Override
  public void acceptTargetError(NoSuchPackageException e) {
    emitError(e.getMessage(), /* location= */ null, e.getDetailedExitCode());
  }

  @Override
  public void acceptTargetError(NoSuchTargetException e, Location location) {
    emitError(e.getMessage(), location, e.getDetailedExitCode());
  }

  private StateMachine determineConfiguration(Tasks tasks, ExtendedEventHandler listener) {
    if (target == null) {
      return DONE; // There was an error.
    }

    // TODO(b/261521010): after removing the rule transition from dependency resolution, remove
    // this. It won't be possible afterwards because null configuration keys will only be used for
    // visibility dependencies.
    BuildConfigurationKey configurationKey = preRuleTransitionKey.getConfigurationKey();
    if (configurationKey == null) {
      if (target.isConfigurable()) {
        // We somehow ended up in a target that requires a non-null configuration but with a key
        // that doesn't have a configuration. This is always an error, but we need to analyze the
        // dependencies of the latter target to realize that. Short-circuit the evaluation to avoid
        // doing useless work and running code with a null configuration that's not prepared for it.
        sink.acceptTargetAndConfigurationError(
            TargetAndConfigurationError.of(new InconsistentNullConfigException()));
        return DONE;
      }
      // TODO(b/261521010): after removing the rule transition from dependency resolution, the logic
      // here changes.
      //
      // A null configuration key will only be used for visibility dependencies so when that's
      // true, a check that the target is a PackageGroup will be performed, throwing
      // InvalidVisibilityDependencyException on failure.
      //
      // The ConfiguredTargetKey cannot fan-in in this case.
      sink.acceptTargetAndConfiguration(
          new TargetAndConfiguration(target, /* configuration= */ null), preRuleTransitionKey);
      return DONE;
    }

    // This may happen for top-level ConfiguredTargets.
    //
    // TODO(b/261521010): this may also happen for targets that are not top-level after removing
    // rule transitions from dependency resolution. Update this comment.
    if (!target.isConfigurable()) {
      var nullConfiguredTargetKey =
          ConfiguredTargetKey.builder().setDelegate(preRuleTransitionKey).build();
      ActionLookupKey delegate = nullConfiguredTargetKey.toKey();
      if (!delegate.equals(preRuleTransitionKey)) {
        // Delegates to the key that already owns the null configuration.
        delegateTo(tasks, delegate);
        return DONE;
      }
      sink.acceptTargetAndConfiguration(
          new TargetAndConfiguration(target, /* configuration= */ null), nullConfiguredTargetKey);
      return DONE;
    }

    return new RuleTransitionConfigurationProducer();
  }

  /** Applies any requested rule transition before producing the final configuration. */
  private class RuleTransitionConfigurationProducer
      implements StateMachine,
          RuleTransitionApplier.ResultSink,
          ValueOrExceptionSink<InvalidConfigurationException> {
    // -------------------- Internal State --------------------
    private BuildConfigurationKey configurationKey;
    private ConfiguredTargetKey fullKey;

    @Override
    public StateMachine step(Tasks tasks, ExtendedEventHandler listener) {
      return new RuleTransitionApplier(
          preRuleTransitionKey.getConfigurationKey(),
          target.getAssociatedRule(),
          trimmingTransitionFactory,
          transitionCache,
          (RuleTransitionApplier.ResultSink) this,
          /* runAfter= */ this::processTransitionedKey);
    }

    @Override
    public void acceptRuleTransitionResult(BuildConfigurationKey result) {
      this.configurationKey = result;
    }

    @Override
    public void acceptRuleTransitionError(TransitionException e) {
      emitTransitionErrorMessage(e.getMessage());
    }

    @Override
    public void acceptRuleTransitionError(OptionsParsingException e) {
      emitTransitionErrorMessage(e.getMessage());
    }

    private StateMachine processTransitionedKey(Tasks tasks, ExtendedEventHandler listener) {
      if (configurationKey == null) {
        return DONE; // There was an error.
      }

      if (!configurationKey.equals(preRuleTransitionKey.getConfigurationKey())) {
        fullKey =
            ConfiguredTargetKey.builder()
                .setDelegate(preRuleTransitionKey)
                .setConfigurationKey(configurationKey)
                .build();
        ActionLookupKey delegate = fullKey.toKey();
        if (!delegate.equals(preRuleTransitionKey)) {
          // Delegates to the key that already owns this configuration.
          delegateTo(tasks, delegate);
          return DONE;
        }
      } else {
        fullKey = preRuleTransitionKey;
      }

      // This key owns the configuration and the computation completes normally.
      tasks.lookUp(
          configurationKey,
          InvalidConfigurationException.class,
          (ValueOrExceptionSink<InvalidConfigurationException>) this);
      return DONE;
    }

    @Override
    public void acceptValueOrException(
        @Nullable SkyValue value, @Nullable InvalidConfigurationException error) {
      if (value != null) {
        sink.acceptTargetAndConfiguration(
            new TargetAndConfiguration(target, (BuildConfigurationValue) value), fullKey);
        return;
      }
      emitTransitionErrorMessage(error.getMessage());
    }

    private void emitTransitionErrorMessage(String message) {
      // The target must be a rule because these errors happen during the Rule transition.
      Rule rule = target.getAssociatedRule();
      emitError(message, rule.getLocation(), /* exitCode= */ null);
    }
  }

  private void delegateTo(Tasks tasks, ActionLookupKey delegate) {
    tasks.lookUp(delegate, (Consumer<SkyValue>) this);
  }

  @Override
  public void accept(SkyValue value) {
    sink.acceptTargetAndConfigurationDelegatedValue((ConfiguredTargetValue) value);
  }

  private void emitError(
      String message, @Nullable Location location, @Nullable DetailedExitCode exitCode) {
    sink.acceptTargetAndConfigurationError(
        TargetAndConfigurationError.of(
            new ConfiguredValueCreationException(
                location,
                message,
                preRuleTransitionKey.getLabel(),
                configurationId(preRuleTransitionKey.getConfigurationKey()),
                /* rootCauses= */ null,
                exitCode)));
  }
}
