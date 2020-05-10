// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import static com.android.tools.r8.kotlin.KotlinMetadataUtils.isValidMethodDescriptor;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.kotlin.KotlinMetadataUtils.KmPropertyProcessor;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.Reporter;
import com.google.common.collect.ImmutableList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import kotlinx.metadata.KmDeclarationContainer;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.KmTypeAlias;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmMethodSignature;

// Holds information about KmDeclarationContainer
public class KotlinDeclarationContainerInfo {

  private final List<KotlinTypeAliasInfo> typeAliases;
  // The functions in notBackedFunctions are KmFunctions where we could not find a representative.
  private final List<KotlinFunctionInfo> functionsWithNoBacking;
  // The properties in propertiesWithNoBacking are KmProperties where we could not find a getter,
  // setter or backing field.
  private final List<KotlinPropertyInfo> propertiesWithNoBacking;

  private KotlinDeclarationContainerInfo(
      List<KotlinTypeAliasInfo> typeAliases,
      List<KotlinFunctionInfo> functionsWithNoBacking,
      List<KotlinPropertyInfo> propertiesWithNoBacking) {
    this.typeAliases = typeAliases;
    this.functionsWithNoBacking = functionsWithNoBacking;
    this.propertiesWithNoBacking = propertiesWithNoBacking;
  }

  public static KotlinDeclarationContainerInfo create(
      KmDeclarationContainer container,
      Map<String, DexEncodedMethod> methodSignatureMap,
      Map<String, DexEncodedField> fieldSignatureMap,
      DexDefinitionSupplier definitionSupplier,
      Reporter reporter) {
    ImmutableList.Builder<KotlinFunctionInfo> notBackedFunctions = ImmutableList.builder();
    for (KmFunction kmFunction : container.getFunctions()) {
      JvmMethodSignature signature = JvmExtensionsKt.getSignature(kmFunction);
      if (signature == null) {
        assert false;
        continue;
      }
      KotlinFunctionInfo kotlinFunctionInfo =
          KotlinFunctionInfo.create(kmFunction, definitionSupplier, reporter);
      DexEncodedMethod method = methodSignatureMap.get(signature.asString());
      if (method == null) {
        notBackedFunctions.add(kotlinFunctionInfo);
        if (!isValidMethodDescriptor(signature.getDesc())) {
          // TODO(b/155536535): Enable this assert.
          // appView
          //     .options()
          //     .reporter
          //     .info(KotlinMetadataDiagnostic.invalidMethodDescriptor(signature.asString()));
        } else {
          // TODO(b/154348568): Enable the assertion below.
          // assert false : "Could not find method with signature " + signature.asString();
        }
        continue;
      }
      method.setKotlinMemberInfo(kotlinFunctionInfo);
    }

    ImmutableList.Builder<KotlinPropertyInfo> notBackedProperties = ImmutableList.builder();
    for (KmProperty kmProperty : container.getProperties()) {
      KotlinPropertyInfo kotlinPropertyInfo =
          KotlinPropertyInfo.create(kmProperty, definitionSupplier, reporter);
      KmPropertyProcessor propertyProcessor = new KmPropertyProcessor(kmProperty);
      boolean hasBacking = false;
      if (propertyProcessor.fieldSignature() != null) {
        DexEncodedField field =
            fieldSignatureMap.get(propertyProcessor.fieldSignature().asString());
        if (field != null) {
          hasBacking = true;
          field.setKotlinMemberInfo(kotlinPropertyInfo);
        }
      }
      if (propertyProcessor.getterSignature() != null) {
        DexEncodedMethod method =
            methodSignatureMap.get(propertyProcessor.getterSignature().asString());
        if (method != null) {
          hasBacking = true;
          method.setKotlinMemberInfo(kotlinPropertyInfo);
        }
      }
      if (propertyProcessor.setterSignature() != null) {
        DexEncodedMethod method =
            methodSignatureMap.get(propertyProcessor.setterSignature().asString());
        if (method != null) {
          hasBacking = true;
          method.setKotlinMemberInfo(kotlinPropertyInfo);
        }
      }
      if (!hasBacking) {
        notBackedProperties.add(kotlinPropertyInfo);
      }
    }
    return new KotlinDeclarationContainerInfo(
        getTypeAliases(container.getTypeAliases(), definitionSupplier, reporter),
        notBackedFunctions.build(),
        notBackedProperties.build());
  }

  private static List<KotlinTypeAliasInfo> getTypeAliases(
      List<KmTypeAlias> aliases, DexDefinitionSupplier definitionSupplier, Reporter reporter) {
    ImmutableList.Builder<KotlinTypeAliasInfo> builder = ImmutableList.builder();
    for (KmTypeAlias alias : aliases) {
      builder.add(KotlinTypeAliasInfo.create(alias, definitionSupplier, reporter));
    }
    return builder.build();
  }

  public void rewrite(
      KmVisitorProviders.KmFunctionVisitorProvider functionProvider,
      KmVisitorProviders.KmPropertyVisitorProvider propertyProvider,
      KmVisitorProviders.KmTypeAliasVisitorProvider typeAliasProvider,
      DexClass clazz,
      AppView<AppInfoWithLiveness> appView,
      NamingLens namingLens) {
    // Type aliases only have a representation here, so we can generate them directly.
    for (KotlinTypeAliasInfo typeAlias : typeAliases) {
      typeAlias.rewrite(typeAliasProvider, appView, namingLens);
    }
    // For properties, we need to combine potentially a field, setter and getter.
    Map<KotlinPropertyInfo, KotlinPropertyGroup> properties = new IdentityHashMap<>();
    for (DexEncodedField field : clazz.fields()) {
      if (field.getKotlinMemberInfo().isFieldProperty()) {
        properties
            .computeIfAbsent(
                field.getKotlinMemberInfo().asFieldProperty(), ignored -> new KotlinPropertyGroup())
            .setBackingField(field);
      }
    }
    for (DexEncodedMethod method : clazz.methods()) {
      if (method.getKotlinMemberInfo().isFunction()) {
        method
            .getKotlinMemberInfo()
            .asFunction()
            .rewrite(functionProvider, method, appView, namingLens);
        continue;
      }
      KotlinPropertyInfo kotlinPropertyInfo = method.getKotlinMemberInfo().asProperty();
      if (kotlinPropertyInfo == null) {
        continue;
      }
      KotlinPropertyGroup kotlinPropertyGroup =
          properties.computeIfAbsent(kotlinPropertyInfo, ignored -> new KotlinPropertyGroup());
      if (method.method.proto.returnType == appView.dexItemFactory().voidType) {
        // This is a setter.
        kotlinPropertyGroup.setSetter(method);
      } else {
        kotlinPropertyGroup.setGetter(method);
      }
    }
    for (KotlinPropertyInfo kotlinPropertyInfo : properties.keySet()) {
      KotlinPropertyGroup kotlinPropertyGroup = properties.get(kotlinPropertyInfo);
      kotlinPropertyInfo.rewrite(
          propertyProvider,
          kotlinPropertyGroup.backingField,
          kotlinPropertyGroup.getter,
          kotlinPropertyGroup.setter,
          appView,
          namingLens);
    }
    // Add all not backed functions and properties.
    for (KotlinFunctionInfo notBackedFunction : functionsWithNoBacking) {
      notBackedFunction.rewrite(functionProvider, null, appView, namingLens);
    }
    for (KotlinPropertyInfo notBackedProperty : propertiesWithNoBacking) {
      notBackedProperty.rewrite(propertyProvider, null, null, null, appView, namingLens);
    }
  }

  public static class KotlinPropertyGroup {

    private DexEncodedField backingField = null;
    private DexEncodedMethod setter = null;
    private DexEncodedMethod getter = null;

    void setBackingField(DexEncodedField backingField) {
      assert this.backingField == null;
      this.backingField = backingField;
    }

    void setGetter(DexEncodedMethod getter) {
      assert this.getter == null;
      this.getter = getter;
    }

    void setSetter(DexEncodedMethod setter) {
      assert this.setter == null;
      this.setter = setter;
    }
  }
}