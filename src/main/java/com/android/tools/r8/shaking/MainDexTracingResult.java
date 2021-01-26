// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramDefinition;
import com.android.tools.r8.utils.SetUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class MainDexTracingResult {

  public static MainDexTracingResult NONE =
      new MainDexTracingResult(ImmutableSet.of(), ImmutableSet.of());

  public static class Builder {
    public final AppInfo appInfo;
    public final Set<DexType> roots;
    public final Set<DexType> dependencies;

    private Builder(AppInfo appInfo) {
      this(appInfo, Sets.newIdentityHashSet(), Sets.newIdentityHashSet());
    }

    private Builder(AppInfo appInfo, MainDexTracingResult mainDexTracingResult) {
      this(
          appInfo,
          SetUtils.newIdentityHashSet(mainDexTracingResult.getRoots()),
          SetUtils.newIdentityHashSet(mainDexTracingResult.getDependencies()));
    }

    private Builder(AppInfo appInfo, Set<DexType> roots, Set<DexType> dependencies) {
      this.appInfo = appInfo;
      this.roots = roots;
      this.dependencies = dependencies;
    }

    public Builder addRoot(DexProgramClass clazz) {
      roots.add(clazz.getType());
      return this;
    }

    public Builder addRoot(DexType type) {
      assert isProgramClass(type) : type.toSourceString();
      roots.add(type);
      return this;
    }

    public Builder addRoots(Collection<DexType> rootSet) {
      assert rootSet.stream().allMatch(this::isProgramClass);
      this.roots.addAll(rootSet);
      return this;
    }

    public Builder addDependency(DexType type) {
      assert isProgramClass(type);
      dependencies.add(type);
      return this;
    }

    public boolean contains(DexType type) {
      return roots.contains(type) || dependencies.contains(type);
    }

    public MainDexTracingResult build() {
      return new MainDexTracingResult(roots, dependencies);
    }

    private boolean isProgramClass(DexType dexType) {
      DexClass clazz = appInfo.definitionFor(dexType);
      return clazz != null && clazz.isProgramClass();
    }
  }

  // The classes in the root set.
  private final Set<DexType> roots;
  // Additional dependencies (direct dependencies and runtime annotations with enums).
  private final Set<DexType> dependencies;
  // All main dex classes.
  private final Set<DexType> classes;

  private MainDexTracingResult(Set<DexType> roots, Set<DexType> dependencies) {
    assert Sets.intersection(roots, dependencies).isEmpty();
    this.roots = Collections.unmodifiableSet(roots);
    this.dependencies = Collections.unmodifiableSet(dependencies);
    this.classes = Sets.union(roots, dependencies);
  }

  public boolean canReferenceItemFromContextWithoutIncreasingMainDexSize(
      ProgramDefinition item, ProgramDefinition context) {
    // If the context is not a root, then additional references from inside the context will not
    // increase the size of the main dex.
    if (!isRoot(context)) {
      return true;
    }
    // Otherwise, require that the item is a root itself.
    return isRoot(item);
  }

  public boolean isEmpty() {
    assert !roots.isEmpty() || dependencies.isEmpty();
    return roots.isEmpty();
  }

  public Set<DexType> getRoots() {
    return roots;
  }

  public Set<DexType> getDependencies() {
    return dependencies;
  }

  public Set<DexType> getClasses() {
    return classes;
  }

  public boolean contains(ProgramDefinition clazz) {
    return contains(clazz.getContextType());
  }

  public boolean contains(DexType type) {
    return getClasses().contains(type);
  }

  private void collectTypesMatching(
      Set<DexType> types, Predicate<DexType> predicate, Consumer<DexType> consumer) {
    types.forEach(
        type -> {
          if (predicate.test(type)) {
            consumer.accept(type);
          }
        });
  }

  public boolean isRoot(ProgramDefinition definition) {
    return getRoots().contains(definition.getContextType());
  }

  public boolean isRoot(DexType type) {
    return getRoots().contains(type);
  }

  public boolean isDependency(ProgramDefinition definition) {
    return getDependencies().contains(definition.getContextType());
  }

  public MainDexTracingResult prunedCopy(AppInfoWithLiveness appInfo) {
    Builder builder = builder(appInfo);
    Predicate<DexType> wasPruned = appInfo::wasPruned;
    collectTypesMatching(roots, wasPruned.negate(), builder::addRoot);
    collectTypesMatching(dependencies, wasPruned.negate(), builder::addDependency);
    return builder.build();
  }

  public static Builder builder(AppInfo appInfo) {
    return new Builder(appInfo);
  }

  public Builder extensionBuilder(AppInfo appInfo) {
    return new Builder(appInfo, this);
  }
}
