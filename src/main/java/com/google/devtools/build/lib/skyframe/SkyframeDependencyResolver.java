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

import static com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationIdMessage;
import static com.google.devtools.build.lib.cmdline.LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolver;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LoadingFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RepositoryFetchException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A dependency resolver for use within Skyframe. Loads packages lazily when possible.
 */
public final class SkyframeDependencyResolver extends DependencyResolver {

  private final LookupEnvironment env;
  private final ExtendedEventHandler listener;

  public SkyframeDependencyResolver(LookupEnvironment env, ExtendedEventHandler listener) {
    this.env = env;
    this.listener = listener;
  }

  private void missingEdgeHook(
      Target from, DependencyKind dependencyKind, Label to, NoSuchThingException e) {
    boolean raiseError = false;
    if (e instanceof NoSuchTargetException) {
      NoSuchTargetException nste = (NoSuchTargetException) e;
      raiseError = to.equals(nste.getLabel());
    } else if (e instanceof NoSuchPackageException) {
      NoSuchPackageException nspe = (NoSuchPackageException) e;
      raiseError = nspe.getPackageId().equals(to.getPackageIdentifier());
    }

    if (!raiseError) {
      return;
    }

    String message;
    if (DependencyKind.isToolchain(dependencyKind)) {
      message =
          String.format(
              "Target '%s' depends on toolchain '%s', which cannot be found: %s'",
              from.getLabel(), to, e.getMessage());
    } else {
      message = TargetUtils.formatMissingEdge(from, to, e, dependencyKind.getAttribute());
    }

    listener.handle(Event.error(TargetUtils.getLocationMaybe(from), message));
  }

  @Nullable
  @Override
  protected Map<Label, Target> getTargets(
      OrderedSetMultimap<DependencyKind, Label> labelMap,
      TargetAndConfiguration fromNode,
      NestedSetBuilder<Cause> rootCauses)
      throws InterruptedException {
    SkyframeLookupResult packages =
        env.getValuesAndExceptions(
            Iterables.transform(labelMap.values(), Label::getPackageIdentifier));

    Target fromTarget = fromNode.getTarget();

    // As per the comment in SkyFunctionEnvironment.getValueOrUntypedExceptions(), we are supposed
    // to prefer reporting errors to reporting null, we first check for errors in our dependencies.
    // This, of course, results in some wasted work in case this will need to be restarted later.

    // Duplicates can occur, so we can't use ImmutableMap.
    HashMap<Label, Target> result = Maps.newHashMapWithExpectedSize(labelMap.size());
    for (Map.Entry<DependencyKind, Label> entry : labelMap.entries()) {
      Label label = entry.getValue();
      if (result.containsKey(label)) {
        continue;
      }

      PackageValue packageValue;
      try {
        packageValue =
            (PackageValue)
                packages.getOrThrow(label.getPackageIdentifier(), NoSuchPackageException.class);
        if (packageValue == null) {
          // Dependency has not been computed yet. There will be a next iteration.
          continue;
        }
      } catch (NoSuchPackageException e) {
        if (e instanceof RepositoryFetchException) {
          Label repositoryLabel;
          try {
            repositoryLabel =
                Label.create(EXTERNAL_PACKAGE_IDENTIFIER, label.getRepository().getName());
          } catch (LabelSyntaxException lse) {
            // We're taking the repository name from something that was already
            // part of a label, so it should be valid. If we really get into this
            // strange we situation, better not try to be smart and report the original
            // label.
            repositoryLabel = label;
          }
          rootCauses.add(new LoadingFailedCause(repositoryLabel, e.getDetailedExitCode()));
          listener.handle(
              Event.error(
                  TargetUtils.getLocationMaybe(fromTarget),
                  String.format(
                      "%s depends on %s in repository %s which failed to fetch. %s",
                      fromTarget.getLabel(), label, label.getRepository(), e.getMessage())));
          continue;
        }
        @Nullable BuildConfigurationValue configuration = fromNode.getConfiguration();
        listener.post(
            AnalysisRootCauseEvent.withConfigurationValue(configuration, label, e.getMessage()));
        rootCauses.add(
            new AnalysisFailedCause(
                label, configurationIdMessage(configuration), e.getDetailedExitCode()));
        missingEdgeHook(fromTarget, entry.getKey(), label, e);
        continue;
      }

      Package pkg = packageValue.getPackage();
      try {
        Target target = pkg.getTarget(label.getName());
        if (pkg.containsErrors()) {
          NoSuchTargetException e = new NoSuchTargetException(target);
          missingEdgeHook(fromTarget, entry.getKey(), label, e);
          rootCauses.add(
              new LoadingFailedCause(
                  label, DetailedExitCode.of(pkg.contextualizeFailureDetailForTarget(target))));
        }
        result.put(label, target);
      } catch (NoSuchTargetException e) {
        rootCauses.add(new LoadingFailedCause(label, e.getDetailedExitCode()));
        missingEdgeHook(fromTarget, entry.getKey(), label, e);
      }
    }

    return env.valuesMissing() ? null : result;
  }
}
