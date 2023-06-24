// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertEventCount;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.ResolvedHashesFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlCompileFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadCycleReporter;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.BzlmodRepoCycleReporter;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.IgnoredPackagePrefixesFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Tests for module extension resolution. */
@RunWith(JUnit4.class)
public class ModuleExtensionResolutionTest extends FoundationTestCase {

  private Path workspaceRoot;
  private Path modulesRoot;
  private MemoizingEvaluator evaluator;
  private EvaluationContext evaluationContext;
  private FakeRegistry registry;
  private RecordingDifferencer differencer;
  private final CyclesReporter cyclesReporter =
      new CyclesReporter(new BzlLoadCycleReporter(), new BzlmodRepoCycleReporter());

  @Before
  public void setup() throws Exception {
    workspaceRoot = scratch.dir("/ws");
    String bazelToolsPath = "/ws/embedded_tools";
    scratch.file(bazelToolsPath + "/MODULE.bazel", "module(name = 'bazel_tools')");
    scratch.file(bazelToolsPath + "/WORKSPACE");
    modulesRoot = scratch.dir("/modules");
    differencer = new SequencedRecordingDifferencer();
    evaluationContext =
        EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build();
    FakeRegistry.Factory registryFactory = new FakeRegistry.Factory();
    registry = registryFactory.newFakeRegistry(modulesRoot.getPathString());
    AtomicReference<PathPackageLocator> packageLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(workspaceRoot)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            workspaceRoot,
            /* defaultSystemJavabase= */ null,
            AnalysisMock.get().getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            packageLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder
        .clearWorkspaceFileSuffixForTesting()
        .addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule()));
    ConfiguredRuleClassProvider ruleClassProvider = builder.build();

    PackageFactory packageFactory =
        AnalysisMock.get()
            .getPackageFactoryBuilderForTesting(directories)
            .build(ruleClassProvider, fileSystem);
    HashFunction hashFunction = fileSystem.getDigestFunction().getHashFunction();

    DownloadManager downloadManager = Mockito.mock(DownloadManager.class);
    SingleExtensionEvalFunction singleExtensionEvalFunction =
        new SingleExtensionEvalFunction(directories, ImmutableMap::of, downloadManager);
    StarlarkRepositoryFunction starlarkRepositoryFunction =
        new StarlarkRepositoryFunction(downloadManager);

    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(FileValue.FILE, new FileFunction(packageLocator, directories))
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(
                        registryFactory,
                        workspaceRoot,
                        // Required to load @_builtins.
                        ImmutableMap.of("bazel_tools", LocalPathOverride.create(bazelToolsPath))))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(
                    SkyFunctions.BZL_COMPILE,
                    new BzlCompileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(), hashFunction))
                .put(
                    SkyFunctions.BZL_LOAD,
                    BzlLoadFunction.create(
                        ruleClassProvider,
                        directories,
                        hashFunction,
                        Caffeine.newBuilder().build()))
                .put(
                    SkyFunctions.STARLARK_BUILTINS,
                    new StarlarkBuiltinsFunction(ruleClassProvider.getBazelStarlarkEnvironment()))
                .put(
                    SkyFunctions.PACKAGE,
                    new PackageFunction(
                        /* packageFactory= */ null,
                        /* pkgLocator= */ null,
                        /* showLoadingProgress= */ null,
                        /* numPackagesSuccessfullyLoaded= */ new AtomicInteger(),
                        /* bzlLoadFunctionForInlining= */ null,
                        /* packageProgress= */ null,
                        PackageFunction.ActionOnIOExceptionReadingBuildFile.UseOriginalIOException
                            .INSTANCE,
                        GlobbingStrategy.SKYFRAME_HYBRID,
                        ignored -> ThreadStateReceiver.NULL_INSTANCE))
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction())
                .put(
                    SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                    new LocalRepositoryLookupFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.IGNORED_PACKAGE_PREFIXES,
                    new IgnoredPackagePrefixesFunction(
                        /* ignoredPackagePrefixesFile= */ PathFragment.EMPTY_FRAGMENT))
                .put(SkyFunctions.RESOLVED_HASH_VALUES, new ResolvedHashesFunction())
                .put(SkyFunctions.REPOSITORY_MAPPING, new RepositoryMappingFunction())
                .put(
                    SkyFunctions.EXTERNAL_PACKAGE,
                    new ExternalPackageFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    WorkspaceFileValue.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        ruleClassProvider,
                        packageFactory,
                        directories,
                        /* bzlLoadFunctionForInlining= */ null))
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryDelegatorFunction(
                        repositoryHandlers,
                        starlarkRepositoryFunction,
                        new AtomicBoolean(true),
                        ImmutableMap::of,
                        directories,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(ruleClassProvider, directories))
                .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(rootDirectory))
                .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction(rootDirectory))
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(SkyFunctions.SINGLE_EXTENSION_USAGES, new SingleExtensionUsagesFunction())
                .put(SkyFunctions.SINGLE_EXTENSION_EVAL, singleExtensionEvalFunction)
                .put(
                    SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                    new ClientEnvironmentFunction(new AtomicReference<>(ImmutableMap.of())))
                .build(),
            differencer);

    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder().setBool(BuildLanguageOptions.ENABLE_BZLMOD, true).build());
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.DEPENDENCY_FOR_UNCONDITIONAL_FETCHING.set(
        differencer, RepositoryDelegatorFunction.DONT_FETCH_UNCONDITIONALLY);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get());
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.OUTPUT_VERIFICATION_REPOSITORY_RULES.set(
        differencer, ImmutableSet.of());
    RepositoryDelegatorFunction.RESOLVED_FILE_FOR_VERIFICATION.set(differencer, Optional.empty());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    BazelModuleResolutionFunction.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.WARNING);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.OFF);

    // Set up a simple repo rule.
    registry.addModule(
        createModuleKey("data_repo", "1.0"), "module(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("data_repo~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("data_repo~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("data_repo~1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', 'data = '+json.encode(ctx.attr.data))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.string()})");
  }

  @Test
  public void simpleExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.tag(name='foo', data='fu')",
        "ext.tag(name='bar', data='ba')",
        "use_repo(ext, 'foo', 'bar')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("foo:fu bar:ba");
  }

  @Test
  public void simpleExtension_nonCanonicalLabel() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='my_module', version = '1.0')",
        "bazel_dep(name='data_repo', version='1.0')",
        "ext1 = use_extension('//:defs.bzl', 'ext')",
        "ext1.tag(name='foo', data='fu')",
        "use_repo(ext1, 'foo')",
        "ext2 = use_extension('@my_module//:defs.bzl', 'ext')",
        "ext2.tag(name='bar', data='ba')",
        "use_repo(ext2, 'bar')",
        "ext3 = use_extension('@//:defs.bzl', 'ext')",
        "ext3.tag(name='quz', data='qu')",
        "use_repo(ext3, 'quz')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "load('@quz//:data.bzl', quz_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data+' quz:'+quz_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("foo:fu bar:ba quz:qu");
  }

  @Test
  public void simpleExtension_nonCanonicalLabel_repoName() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='my_module', version = '1.0', repo_name='my_name')",
        "bazel_dep(name='data_repo', version='1.0')",
        "ext1 = use_extension('//:defs.bzl', 'ext')",
        "ext1.tag(name='foo', data='fu')",
        "use_repo(ext1, 'foo')",
        "ext2 = use_extension('@my_name//:defs.bzl', 'ext')",
        "ext2.tag(name='bar', data='ba')",
        "use_repo(ext2, 'bar')",
        "ext3 = use_extension('@//:defs.bzl', 'ext')",
        "ext3.tag(name='quz', data='qu')",
        "use_repo(ext3, 'quz')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "load('@quz//:data.bzl', quz_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data+' quz:'+quz_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("foo:fu bar:ba quz:qu");
  }

  @Test
  public void multipleModules() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='root',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='root')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='quux',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='foo@1.0')");
    registry.addModule(
        createModuleKey("bar", "2.0"),
        "module(name='bar',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='quux',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='bar@2.0')");
    registry.addModule(
        createModuleKey("quux", "1.0"),
        "module(name='quux',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='quux@1.0')");
    registry.addModule(
        createModuleKey("quux", "2.0"),
        "module(name='quux',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='quux@2.0')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = ''",
        "  for mod in ctx.modules:",
        "    data_str += mod.name + '@' + mod.version + (' (root): ' if mod.is_root else ': ')",
        "    for tag in mod.tags.tag:",
        "      data_str += tag.data",
        "    data_str += '\\n'",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo(
            "root@1.0 (root): root\nfoo@1.0: foo@1.0\nbar@2.0: bar@2.0\nquux@2.0: quux@2.0\n");
  }

  @Test
  public void multipleModules_devDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='root')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='foo@1.0')");
    registry.addModule(
        createModuleKey("bar", "2.0"),
        "module(name='bar',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='bar@2.0')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = 'modules:'",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + tag.data + ' ' + str(ctx.is_dev_dependency(tag))",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("modules: root True bar@2.0 False");
  }

  @Test
  public void multipleModules_ignoreDevDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='root')",
        "use_repo(ext,'ext_repo')");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='foo@1.0')");
    registry.addModule(
        createModuleKey("bar", "2.0"),
        "module(name='bar',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='bar@2.0')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = 'modules:'",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + tag.data + ' ' + str(ctx.is_dev_dependency(tag))",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, true);

    SkyKey skyKey =
        BzlLoadValue.keyForBuild(Label.parseCanonical("@@ext~1.0~ext~ext_repo//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("modules: bar@2.0 False");
  }

  @Test
  public void labels_readInModuleExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='//:requirements.txt')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(workspaceRoot.getRelative("requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='@bar//:requirements.txt')");
    registry.addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    scratch.file(modulesRoot.getRelative("bar~2.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("bar~2.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("bar~2.0/requirements.txt").getPathString(), "go to bed at 11pm.");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = 'requirements:'",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + ctx.read(tag.file).strip()",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("requirements: get up at 6am. go to bed at 11pm.");
  }

  @Test
  public void labels_passedOnToRepoRule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='//:requirements.txt')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(workspaceRoot.getRelative("requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='@bar//:requirements.txt')");
    registry.addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    scratch.file(modulesRoot.getRelative("bar~2.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("bar~2.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("bar~2.0/requirements.txt").getPathString(), "go to bed at 11pm.");

    registry.addModule(createModuleKey("ext", "1.0"), "module(name='ext',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  content = ' '.join([ctx.read(l).strip() for l in ctx.attr.files])",
        "  ctx.file('data.bzl', 'data='+json.encode(content))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'files':attr.label_list()})",
        "",
        "def _ext_impl(ctx):",
        "  data_files = []",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_files.append(tag.file)",
        "  data_repo(name='ext_repo',files=data_files)",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("get up at 6am. go to bed at 11pm.");
  }

  @Test
  public void labels_fromExtensionGeneratedRepo() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "myext = use_extension('//:defs.bzl','myext')",
        "use_repo(myext,'myrepo')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='@myrepo//:requirements.txt')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _myrepo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('requirements.txt', 'get up at 6am.')",
        "myrepo = repository_rule(implementation=_myrepo_impl)",
        "",
        "def _myext_impl(ctx):",
        "  myrepo(name='myrepo')",
        "myext=module_extension(implementation=_myext_impl)");
    scratch.file(workspaceRoot.getRelative("requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(createModuleKey("ext", "1.0"), "module(name='ext',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  content = ' '.join([ctx.read(l).strip() for l in ctx.attr.files])",
        "  ctx.file('data.bzl', 'data='+json.encode(content))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'files':attr.label_list()})",
        "",
        "def _ext_impl(ctx):",
        "  data_files = []",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_files.append(tag.file)",
        "  data_repo(name='ext_repo',files=data_files)",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("get up at 6am.");
  }

  @Test
  public void labels_constructedInModuleExtension_readInModuleExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag()",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~1.0/requirements.txt").getPathString(), "get up at 6am.");
    registry.addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    scratch.file(modulesRoot.getRelative("bar~2.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("bar~2.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("bar~2.0/requirements.txt").getPathString(), "go to bed at 11pm.");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        // The Label() call on the following line should work, using ext.1.0's repo mapping.
        "  data_str = 'requirements: ' + ctx.read(Label('@foo//:requirements.txt')).strip()",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + ctx.read(tag.file).strip()",
        "  data_repo(name='ext_repo',data=data_str)",
        // So should the attr.label default value on the following line.
        "tag=tag_class(attrs={'file':attr.label(default='@bar//:requirements.txt')})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("requirements: get up at 6am. go to bed at 11pm.");
  }

  @Test
  public void labels_constructedInModuleExtensionAsString_passedOnToRepoRule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~1.0/requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  content = ctx.read(ctx.attr.file).strip()",
        "  ctx.file('data.bzl', 'data='+json.encode(content))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'file':attr.label()})",
        "",
        "def _ext_impl(ctx):",
        // The label literal on the following line should be interpreted using ext.1.0's repo
        // mapping.
        "  data_repo(name='ext_repo',file='@foo//:requirements.txt')",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("get up at 6am.");
  }

  /** Tests that a complex-typed attribute (here, string_list_dict) behaves well on a tag. */
  @Test
  public void complexTypedAttribute() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.tag(data={'foo':['val1','val2'],'bar':['val3','val4']})",
        "use_repo(ext, 'foo', 'bar')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'data':attr.string_list_dict()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      for key in tag.data:",
        "        data_repo(name=key,data=','.join(tag.data[key]))",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo:val1,val2 bar:val3,val4");
  }

  /**
   * Tests that a complex-typed attribute (here, string_list_dict) behaves well when it has a
   * default value and is omitted in a tag.
   */
  @Test
  public void complexTypedAttribute_default() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.tag()",
        "use_repo(ext, 'foo', 'bar')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {",
        "  'data': attr.string_list_dict(",
        "    default = {'foo':['val1','val2'],'bar':['val3','val4']},",
        ")})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      for key in tag.data:",
        "        data_repo(name=key,data=','.join(tag.data[key]))",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo:val1,val2 bar:val3,val4");
  }

  @Test
  public void generatedReposHaveCorrectMappings() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@foo//:data.bzl', foo_data='data')",
        "load('@internal//:data.bzl', internal_data='data')",
        "data = 'foo: '+foo_data+' internal: '+internal_data",
        "\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "def _internal_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', 'data='+json.encode('internal-stuff'))",
        "internal_repo = repository_rule(implementation=_internal_repo_impl)",
        "",
        "def _ext_impl(ctx):",
        "  internal_repo(name='internal')",
        "  ext_repo(name='ext')",
        "ext=module_extension(implementation=_ext_impl)");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~1.0/BUILD").getPathString());
    scratch.file(modulesRoot.getRelative("foo~1.0/data.bzl").getPathString(), "data = 'foo-stuff'");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo: foo-stuff internal: internal-stuff");
  }

  @Test
  public void generatedReposHaveCorrectMappings_moduleOwnRepoName() throws Exception {
    // tests that things work correctly when the module specifies its own repo name (via
    // `module(repo_name=...)`).
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='foo',version='1.0',repo_name='bar')",
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(workspaceRoot.getRelative("data.bzl").getPathString(), "data='hello world'");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@bar//:data.bzl', bar_data='data')",
        "data = 'bar: '+bar_data",
        "\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "ext=module_extension(implementation=lambda ctx: ext_repo(name='ext'))");
    scratch.file(
        workspaceRoot.getRelative("ext_data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data='ext: ' + ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:ext_data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("ext: bar: hello world");
  }

  @Test
  public void generatedReposHaveCorrectMappings_internalRepoWins() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@foo//:data.bzl', foo_data='data')",
        "data = 'the foo I see is '+foo_data",
        "\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "def _internal_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', 'data='+json.encode('inner-foo'))",
        "internal_repo = repository_rule(implementation=_internal_repo_impl)",
        "",
        "def _ext_impl(ctx):",
        "  internal_repo(name='foo')",
        "  ext_repo(name='ext')",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~1.0/BUILD").getPathString());
    scratch.file(modulesRoot.getRelative("foo~1.0/data.bzl").getPathString(), "data = 'outer-foo'");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("the foo I see is inner-foo");
  }

  @Test
  public void generatedReposHaveCorrectMappings_strictDepsViolation() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@foo//:data.bzl', 'data')\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "def _ext_impl(ctx):",
        "  ext_repo(name='ext')",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains("No repository visible as '@foo' from repository '@_main~ext~ext'");
  }

  @Test
  public void wrongModuleExtensionLabel() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//foo/defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains(
            "Label '//foo/defs.bzl:defs.bzl' is invalid because 'foo/defs.bzl' is not a package");
  }

  @Test
  public void importNonExistentRepo() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "bazel_dep(name='data_repo', version='1.0')",
        "use_repo(ext,my_repo='missing_repo')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='ext',data='void')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@@_main~ext~ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains(
            "module extension \"ext\" from \"//:defs.bzl\" does not generate repository"
                + " \"missing_repo\", yet it is imported as \"my_repo\" in the usage at"
                + " /ws/MODULE.bazel:1:20");
  }

  @Test
  public void badRepoNameInExtensionImplFunction() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "bazel_dep(name='data_repo', version='1.0')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='_something',data='void')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent("invalid user-provided repo name '_something'");
  }

  @Test
  public void nativeExistingRuleIsEmpty() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  if not native.existing_rules():",
        "    data_repo(name='ext',data='haha')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data = ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("haha");
  }

  @Test
  public void extensionLoadsRepoFromAnotherExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'summarized_candy')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'exposed_candy')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "load('@@ext~1.0~ext~candy//:data.bzl', candy='data')",
        "load('@exposed_candy//:data.bzl', exposed_candy='data')",
        "def _ext_impl(ctx):",
        "  data_str = exposed_candy + ' (and ' + candy + ')'",
        "  data_repo(name='summarized_candy', data=data_str)",
        "my_ext=module_extension(implementation=_ext_impl)");

    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@summarized_candy//:data.bzl', data='data')",
        "candy_data = 'candy: ' + data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='candy', data='cotton candy')",
        "  data_repo(name='exposed_candy', data='lollipops')",
        "ext = module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("candy_data"))
        .isEqualTo("candy: lollipops (and cotton candy)");
  }

  @Test
  public void extensionRepoCtxReadsFromAnotherExtensionRepo() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')",
        // Repos from this extension (i.e. my_ext2) can still be used if their canonical name is
        // somehow known
        "my_ext2 = use_extension('@//:defs.bzl', 'my_ext2')");

    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_file = ctx.read(Label('@@_main~my_ext2~candy2//:data.bzl'))",
        "  data_repo(name='candy1',data=data_file)",
        "my_ext=module_extension(implementation=_ext_impl)",
        "def _ext_impl2(ctx):",
        "  data_repo(name='candy2',data='lollipops')",
        "my_ext2=module_extension(implementation=_ext_impl2)");

    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@candy1//:data.bzl', data='data')",
        "candy_data_file = data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw Objects.requireNonNull(result.getError().getException());
    }
    assertThat(result.get(skyKey).getModule().getGlobal("candy_data_file"))
        .isEqualTo("data = \"lollipops\"");
  }

  @Test
  public void testReportRepoAndBzlCycles_circularExtReposCtxRead() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')",
        "my_ext2 = use_extension('@//:defs.bzl', 'my_ext2')",
        "use_repo(my_ext2, 'candy2')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  ctx.read(Label('@candy2//:data.bzl'))",
        "  data_repo(name='candy1',data='lollipops')",
        "my_ext=module_extension(implementation=_ext_impl)",
        "def _ext_impl2(ctx):",
        "  ctx.read(Label('@candy1//:data.bzl'))",
        "  data_repo(name='candy2',data='lollipops')",
        "my_ext2=module_extension(implementation=_ext_impl2)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    SkyKey skyKey =
        PackageIdentifier.create(
            RepositoryName.createUnvalidated("_main~my_ext~candy1"), PathFragment.EMPTY_FRAGMENT);
    EvaluationResult<PackageValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getCycleInfo()).isNotEmpty();
    reporter.removeHandler(failFastHandler);
    cyclesReporter.reportCycles(
        result.getError().getCycleInfo(), skyKey, evaluationContext.getEventHandler());
    assertContainsEvent(
        "ERROR <no location>: Circular definition of repositories generated by module extensions"
            + " and/or .bzl files:\n"
            + ".-> @_main~my_ext~candy1\n"
            + "|   extension 'my_ext' defined in //:defs.bzl\n"
            + "|   @_main~my_ext2~candy2\n"
            + "|   extension 'my_ext2' defined in //:defs.bzl\n"
            + "`-- @_main~my_ext~candy1");
  }

  @Test
  public void testReportRepoAndBzlCycles_circularExtReposLoadInDefFile() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')",
        "my_ext2 = use_extension('@//:defs2.bzl', 'my_ext2')",
        "use_repo(my_ext2, 'candy2')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  ctx.read(Label('@candy2//:data.bzl'))",
        "  data_repo(name='candy1',data='lollipops')",
        "my_ext=module_extension(implementation=_ext_impl)");
    scratch.file(
        workspaceRoot.getRelative("defs2.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "load('@candy1//:data.bzl','data')",
        "def _ext_impl(ctx):",
        "  data_repo(name='candy2',data='lollipops')",
        "my_ext2=module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    SkyKey skyKey =
        PackageIdentifier.create(
            RepositoryName.createUnvalidated("_main~my_ext~candy1"),
            PathFragment.create("data.bzl"));
    EvaluationResult<PackageValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getCycleInfo()).isNotEmpty();
    reporter.removeHandler(failFastHandler);
    cyclesReporter.reportCycles(
        result.getError().getCycleInfo(), skyKey, evaluationContext.getEventHandler());
    assertContainsEvent(
        "ERROR <no location>: Circular definition of repositories generated by module extensions"
            + " and/or .bzl files:\n"
            + ".-> @_main~my_ext~candy1\n"
            + "|   extension 'my_ext' defined in //:defs.bzl\n"
            + "|   @_main~my_ext2~candy2\n"
            + "|   extension 'my_ext2' defined in //:defs2.bzl\n"
            + "|   //:defs2.bzl\n"
            + "|   @_main~my_ext~candy1//:data.bzl\n"
            + "`-- @_main~my_ext~candy1");
  }

  @Test
  public void testReportRepoAndBzlCycles_extRepoLoadSelfCycle() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "load('@candy1//:data.bzl','data')",
        "def _ext_impl(ctx):",
        "  data_repo(name='candy1',data='lollipops')",
        "my_ext=module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    SkyKey skyKey =
        PackageIdentifier.create(
            RepositoryName.createUnvalidated("_main~my_ext~candy1"),
            PathFragment.create("data.bzl"));
    EvaluationResult<PackageValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getCycleInfo()).isNotEmpty();
    reporter.removeHandler(failFastHandler);
    cyclesReporter.reportCycles(
        result.getError().getCycleInfo(), skyKey, evaluationContext.getEventHandler());
    assertContainsEvent(
        "ERROR <no location>: Circular definition of repositories generated by module extensions"
            + " and/or .bzl files:\n"
            + ".-> @_main~my_ext~candy1\n"
            + "|   extension 'my_ext' defined in //:defs.bzl\n"
            + "|   //:defs.bzl\n"
            + "|   @_main~my_ext~candy1//:data.bzl\n"
            + "`-- @_main~my_ext~candy1");
  }

  @Test
  public void extensionMetadata_exactlyOneArgIsNone() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_deps=['foo'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must both be specified or both be"
            + " unspecified");
  }

  @Test
  public void extensionMetadata_exactlyOneArgIsNoneDev() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_dev_deps=['foo'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must both be specified or both be"
            + " unspecified");
  }

  @Test
  public void extensionMetadata_allUsedTwice() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps='all',root_module_direct_dev_deps='all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "if one of root_module_direct_deps and root_module_direct_dev_deps is \"all\", the other"
            + " must be an empty list");
  }

  @Test
  public void extensionMetadata_allAndNone() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_deps='all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "if one of root_module_direct_deps and root_module_direct_dev_deps is \"all\", the other"
            + " must be an empty list");
  }

  @Test
  public void extensionMetadata_unsupportedString() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_deps='not_all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must be None, \"all\", or a list"
            + " of strings");
  }

  @Test
  public void extensionMetadata_unsupportedStringDev() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_dev_deps='not_all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must be None, \"all\", or a list"
            + " of strings");
  }

  @Test
  public void extensionMetadata_invalidRepoName() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['~invalid'],root_module_direct_dev_deps=[])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "in root_module_direct_deps: invalid user-provided repo name '~invalid': valid names may"
            + " contain only A-Z, a-z, 0-9, '-', '_', '.', and must start with a letter");
  }

  @Test
  public void extensionMetadata_invalidDevRepoName() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_dev_deps=['~invalid'],root_module_direct_deps=[])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "in root_module_direct_dev_deps: invalid user-provided repo name '~invalid': valid names"
            + " may contain only A-Z, a-z, 0-9, '-', '_', '.', and must start with a letter");
  }

  @Test
  public void extensionMetadata_duplicateRepo() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['dep','dep'],root_module_direct_dev_deps=[])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("in root_module_direct_deps: duplicate entry 'dep'");
  }

  @Test
  public void extensionMetadata_duplicateDevRepo() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=[],root_module_direct_dev_deps=['dep','dep'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("in root_module_direct_dev_deps: duplicate entry 'dep'");
  }

  @Test
  public void extensionMetadata_duplicateRepoAcrossTypes() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['dep'],root_module_direct_dev_deps=['dep'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "in root_module_direct_dev_deps: entry 'dep' is also in root_module_direct_deps");
  }

  @Test
  public void extensionMetadata() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(",
        "  ext,",
        "  'indirect_dep',",
        "  'invalid_dep',",
        "  my_direct_dep = 'direct_dep',",
        ")",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(",
        "  ext_dev,",
        "  'indirect_dev_dep',",
        "  'invalid_dev_dep',",
        "  my_direct_dev_dep = 'direct_dev_dep',",
        ")");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@my_direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=['direct_dep', 'missing_direct_dep'],",
        "    root_module_direct_dev_deps=['direct_dev_dep', 'missing_direct_dev_dep'],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:20: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Imported, but not created by the extension (will cause the build to fail):\n"
            + "    invalid_dep, invalid_dev_dep\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep, missing_direct_dev_dep\n"
            + "\n"
            + "Imported, but reported as indirect dependencies by the extension:\n"
            + "    indirect_dep, indirect_dev_dep\n"
            + "\n"
            + "\033[35m\033[1m ** You can use the following buildozer command(s) to fix these"
            + " issues:\033[0m\n"
            + "\n"
            + "buildozer 'use_repo_add @ext//:defs.bzl ext missing_direct_dep' //MODULE.bazel:all\n"
            + "buildozer 'use_repo_remove @ext//:defs.bzl ext indirect_dep invalid_dep'"
            + " //MODULE.bazel:all\n"
            + "buildozer 'use_repo_add dev @ext//:defs.bzl ext missing_direct_dev_dep'"
            + " //MODULE.bazel:all\n"
            + "buildozer 'use_repo_remove dev @ext//:defs.bzl ext indirect_dev_dep invalid_dev_dep'"
            + " //MODULE.bazel:all",
        ImmutableSet.of(EventKind.WARNING));
  }

  @Test
  public void extensionMetadata_all() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'direct_dep', 'indirect_dep', 'invalid_dep')",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'direct_dev_dep', 'indirect_dev_dep', 'invalid_dev_dep')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps='all',",
        "    root_module_direct_dev_deps=[],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "module extension \"ext\" from \"@ext~1.0//:defs.bzl\" does not generate repository "
                + "\"invalid_dep\", yet it is imported as \"invalid_dep\" in the usage at "
                + "/ws/MODULE.bazel:3:20");

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:20: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Imported, but not created by the extension (will cause the build to fail):\n"
            + "    invalid_dep, invalid_dev_dep\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep, missing_direct_dev_dep\n"
            + "\n"
            + "\033[35m\033[1m ** You can use the following buildozer command(s) to fix these"
            + " issues:\033[0m\n"
            + "\n"
            + "buildozer 'use_repo_add @ext//:defs.bzl ext direct_dev_dep indirect_dev_dep"
            + " missing_direct_dep missing_direct_dev_dep' //MODULE.bazel:all\n"
            + "buildozer 'use_repo_remove @ext//:defs.bzl ext invalid_dep' //MODULE.bazel:all\n"
            + "buildozer 'use_repo_remove dev @ext//:defs.bzl ext direct_dev_dep indirect_dev_dep"
            + " invalid_dev_dep' //MODULE.bazel:all",
        ImmutableSet.of(EventKind.WARNING));
  }

  @Test
  public void extensionMetadata_allDev() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'direct_dep', 'indirect_dep', 'invalid_dep')",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'direct_dev_dep', 'indirect_dev_dep', 'invalid_dev_dep')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=[],",
        "    root_module_direct_dev_deps='all',",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "module extension \"ext\" from \"@ext~1.0//:defs.bzl\" does not generate repository "
                + "\"invalid_dep\", yet it is imported as \"invalid_dep\" in the usage at "
                + "/ws/MODULE.bazel:3:20");

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:20: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Imported, but not created by the extension (will cause the build to fail):\n"
            + "    invalid_dep, invalid_dev_dep\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep, missing_direct_dev_dep\n"
            + "\n"
            + "\033[35m\033[1m ** You can use the following buildozer command(s) to fix these"
            + " issues:\033[0m\n"
            + "\n"
            + "buildozer 'use_repo_remove @ext//:defs.bzl ext direct_dep indirect_dep invalid_dep'"
            + " //MODULE.bazel:all\n"
            + "buildozer 'use_repo_add dev @ext//:defs.bzl ext direct_dep indirect_dep"
            + " missing_direct_dep missing_direct_dev_dep' //MODULE.bazel:all\n"
            + "buildozer 'use_repo_remove dev @ext//:defs.bzl ext invalid_dev_dep'"
            + " //MODULE.bazel:all",
        ImmutableSet.of(EventKind.WARNING));
  }

  @Test
  public void extensionMetadata_noRootUsage() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep', data='indirect_dep_data')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps='all',",
        "    root_module_direct_dev_deps=[],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");
    scratch.file(
        modulesRoot.getRelative("ext~1.0/data.bzl").getPathString(),
        "load('@indirect_dep//:data.bzl', indirect_dep_data='data')",
        "data = indirect_dep_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("@ext~1.0//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("indirect_dep_data");

    assertEventCount(0, eventCollector);
  }

  private EvaluationResult<SingleExtensionEvalValue> evaluateSimpleModuleExtension(
      String returnStatement) throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl', 'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_impl(ctx):",
        "  " + returnStatement,
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    ModuleExtensionId extensionId =
        ModuleExtensionId.create(Label.parseCanonical("//:defs.bzl"), "ext");
    reporter.removeHandler(failFastHandler);
    return evaluator.evaluate(
        ImmutableList.of(SingleExtensionEvalValue.key(extensionId)), evaluationContext);
  }
}
