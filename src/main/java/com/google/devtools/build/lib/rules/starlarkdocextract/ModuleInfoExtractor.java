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

package com.google.devtools.build.lib.rules.starlarkdocextract;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleClassFunctions.StarlarkRuleFunction;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.skydoc.rendering.DocstringParseException;
import com.google.devtools.build.skydoc.rendering.FunctionUtil;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.AspectInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.AttributeInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.AttributeType;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.ModuleInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.OriginKey;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.ProviderFieldInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.ProviderInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.ProviderNameGroup;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.RuleInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Structure;

/** API documentation extractor for a compiled, loaded Starlark module. */
final class ModuleInfoExtractor {
  private final Predicate<String> isWantedGlobal;
  private final RepositoryMapping repositoryMapping;

  @VisibleForTesting
  static final AttributeInfo IMPLICIT_NAME_ATTRIBUTE_INFO =
      AttributeInfo.newBuilder()
          .setName("name")
          .setType(AttributeType.NAME)
          .setMandatory(true)
          .setDocString("A unique name for this target.")
          .build();

  // TODO(b/276733504): do we want to add an implicit repo_mapping attribute for repo rules, as
  // FakeRepositoryModule currently does?

  /**
   * Constructs an instance of {@code ModuleInfoExtractor}.
   *
   * @param isWantedGlobal a filter applied to the module's globals; only those symbols which both
   *     are loadable (meaning the first character is alphabetic) and for which the filter returns
   *     true will be documented
   * @param repositoryMapping the repository mapping for the repo in which we want to render labels
   *     as strings
   */
  public ModuleInfoExtractor(
      Predicate<String> isWantedGlobal, RepositoryMapping repositoryMapping) {
    this.isWantedGlobal = isWantedGlobal;
    this.repositoryMapping = repositoryMapping;
  }

  /** Extracts structured documentation for the loadable symbols of a given module. */
  public ModuleInfo extractFrom(Module module) throws ExtractionException {
    ModuleInfo.Builder builder = ModuleInfo.newBuilder();
    Optional.ofNullable(module.getDocumentation()).ifPresent(builder::setModuleDocstring);
    Optional.ofNullable(BazelModuleContext.of(module))
        .map(bazelModuleContext -> bazelModuleContext.label().getDisplayForm(repositoryMapping))
        .ifPresent(builder::setFile);

    // We do two traversals over the module's globals: (1) find qualified names (including any
    // nesting structs) for providers loadable from this module; (2) build the documentation
    // proto, using the information from traversal 1 for provider names references by rules and
    // attributes.
    ProviderQualifiedNameCollector providerQualifiedNameCollector =
        new ProviderQualifiedNameCollector();
    providerQualifiedNameCollector.traverse(module);
    DocumentationExtractor documentationExtractor =
        new DocumentationExtractor(
            builder,
            isWantedGlobal,
            repositoryMapping,
            providerQualifiedNameCollector.buildQualifiedNames());
    documentationExtractor.traverse(module);
    return builder.build();
  }

  private static boolean isPublicName(String name) {
    return name.length() > 0 && Character.isAlphabetic(name.charAt(0));
  }

  /** An exception indicating that the module's API documentation could not be extracted. */
  public static class ExtractionException extends Exception {
    public ExtractionException(String message) {
      super(message);
    }

    public ExtractionException(Throwable cause) {
      super(cause);
    }

    public ExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * A stateful visitor which traverses a Starlark module's documentable globals, recursing into
   * structs.
   */
  private abstract static class GlobalsVisitor {
    public void traverse(Module module) throws ExtractionException {
      for (var entry : module.getGlobals().entrySet()) {
        String globalSymbol = entry.getKey();
        if (shouldVisitGlobal(globalSymbol)) {
          visit(globalSymbol, entry.getValue());
        }
      }
    }

    /** Returns whether the visitor should visit (and possibly recurse into) the given global. */
    protected abstract boolean shouldVisitGlobal(String globalSymbol);

    /**
     * @param qualifiedName the name under which the value may be accessed by a user of the module;
     *     for example, "foo.bar" for field bar of global struct foo
     * @param value the Starlark value
     */
    private void visit(String qualifiedName, Object value) throws ExtractionException {
      if (value instanceof StarlarkRuleFunction) {
        visitRule(qualifiedName, (StarlarkRuleFunction) value);
      } else if (value instanceof StarlarkProvider) {
        visitProvider(qualifiedName, (StarlarkProvider) value);
      } else if (value instanceof StarlarkFunction) {
        visitFunction(qualifiedName, (StarlarkFunction) value);
      } else if (value instanceof StarlarkDefinedAspect) {
        visitAspect(qualifiedName, (StarlarkDefinedAspect) value);
      } else if (value instanceof Structure) {
        visitStructure(qualifiedName, (Structure) value);
      }
      // else the value is a constant (string, list etc.), and we currently don't have a convention
      // for associating a doc string with one - so we don't emit documentation for it.
      // TODO(b/276733504): should we recurse into dicts to search for documentable values? Note
      // that dicts (unlike structs!) can have reference cycles, so we would need to track the set
      // of traversed entities.
    }

    protected void visitRule(String qualifiedName, StarlarkRuleFunction value)
        throws ExtractionException {}

    protected void visitProvider(String qualifiedName, StarlarkProvider value) {}

    protected void visitFunction(String qualifiedName, StarlarkFunction value)
        throws ExtractionException {}

    protected void visitAspect(String qualifiedName, StarlarkDefinedAspect aspect)
        throws ExtractionException {}

    private void visitStructure(String qualifiedName, Structure structure)
        throws ExtractionException {
      for (String fieldName : structure.getFieldNames()) {
        if (isPublicName(fieldName)) {
          try {
            Object fieldValue = structure.getValue(fieldName);
            if (fieldValue != null) {
              visit(String.format("%s.%s", qualifiedName, fieldName), fieldValue);
            }
          } catch (EvalException e) {
            throw new ExtractionException(
                String.format(
                    "in struct %s field %s: failed to read value", qualifiedName, fieldName),
                e);
          }
        }
      }
    }
  }

  /**
   * A {@link GlobalsVisitor} which finds the qualified names (including any nesting structs) for
   * providers loadable from this module.
   */
  private static final class ProviderQualifiedNameCollector extends GlobalsVisitor {
    private final LinkedHashMap<StarlarkProvider.Key, String> qualifiedNames =
        new LinkedHashMap<>();

    /**
     * Builds a map from the keys of the Starlark providers which were walked via {@link #traverse}
     * to the qualified names (including any structs) under which those providers may be accessed by
     * a user of this module.
     *
     * <p>If the same provider is accessible under multiple names, the first documentable name wins.
     */
    public ImmutableMap<StarlarkProvider.Key, String> buildQualifiedNames() {
      return ImmutableMap.copyOf(qualifiedNames);
    }

    /**
     * Returns true if the symbol is a loadable name (starts with an alphabetic character, not '_').
     *
     * <p>{@link ProviderQualifiedNameCollector} traverses all loadable providers, not filtering by
     * ModuleInfoExtractor#isWantedName, because a non-wanted provider symbol may still be referred
     * to by a wanted rule; we do not want the provider names emitted in rule documentation to vary
     * when we change the isWantedName filter.
     */
    @Override
    protected boolean shouldVisitGlobal(String globalSymbol) {
      return isPublicName(globalSymbol);
    }

    @Override
    protected void visitProvider(String qualifiedName, StarlarkProvider value) {
      qualifiedNames.putIfAbsent(value.getKey(), qualifiedName);
    }
  }

  /** A {@link GlobalsVisitor} which extracts documentation for symbols in this module. */
  private static final class DocumentationExtractor extends GlobalsVisitor {
    private final ModuleInfo.Builder moduleInfoBuilder;
    private final Predicate<String> isWantedGlobal;
    private final RepositoryMapping repositoryMapping;
    private final ImmutableMap<StarlarkProvider.Key, String> providerQualifiedNames;

    /**
     * @param moduleInfoBuilder builder to which {@link #traverse} adds extracted documentation
     * @param isWantedGlobal a filter applied to global symbols; only those symbols which both are
     *     loadable (meaning the first character is alphabetic) and for which the filter returns
     *     true will be documented
     * @param repositoryMapping repo mapping to use for stringifying labels
     * @param providerQualifiedNames a map from the keys of documentable Starlark providers loadable
     *     from this module to the qualified names (including structure namespaces) under which
     *     those providers are accessible to a user of this module
     */
    DocumentationExtractor(
        ModuleInfo.Builder moduleInfoBuilder,
        Predicate<String> isWantedGlobal,
        RepositoryMapping repositoryMapping,
        ImmutableMap<StarlarkProvider.Key, String> providerQualifiedNames) {
      this.moduleInfoBuilder = moduleInfoBuilder;
      this.isWantedGlobal = isWantedGlobal;
      this.repositoryMapping = repositoryMapping;
      this.providerQualifiedNames = providerQualifiedNames;
    }

    @Override
    protected boolean shouldVisitGlobal(String globalSymbol) {
      return isPublicName(globalSymbol) && isWantedGlobal.test(globalSymbol);
    }

    @Override
    protected void visitFunction(String qualifiedName, StarlarkFunction function)
        throws ExtractionException {
      try {
        moduleInfoBuilder.addFuncInfo(
            FunctionUtil.fromNameAndFunction(
                qualifiedName, function, /* withOriginKey= */ true, repositoryMapping));
      } catch (DocstringParseException e) {
        throw new ExtractionException(e);
      }
    }

    @Override
    protected void visitRule(String qualifiedName, StarlarkRuleFunction ruleFunction)
        throws ExtractionException {
      RuleInfo.Builder ruleInfoBuilder = RuleInfo.newBuilder();
      // Record the name under which this symbol is made accessible, which may differ from the
      // symbol's exported name
      ruleInfoBuilder.setRuleName(qualifiedName);
      // ... but record the origin rule key for cross references.
      ruleInfoBuilder.setOriginKey(
          OriginKey.newBuilder()
              .setName(ruleFunction.getName())
              .setFile(ruleFunction.getExtensionLabel().getDisplayForm(repositoryMapping)));
      ruleFunction.getDocumentation().ifPresent(ruleInfoBuilder::setDocString);
      RuleClass ruleClass = ruleFunction.getRuleClass();
      ruleInfoBuilder.addAttribute(IMPLICIT_NAME_ATTRIBUTE_INFO); // name comes first
      for (Attribute attribute : ruleClass.getAttributes()) {
        if (attribute.starlarkDefined()
            && attribute.isDocumented()
            && isPublicName(attribute.getPublicName())) {
          ruleInfoBuilder.addAttribute(buildAttributeInfo(attribute, "rule " + qualifiedName));
        }
      }
      ImmutableSet<StarlarkProviderIdentifier> advertisedProviders =
          ruleClass.getAdvertisedProviders().getStarlarkProviders();
      if (!advertisedProviders.isEmpty()) {
        ruleInfoBuilder.setAdvertisedProviders(buildProviderNameGroup(advertisedProviders));
      }
      moduleInfoBuilder.addRuleInfo(ruleInfoBuilder);
    }

    @Override
    protected void visitProvider(String qualifiedName, StarlarkProvider provider) {
      ProviderInfo.Builder providerInfoBuilder = ProviderInfo.newBuilder();
      // Record the name under which this symbol is made accessible, which may differ from the
      // symbol's exported name.
      // Note that it's possible that qualifiedName != getDocumentedProviderName() if the same
      // provider symbol is made accessible under more than one qualified name.
      // TODO(b/276733504): if a provider (or any other documentable entity) is made accessible
      // under two different qualified names, record them in a repeated field inside a single *Info
      // object, instead of producing a separate *Info object for each alias.
      providerInfoBuilder.setProviderName(qualifiedName);
      // Record the origin provider key for cross references.
      providerInfoBuilder.setOriginKey(
          OriginKey.newBuilder()
              .setName(provider.getName())
              .setFile(provider.getKey().getExtensionLabel().getDisplayForm(repositoryMapping)));
      provider.getDocumentation().ifPresent(providerInfoBuilder::setDocString);
      ImmutableMap<String, Optional<String>> schema = provider.getSchema();
      if (schema != null) {
        for (Map.Entry<String, Optional<String>> entry : schema.entrySet()) {
          if (isPublicName(entry.getKey())) {
            ProviderFieldInfo.Builder fieldInfoBuilder = ProviderFieldInfo.newBuilder();
            fieldInfoBuilder.setName(entry.getKey());
            entry.getValue().ifPresent(fieldInfoBuilder::setDocString);
            providerInfoBuilder.addFieldInfo(fieldInfoBuilder.build());
          }
        }
      }
      moduleInfoBuilder.addProviderInfo(providerInfoBuilder);
    }

    @Override
    protected void visitAspect(String qualifiedName, StarlarkDefinedAspect aspect)
        throws ExtractionException {
      AspectInfo.Builder aspectInfoBuilder = AspectInfo.newBuilder();
      // Record the name under which this symbol is made accessible, which may differ from the
      // symbol's exported name
      aspectInfoBuilder.setAspectName(qualifiedName);
      // ... but record the origin aspect key for cross references.
      aspectInfoBuilder.setOriginKey(
          OriginKey.newBuilder()
              .setName(aspect.getAspectClass().getExportedName())
              .setFile(
                  aspect.getAspectClass().getExtensionLabel().getDisplayForm(repositoryMapping)));
      aspect.getDocumentation().ifPresent(aspectInfoBuilder::setDocString);
      aspectInfoBuilder.addAllAspectAttribute(aspect.getAttributeAspects());
      aspectInfoBuilder.addAttribute(IMPLICIT_NAME_ATTRIBUTE_INFO); // name comes first
      for (Attribute attribute : aspect.getAttributes()) {
        if (isPublicName(attribute.getPublicName())) {
          aspectInfoBuilder.addAttribute(buildAttributeInfo(attribute, "aspect " + qualifiedName));
        }
      }
      moduleInfoBuilder.addAspectInfo(aspectInfoBuilder);
    }

    /**
     * Recursively transforms labels to strings via {@link Label#getShorthandDisplayForm}.
     *
     * @return the label's shorthand display string if {@code o} is a label; a container with label
     *     elements transformed into shorthand display strings recursively if {@code o} is a
     *     Starlark container; or the original object {@code o} if no label stringification was
     *     performed.
     */
    private Object stringifyLabels(Object o) {
      if (o instanceof Label) {
        return ((Label) o).getShorthandDisplayForm(repositoryMapping);
      } else if (o instanceof Map) {
        return stringifyLabelsOfMap((Map<?, ?>) o);
      } else if (o instanceof List) {
        return stringifyLabelsOfList((List<?>) o);
      } else {
        return o;
      }
    }

    private Object stringifyLabelsOfMap(Map<?, ?> dict) {
      boolean neededToStringify = false;
      ImmutableMap.Builder<Object, Object> builder = ImmutableMap.builder();
      for (Map.Entry<?, ?> entry : dict.entrySet()) {
        Object keyWithStringifiedLabels = stringifyLabels(entry.getKey());
        Object valueWithStringifiedLabels = stringifyLabels(entry.getValue());
        if (keyWithStringifiedLabels != entry.getKey()
            || valueWithStringifiedLabels != entry.getValue() /* as Objects */) {
          neededToStringify = true;
        }
        builder.put(keyWithStringifiedLabels, valueWithStringifiedLabels);
      }
      return neededToStringify ? Dict.immutableCopyOf(builder.buildOrThrow()) : dict;
    }

    private Object stringifyLabelsOfList(List<?> list) {
      boolean neededToStringify = false;
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object element : list) {
        Object elementWithStringifiedLabels = stringifyLabels(element);
        if (elementWithStringifiedLabels != element /* as Objects */) {
          neededToStringify = true;
        }
        builder.add(elementWithStringifiedLabels);
      }
      return neededToStringify ? StarlarkList.immutableCopyOf(builder.build()) : list;
    }

    private static AttributeType getAttributeType(Attribute attribute, String where)
        throws ExtractionException {
      Type<?> type = attribute.getType();
      if (type.equals(Type.INTEGER)) {
        return AttributeType.INT;
      } else if (type.equals(BuildType.LABEL)) {
        return AttributeType.LABEL;
      } else if (type.equals(Type.STRING)) {
        if (attribute.getPublicName().equals("name")) {
          return AttributeType.NAME;
        } else {
          return AttributeType.STRING;
        }
      } else if (type.equals(Type.STRING_LIST)) {
        return AttributeType.STRING_LIST;
      } else if (type.equals(Type.INTEGER_LIST)) {
        return AttributeType.INT_LIST;
      } else if (type.equals(BuildType.LABEL_LIST)) {
        return AttributeType.LABEL_LIST;
      } else if (type.equals(Type.BOOLEAN)) {
        return AttributeType.BOOLEAN;
      } else if (type.equals(BuildType.LABEL_KEYED_STRING_DICT)) {
        return AttributeType.LABEL_STRING_DICT;
      } else if (type.equals(Type.STRING_DICT)) {
        return AttributeType.STRING_DICT;
      } else if (type.equals(Type.STRING_LIST_DICT)) {
        return AttributeType.STRING_LIST_DICT;
      } else if (type.equals(BuildType.OUTPUT)) {
        return AttributeType.OUTPUT;
      } else if (type.equals(BuildType.OUTPUT_LIST)) {
        return AttributeType.OUTPUT_LIST;
      }

      throw new ExtractionException(
          String.format(
              "in %s attribute %s: unsupported type %s",
              where, attribute.getPublicName(), type.getClass().getSimpleName()));
    }

    private AttributeInfo buildAttributeInfo(Attribute attribute, String where)
        throws ExtractionException {
      AttributeInfo.Builder builder = AttributeInfo.newBuilder();
      builder.setName(attribute.getPublicName());
      Optional.ofNullable(attribute.getDoc()).ifPresent(builder::setDocString);
      builder.setType(getAttributeType(attribute, where));
      builder.setMandatory(attribute.isMandatory());
      for (ImmutableSet<StarlarkProviderIdentifier> providerGroup :
          attribute.getRequiredProviders().getStarlarkProviders()) {
        builder.addProviderNameGroup(buildProviderNameGroup(providerGroup));
      }

      if (!attribute.isMandatory()) {
        Object defaultValue = Attribute.valueToStarlark(attribute.getDefaultValueUnchecked());
        builder.setDefaultValue(new Printer().repr(stringifyLabels(defaultValue)).toString());
      }
      return builder.build();
    }

    /**
     * Returns the provider name suitable for use in this module's documentation. For a provider
     * loadable from this module, this is the qualified name (or more precisely, the first qualified
     * name) under which a user of this module may access it. For local providers and for providers
     * loaded but not re-exported via a global, it's the provider key name (a.k.a. {@code
     * provider.toString()}). For legacy struct providers, it's the legacy ID (which also happens to
     * be {@code provider.toString()}).
     */
    private String getDocumentedProviderName(StarlarkProviderIdentifier provider) {
      if (!provider.isLegacy()) {
        String qualifiedName = providerQualifiedNames.get(provider.getKey());
        if (qualifiedName != null) {
          return qualifiedName;
        }
      }
      return provider.toString();
    }

    private ProviderNameGroup buildProviderNameGroup(
        ImmutableSet<StarlarkProviderIdentifier> providerGroup) {
      ProviderNameGroup.Builder providerNameGroupBuilder = ProviderNameGroup.newBuilder();
      for (StarlarkProviderIdentifier provider : providerGroup) {
        providerNameGroupBuilder.addProviderName(getDocumentedProviderName(provider));
        OriginKey.Builder providerKeyBuilder = OriginKey.newBuilder().setName(provider.toString());
        if (!provider.isLegacy()) {
          if (provider.getKey() instanceof StarlarkProvider.Key) {
            Label definingModule = ((StarlarkProvider.Key) provider.getKey()).getExtensionLabel();
            providerKeyBuilder.setFile(definingModule.getDisplayForm(repositoryMapping));
          } else if (provider.getKey() instanceof BuiltinProvider.Key) {
            providerKeyBuilder.setFile("<native>");
          }
        }
        providerNameGroupBuilder.addOriginKey(providerKeyBuilder.build());
      }
      return providerNameGroupBuilder.build();
    }
  }
}
