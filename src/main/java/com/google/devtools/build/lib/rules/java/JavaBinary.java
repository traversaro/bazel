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
package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER;
import static com.google.devtools.build.lib.packages.ExecGroup.DEFAULT_EXEC_GROUP_NAME;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.rules.cpp.CppRuleClasses.JAVA_LAUNCHER_LINK;
import static com.google.devtools.build.lib.rules.cpp.CppRuleClasses.STATIC_LINKING_MODE;
import static com.google.devtools.build.lib.rules.java.DeployArchiveBuilder.Compression.COMPRESSED;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.Allowlist;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.SourceManifestAction;
import com.google.devtools.build.lib.analysis.SourceManifestAction.ManifestType;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.test.ExecutionInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.cpp.CcCommon;
import com.google.devtools.build.lib.rules.cpp.CcCommon.Language;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppHelper;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider.ClasspathType;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.OneVersionEnforcementLevel;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.rules.java.proto.GeneratedExtensionRegistryProvider;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/** An implementation of java_binary. */
public class JavaBinary implements RuleConfiguredTargetFactory {
  private static final PathFragment CPP_RUNTIMES = PathFragment.create("_cpp_runtimes");

  private final JavaSemantics semantics;

  protected JavaBinary(JavaSemantics semantics) {
    this.semantics = semantics;
  }

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    final JavaCommon common = new JavaCommon(ruleContext, semantics);
    DeployArchiveBuilder deployArchiveBuilder = new DeployArchiveBuilder(semantics, ruleContext);
    Runfiles.Builder runfilesBuilder =
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(),
            ruleContext.getConfiguration().legacyExternalRunfiles());
    List<String> jvmFlags = new ArrayList<>();

    JavaTargetAttributes.Builder attributesBuilder = common.initCommon();
    attributesBuilder.addClassPathResources(
        ruleContext.getPrerequisiteArtifacts("classpath_resources").list());

    // Add Java8 timezone resource data
    addTimezoneResourceForJavaBinaries(ruleContext, attributesBuilder);

    List<String> userJvmFlags = JavaCommon.getJvmFlags(ruleContext);

    ruleContext.checkSrcsSamePackage(true);
    boolean createExecutable = ruleContext.attributes().get("create_executable", Type.BOOLEAN);

    if (!createExecutable
        && ruleContext.attributes().isAttributeValueExplicitlySpecified("launcher")) {
      ruleContext.ruleError("launcher specified but create_executable is false");
    }

    if (!ruleContext.attributes().get("use_launcher", Type.BOOLEAN)
        && ruleContext.attributes().isAttributeValueExplicitlySpecified("launcher")) {
      ruleContext.ruleError("launcher specified but use_launcher is false");
    }

    if (ruleContext.attributes().isAttributeValueExplicitlySpecified("add_exports")
        && Allowlist.hasAllowlist(ruleContext, "java_add_exports_allowlist")
        && !Allowlist.isAvailable(ruleContext, "java_add_exports_allowlist")) {
      ruleContext.ruleError("setting add_exports is not permitted");
    }
    if (ruleContext.attributes().isAttributeValueExplicitlySpecified("add_opens")
        && Allowlist.hasAllowlist(ruleContext, "java_add_opens_allowlist")
        && !Allowlist.isAvailable(ruleContext, "java_add_opens_allowlist")) {
      ruleContext.ruleError("setting add_opens is not permitted");
    }

    semantics.checkRule(ruleContext, common);
    semantics.checkForProtoLibraryAndJavaProtoLibraryOnSameProto(ruleContext, common);
    String mainClass = semantics.getMainClass(ruleContext, common.getSrcsArtifacts());
    String originalMainClass = mainClass;
    if (ruleContext.hasErrors()) {
      return null;
    }

    // Collect the transitive dependencies.
    JavaCompilationHelper helper =
        new JavaCompilationHelper(ruleContext, semantics, common.getJavacOpts(), attributesBuilder);
    List<TransitiveInfoCollection> deps =
        Lists.newArrayList(common.targetsTreatedAsDeps(ClasspathType.COMPILE_ONLY));
    helper.addLibrariesToAttributes(deps);
    attributesBuilder.addNativeLibraries(
        JavaCommon.collectNativeLibraries(common.targetsTreatedAsDeps(ClasspathType.BOTH)));

    // deploy_env is valid for java_binary, but not for java_test.
    if (ruleContext.getRule().isAttrDefined("deploy_env", BuildType.LABEL_LIST)) {
      for (JavaRuntimeClasspathProvider envTarget :
          ruleContext.getPrerequisites("deploy_env", JavaRuntimeClasspathProvider.class)) {
        attributesBuilder.addExcludedArtifacts(envTarget.getRuntimeClasspathNestedSet());
      }
    }

    Artifact srcJar = ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_SOURCE_JAR);
    JavaSourceJarsProvider.Builder javaSourceJarsProviderBuilder =
        JavaSourceJarsProvider.builder()
            .addSourceJar(srcJar)
            .addAllTransitiveSourceJars(common.collectTransitiveSourceJars(srcJar));
    Artifact classJar = ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_CLASS_JAR);

    CppConfiguration cppConfiguration =
        ruleContext.getConfiguration().getFragment(CppConfiguration.class);
    CcToolchainProvider ccToolchain =
        CppHelper.getToolchainUsingDefaultCcToolchainAttribute(ruleContext);
    FeatureConfiguration featureConfiguration = null;
    try {
      featureConfiguration =
          CcCommon.configureFeaturesOrThrowEvalException(
              /* requestedFeatures= */ ImmutableSet.<String>builder()
                  .addAll(ruleContext.getFeatures())
                  .add(STATIC_LINKING_MODE)
                  .add(JAVA_LAUNCHER_LINK)
                  .build(),
              /* unsupportedFeatures= */ ruleContext.getDisabledFeatures(),
              Language.CPP,
              ccToolchain,
              cppConfiguration);
    } catch (EvalException e) {
      ruleContext.ruleError(e.getMessage());
    }
    boolean stripAsDefault =
        ccToolchain.shouldCreatePerObjectDebugInfo(featureConfiguration, cppConfiguration)
            && cppConfiguration.getCompilationMode() == CompilationMode.OPT;
    DeployArchiveBuilder unstrippedDeployArchiveBuilder = null;
    if (stripAsDefault) {
      unstrippedDeployArchiveBuilder = new DeployArchiveBuilder(semantics, ruleContext);
    }
    Pair<Artifact, Artifact> launcherAndUnstrippedLauncher =
        semantics.getLauncher(
            ruleContext,
            common,
            deployArchiveBuilder,
            unstrippedDeployArchiveBuilder,
            runfilesBuilder,
            jvmFlags,
            attributesBuilder,
            stripAsDefault,
            ccToolchain,
            featureConfiguration);
    Artifact launcher = launcherAndUnstrippedLauncher.first;
    Artifact unstrippedLauncher = launcherAndUnstrippedLauncher.second;

    JavaCompilationArtifacts.Builder javaArtifactsBuilder = new JavaCompilationArtifacts.Builder();

    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    Artifact executableForRunfiles = null;
    if (createExecutable) {
      // This artifact is named as the rule itself, e.g. //foo:bar_bin -> bazel-bin/foo/bar_bin
      // On Windows, it's going to be bazel-bin/foo/bar_bin.exe
      if (OS.getCurrent() == OS.WINDOWS) {
        executableForRunfiles =
            ruleContext.getImplicitOutputArtifact(ruleContext.getTarget().getName() + ".exe");
      } else {
        executableForRunfiles = ruleContext.createOutputArtifact();
      }
      filesBuilder.add(classJar).add(executableForRunfiles);

      if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
        mainClass = semantics.addCoverageSupport(helper, executableForRunfiles);
      }
    } else {
      filesBuilder.add(classJar);
    }

    JavaCompileOutputs<Artifact> outputs = helper.createOutputs(classJar);
    JavaRuleOutputJarsProvider.Builder ruleOutputJarsProviderBuilder =
        JavaRuleOutputJarsProvider.builder()
            .addJavaOutput(
                JavaOutput.builder().fromJavaCompileOutputs(outputs).addSourceJar(srcJar).build());

    JavaTargetAttributes attributes = attributesBuilder.build();
    List<Artifact> nativeLibraries = attributes.getNativeLibraries();
    if (!nativeLibraries.isEmpty()) {
      jvmFlags.add(
          "-Djava.library.path="
              + JavaCommon.javaLibraryPath(
                  nativeLibraries, ruleContext.getRule().getPackage().getWorkspaceName()));
    }

    JavaConfiguration javaConfig = ruleContext.getFragment(JavaConfiguration.class);
    if (attributes.hasMessages()) {
      helper.setTranslations(semantics.translate(ruleContext, attributes.getMessages()));
    }

    if (attributes.hasSources() || attributes.hasResources()) {
      // We only want to add a jar to the classpath of a dependent rule if it has content.
      javaArtifactsBuilder.addRuntimeJar(classJar);
    }

    GeneratedExtensionRegistryProvider generatedExtensionRegistryProvider =
        semantics.createGeneratedExtensionRegistry(
            ruleContext,
            common,
            filesBuilder,
            javaArtifactsBuilder,
            ruleOutputJarsProviderBuilder,
            javaSourceJarsProviderBuilder);
    javaArtifactsBuilder.setCompileTimeDependencies(outputs.depsProto());

    JavaCompilationArtifacts javaArtifacts = javaArtifactsBuilder.build();
    common.setJavaCompilationArtifacts(javaArtifacts);

    helper.createCompileAction(outputs);
    helper.createSourceJarAction(srcJar, outputs.genSource());

    common.setClassPathFragment(
        new ClasspathConfiguredFragment(
            javaArtifacts, attributes, false, helper.getBootclasspathOrDefault()));

    Iterables.addAll(
        jvmFlags, semantics.getJvmFlags(ruleContext, common.getSrcsArtifacts(), userJvmFlags));

    JavaModuleFlagsProvider javaModuleFlagsProvider =
        JavaModuleFlagsProvider.create(
            ruleContext,
            JavaInfo.moduleFlagsProviders(common.targetsTreatedAsDeps(ClasspathType.BOTH))
                .stream());

    javaModuleFlagsProvider.toFlags().stream()
        // Share strings in the heap with the equivalent javacopt flags, which are also interned
        .map(StringCanonicalizer::intern)
        .forEach(jvmFlags::add);

    if (ruleContext.hasErrors()) {
      return null;
    }

    Artifact executableToRun = executableForRunfiles;
    if (createExecutable) {
      String javaExecutable;
      if (semantics.isJavaExecutableSubstitution()) {
        javaExecutable = JavaCommon.getJavaBinSubstitution(ruleContext, launcher);
      } else {
        javaExecutable = JavaCommon.getJavaExecutableForStub(ruleContext, launcher);
      }
      // Create a shell stub for a Java application
      executableToRun =
          semantics.createStubAction(
              ruleContext,
              common,
              jvmFlags,
              executableForRunfiles,
              mainClass,
              originalMainClass,
              filesBuilder,
              javaExecutable,
              /* createCoverageMetadataJar= */ false);
      if (!executableToRun.equals(executableForRunfiles)) {
        filesBuilder.add(executableToRun);
        runfilesBuilder.addArtifact(executableToRun);
      }
    }

    JavaSourceJarsProvider sourceJarsProvider = javaSourceJarsProviderBuilder.build();
    NestedSet<Artifact> transitiveSourceJars = sourceJarsProvider.getTransitiveSourceJars();

    // TODO(bazel-team): if (getOptions().sourceJars) then make this a dummy prerequisite for the
    // DeployArchiveAction ? Needs a few changes there as we can't pass inputs
    SingleJarActionBuilder.createSourceJarAction(
        ruleContext,
        semantics,
        NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
        transitiveSourceJars,
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_DEPLOY_SOURCE_JAR),
        ruleContext.useAutoExecGroups()
            ? semantics.getJavaToolchainType()
            : DEFAULT_EXEC_GROUP_NAME);

    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext);
    builder.add(
        JavaPrimaryClassProvider.class,
        new JavaPrimaryClassProvider(
            semantics.getPrimaryClass(ruleContext, common.getSrcsArtifacts())));
    if (generatedExtensionRegistryProvider != null) {
      builder.addNativeDeclaredProvider(generatedExtensionRegistryProvider);
    }

    Artifact deployJar =
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_DEPLOY_JAR);

    if (javaConfig.oneVersionEnforcementLevel() != OneVersionEnforcementLevel.OFF) {
      // This JavaBinary class is also the implementation for java_test targets (via the
      // {Google,Bazel}JavaTest subclass). java_test targets can have their one version enforcement
      // disabled with a second flag (to avoid the incremental build performance cost at the expense
      // of safety.)
      if (javaConfig.enforceOneVersionOnJavaTests() || !isJavaTestRule(ruleContext)) {
        Artifact oneVersionOutputArtifact =
            OneVersionCheckActionBuilder.newBuilder()
                .withEnforcementLevel(javaConfig.oneVersionEnforcementLevel())
                .useToolchain(JavaToolchainProvider.from(ruleContext))
                .checkJars(
                    NestedSetBuilder.fromNestedSet(attributes.getRuntimeClassPath())
                        .add(classJar)
                        .build())
                .build(ruleContext);
        if (oneVersionOutputArtifact != null) {
          builder.addOutputGroup(OutputGroupInfo.VALIDATION, oneVersionOutputArtifact);
        }
      }
    }
    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    NestedSet<Artifact> dynamicRuntimeActionInputs;
    try {
      dynamicRuntimeActionInputs = ccToolchain.getDynamicRuntimeLinkInputs(featureConfiguration);
    } catch (EvalException e) {
      throw ruleContext.throwWithRuleError(e);
    }

    collectDefaultRunfiles(
        runfilesBuilder,
        ruleContext,
        common,
        javaArtifacts,
        filesToBuild,
        launcher,
        dynamicRuntimeActionInputs);
    Runfiles defaultRunfiles = runfilesBuilder.build();

    RunfilesSupport runfilesSupport = null;
    NestedSetBuilder<Artifact> extraFilesToRunBuilder = NestedSetBuilder.stableOrder();
    if (createExecutable) {
      List<String> extraArgs =
          new ArrayList<>(semantics.getExtraArguments(ruleContext, common.getSrcsArtifacts()));
      // The executable we pass here will be used when creating the runfiles directory. E.g. for the
      // stub script called bazel-bin/foo/bar_bin, the runfiles directory will be created under
      // bazel-bin/foo/bar_bin.runfiles . On platforms where there's an extra stub script (Windows)
      // which dispatches to this one, we still create the runfiles directory for the shell script,
      // but use the dispatcher script (a batch file) as the RunfilesProvider's executable.
      runfilesSupport =
          RunfilesSupport.withExecutable(
              ruleContext, defaultRunfiles, executableForRunfiles, extraArgs);
      extraFilesToRunBuilder.add(runfilesSupport.getRunfilesMiddleman());
    }

    RunfilesProvider runfilesProvider =
        RunfilesProvider.withData(
            defaultRunfiles,
            new Runfiles.Builder(
                    ruleContext.getWorkspaceName(),
                    ruleContext.getConfiguration().legacyExternalRunfiles())
                .merge(runfilesSupport)
                .build());

    ImmutableList<String> deployManifestLines =
        getDeployManifestLines(ruleContext, originalMainClass);

    // Create the java_binary target specific CDS archive.
    Artifact jsa = createSharedArchive(ruleContext, javaArtifacts, attributes);

    if (ruleContext.isAttrDefined("hermetic", BOOLEAN)
        && ruleContext.attributes().get("hermetic", BOOLEAN)) {
      if (!createExecutable) {
        ruleContext.ruleError("hermetic specified but create_executable is false");
      }

      JavaRuntimeInfo javaRuntime = JavaRuntimeInfo.from(ruleContext);
      if (!javaRuntime.hermeticInputs().isEmpty()
          && javaRuntime.libModules() != null
          && !javaRuntime.hermeticStaticLibs().isEmpty()) {
        deployArchiveBuilder
            .setJavaHome(javaRuntime.javaHomePathFragment())
            .setLibModules(javaRuntime.libModules())
            .setHermeticInputs(javaRuntime.hermeticInputs());
      }

      if (jsa == null) {
        // Use the JDK default CDS specified by the JavaRuntime if the
        // java_binary target specific CDS archive is null, when building
        // a hermetic deploy JAR.
        jsa = javaRuntime.defaultCDS();
      }
    }

    deployArchiveBuilder
        .setOutputJar(deployJar)
        .setJavaStartClass(mainClass)
        .setDeployManifestLines(deployManifestLines)
        .setAttributes(attributes)
        .addRuntimeJars(javaArtifacts.getRuntimeJars())
        .setIncludeBuildData(true)
        .setRunfilesMiddleman(
            runfilesSupport == null ? null : runfilesSupport.getRunfilesMiddleman())
        .setCompression(COMPRESSED)
        .setLauncher(launcher)
        .setOneVersionEnforcementLevel(
            javaConfig.oneVersionEnforcementLevel(),
            JavaToolchainProvider.from(ruleContext).getOneVersionAllowlist())
        .setMultiReleaseDeployJars(javaConfig.multiReleaseDeployJars())
        .setSharedArchive(jsa)
        .setAddExports(javaModuleFlagsProvider.addExports())
        .setAddOpens(javaModuleFlagsProvider.addOpens())
        .build();

    Artifact unstrippedDeployJar =
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_UNSTRIPPED_BINARY_DEPLOY_JAR);
    if (stripAsDefault) {
      requireNonNull(unstrippedDeployArchiveBuilder); // guarded by stripAsDefault
      unstrippedDeployArchiveBuilder
          .setOutputJar(unstrippedDeployJar)
          .setJavaStartClass(mainClass)
          .setDeployManifestLines(deployManifestLines)
          .setAttributes(attributes)
          .addRuntimeJars(javaArtifacts.getRuntimeJars())
          .setIncludeBuildData(true)
          .setRunfilesMiddleman(
              runfilesSupport == null ? null : runfilesSupport.getRunfilesMiddleman())
          .setCompression(COMPRESSED)
          .setLauncher(unstrippedLauncher);

      unstrippedDeployArchiveBuilder.build();
    } else {
      // Write an empty file as the name_deploy.jar.unstripped when the default output jar is not
      // stripped.
      ruleContext.registerAction(
          FileWriteAction.create(ruleContext, unstrippedDeployJar, "", false));
    }

    JavaRuleOutputJarsProvider ruleOutputJarsProvider = ruleOutputJarsProviderBuilder.build();

    JavaInfo.Builder javaInfoBuilder = JavaInfo.Builder.create();

    NestedSetBuilder<Pair<String, String>> coverageEnvironment = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> coverageSupportFiles = NestedSetBuilder.stableOrder();
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {

      // Create an artifact that contains the runfiles relative paths of the jars on the runtime
      // classpath. Using SourceManifestAction is the only reliable way to match the runfiles
      // creation code.
      Artifact runtimeClasspathArtifact =
          ruleContext.getUniqueDirectoryArtifact(
              "runtime_classpath_for_coverage",
              "runtime_classpath.txt",
              ruleContext.getBinOrGenfilesDirectory());
      ruleContext.registerAction(
          new SourceManifestAction(
              ManifestType.SOURCES_ONLY,
              ruleContext.getActionOwner(),
              runtimeClasspathArtifact,
              new Runfiles.Builder(
                      ruleContext.getWorkspaceName(),
                      ruleContext.getConfiguration().legacyExternalRunfiles())
                  // This matches the code below in collectDefaultRunfiles.
                  .addTransitiveArtifactsWrappedInStableOrder(common.getRuntimeClasspath())
                  .build(),
              null,
              true));
      filesBuilder.add(runtimeClasspathArtifact);

      // Pass the artifact through an environment variable in the coverage environment so it
      // can be read by the coverage collection script.
      coverageEnvironment.add(
          new Pair<>(
              "JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE", runtimeClasspathArtifact.getExecPathString()));
      // Add the file to coverageSupportFiles so it ends up as an input for the test action
      // when coverage is enabled.
      coverageSupportFiles.add(runtimeClasspathArtifact);

      // Make single jar reachable from the coverage environment because it needs to be executed
      // by the coverage collection script.
      FilesToRunProvider singleJar = JavaToolchainProvider.from(ruleContext).getSingleJar();
      coverageEnvironment.add(
          new Pair<>("SINGLE_JAR_TOOL", singleJar.getExecutable().getExecPathString()));
      coverageSupportFiles.addTransitive(singleJar.getFilesToRun());
    }

    common.addTransitiveInfoProviders(
        builder,
        javaInfoBuilder,
        filesToBuild,
        classJar,
        coverageEnvironment.build(),
        coverageSupportFiles.build());
    common.addGenJarsProvider(builder, javaInfoBuilder, outputs.genClass(), outputs.genSource());

    // This rule specifically _won't_ build deploy_env, so we selectively propagate validations to
    // filter out deploy_env's if there are any (and otherwise rely on automatic validation
    // propagation). Note that any validations not propagated here will still be built if and when
    // deploy_env is built.
    if (ruleContext.getRule().isAttrDefined("deploy_env", BuildType.LABEL_LIST)) {
      NestedSetBuilder<Artifact> excluded = NestedSetBuilder.stableOrder();
      for (OutputGroupInfo outputGroup :
          ruleContext.getPrerequisites("deploy_env", OutputGroupInfo.STARLARK_CONSTRUCTOR)) {
        NestedSet<Artifact> toExclude = outputGroup.getOutputGroup(OutputGroupInfo.VALIDATION);
        if (!toExclude.isEmpty()) {
          excluded.addTransitive(toExclude);
        }
      }
      if (!excluded.isEmpty()) {
        NestedSetBuilder<Artifact> validations = NestedSetBuilder.stableOrder();
        RuleConfiguredTargetBuilder.collectTransitiveValidationOutputGroups(
            ruleContext,
            attributeName -> !"deploy_env".equals(attributeName),
            validations::addTransitive);

        // Likely, deploy_env will overlap with deps/runtime_deps. Unless we're building an
        // executable (which is rare and somewhat questionable when deploy_env is specified), we can
        // exclude validations from deploy_env entirely from this rule, since this rule specifically
        // never builds the referenced code.
        if (createExecutable) {
          // Executable classpath isn't affected by deploy_env, so build all collected validations.
          builder.addOutputGroup(OutputGroupInfo.VALIDATION_TRANSITIVE, validations.build());
        } else {
          // Filter validations similar to JavaTargetAttributes.getRuntimeClassPathForArchive().
          builder.addOutputGroup(
              OutputGroupInfo.VALIDATION_TRANSITIVE,
              NestedSetBuilder.wrap(
                  Order.STABLE_ORDER,
                  Iterables.filter(
                      validations.build().toList(),
                      Predicates.not(Predicates.in(excluded.build().toSet())))));
        }
      }
    }

    Artifact validation =
        AndroidLintActionBuilder.create(
            ruleContext,
            javaConfig,
            attributes,
            helper.getBootclasspathOrDefault(),
            common,
            semantics,
            outputs);
    if (validation != null) {
      builder.addOutputGroup(
          OutputGroupInfo.VALIDATION, NestedSetBuilder.create(STABLE_ORDER, validation));
    }

    // Support test execution on darwin.
    if (ApplePlatform.isApplePlatform(ruleContext.getConfiguration().getCpu())
        && TargetUtils.isTestRule(ruleContext.getRule())) {
      builder.addNativeDeclaredProvider(
          new ExecutionInfo(ImmutableMap.of(ExecutionRequirements.REQUIRES_DARWIN, "")));
    }

    JavaInfo javaInfo =
        javaInfoBuilder
            .javaSourceJars(sourceJarsProvider)
            .javaRuleOutputs(ruleOutputJarsProvider)
            .build();

    return builder
        .setFilesToBuild(filesToBuild)
        .addStarlarkDeclaredProvider(javaInfo)
        .add(RunfilesProvider.class, runfilesProvider)
        // The executable to run (below) may be different from the executable for runfiles (the one
        // we create the runfiles support object with). On Linux they are the same (it's the same
        // shell script), on Windows they are different (the executable to run is a batch file, the
        // executable for runfiles is the shell script).
        .setRunfilesSupport(runfilesSupport, executableToRun)
        // Add the native libraries as test action tools. Useful for the persistent test runner
        // to include them in the worker's key and re-build a worker if the native dependencies
        // have changed.
        .addTestActionTools(nativeLibraries)
        .addFilesToRun(extraFilesToRunBuilder.build())
        .add(
            JavaRuntimeClasspathProvider.class,
            new JavaRuntimeClasspathProvider(common.getRuntimeClasspath()))
        .addOutputGroup(JavaSemantics.SOURCE_JARS_OUTPUT_GROUP, transitiveSourceJars)
        .addOutputGroup(
            JavaSemantics.DIRECT_SOURCE_JARS_OUTPUT_GROUP,
            NestedSetBuilder.wrap(Order.STABLE_ORDER, sourceJarsProvider.getSourceJars()))
        .build();
  }

  @Nullable
  private static Artifact createSharedArchive(
      RuleContext ruleContext,
      JavaCompilationArtifacts javaArtifacts,
      JavaTargetAttributes attributes)
      throws InterruptedException {
    if (!ruleContext.getRule().isAttrDefined("classlist", BuildType.LABEL)) {
      return null;
    }
    Artifact classlist = ruleContext.getPrerequisiteArtifact("classlist");
    if (classlist == null) {
      return null;
    }
    NestedSet<Artifact> classpath =
        NestedSetBuilder.<Artifact>stableOrder()
            .addAll(javaArtifacts.getRuntimeJars())
            .addTransitive(attributes.getRuntimeClassPathForArchive())
            .build();
    Artifact jsa = ruleContext.getImplicitOutputArtifact(JavaSemantics.SHARED_ARCHIVE_ARTIFACT);
    Artifact merged =
        ruleContext.getDerivedArtifact(
            jsa.getOutputDirRelativePath(ruleContext.getConfiguration().isSiblingRepositoryLayout())
                .replaceName(
                    FileSystemUtils.removeExtension(jsa.getRootRelativePath().getBaseName())
                        + "-merged.jar"),
            jsa.getRoot());
    SingleJarActionBuilder.createSingleJarAction(ruleContext, classpath, merged);
    JavaRuntimeInfo javaRuntime = JavaRuntimeInfo.from(ruleContext);
    Artifact configFile = ruleContext.getPrerequisiteArtifact("cds_config_file");

    CustomCommandLine.Builder commandLine =
        CustomCommandLine.builder()
            .add("-Xshare:dump")
            .addFormatted("-XX:SharedArchiveFile=%s", jsa.getExecPath())
            .addFormatted("-XX:SharedClassListFile=%s", classlist.getExecPath());
    if (configFile != null) {
      commandLine.addFormatted("-XX:SharedArchiveConfigFile=%s", configFile.getExecPath());
    }
    commandLine.add("-cp").addExecPath(merged);
    SpawnAction.Builder spawnAction = new SpawnAction.Builder();
    if (ruleContext.getRule().isAttrDefined("jvm_flags_for_cds_image_creation", Type.STRING_LIST)) {
      commandLine.addAll(
          ruleContext
              .getExpander()
              .withDataExecLocations()
              .list("jvm_flags_for_cds_image_creation"));
      spawnAction.addTransitiveInputs(PrerequisiteArtifacts.nestedSet(ruleContext, "data"));
    }
    spawnAction
        .setExecutable(javaRuntime.javaBinaryExecPathFragment())
        .addCommandLine(commandLine.build())
        .setMnemonic("JavaJSA")
        .setProgressMessage("Dumping Java Shared Archive %s", jsa.prettyPrint())
        .addOutput(jsa)
        .addInput(classlist)
        .addInput(merged)
        .addTransitiveInputs(javaRuntime.javaBaseInputs());
    if (configFile != null) {
      spawnAction.addInput(configFile);
    }
    ruleContext.registerAction(spawnAction.build(ruleContext));
    return jsa;
  }

  // Create the deploy jar and make it dependent on the runfiles middleman if an executable is
  // created. Do not add the deploy jar to files to build, so we will only build it when it gets
  // requested.
  private static ImmutableList<String> getDeployManifestLines(
      RuleContext ruleContext, String originalMainClass) {
    ImmutableList.Builder<String> builder =
        ImmutableList.<String>builder()
            .addAll(ruleContext.attributes().get("deploy_manifest_lines", Type.STRING_LIST));
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      builder.add("Coverage-Main-Class: " + originalMainClass);
    }
    return builder.build();
  }

  /** Add Java8 timezone resource jar to java binary, if specified in tool chain. */
  private static void addTimezoneResourceForJavaBinaries(
      RuleContext ruleContext, JavaTargetAttributes.Builder attributesBuilder) {
    JavaToolchainProvider toolchainProvider = JavaToolchainProvider.from(ruleContext);
    if (toolchainProvider.getTimezoneData() != null) {
      attributesBuilder.addResourceJars(
          NestedSetBuilder.create(Order.STABLE_ORDER, toolchainProvider.getTimezoneData()));
    }
  }

  private void collectDefaultRunfiles(
      Runfiles.Builder builder,
      RuleContext ruleContext,
      JavaCommon common,
      JavaCompilationArtifacts javaArtifacts,
      NestedSet<Artifact> filesToBuild,
      Artifact launcher,
      NestedSet<Artifact> dynamicRuntimeActionInputs)
      throws RuleErrorException {
    builder.addTransitiveArtifactsWrappedInStableOrder(filesToBuild);
    builder.addArtifacts(javaArtifacts.getRuntimeJars());
    if (launcher != null) {
      final TransitiveInfoCollection defaultLauncher =
          JavaHelper.launcherForTarget(semantics, ruleContext);
      final Artifact defaultLauncherArtifact =
          JavaHelper.launcherArtifactForTarget(semantics, ruleContext);
      if (!defaultLauncherArtifact.equals(launcher)) {
        builder.addArtifact(launcher);

        // N.B. The "default launcher" referred to here is the launcher target specified through
        // an attribute or flag. We wish to retain the runfiles of the default launcher, *except*
        // for the original cc_binary artifact, because we've swapped it out with our custom
        // launcher. Hence, instead of calling builder.addTarget(), or adding an odd method
        // to Runfiles.Builder, we "unravel" the call and manually add things to the builder.
        // Because the NestedSet representing each target's launcher runfiles is re-built here,
        // we may see increased memory consumption for representing the target's runfiles.
        Runfiles runfiles =
            defaultLauncher.getProvider(RunfilesProvider.class).getDefaultRunfiles();
        NestedSetBuilder<Artifact> unconditionalArtifacts = NestedSetBuilder.compileOrder();
        for (Artifact a : runfiles.getArtifacts().toList()) {
          if (!a.equals(defaultLauncherArtifact)) {
            unconditionalArtifacts.add(a);
          }
        }
        builder.addTransitiveArtifacts(unconditionalArtifacts.build());
        builder.addSymlinks(runfiles.getSymlinks());
        builder.addRootSymlinks(runfiles.getRootSymlinks());
      } else {
        builder.addTarget(
            defaultLauncher,
            RunfilesProvider.DEFAULT_RUNFILES,
            ruleContext.getConfiguration().alwaysIncludeFilesToBuildInData());
      }
    }

    semantics.addRunfilesForBinary(ruleContext, launcher, builder);
    builder.addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES);

    List<? extends TransitiveInfoCollection> runtimeDeps =
        ruleContext.getPrerequisites("runtime_deps");
    builder.addTargets(
        runtimeDeps,
        RunfilesProvider.DEFAULT_RUNFILES,
        ruleContext.getConfiguration().alwaysIncludeFilesToBuildInData());

    builder.addTransitiveArtifactsWrappedInStableOrder(common.getRuntimeClasspath());

    // Add the JDK files if it comes from the source repository (see java_stub_template.txt).
    JavaRuntimeInfo javaRuntime = JavaRuntimeInfo.from(ruleContext);
    if (javaRuntime != null) {
      builder.addTransitiveArtifacts(javaRuntime.javaBaseInputs());

      if (!javaRuntime.javaHomePathFragment().isAbsolute()) {
        // Add symlinks to the C++ runtime libraries under a path that can be built
        // into the Java binary without having to embed the crosstool, gcc, and grte
        // version information contained within the libraries' package paths.
        for (Artifact lib : dynamicRuntimeActionInputs.toList()) {
          PathFragment path = CPP_RUNTIMES.getRelative(lib.getExecPath().getBaseName());
          builder.addSymlink(path, lib);
        }
      }
    }
  }

  private static boolean isJavaTestRule(RuleContext ruleContext) {
    return ruleContext.getRule().getRuleClass().endsWith("_test");
  }
}
