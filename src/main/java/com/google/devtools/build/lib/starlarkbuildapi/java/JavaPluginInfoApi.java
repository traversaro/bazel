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
package com.google.devtools.build.lib.starlarkbuildapi.java;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.StarlarkConstructor;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaPluginInfoApi.JavaPluginDataApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;

/** Info object encapsulating information about Java plugins. */
public interface JavaPluginInfoApi<
        FileT extends FileApi,
        JavaPluginDataT extends JavaPluginDataApi,
        JavaOutputT extends JavaOutputApi<FileT>>
    extends StructApi {
  @StarlarkMethod(
      name = "plugins",
      doc =
          "Returns data about all plugins that a consuming target should apply."
              + "<p>This is typically either a <code>java_plugin</code> itself or a "
              + "<code>java_library</code> exporting one or more plugins. "
              + "<p>A <code>java_library</code> runs annotation processing with all plugins from "
              + "this field appearing in <code>deps</code> and <code>plugins</code> attributes.",
      structField = true)
  JavaPluginDataT plugins();

  @StarlarkMethod(
      name = "api_generating_plugins",
      doc =
          "Returns data about API generating plugins defined or exported by this target. "
              + "<p>Those annotation processors are applied to a Java target before producing "
              + "its header jars (which contain method signatures). When no API plugins are "
              + "present, header jars are generated from the sources, reducing critical path. "
              + "<p>The <code>api_generating_plugins</code> is a subset of <code>plugins</code>.",
      structField = true)
  JavaPluginDataT apiGeneratingPlugins();

  /** Info object encapsulating information about a Java compatible plugin. */
  interface JavaPluginDataApi extends StructApi {
    @StarlarkMethod(
        name = "processor_jars",
        doc = "Returns the jars needed to apply the encapsulated annotation processors.",
        structField = true)
    Depset /*<FileApi>*/ getProcessorJarsForStarlark();

    @StarlarkMethod(
        name = "processor_classes",
        doc =
            "Returns the fully qualified class names needed to apply the encapsulated annotation"
                + " processors.",
        structField = true)
    Depset /*<String>*/ getProcessorClassesForStarlark();

    @StarlarkMethod(
        name = "processor_data",
        doc =
            "Returns the files needed during execution by the encapsulated annotation processors.",
        structField = true)
    Depset /*<FileApi>*/ getProcessorDataForStarlark();

    @Override
    default boolean isImmutable() {
      return true;
    }
  }

  @StarlarkMethod(
      name = "java_outputs",
      doc = "Returns information about outputs of this Java/Java-like target.",
      structField = true)
  ImmutableList<JavaOutputT> getJavaOutputs();

  /** Provider class for {@link JavaPluginInfoApi} objects. */
  interface Provider<JavaInfoT extends JavaInfoApi<?, ?, ?>> extends ProviderApi {

    @StarlarkMethod(
        name = "JavaPluginInfo",
        doc = "The <code>JavaPluginInfo</code> constructor.",
        parameters = {
          @Param(
              name = "runtime_deps",
              allowedTypes = {
                @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class),
              },
              named = true,
              doc = "The library containing an annotation processor."),
          @Param(
              name = "processor_class",
              named = true,
              positional = false,
              allowedTypes = {
                @ParamType(type = String.class),
                @ParamType(type = NoneType.class),
              },
              doc =
                  "The fully qualified class name that the Java compiler uses as "
                      + "an entry point to the annotation processor."),
          @Param(
              name = "data",
              allowedTypes = {
                @ParamType(type = Sequence.class, generic1 = FileApi.class),
                @ParamType(type = Depset.class, generic1 = FileApi.class),
              },
              named = true,
              positional = false,
              defaultValue = "[]",
              doc = "The files needed by this annotation processor during execution."),
          @Param(
              name = "generates_api",
              named = true,
              positional = false,
              defaultValue = "False",
              doc =
                  "Set to true when this annotation processor generates API code. "
                      + "<p>Such annotation processor is applied to a Java target before producing "
                      + "its header jars (which contains method signatures). When no API plugins "
                      + "are present, header jars are generated from the sources, reducing the "
                      + "critical path. "
                      + "<p><em class=\"harmful\">WARNING: This parameter affects build "
                      + "performance, use it only if necessary.</em>"),
        },
        selfCall = true)
    @StarlarkConstructor
    JavaPluginInfoApi<?, ?, ?> javaPluginInfo(
        Sequence<?> runtimeDeps, Object processorClass, Object processorData, Boolean generatesApi)
        throws EvalException, TypeException;
  }
}
