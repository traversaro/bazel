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

import com.google.auto.value.AutoOneOf;
import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.common.options.OptionsParsingException;

/** Tagged union of exceptions thrown by {@link DependencyProducer}. */
@AutoOneOf(DependencyError.Kind.class)
public abstract class DependencyError {
  /**
   * Tags for different error types.
   *
   * <p>The earlier in this list, the higher the priority when there are multiple errors. See {@link
   * #isSecondErrorMoreImportant}.
   */
  public enum Kind {
    DEPENDENCY_OPTIONS_PARSING,
    DEPENDENCY_TRANSITION,
    INVALID_VISIBILITY,
    DEPENDENCY_CREATION,
    ASPECT_CREATION,
  }

  public abstract Kind kind();

  public abstract OptionsParsingException dependencyOptionsParsing();

  public abstract TransitionException dependencyTransition();

  public abstract InvalidVisibilityDependencyException invalidVisibility();

  public abstract ConfiguredValueCreationException dependencyCreation();

  public abstract DependencyEvaluationException aspectCreation();

  public static boolean isSecondErrorMoreImportant(DependencyError first, DependencyError second) {
    int cmp = first.kind().compareTo(second.kind());
    if (cmp == 0) {
      switch (first.kind()) {
        case DEPENDENCY_OPTIONS_PARSING:
        case DEPENDENCY_TRANSITION:
        case INVALID_VISIBILITY:
        case ASPECT_CREATION:
          // There isn't a good way to prioritize these so we just keep the first.
          return false;
        case DEPENDENCY_CREATION:
          DetailedExitCode firstExitCode = first.dependencyCreation().getDetailedExitCode();
          DetailedExitCode secondExitCode = second.dependencyCreation().getDetailedExitCode();
          return secondExitCode.equals(
              DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                  secondExitCode, firstExitCode));
      }
      throw new IllegalStateException("unreachable " + first.kind());
    }
    return cmp > 0;
  }

  public Exception getException() {
    switch (kind()) {
      case DEPENDENCY_OPTIONS_PARSING:
        return dependencyOptionsParsing();
      case DEPENDENCY_TRANSITION:
        return dependencyTransition();
      case INVALID_VISIBILITY:
        return invalidVisibility();
      case DEPENDENCY_CREATION:
        return dependencyCreation();
      case ASPECT_CREATION:
        return aspectCreation();
    }
    throw new IllegalStateException("unreachable");
  }

  static DependencyError of(TransitionException e) {
    return AutoOneOf_DependencyError.dependencyTransition(e);
  }

  static DependencyError of(OptionsParsingException e) {
    return AutoOneOf_DependencyError.dependencyOptionsParsing(e);
  }

  static DependencyError of(InvalidVisibilityDependencyException e) {
    return AutoOneOf_DependencyError.invalidVisibility(e);
  }

  static DependencyError of(ConfiguredValueCreationException e) {
    return AutoOneOf_DependencyError.dependencyCreation(e);
  }

  static DependencyError of(DependencyEvaluationException e) {
    return AutoOneOf_DependencyError.aspectCreation(e);
  }
}
