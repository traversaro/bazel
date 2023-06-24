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
package com.google.devtools.build.lib.rules.android;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;
import static org.junit.Assert.fail;

import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.MoreCollectors;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.AnalysisTestUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass.AndroidDeployInfo;
import com.google.devtools.build.lib.rules.java.JavaCompileAction;
import com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.Before;

/** Common methods shared between Android related {@link BuildViewTestCase}s. */
public abstract class AndroidBuildViewTestCase extends BuildViewTestCase {

  @Before
  public void setupStarlarkJavaLibrary() throws Exception {
    setBuildLanguageOptions("--experimental_google_legacy_api");
  }

  /** Override this to trigger platform-based Android toolchain resolution. */
  protected boolean platformBasedToolchains() {
    return false;
  }

  protected String defaultPlatformFlag() {
    return "";
  }

  @Override
  protected void useConfiguration(ImmutableMap<String, Object> starlarkOptions, String... args)
      throws Exception {

    if (!platformBasedToolchains()) {
      super.useConfiguration(
          starlarkOptions,
          ImmutableList.builder()
              .add((Object[]) args)
              .add("--noincompatible_enable_cc_toolchain_resolution")
              .build()
              .toArray(new String[0]));
      return;
    }

    // Platform-based toolchain resolution:
    ImmutableList.Builder<String> fullArgs = ImmutableList.builder();
    fullArgs.add("--incompatible_enable_android_toolchain_resolution");
    // Uncomment the below to get more info when tests fail because of toolchain resolution.
    // fullArgs.add("--toolchain_resolution_debug=tools/android:.*toolchain_type");
    boolean hasPlatform = false;
    for (String arg : args) {
      if (arg.startsWith("--android_sdk=")) {
        // --android_sdk is a legacy toolchain resolution flag. Remap it to the platform-equivalent:
        // wrap a toolchain definition around the SDK with no constraint requirements and register
        // it with --extra_toolchains. --extra_toolchains guarantees this SDK will be chosen before
        // anything registered in the WORKSPACE.
        String sdkLabel = arg.substring("--android_sdk=".length());
        scratch.file(
            "legacy_to_platform_sdk/BUILD",
            "toolchain(",
            "    name = 'custom_sdk_toolchain',",
            String.format("    toolchain_type = '%s',", TestConstants.ANDROID_TOOLCHAIN_TYPE_LABEL),
            String.format("    toolchain = '%s',", sdkLabel),
            ")");
        fullArgs.add("--extra_toolchains=//legacy_to_platform_sdk:custom_sdk_toolchain");
      } else {
        fullArgs.add(arg);
      }

      if (arg.startsWith("--platforms=") || arg.startsWith("--android_platforms=")) {
        hasPlatform = true;
      }
    }
    if (!hasPlatform) {
      fullArgs.add(
          String.format(
              "--platforms=%sandroid:armeabi-v7a", TestConstants.CONSTRAINTS_PACKAGE_ROOT));
      fullArgs.add(defaultPlatformFlag());
    }
    fullArgs.add("--incompatible_enable_cc_toolchain_resolution");
    super.useConfiguration(starlarkOptions, fullArgs.build().toArray(new String[0]));
  }

  protected Iterable<Artifact> getNativeLibrariesInApk(ConfiguredTarget target) {
    return Iterables.filter(
        getGeneratingAction(getCompressedUnsignedApk(target)).getInputs().toList(),
        a -> a.getFilename().endsWith(".so"));
  }

  protected Label getGeneratingLabelForArtifact(Artifact artifact) {
    Action generatingAction = getGeneratingAction(artifact);
    return generatingAction != null ? getGeneratingAction(artifact).getOwner().getLabel() : null;
  }

  protected void assertNativeLibrariesCopiedNotLinked(
      ConfiguredTarget target,
      BuildConfigurationValue targetConfiguration,
      String... expectedLibNames) {
    Iterable<Artifact> copiedLibs = getNativeLibrariesInApk(target);
    for (Artifact copiedLib : copiedLibs) {
      assertWithMessage("Native libraries were linked to produce " + copiedLib)
          .that(getGeneratingLabelForArtifact(copiedLib))
          .isNotEqualTo(target.getLabel());
    }
    assertThat(AnalysisTestUtil.artifactsToStrings(targetConfiguration, copiedLibs))
        .containsAtLeastElementsIn(ImmutableSet.copyOf(Arrays.asList(expectedLibNames)));
  }

  protected String flagValue(String flag, List<String> args) {
    assertThat(args).contains(flag);
    return args.get(args.indexOf(flag) + 1);
  }

  protected Set<String> flagValues(String flag, List<String> args) {
    Set<String> result = new HashSet<>();
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals(flag)) {
        result.add(args.get(++i));
      }
    }
    return result;
  }

  /**
   * The unsigned APK is created in two actions. The first action adds everything that needs to be
   * unconditionally compressed in the APK. The second action adds everything else, preserving their
   * compression.
   */
  protected Artifact getCompressedUnsignedApk(ConfiguredTarget target) {
    return artifactByPath(
        actionsTestUtil().artifactClosureOf(getFinalUnsignedApk(target)),
        "_unsigned.apk",
        "_unsigned.apk");
  }

  protected Artifact getFinalUnsignedApk(ConfiguredTarget target) {
    return getFirstArtifactEndingWith(
        target.getProvider(FileProvider.class).getFilesToBuild(), "_unsigned.apk");
  }

  protected Artifact getDeployJar(ConfiguredTarget target) {
    return getFirstArtifactEndingWith(
        target.getProvider(FileProvider.class).getFilesToBuild(), "_deploy.jar");
  }

  protected Artifact getResourceApk(ConfiguredTarget target) {
    Artifact resourceApk =
        getFirstArtifactEndingWith(
            getGeneratingAction(getFinalUnsignedApk(target)).getInputs(), ".ap_");
    assertThat(resourceApk).isNotNull();
    return resourceApk;
  }

  protected void assertProguardUsed(ConfiguredTarget binary) {
    assertWithMessage("proguard.jar is not in the rule output")
        .that(
            actionsTestUtil()
                .getActionForArtifactEndingWith(getFilesToBuild(binary), "_proguard.jar"))
        .isNotNull();
  }

  protected List<String> resourceArguments(ValidatedAndroidResources resource)
      throws CommandLineExpansionException, InterruptedException {
    return getGeneratingSpawnActionArgs(resource.getApk());
  }

  protected SpawnAction resourceGeneratingAction(ValidatedAndroidResources resource) {
    return getGeneratingSpawnAction(resource.getApk());
  }

  protected static ValidatedAndroidResources getValidatedResources(ConfiguredTarget target) {
    return getValidatedResources(target, /* transitive= */ false);
  }

  protected static ValidatedAndroidResources getValidatedResources(
      ConfiguredTarget target, boolean transitive) {
    Preconditions.checkNotNull(target);
    final AndroidResourcesInfo info = target.get(AndroidResourcesInfo.PROVIDER);
    assertWithMessage("No android resources exported from the target.").that(info).isNotNull();
    return transitive
        ? info.getTransitiveAndroidResources().getSingleton()
        : info.getDirectAndroidResources().getSingleton();
  }

  protected Artifact getResourceClassJar(final ConfiguredTargetAndData target) throws Exception {
    JavaRuleOutputJarsProvider jarProvider =
        JavaInfo.getProvider(JavaRuleOutputJarsProvider.class, target.getConfiguredTarget());
    assertThat(jarProvider).isNotNull();
    return Iterables.find(
            jarProvider.getJavaOutputs(),
            javaOutput -> {
              assertThat(javaOutput).isNotNull();
              assertThat(javaOutput.getClassJar()).isNotNull();
              return javaOutput
                  .getClassJar()
                  .getFilename()
                  .equals(target.getTargetForTesting().getName() + "_resources.jar");
            })
        .getClassJar();
  }

  // android resources related tests
  protected void assertPrimaryResourceDirs(List<String> expectedPaths, List<String> actualArgs) {
    assertThat(actualArgs).contains("--primaryData");
    String actualFlagValue = actualArgs.get(actualArgs.indexOf("--primaryData") + 1);
    List<String> actualPaths = null;
    if (actualFlagValue.matches("[^;]*;[^;]*;.*")) {
      actualPaths =
          Arrays.asList(Iterables.get(Splitter.on(';').split(actualFlagValue), 0).split("#"));

    } else if (actualFlagValue.matches("[^:]*:[^:]*:.*")) {
      actualPaths =
          Arrays.asList(Iterables.get(Splitter.on(':').split(actualFlagValue), 0).split("#"));
    } else {
      fail(String.format("Failed to parse --primaryData: %s", actualFlagValue));
    }
    assertThat(actualPaths).containsAtLeastElementsIn(expectedPaths);
  }

  protected List<String> getDirectDependentResourceDirs(List<String> actualArgs) {
    assertThat(actualArgs).contains("--directData");
    String actualFlagValue = actualArgs.get(actualArgs.indexOf("--directData") + 1);
    return getDependentResourceDirs(actualFlagValue);
  }

  protected List<String> getTransitiveDependentResourceDirs(List<String> actualArgs) {
    assertThat(actualArgs).contains("--data");
    String actualFlagValue = actualArgs.get(actualArgs.indexOf("--data") + 1);
    return getDependentResourceDirs(actualFlagValue);
  }

  private static List<String> getDependentResourceDirs(String actualFlagValue) {
    String separator = null;
    if (actualFlagValue.matches("[^;]*;[^;]*;[^;]*;.*")) {
      separator = ";";
    } else if (actualFlagValue.matches("[^:]*:[^:]*:[^:]*:.*")) {
      separator = ":";
    } else {
      fail(String.format("Failed to parse flag: %s", actualFlagValue));
    }
    ImmutableList.Builder<String> actualPaths = ImmutableList.builder();
    for (String resourceDependency : Splitter.on(',').split(actualFlagValue)) {
      actualPaths.add(
          Iterables.get(Splitter.on(separator).split(resourceDependency), 0).split("#"));
    }
    return actualPaths.build();
  }

  protected String execPathEndingWith(NestedSet<Artifact> inputs, String suffix) {
    return getFirstArtifactEndingWith(inputs, suffix).getExecPathString();
  }

  protected String execPathEndingWith(Iterable<Artifact> inputs, String suffix) {
    return getFirstArtifactEndingWith(inputs, suffix).getExecPathString();
  }

  @Nullable
  protected AndroidDeployInfo getAndroidDeployInfo(Artifact artifact) throws IOException {
    Action generatingAction = getGeneratingAction(artifact);
    if (generatingAction instanceof AndroidDeployInfoAction) {
      AndroidDeployInfoAction writeAction = (AndroidDeployInfoAction) generatingAction;
      return writeAction.getDeployInfo();
    }
    return null;
  }

  protected List<String> getProcessorNames(JavaCompileAction compileAction) throws Exception {
    return JavaCompileActionTestHelper.getProcessorNames(compileAction);
  }

  protected List<String> getProcessorNames(String outputTarget) throws Exception {
    OutputFileConfiguredTarget out =
        (OutputFileConfiguredTarget) getFileConfiguredTarget(outputTarget);
    JavaCompileAction compileAction = (JavaCompileAction) getGeneratingAction(out.getArtifact());
    return getProcessorNames(compileAction);
  }

  // Returns an artifact that will be generated when a rule has resources.
  protected static Artifact getResourceArtifact(ConfiguredTarget target) {
    // the last provider is the provider from the target.
    return Iterables.getLast(
            target.get(AndroidResourcesInfo.PROVIDER).getDirectAndroidResources().toList())
        .getJavaClassJar();
  }

  protected Map<String, String> getBinaryMergeeManifests(ConfiguredTarget target) throws Exception {
    return getMergeeManifests(target.get(ApkInfo.PROVIDER).getMergedManifest());
  }

  protected Map<String, String> getLocalTestMergeeManifests(ConfiguredTarget target)
      throws Exception {
    return getMergeeManifests(
        collectRunfiles(target).toList().stream()
            .filter(
                (artifact) ->
                    artifact.getFilename().equals("AndroidManifest.xml")
                        && artifact.getOwnerLabel().equals(target.getLabel()))
            .collect(MoreCollectors.onlyElement()));
  }

  /** Gets the map of mergee manifests in the order specified on the command line. */
  protected Map<String, String> getMergeeManifests(Artifact processedManifest) throws Exception {
    List<String> processingActionArgs = getGeneratingSpawnActionArgs(processedManifest);
    assertThat(processingActionArgs).contains("--primaryData");
    String primaryData =
        processingActionArgs.get(processingActionArgs.indexOf("--primaryData") + 1);
    String mergedManifestExecPathString = Splitter.on(":").splitToList(primaryData).get(2);
    SpawnAction processingAction = getGeneratingSpawnAction(processedManifest);
    Artifact mergedManifest =
        Iterables.find(
            processingAction.getInputs().toList(),
            (artifact) -> artifact.getExecPath().toString().equals(mergedManifestExecPathString));
    List<String> mergeArgs = getGeneratingSpawnActionArgs(mergedManifest);
    if (!mergeArgs.contains("--mergeeManifests")) {
      return ImmutableMap.of();
    }
    Map<String, String> splitData =
        Splitter.on(",")
            .withKeyValueSeparator(Splitter.on(Pattern.compile("(?<!\\\\):")))
            .split(mergeArgs.get(mergeArgs.indexOf("--mergeeManifests") + 1));
    ImmutableMap.Builder<String, String> results = new ImmutableMap.Builder<>();
    for (Map.Entry<String, String> manifestAndLabel : splitData.entrySet()) {
      results.put(manifestAndLabel.getKey(), manifestAndLabel.getValue().replace("\\:", ":"));
    }
    return results.build();
  }

  /** Gets the processed manifest exported by the given library. */
  protected Artifact getLibraryManifest(ConfiguredTarget target) throws Exception {
    if (target.get(AndroidManifestInfo.PROVIDER) != null) {
      return target.get(AndroidManifestInfo.PROVIDER).getManifest();
    }
    return null;
  }

  // Returns an artifact that will be generated when a rule has assets that are processed separately
  static Artifact getDecoupledAssetArtifact(ConfiguredTarget target) {
    return target.get(AndroidAssetsInfo.PROVIDER).getValidationResult();
  }

  protected static Set<Artifact> getNonToolInputs(Action action) {
    return Sets.difference(action.getInputs().toSet(), action.getTools().toSet());
  }

  protected String getAndroidJarPath() throws Exception {
    return getAndroidSdk().getAndroidJar().getExecPathString();
  }

  protected String getAndroidJarFilename() throws Exception {
    return getAndroidSdk().getAndroidJar().getFilename();
  }

  protected Artifact getProguardBinary() throws Exception {
    return getAndroidSdk().getProguard().getExecutable();
  }

  protected String getMainDexClassesPath() throws Exception {
    return getAndroidSdk().getMainDexClasses().getExecPathString();
  }

  protected String getMainDexClassesFilename() throws Exception {
    return getAndroidSdk().getMainDexClasses().getFilename();
  }

  private AndroidSdkProvider getAndroidSdk() throws Exception {
    Label sdk = targetConfig.getFragment(AndroidConfiguration.class).getSdk();
    return getConfiguredTarget(sdk, targetConfig).get(AndroidSdkProvider.PROVIDER);
  }

  /**
   * Asserts that the expected jar is produced with the expected runtype. Returns the previous
   * action behind lastStageAction.
   */
  private SpawnAction assertJarAndRunType(String runType, SpawnAction lastStageAction, int pass)
      throws Exception {
    String jarMnemonic = Ascii.toLowerCase(runType);
    Artifact lastStageOutput =
        ActionsTestUtil.getFirstArtifactEndingWith(
            lastStageAction.getInputs(), "_" + jarMnemonic + "_" + pass + ".jar");
    assertWithMessage(jarMnemonic + "_" + pass + ".jar is not in rule output")
        .that(lastStageOutput)
        .isNotNull();
    SpawnAction previousAction = getGeneratingSpawnAction(lastStageOutput);
    assertThat(previousAction.getArguments()).contains("-runtype " + runType);
    return previousAction;
  }

  protected void checkProguardUse(
      ConfiguredTarget binary,
      String artifact,
      boolean expectMapping,
      @Nullable Integer passes,
      int bytecodeOptimizationPassActions,
      String... expectedlibraryJars)
      throws Exception {
    assertProguardUsed(binary);
    assertProguardGenerated(binary);

    Action dexAction =
        actionsTestUtil()
            .getActionForArtifactEndingWith(
                actionsTestUtil().artifactClosureOf(getFilesToBuild(binary)), "classes.dex.zip");
    dexAction =
        actionsTestUtil().getActionForArtifactEndingWith(dexAction.getInputs(), "classes.dex.zip");
    Artifact trimmedJar = getFirstArtifactEndingWith(dexAction.getInputs(), artifact);
    assertWithMessage("Dex should be built from jar trimmed with Proguard.")
        .that(trimmedJar)
        .isNotNull();
    SpawnAction proguardAction = getGeneratingSpawnAction(trimmedJar);

    if (passes == null) {
      // Verify proguard as a single action.
      Action proguardMap =
          actionsTestUtil()
              .getActionForArtifactEndingWith(getFilesToBuild(binary), "_proguard.map");
      if (expectMapping) {
        assertWithMessage("proguard.map is not in the rule output").that(proguardMap).isNotNull();
      } else {
        assertWithMessage("proguard.map is in the rule output").that(proguardMap).isNull();
      }
      checkProguardLibJars(proguardAction, expectedlibraryJars);
    } else {
      // Verify the multi-stage system generated the correct number of stages.
      Artifact proguardMap =
          ActionsTestUtil.getFirstArtifactEndingWith(proguardAction.getOutputs(), "_proguard.map");
      if (expectMapping) {
        assertWithMessage("proguard.map is not in the rule output").that(proguardMap).isNotNull();
      } else {
        assertWithMessage("proguard.map is in the rule output").that(proguardMap).isNull();
      }

      assertThat(proguardAction.getArguments()).contains("-runtype FINAL");
      checkProguardLibJars(proguardAction, expectedlibraryJars);

      SpawnAction lastStageAction = proguardAction;
      // Verify Obfuscation/Optimization config.
      for (int pass = passes; pass > 0; pass--) {
        if (bytecodeOptimizationPassActions == 2) {
          // This is used to provide coverage of the OPTIMIZATION_FINAL/OPTIMIZATION_INITIAL legacy
          // behavior.
          lastStageAction = assertJarAndRunType("OPTIMIZATION_FINAL", lastStageAction, pass);
          lastStageAction = assertJarAndRunType("OPTIMIZATION_INITIAL", lastStageAction, pass);
        } else {
          for (int action = bytecodeOptimizationPassActions; action > 0; action--) {
            lastStageAction =
                assertJarAndRunType(
                    "OPTIMIZATION_ACTION_" + action + "_OF_" + bytecodeOptimizationPassActions,
                    lastStageAction,
                    pass);
          }
        }
        checkProguardLibJars(lastStageAction, expectedlibraryJars);
      }

      Artifact preoptimizationOutput =
          ActionsTestUtil.getFirstArtifactEndingWith(
              lastStageAction.getInputs(), "proguard_preoptimization.jar");
      assertWithMessage("proguard_preoptimization.jar is not in rule output")
          .that(preoptimizationOutput)
          .isNotNull();
      SpawnAction proOptimization = getGeneratingSpawnAction(preoptimizationOutput);

      // Verify initial step.
      assertThat(proOptimization.getArguments()).contains("-runtype INITIAL");
      checkProguardLibJars(proOptimization, expectedlibraryJars);
    }
  }

  void checkProguardLibJars(SpawnAction proguardAction, String... expectedlibraryJars)
      throws Exception {
    Collection<String> libraryJars = new ArrayList<>();
    Iterator<String> argsIterator = proguardAction.getArguments().iterator();
    for (String argument = argsIterator.next();
        argsIterator.hasNext();
        argument = argsIterator.next()) {
      if (argument.equals("-libraryjars")) {
        libraryJars.add(argsIterator.next());
      }
    }
    assertThat(libraryJars).containsExactly((Object[]) expectedlibraryJars);
  }

  protected void assertProguardGenerated(ConfiguredTarget binary) {
    Action generateProguardAction =
        actionsTestUtil()
            .getActionForArtifactEndingWith(
                actionsTestUtil().artifactClosureOf(getFilesToBuild(binary)), "_proguard.cfg");
    assertWithMessage("proguard generating action not spawned")
        .that(generateProguardAction)
        .isNotNull();
    Action proguardAction =
        actionsTestUtil().getActionForArtifactEndingWith(getFilesToBuild(binary), "_proguard.jar");
    actionsTestUtil();
    assertWithMessage("Generated config not in inputs to proguard action")
        .that(proguardAction.getInputs().toList())
        .contains(
            ActionsTestUtil.getFirstArtifactEndingWith(
                generateProguardAction.getOutputs(), "_proguard.cfg"));
  }

  protected void assertProguardNotUsed(ConfiguredTarget binary) {
    assertWithMessage("proguard.jar is in the rule output")
        .that(
            actionsTestUtil()
                .getActionForArtifactEndingWith(getFilesToBuild(binary), "_proguard.jar"))
        .isNull();
  }
}
