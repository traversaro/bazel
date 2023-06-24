# Copyright 2022 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Starlark implementation of cc_proto_library"""

load(":common/proto/proto_info.bzl", "ProtoInfo")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/proto/proto_common.bzl", "ProtoLangToolchainInfo", proto_common = "proto_common_do_not_use")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_common.bzl", "cc_common")

ProtoCcFilesInfo = provider(fields = ["files"], doc = "Provide cc proto files.")
ProtoCcHeaderInfo = provider(fields = ["headers"], doc = "Provide cc proto headers.")

def _are_srcs_excluded(ctx, target):
    return not proto_common.experimental_should_generate_code(target[ProtoInfo], ctx.attr._aspect_cc_proto_toolchain[ProtoLangToolchainInfo], "cc_proto_library", target.label)

def _get_feature_configuration(ctx, target, cc_toolchain, proto_info):
    requested_features = list(ctx.features)
    unsupported_features = list(ctx.disabled_features)
    unsupported_features.append("parse_headers")
    unsupported_features.append("layering_check")
    if not _are_srcs_excluded(ctx, target) and len(proto_info.direct_sources) != 0:
        requested_features.append("header_modules")
    else:
        unsupported_features.append("header_modules")
    return cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = requested_features,
        unsupported_features = unsupported_features,
    )

def _check_proto_libraries_in_deps(deps):
    for dep in deps:
        if ProtoInfo in dep and CcInfo not in dep:
            fail("proto_library '{}' does not produce output for C++".format(dep.label), "deps")

def _get_output_files(ctx, target, suffixes):
    result = []
    for suffix in suffixes:
        result.extend(
            proto_common.declare_generated_files(
                actions = ctx.actions,
                proto_info = target[ProtoInfo],
                extension = suffix,
            ),
        )
    return result

def _get_strip_include_prefix(ctx, proto_info):
    proto_root = proto_info.proto_source_root
    if proto_root == "." or proto_root == ctx.label.workspace_root:
        return ""
    strip_include_prefix = ""
    if proto_root.startswith(ctx.bin_dir.path):
        proto_root = proto_root[len(ctx.bin_dir.path) + 1:]
    elif proto_root.startswith(ctx.genfiles_dir.path):
        proto_root = proto_root[len(ctx.genfiles_dir.path) + 1:]

    if proto_root.startswith(ctx.label.workspace_root):
        proto_root = proto_root[len(ctx.label.workspace_root):]

    strip_include_prefix = "//" + proto_root
    return strip_include_prefix

def _aspect_impl(target, ctx):
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    proto_info = target[ProtoInfo]
    feature_configuration = _get_feature_configuration(ctx, target, cc_toolchain, proto_info)
    proto_configuration = ctx.fragments.proto

    deps = []
    runtime = ctx.attr._aspect_cc_proto_toolchain[ProtoLangToolchainInfo].runtime
    if runtime != None:
        deps.append(runtime)
    deps.extend(getattr(ctx.rule.attr, "deps", []))
    _check_proto_libraries_in_deps(deps)

    # shouldProcessHeaders is set to true everytime, however java implementation of
    # compile gets this value from cc_toolchain.
    # Missing grep_includes, should not be necessary.
    # Missing stl compilation context, should not be necessary.

    outputs = []
    headers = []
    sources = []
    textual_hdrs = []
    additional_exported_hdrs = []

    if _are_srcs_excluded(ctx, target):
        header_provider = None

        # Hack: This is a proto_library for descriptor.proto or similar.
        #
        # The headers of those libraries are precomputed . They are also explicitly part of normal
        # cc_library rules that export them in their 'hdrs' attribute, and compile them as header
        # module if requested.
        #
        # The sole purpose of a proto_library with forbidden srcs is so other proto_library rules
        # can import them from a protocol buffer, as proto_library rules can only depend on other
        # proto library rules.
        for source in proto_info.direct_sources:
            for suffix in proto_configuration.cc_proto_library_header_suffixes():
                # We add the header to the proto_library's module map as additional (textual) header for
                # two reasons:
                # 1. The header will be exported via a normal cc_library, and a header must only be exported
                #    non-textually from one library.
                # 2. We want to allow proto_library rules that depend on the bootstrap-hack proto_library
                #    to be layering-checked; we need to provide a module map for the layering check to work.
                additional_exported_hdrs.append(source.short_path[:-len(source.extension)] + suffix)
    elif len(proto_info.direct_sources) != 0:
        headers = _get_output_files(
            ctx,
            target,
            proto_configuration.cc_proto_library_header_suffixes(),
        )
        sources = _get_output_files(
            ctx,
            target,
            proto_configuration.cc_proto_library_source_suffixes(),
        )
        outputs.extend(headers)
        outputs.extend(sources)
        header_provider = ProtoCcHeaderInfo(headers = depset(headers))
    else:
        # If this proto_library doesn't have sources, it provides the combined headers of all its
        # direct dependencies. Thus, if a direct dependency does have sources, the generated files
        # are also provided by this library. If a direct dependency does not have sources, it will
        # do the same thing, so that effectively this library looks through all source-less
        # proto_libraries and provides all generated headers of the proto_libraries with sources
        # that it depends on.
        transitive_headers = []
        for dep in getattr(ctx.rule.attr, "deps", []):
            if ProtoCcHeaderInfo not in dep:
                continue
            header_provider = dep[ProtoCcHeaderInfo]
            textual_hdrs.extend(header_provider.headers.to_list())
            transitive_headers.append(header_provider.headers)
        header_provider = ProtoCcHeaderInfo(headers = depset(transitive = transitive_headers))

    files_to_build = list(outputs)
    proto_common.compile(
        actions = ctx.actions,
        proto_info = proto_info,
        proto_lang_toolchain_info = ctx.attr._aspect_cc_proto_toolchain[ProtoLangToolchainInfo],
        generated_files = outputs,
        experimental_output_files = "multiple",
    )

    (cc_compilation_context, cc_compilation_outputs) = cc_common.compile(
        name = ctx.label.name,
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        compilation_contexts = cc_helper.get_compilation_contexts_from_deps(deps),
        hdrs_checking_mode = "loose",
        # Don't instrument the generated C++ files even when --collect_code_coverage is set.
        code_coverage_enabled = False,
        srcs = sources,
        public_hdrs = headers,
        additional_exported_hdrs = additional_exported_hdrs,
        textual_hdrs = textual_hdrs,
        strip_include_prefix = _get_strip_include_prefix(ctx, proto_info),
    )

    library_to_link = []
    deps_linking_contexts = cc_helper.get_linking_contexts_from_deps(deps)

    if not cc_helper.is_compilation_outputs_empty(cc_compilation_outputs):
        disallow_dynamic_library = False
        if not cc_common.is_enabled(feature_name = "supports_dynamic_linker", feature_configuration = feature_configuration):
            # TODO(dougk): Configure output artifact with action_config
            # once proto compile action is configurable from the crosstool.
            disallow_dynamic_library = True

        _, cc_linking_outputs = cc_common.create_linking_context_from_compilation_outputs(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = cc_toolchain,
            name = ctx.label.name,
            linking_contexts = deps_linking_contexts,
            compilation_outputs = cc_compilation_outputs,
            disallow_dynamic_library = disallow_dynamic_library,
            test_only_target = getattr(ctx.rule.attr, "testonly", False),
        )

        if cc_linking_outputs.library_to_link != None:
            library_to_link.append(cc_linking_outputs.library_to_link)

    contexts_to_merge = [cc_helper.build_linking_context_from_libraries(ctx, library_to_link)]
    contexts_to_merge.extend(deps_linking_contexts)
    cc_linking_context = cc_common.merge_linking_contexts(
        linking_contexts = contexts_to_merge,
    )

    cc_infos = [dep[CcInfo] for dep in deps if CcInfo in dep]
    cc_debug_info_context = cc_helper.merge_cc_debug_contexts(cc_compilation_outputs, cc_infos)

    cc_info = CcInfo(
        compilation_context = cc_compilation_context,
        linking_context = cc_linking_context,
        debug_context = cc_debug_info_context,
    )

    output_groups = OutputGroupInfo(temp_files_INTERNAL_ = cc_compilation_outputs.temps())

    # On Windows, dynamic library is not built by default, so don't add them to filesToBuild.
    if len(library_to_link) != 0:
        artifacts_to_build = library_to_link[0]
        if artifacts_to_build.static_library != None:
            files_to_build.append(artifacts_to_build.static_library)
        if artifacts_to_build.pic_static_library != None:
            files_to_build.append(artifacts_to_build.pic_static_library)
        if not cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "targets_windows"):
            if artifacts_to_build.resolved_symlink_dynamic_library != None:
                files_to_build.append(artifacts_to_build.resolved_symlink_dynamic_library)
            elif artifacts_to_build.dynamic_library != None:
                files_to_build.append(artifacts_to_build.dynamic_library)
            if artifacts_to_build.resolved_symlink_interface_library != None:
                files_to_build.append(artifacts_to_build.resolved_symlink_interface_library)
            elif artifacts_to_build.interface_library != None:
                files_to_build.append(artifacts_to_build.interface_library)

    providers = [
        cc_info,
        output_groups,
        ProtoCcFilesInfo(files = depset(files_to_build)),
    ]
    if header_provider != None:
        providers.append(header_provider)
    return providers

cc_proto_aspect = aspect(
    implementation = _aspect_impl,
    attr_aspects = ["deps"],
    fragments = ["cpp", "proto"],
    required_providers = [ProtoInfo],
    provides = [CcInfo],
    attrs = {
        "_aspect_cc_proto_toolchain": attr.label(
            default = configuration_field(fragment = "proto", name = "proto_toolchain_for_cc"),
        ),
        "_cc_toolchain": attr.label(default = "@bazel_tools//tools/cpp:current_cc_toolchain"),
    },
    toolchains = cc_helper.use_cpp_toolchain(),
)

def _impl(ctx):
    if len(ctx.attr.deps) != 1:
        fail(
            "'deps' attribute must contain exactly one label " +
            "(we didn't name it 'dep' for consistency). " +
            "The main use-case for multiple deps is to create a rule that contains several " +
            "other targets. This makes dependency bloat more likely. It also makes it harder" +
            "to remove unused deps.",
            attr = "deps",
        )
    dep = ctx.attr.deps[0]
    cc_info = dep[CcInfo]
    output_groups = dep[OutputGroupInfo]
    return [cc_info, DefaultInfo(files = dep[ProtoCcFilesInfo].files), output_groups]

cc_proto_library = rule(
    implementation = _impl,
    attrs = {
        "deps": attr.label_list(
            aspects = [cc_proto_aspect],
            allow_rules = ["proto_library"],
            allow_files = False,
        ),
    },
    provides = [CcInfo],
)
