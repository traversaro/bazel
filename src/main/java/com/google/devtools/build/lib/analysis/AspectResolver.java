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
package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.analysis.AspectCollection.buildAspectKey;
import static com.google.devtools.build.lib.analysis.AspectResolutionHelpers.aspectMatchesConfiguredTarget;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.AspectCreationException;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Returns the aspects to attach to rule dependencies.
 */
public final class AspectResolver {
  /**
   * Given a list of {@link Dependency} objects, returns a multimap from the {@link Dependency}s to
   * the {@link ConfiguredAspect} instances that should be merged with them.
   *
   * <p>Returns null if the required aspects are not yet available from Skyframe.
   */
  @Nullable
  public static OrderedSetMultimap<Dependency, ConfiguredAspect> resolveAspectDependencies(
      SkyFunction.LookupEnvironment env,
      Map<ConfiguredTargetKey, ConfiguredTargetAndData> configuredTargetMap,
      Iterable<Dependency> deps,
      @Nullable NestedSetBuilder<Package> transitivePackages)
      throws AspectCreationException, InterruptedException {
    OrderedSetMultimap<Dependency, ConfiguredAspect> result = OrderedSetMultimap.create();
    Set<SkyKey> allAspectKeys = new HashSet<>();
    for (Dependency dep : deps) {
      allAspectKeys.addAll(getAspectKeys(dep, configuredTargetMap).values());
    }

    SkyframeLookupResult depAspects = env.getValuesAndExceptions(allAspectKeys);

    for (Dependency dep : deps) {
      Map<AspectDescriptor, AspectKey> aspectToKeys = getAspectKeys(dep, configuredTargetMap);

      for (AspectCollection.AspectDeps depAspect : dep.getAspects().getUsedAspects()) {
        AspectKey aspectKey = aspectToKeys.get(depAspect.getAspect());

        AspectValue aspectValue;
        try {
          // TODO(ulfjack): Catch all thrown AspectCreationException and NoSuchThingException
          // instances and merge them into a single Exception to get full root cause data.
          aspectValue =
              (AspectValue)
                  depAspects.getOrThrow(
                      aspectKey, AspectCreationException.class, NoSuchThingException.class);
        } catch (NoSuchThingException e) {
          throw new AspectCreationException(
              String.format(
                  "Evaluation of aspect %s on %s failed: %s",
                  depAspect.getAspect().getAspectClass().getName(), dep.getLabel(), e),
              new LabelCause(dep.getLabel(), e.getDetailedExitCode()));
        }

        if (aspectValue == null) {
          // Dependent aspect has either not been computed yet or is in error.
          return null;
        }

        // Validate that aspect is applicable to "bare" configured target.
        ConfiguredTargetAndData associatedTarget =
            configuredTargetMap.get(dep.getConfiguredTargetKey());
        if (!aspectMatchesConfiguredTarget(associatedTarget, aspectValue.getAspect())) {
          continue;
        }

        result.put(dep, aspectValue.getConfiguredAspect());
        if (transitivePackages != null) {
          transitivePackages.addTransitive(
              Preconditions.checkNotNull(aspectValue.getTransitivePackages()));
        }
      }
    }
    return result;
  }

  /**
   * Merges each direct dependency configured target with the aspects associated with it.
   *
   * <p>Note that the combination of a configured target and its associated aspects are not
   * represented by a Skyframe node. This is because there can possibly be many different
   * combinations of aspects for a particular configured target, so it would result in a
   * combinatorial explosion of Skyframe nodes.
   */
  public static OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> mergeAspects(
      OrderedSetMultimap<DependencyKind, Dependency> depValueNames,
      Map<ConfiguredTargetKey, ConfiguredTargetAndData> depConfiguredTargetMap,
      OrderedSetMultimap<Dependency, ConfiguredAspect> depAspectMap)
      throws DuplicateException {
    OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> result =
        OrderedSetMultimap.create();

    for (Map.Entry<DependencyKind, Dependency> entry : depValueNames.entries()) {
      Dependency dep = entry.getValue();
      ConfiguredTargetKey depKey = dep.getConfiguredTargetKey();
      ConfiguredTargetAndData depConfiguredTarget = depConfiguredTargetMap.get(depKey);

      result.put(
          entry.getKey(),
          depConfiguredTarget.fromConfiguredTarget(
              MergedConfiguredTarget.of(
                  depConfiguredTarget.getConfiguredTarget(), depAspectMap.get(dep))));
    }

    return result;
  }

  private static Map<AspectDescriptor, AspectKey> getAspectKeys(
      Dependency dep, Map<ConfiguredTargetKey, ConfiguredTargetAndData> configuredTargetMap) {
    HashMap<AspectDescriptor, AspectKey> result = new HashMap<>();
    AspectCollection aspects = dep.getAspects();
    for (AspectCollection.AspectDeps aspectDeps : aspects.getUsedAspects()) {
      ConfiguredTargetKey depKey = dep.getConfiguredTargetKey();

      BuildConfigurationKey depConfigurationKey =
          configuredTargetMap.get(depKey).getConfigurationKey();
      // The aspect key's base key should match the match the configuration of the underlying
      // configured target.
      //
      // In the current, transitional, state, configuration mismatches should be rare, occurring
      // when rule transitions are not idempotent, for example, b/280040767. Mismatches becomes more
      // common once rule transitions are removed from dependency resolution.
      //
      // TODO(b/261521010); update this comment.
      if (!depConfigurationKey.equals(depKey.getConfigurationKey())) {
        depKey = depKey.toBuilder().setConfigurationKey(depConfigurationKey).build();
      }

      buildAspectKey(aspectDeps, result, depKey);
    }
    return result;
  }
}
