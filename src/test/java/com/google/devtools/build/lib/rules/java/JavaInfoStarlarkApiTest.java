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
package com.google.devtools.build.lib.rules.java;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests JavaInfo API for Starlark. */
@RunWith(JUnit4.class)
public class JavaInfoStarlarkApiTest extends BuildViewTestCase {

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarOnly() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarAndUseIJar() throws Exception {

    ruleBuilder().withIJar().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-ijar.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-ijar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaRuleOutputJarsProviderSourceJarOutputJarAndUseIJar()
      throws Exception {
    ruleBuilder().withIJar().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllClassOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");

    assertThat(javaRuleOutputJarsProvider.getJavaOutputs()).hasSize(1);
    JavaOutput javaOutput = javaRuleOutputJarsProvider.getJavaOutputs().get(0);

    assertThat(javaOutput.getCompileJar().prettyPrint())
        .isEqualTo("foo/my_starlark_rule_lib-ijar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithRunTimeDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep_runtime = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
  }

  /** Tests that JavaInfo can be constructed with CC native libraries as dependencies. */
  @Test
  public void javaInfo_setNativeLibraries() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "cc_library(name = 'my_cc_lib_direct', srcs = ['cc/a.cc'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        cc_dep = [':my_cc_lib_direct']",
        ")");
    assertNoEvents();

    JavaInfo javaInfoProvider = fetchJavaInfo();

    NestedSet<LibraryToLink> librariesForTopTarget =
        javaInfoProvider.getTransitiveNativeLibraries();
    assertThat(librariesForTopTarget.toList().stream().map(LibraryToLink::getLibraryIdentifier))
        .contains("foo/libmy_cc_lib_direct");
  }

  @Test
  public void buildHelperCreateJavaInfoWithDepsAndNeverLink() throws Exception {
    ruleBuilder().withNeverLink().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars())).isEmpty();
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoSourceJarsProviderWithSourceJars() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");
  }

  @Test
  public void buildHelperPackSources_repackSingleJar() throws Exception {
    ruleBuilder().withSourceFiles().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithSourcesFiles() throws Exception {
    ruleBuilder().withSourceFiles().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  sources = ['ClassA.java', 'ClassB.java', 'ClassC.java', 'ClassD.java'],",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithSourcesFilesAndSourcesJars() throws Exception {
    ruleBuilder().withSourceFiles().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  sources = ['ClassA.java', 'ClassB.java', 'ClassC.java', 'ClassD.java'],",
        "  source_jars = ['my_starlark_rule_src-A.jar']",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoSourceJarsProviderWithDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar", "foo/libmy_java_lib_direct-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndRuntimeDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep_runtime = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar", "foo/libmy_java_lib_direct-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndTransitiveDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_transitive', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_direct',",
        "             srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_transitive'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly(
            "foo/my_starlark_rule_src.jar",
            "foo/libmy_java_lib_direct-src.jar",
            "foo/libmy_java_lib_transitive-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndTransitiveRuntimeDeps()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_transitive', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_direct',",
        "             srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_transitive'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        source_jars = ['my_starlark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly(
            "foo/my_starlark_rule_src.jar",
            "foo/libmy_java_lib_direct-src.jar",
            "foo/libmy_java_lib_transitive-src.jar");
  }

  /** Test exports adds dependencies to JavaCompilationArgsProvider. */
  @Test
  public void buildHelperCreateJavaInfoExportProviderExportsDepsAdded() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_exports', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        dep_exports = [':my_java_lib_exports']",
        ")");
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaSourceJarsProvider javaSourceJarsProvider =
        javaInfo.getProvider(JavaSourceJarsProvider.class);

    assertThat(javaSourceJarsProvider.getSourceJars()).isEmpty();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports-hjar.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports-hjar.jar");
  }

  /** Test exports adds itself and recursive dependencies to JavaCompilationArgsProvider. */
  @Test
  public void buildHelperCreateJavaInfoExportProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_b', ':my_java_lib_c'],",
        "             exports = [':my_java_lib_b']",
        "            )",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        dep_exports = [':my_java_lib_a']",
        ")");
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a.jar",
            "foo/libmy_java_lib_b.jar",
            "foo/libmy_java_lib_c.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar");
  }

  /**
   * Tests case: my_lib // \ a c // \\ b d
   *
   * <p>where single line is normal dependency and double is exports dependency.
   */
  @Test
  public void buildHelperCreateJavaInfoExportProvider001() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_b'],",
        "             exports = [':my_java_lib_b']",
        "            )",
        "java_library(name = 'my_java_lib_d', srcs = ['java/D.java'])",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'],",
        "             deps = [':my_java_lib_d'],",
        "             exports = [':my_java_lib_d']",
        "            )",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        dep = [':my_java_lib_a', ':my_java_lib_c'],",
        "        dep_exports = [':my_java_lib_a']",
        ")");
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a.jar",
            "foo/libmy_java_lib_b.jar",
            "foo/libmy_java_lib_c.jar",
            "foo/libmy_java_lib_d.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar",
            "foo/libmy_java_lib_d-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoPluginsFromExports() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'plugin_dep',",
        "    srcs = [ 'ProcessorDep.java'])",
        "java_plugin(name = 'plugin',",
        "    srcs = ['AnnotationProcessor.java'],",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [ ':plugin_dep' ])",
        "java_library(",
        "  name = 'export',",
        "  exported_plugins = [ ':plugin'],",
        ")",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        dep_exports = [':export']",
        ")");
    assertNoEvents();

    assertThat(fetchJavaInfo().getJavaPluginInfo().plugins().processorClasses().toList())
        .containsExactly("com.google.process.stuff");
  }

  @Test
  public void buildHelperCreateJavaInfoWithPlugins() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'plugin_dep',",
        "    srcs = [ 'ProcessorDep.java'])",
        "java_plugin(name = 'plugin',",
        "    srcs = ['AnnotationProcessor.java'],",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [ ':plugin_dep' ])",
        "my_rule(name = 'my_starlark_rule',",
        "        output_jar = 'my_starlark_rule_lib.jar',",
        "        dep_exported_plugins = [':plugin']",
        ")");
    assertNoEvents();

    assertThat(fetchJavaInfo().getJavaPluginInfo().plugins().processorClasses().toList())
        .containsExactly("com.google.process.stuff");
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarAndStampJar() throws Exception {
    ruleBuilder().withStampJar().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-stamped.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-stamped.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithJdeps_javaRuleOutputJarsProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  jdeps = 'my_jdeps.pb',",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(ruleOutputs.getAllClassOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(ruleOutputs.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");
    assertThat(
            prettyArtifactNames(
                ruleOutputs.getJavaOutputs().stream()
                    .map(JavaOutput::getJdeps)
                    .collect(toImmutableList())))
        .containsExactly("foo/my_jdeps.pb");
  }

  @Test
  public void buildHelperCreateJavaInfoWithGeneratedJars_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  generated_class_jar = 'generated_class.jar',",
        "  generated_source_jar = 'generated_srcs.jar',",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.getJavaOutputs().stream()
                    .map(JavaOutput::getGeneratedClassJar)
                    .collect(toImmutableList())))
        .containsExactly("foo/generated_class.jar");
    assertThat(
            prettyArtifactNames(
                ruleOutputs.getJavaOutputs().stream()
                    .map(JavaOutput::getGeneratedSourceJar)
                    .collect(toImmutableList())))
        .containsExactly("foo/generated_srcs.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithGeneratedJars_javaGenJarsProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  generated_class_jar = 'generated_class.jar',",
        "  generated_source_jar = 'generated_srcs.jar',",
        ")");
    assertNoEvents();

    JavaGenJarsProvider ruleOutputs = fetchJavaInfo().getProvider(JavaGenJarsProvider.class);

    assertThat(ruleOutputs.getGenClassJar().prettyPrint()).isEqualTo("foo/generated_class.jar");
    assertThat(ruleOutputs.getGenSourceJar().prettyPrint()).isEqualTo("foo/generated_srcs.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithCompileJdeps_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  compile_jdeps = 'compile.deps',",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.getJavaOutputs().stream()
                    .map(JavaOutput::getCompileJdeps)
                    .collect(toImmutableList())))
        .containsExactly("foo/compile.deps");
  }

  @Test
  public void buildHelperCreateJavaInfoWithNativeHeaders_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  native_headers_jar = 'nativeheaders.jar',",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.getJavaOutputs().stream()
                    .map(JavaOutput::getNativeHeadersJar)
                    .collect(toImmutableList())))
        .containsExactly("foo/nativeheaders.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithManifestProto_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_starlark_rule',",
        "  output_jar = 'my_starlark_rule_lib.jar',",
        "  source_jars = ['my_starlark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  manifest_proto = 'manifest.proto',",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.getJavaOutputs().stream()
                    .map(JavaOutput::getManifestProto)
                    .collect(toImmutableList())))
        .containsExactly("foo/manifest.proto");
  }

  @Test
  public void buildHelperCreateJavaInfoWithModuleFlags() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(",
        "    name = 'my_java_lib_direct',",
        "    srcs = ['java/A.java'],",
        "    add_opens = ['java.base/java.lang'],",
        ")",
        "my_rule(",
        "    name = 'my_starlark_rule',",
        "    dep = [':my_java_lib_direct'],",
        "    output_jar = 'my_starlark_rule_lib.jar',",
        ")");
    assertNoEvents();

    JavaModuleFlagsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaModuleFlagsProvider.class);

    assertThat(ruleOutputs.toFlags())
        .containsExactly("--add-opens=java.base/java.lang=ALL-UNNAMED");
  }

  private RuleBuilder ruleBuilder() {
    return new RuleBuilder();
  }

  private class RuleBuilder {
    private boolean useIJar = false;
    private boolean stampJar;
    private boolean neverLink = false;
    private boolean sourceFiles = false;

    @CanIgnoreReturnValue
    private RuleBuilder withIJar() {
      useIJar = true;
      return this;
    }

    @CanIgnoreReturnValue
    private RuleBuilder withStampJar() {
      stampJar = true;
      return this;
    }

    @CanIgnoreReturnValue
    private RuleBuilder withNeverLink() {
      neverLink = true;
      return this;
    }

    @CanIgnoreReturnValue
    private RuleBuilder withSourceFiles() {
      sourceFiles = true;
      return this;
    }

    private String[] newJavaInfo() {
      assertThat(useIJar && stampJar).isFalse();
      ImmutableList.Builder<String> lines = ImmutableList.builder();
      lines.add(
          "result = provider()",
          "def _impl(ctx):",
          "  ctx.actions.write(ctx.outputs.output_jar, 'JavaInfo API Test', is_executable=False) ",
          "  dp = [dep[java_common.provider] for dep in ctx.attr.dep]",
          "  dp_runtime = [dep[java_common.provider] for dep in ctx.attr.dep_runtime]",
          "  dp_exports = [dep[java_common.provider] for dep in ctx.attr.dep_exports]",
          "  dp_exported_plugins = [dep[JavaPluginInfo] for dep in ctx.attr.dep_exported_plugins]",
          "  dp_libs = [dep[CcInfo] for dep in ctx.attr.cc_dep]");

      if (useIJar) {
        lines.add(
            "  compile_jar = java_common.run_ijar(",
            "    ctx.actions,",
            "    jar = ctx.outputs.output_jar,",
            "    java_toolchain = ctx.attr._toolchain[java_common.JavaToolchainInfo],",
            "  )");
      } else if (stampJar) {
        lines.add(
            "  compile_jar = java_common.stamp_jar(",
            "    ctx.actions,",
            "    jar = ctx.outputs.output_jar,",
            "    target_label = ctx.label,",
            "    java_toolchain = ctx.attr._toolchain[java_common.JavaToolchainInfo],",
            "  )");
      } else {
        lines.add("  compile_jar = ctx.outputs.output_jar");
      }
      if (sourceFiles) {
        lines.add(
            "  source_jar = java_common.pack_sources(",
            "    ctx.actions,",
            "    output_source_jar = ",
            "      ctx.actions.declare_file(ctx.outputs.output_jar.basename[:-4] + '-src.jar'),",
            "    sources = ctx.files.sources,",
            "    source_jars = ctx.files.source_jars,",
            "    java_toolchain = ctx.attr._toolchain[java_common.JavaToolchainInfo],",
            ")");
      } else {
        lines.add(
            "  if ctx.files.source_jars:",
            "    source_jar = list(ctx.files.source_jars)[0]",
            "  else:",
            "    source_jar = None");
      }
      lines.add(
          "  javaInfo = JavaInfo(",
          "    output_jar = ctx.outputs.output_jar,",
          "    compile_jar = compile_jar,",
          "    source_jar = source_jar,",
          neverLink ? "    neverlink = True," : "",
          "    deps = dp,",
          "    runtime_deps = dp_runtime,",
          "    exports = dp_exports,",
          "    exported_plugins = dp_exported_plugins,",
          "    jdeps = ctx.file.jdeps,",
          "    compile_jdeps = ctx.file.compile_jdeps,",
          "    generated_class_jar = ctx.file.generated_class_jar,",
          "    generated_source_jar = ctx.file.generated_source_jar,",
          "    native_headers_jar = ctx.file.native_headers_jar,",
          "    manifest_proto = ctx.file.manifest_proto,",
          "    native_libraries = dp_libs,",
          "  )",
          "  return [result(property = javaInfo)]");
      return lines.build().toArray(new String[] {});
    }

    private void build() throws Exception {
      if (useIJar || stampJar || sourceFiles) {
        JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
      }

      ImmutableList.Builder<String> lines = ImmutableList.builder();
      lines.add(newJavaInfo());
      lines.add(
          "my_rule = rule(",
          "  implementation = _impl,",
          "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
          "  attrs = {",
          "    'dep' : attr.label_list(),",
          "    'cc_dep' : attr.label_list(),",
          "    'dep_runtime' : attr.label_list(),",
          "    'dep_exports' : attr.label_list(),",
          "    'dep_exported_plugins' : attr.label_list(),",
          "    'output_jar' : attr.output(mandatory=True),",
          "    'source_jars' : attr.label_list(allow_files=['.jar']),",
          "    'sources' : attr.label_list(allow_files=['.java']),",
          "    'jdeps' : attr.label(allow_single_file=True),",
          "    'compile_jdeps' : attr.label(allow_single_file=True),",
          "    'generated_class_jar' : attr.label(allow_single_file=True),",
          "    'generated_source_jar' : attr.label(allow_single_file=True),",
          "    'native_headers_jar' : attr.label(allow_single_file=True),",
          "    'manifest_proto' : attr.label(allow_single_file=True),",
          useIJar || stampJar || sourceFiles
              ? "    '_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),"
              : "",
          "  }",
          ")");

      scratch.file("foo/extension.bzl", lines.build().toArray(new String[] {}));
    }
  }

  private JavaInfo fetchJavaInfo() throws Exception {
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    return JavaInfo.PROVIDER.wrap(info.getValue("property", Info.class));
  }
}
