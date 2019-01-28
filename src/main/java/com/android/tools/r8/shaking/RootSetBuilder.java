// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Descriptor;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDefinition;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.MethodSignatureEquivalence;
import com.android.tools.r8.utils.OffOrAuto;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RootSetBuilder {

  private final AppView<? extends AppInfo> appView;
  private final DirectMappedDexApplication application;
  private final Iterable<? extends ProguardConfigurationRule> rules;
  private final Map<DexDefinition, Set<ProguardKeepRule>> noShrinking = new IdentityHashMap<>();
  private final Set<DexDefinition> noOptimization = Sets.newIdentityHashSet();
  private final Set<DexDefinition> noObfuscation = Sets.newIdentityHashSet();
  private final LinkedHashMap<DexDefinition, DexDefinition> reasonAsked = new LinkedHashMap<>();
  private final Set<DexDefinition> keepPackageName = Sets.newIdentityHashSet();
  private final Set<ProguardConfigurationRule> rulesThatUseExtendsOrImplementsWrong =
      Sets.newIdentityHashSet();
  private final Set<DexDefinition> checkDiscarded = Sets.newIdentityHashSet();
  private final Set<DexMethod> alwaysInline = Sets.newIdentityHashSet();
  private final Set<DexMethod> forceInline = Sets.newIdentityHashSet();
  private final Set<DexMethod> neverInline = Sets.newIdentityHashSet();
  private final Set<DexMethod> keepParametersWithConstantValue = Sets.newIdentityHashSet();
  private final Set<DexMethod> keepUnusedArguments = Sets.newIdentityHashSet();
  private final Set<DexType> neverClassInline = Sets.newIdentityHashSet();
  private final Set<DexType> neverMerge = Sets.newIdentityHashSet();
  private final Map<DexDefinition, Map<DexDefinition, Set<ProguardKeepRule>>> dependentNoShrinking =
      new IdentityHashMap<>();
  private final Map<DexDefinition, ProguardMemberRule> noSideEffects = new IdentityHashMap<>();
  private final Map<DexDefinition, ProguardMemberRule> assumedValues = new IdentityHashMap<>();
  private final Set<DexReference> identifierNameStrings = Sets.newIdentityHashSet();
  private final InternalOptions options;

  private final DexStringCache dexStringCache = new DexStringCache();
  private final Set<ProguardIfRule> ifRules = Sets.newIdentityHashSet();

  public RootSetBuilder(
      AppView<? extends AppInfo> appView,
      DexApplication application,
      Iterable<? extends ProguardConfigurationRule> rules,
      InternalOptions options) {
    this.appView = appView;
    this.application = application.asDirect();
    this.rules = rules;
    this.options = options;
  }

  RootSetBuilder(
      AppView<? extends AppInfo> appView,
      Collection<ProguardIfRule> ifRules,
      InternalOptions options) {
    this(appView, appView.appInfo().app, ifRules, options);
  }

  // Process a class with the keep rule.
  private void process(
      DexClass clazz,
      ProguardConfigurationRule rule,
      ProguardIfRule ifRule) {
    if (!satisfyClassType(rule, clazz)) {
      return;
    }
    if (!satisfyAccessFlag(rule, clazz)) {
      return;
    }
    if (!satisfyAnnotation(rule, clazz)) {
      return;
    }
    // In principle it should make a difference whether the user specified in a class
    // spec that a class either extends or implements another type. However, proguard
    // seems not to care, so users have started to use this inconsistently. We are thus
    // inconsistent, as well, but tell them.
    // TODO(herhut): One day make this do what it says.
    if (rule.hasInheritanceClassName() && !satisfyInheritanceRule(clazz, rule)) {
      return;
    }

    if (rule.getClassNames().matches(clazz.type)) {
      Collection<ProguardMemberRule> memberKeepRules = rule.getMemberRules();
      Map<Predicate<DexDefinition>, DexDefinition> preconditionSupplier;
      if (rule instanceof ProguardKeepRule) {
        if (clazz.isLibraryClass()) {
          return;
        }
        switch (((ProguardKeepRule) rule).getType()) {
          case KEEP_CLASS_MEMBERS:
            // Members mentioned at -keepclassmembers always depend on their holder.
            preconditionSupplier = ImmutableMap.of(definition -> true, clazz);
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, preconditionSupplier, false);
            markMatchingVisibleFields(clazz, memberKeepRules, rule, preconditionSupplier, false);
            break;
          case KEEP_CLASSES_WITH_MEMBERS:
            if (!allRulesSatisfied(memberKeepRules, clazz)) {
              break;
            }
            // fallthrough;
          case KEEP:
            markClass(clazz, rule);
            preconditionSupplier = new HashMap<>();
            if (ifRule != null) {
              // Static members in -keep are pinned no matter what.
              preconditionSupplier.put(DexDefinition::isStaticMember, null);
              // Instance members may need to be kept even though the holder is not instantiated.
              preconditionSupplier.put(definition -> !definition.isStaticMember(), clazz);
            } else {
              // Members mentioned at -keep should always be pinned as long as that -keep rule is
              // not triggered conditionally.
              preconditionSupplier.put((definition -> true), null);
            }
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, preconditionSupplier, false);
            markMatchingVisibleFields(clazz, memberKeepRules, rule, preconditionSupplier, false);
            break;
          case CONDITIONAL:
            throw new Unreachable("-if rule will be evaluated separately, not here.");
        }
      } else if (rule instanceof ProguardIfRule) {
        throw new Unreachable("-if rule will be evaluated separately, not here.");
      } else if (rule instanceof ProguardCheckDiscardRule) {
        if (memberKeepRules.isEmpty()) {
          markClass(clazz, rule);
        } else {
          preconditionSupplier = ImmutableMap.of((definition -> true), clazz);
          markMatchingFields(clazz, memberKeepRules, rule, preconditionSupplier);
          markMatchingMethods(clazz, memberKeepRules, rule, preconditionSupplier);
        }
      } else if (rule instanceof ProguardWhyAreYouKeepingRule
          || rule instanceof ProguardKeepPackageNamesRule) {
        markClass(clazz, rule);
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null, true);
        markMatchingVisibleFields(clazz, memberKeepRules, rule, null, true);
      } else if (rule instanceof ProguardAssumeNoSideEffectRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null, true);
        markMatchingVisibleFields(clazz, memberKeepRules, rule, null, true);
      } else if (rule instanceof ClassMergingRule) {
        if (allRulesSatisfied(memberKeepRules, clazz)) {
          markClass(clazz, rule);
        }
      } else if (rule instanceof InlineRule
          || rule instanceof ConstantArgumentRule
          || rule instanceof UnusedArgumentRule) {
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ClassInlineRule) {
        if (allRulesSatisfied(memberKeepRules, clazz)) {
          markClass(clazz, rule);
        }
      } else if (rule instanceof ProguardAssumeValuesRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null, true);
        markMatchingVisibleFields(clazz, memberKeepRules, rule, null, true);
      } else {
        assert rule instanceof ProguardIdentifierNameStringRule;
        markMatchingFields(clazz, memberKeepRules, rule, null);
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      }
    }
  }

  private void runPerRule(
      ExecutorService executorService,
      List<Future<?>> futures,
      ProguardConfigurationRule rule,
      ProguardIfRule ifRule) {
    List<DexType> specifics = rule.getClassNames().asSpecificDexTypes();
    if (specifics != null) {
      // This keep rule only lists specific type matches.
      // This means there is no need to iterate over all classes.
      for (DexType type : specifics) {
        DexClass clazz = application.definitionFor(type);
        // Ignore keep rule iff it does not reference a class in the app.
        if (clazz != null) {
          process(clazz, rule, ifRule);
        }
      }
    } else {
      futures.add(executorService.submit(() -> {
        for (DexProgramClass clazz : application.classes()) {
          process(clazz, rule, ifRule);
        }
        if (rule.applyToLibraryClasses()) {
          for (DexLibraryClass clazz : application.libraryClasses()) {
            process(clazz, rule, ifRule);
          }
        }
      }));
    }
  }

  public RootSet run(ExecutorService executorService) throws ExecutionException {
    application.timing.begin("Build root set...");
    try {
      List<Future<?>> futures = new ArrayList<>();
      // Mark all the things explicitly listed in keep rules.
      if (rules != null) {
        for (ProguardConfigurationRule rule : rules) {
          if (rule instanceof ProguardIfRule) {
            ProguardIfRule ifRule = (ProguardIfRule) rule;
            ifRules.add(ifRule);
          } else {
            runPerRule(executorService, futures, rule, null);
          }
        }
        ThreadUtils.awaitFutures(futures);
      }
    } finally {
      application.timing.end();
    }
    return new RootSet(
        noShrinking,
        noOptimization,
        noObfuscation,
        ImmutableList.copyOf(reasonAsked.values()),
        keepPackageName,
        checkDiscarded,
        alwaysInline,
        forceInline,
        neverInline,
        keepParametersWithConstantValue,
        keepUnusedArguments,
        neverClassInline,
        neverMerge,
        noSideEffects,
        assumedValues,
        dependentNoShrinking,
        identifierNameStrings,
        ifRules);
  }

  IfRuleEvaluator getIfRuleEvaluator(
      Set<DexEncodedField> liveFields,
      Set<DexEncodedMethod> liveMethods,
      Set<DexEncodedMethod> targetedMethods,
      ExecutorService executorService) {
    return new IfRuleEvaluator(liveFields, liveMethods, targetedMethods, executorService);
  }

  class IfRuleEvaluator {

    private final Set<DexEncodedField> liveFields;
    private final Set<DexEncodedMethod> liveMethods;
    private final Set<DexEncodedMethod> targetedMethods;

    private final ExecutorService executorService;

    private final List<Future<?>> futures = new ArrayList<>();

    public IfRuleEvaluator(
        Set<DexEncodedField> liveFields,
        Set<DexEncodedMethod> liveMethods,
        Set<DexEncodedMethod> targetedMethods,
        ExecutorService executorService) {
      this.liveFields = liveFields;
      this.liveMethods = liveMethods;
      this.targetedMethods = targetedMethods;
      this.executorService = executorService;
    }

    public ConsequentRootSet run(Set<DexType> liveTypes) throws ExecutionException {
      application.timing.begin("Find consequent items for -if rules...");
      try {
        if (rules != null) {
          for (ProguardConfigurationRule rule : rules) {
            assert rule instanceof ProguardIfRule;
            ProguardIfRule ifRule = (ProguardIfRule) rule;
            // Depending on which types that trigger the -if rule, the application of the subsequent
            // -keep rule may vary (due to back references). So, we need to try all pairs of -if
            // rule and live types.
            for (DexType type : liveTypes) {
              DexClass clazz = appView.appInfo().definitionFor(type);
              if (clazz == null) {
                continue;
              }

              // Check if the class matches the if-rule.
              evaluateIfRule(ifRule, clazz, clazz);

              // Check if one of the types that have been merged into `clazz` satisfies the if-rule.
              if (options.enableVerticalClassMerging && appView.verticallyMergedClasses() != null) {
                for (DexType sourceType : appView.verticallyMergedClasses().getSourcesFor(type)) {
                  // Note that, although `sourceType` has been merged into `type`, the dex class for
                  // `sourceType` is still available until the second round of tree shaking. This
                  // way
                  // we can still retrieve the access flags of `sourceType`.
                  DexClass sourceClass = appView.appInfo().definitionFor(sourceType);
                  assert sourceClass != null;
                  evaluateIfRule(ifRule, sourceClass, clazz);
                }
              }
            }
          }
          ThreadUtils.awaitFutures(futures);
        }
      } finally {
        application.timing.end();
      }
      return new ConsequentRootSet(
          neverInline,
          neverClassInline,
          noShrinking,
          noOptimization,
          noObfuscation,
          dependentNoShrinking);
    }

    /**
     * Determines if `sourceClass` satisfies the given if-rule. If `sourceClass` has not been merged
     * into another class, then `targetClass` is the same as `sourceClass`. Otherwise, `targetClass`
     * denotes the class that `sourceClass` has been merged into.
     */
    private void evaluateIfRule(ProguardIfRule rule, DexClass sourceClass, DexClass targetClass) {
      if (!satisfyClassType(rule, sourceClass)) {
        return;
      }
      if (!satisfyAccessFlag(rule, sourceClass)) {
        return;
      }
      if (!satisfyAnnotation(rule, sourceClass)) {
        return;
      }
      if (!rule.getClassNames().matches(sourceClass.type)) {
        return;
      }
      if (rule.hasInheritanceClassName()) {
        // Note that, in presence of vertical class merging, we check if the resulting class
        // (i.e., the target class) satisfies the implements/extends-matcher.
        if (!satisfyInheritanceRule(targetClass, rule)) {
          // Try another live type since the current one doesn't satisfy the inheritance rule.
          return;
        }
      }
      Collection<ProguardMemberRule> memberKeepRules = rule.getMemberRules();
      if (memberKeepRules.isEmpty()) {
        materializeIfRule(rule);
        return;
      }

      Set<DexDefinition> filteredMembers = Sets.newIdentityHashSet();
      Iterables.addAll(
          filteredMembers,
          targetClass.fields(
              f ->
                  liveFields.contains(f)
                      && appView.graphLense().getOriginalFieldSignature(f.field).getHolder()
                          == sourceClass.type));
      Iterables.addAll(
          filteredMembers,
          targetClass.methods(
              m ->
                  (liveMethods.contains(m) || targetedMethods.contains(m))
                      && appView.graphLense().getOriginalMethodSignature(m.method).getHolder()
                          == sourceClass.type));

      // If the number of member rules to hold is more than live members, we can't make it.
      if (filteredMembers.size() < memberKeepRules.size()) {
        return;
      }

      // Depending on which members trigger the -if rule, the application of the subsequent
      // -keep rule may vary (due to back references). So, we need to try literally all
      // combinations of live members. But, we can at least limit the number of elements per
      // combination as the size of member rules to satisfy.
      Sets.combinations(filteredMembers, memberKeepRules.size())
          .forEach(
              combination -> {
                Collection<DexEncodedField> fieldsInCombination =
                    DexDefinition.filterDexEncodedField(combination.stream())
                        .collect(Collectors.toList());
                Collection<DexEncodedMethod> methodsInCombination =
                    DexDefinition.filterDexEncodedMethod(combination.stream())
                        .collect(Collectors.toList());
                // Member rules are combined as AND logic: if found unsatisfied member rule, this
                // combination of live members is not a good fit.
                boolean satisfied =
                    memberKeepRules.stream()
                        .allMatch(
                            memberRule ->
                                ruleSatisfiedByFields(memberRule, fieldsInCombination)
                                    || ruleSatisfiedByMethods(memberRule, methodsInCombination));
                if (satisfied) {
                  materializeIfRule(rule);
                }
              });
    }

    private void materializeIfRule(ProguardIfRule rule) {
      ProguardIfRule materializedRule = rule.materialize();

      // We need to abort class inlining of classes that could be matched by the condition of this
      // -if rule.
      ClassInlineRule neverClassInlineRuleForCondition =
          materializedRule.neverClassInlineRuleForCondition();
      if (neverClassInlineRuleForCondition != null) {
        runPerRule(executorService, futures, neverClassInlineRuleForCondition, materializedRule);
      }

      // If the condition of the -if rule has any members, then we need to keep these members to
      // ensure that the subsequent rule will be applied again in the second round of tree
      // shaking.
      InlineRule neverInlineRuleForCondition = materializedRule.neverInlineRuleForCondition();
      if (neverInlineRuleForCondition != null) {
        runPerRule(executorService, futures, neverInlineRuleForCondition, materializedRule);
      }

      // Keep whatever is required by the -if rule.
      runPerRule(executorService, futures, materializedRule.subsequentRule, materializedRule);
    }
  }

  private static DexDefinition testAndGetPrecondition(
      DexDefinition definition, Map<Predicate<DexDefinition>, DexDefinition> preconditionSupplier) {
    if (preconditionSupplier == null) {
      return null;
    }
    DexDefinition precondition = null;
    boolean conditionEverMatched = false;
    for (Entry<Predicate<DexDefinition>, DexDefinition> entry : preconditionSupplier.entrySet()) {
      if (entry.getKey().test(definition)) {
        precondition = entry.getValue();
        conditionEverMatched = true;
        break;
      }
    }
    // If precondition-supplier is given, there should be at least one predicate that holds.
    // Actually, there should be only one predicate as we break the loop when it is found.
    assert conditionEverMatched;
    return precondition;
  }

  private void markMatchingVisibleMethods(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      Map<Predicate<DexDefinition>, DexDefinition> preconditionSupplier,
      boolean includeLibraryClasses) {
    Set<Wrapper<DexMethod>> methodsMarked =
        options.forceProguardCompatibility ? null : new HashSet<>();
    DexClass startingClass = clazz;
    while (clazz != null) {
      if (!includeLibraryClasses && clazz.isLibraryClass()) {
        return;
      }
      // In compat mode traverse all direct methods in the hierarchy.
      if (clazz == startingClass || options.forceProguardCompatibility) {
        Arrays.stream(clazz.directMethods())
            .forEach(
                method -> {
                  DexDefinition precondition = testAndGetPrecondition(method, preconditionSupplier);
                  markMethod(method, memberKeepRules, methodsMarked, rule, precondition);
                });
      }
      Arrays.stream(clazz.virtualMethods())
          .forEach(
              method -> {
                DexDefinition precondition = testAndGetPrecondition(method, preconditionSupplier);
                markMethod(method, memberKeepRules, methodsMarked, rule, precondition);
              });
      clazz = clazz.superType == null ? null : application.definitionFor(clazz.superType);
    }
  }

  private void markMatchingMethods(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      Map<Predicate<DexDefinition>, DexDefinition> preconditionSupplier) {
    clazz.forEachMethod(
        method -> {
          DexDefinition precondition = testAndGetPrecondition(method, preconditionSupplier);
          markMethod(method, memberKeepRules, null, rule, precondition);
        });
  }

  private void markMatchingVisibleFields(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      Map<Predicate<DexDefinition>, DexDefinition> preconditionSupplier,
      boolean includeLibraryClasses) {
    while (clazz != null) {
      if (!includeLibraryClasses && clazz.isLibraryClass()) {
        return;
      }
      clazz.forEachField(
          field -> {
            DexDefinition precondition = testAndGetPrecondition(field, preconditionSupplier);
            markField(field, memberKeepRules, rule, precondition);
          });
      clazz = clazz.superType == null ? null : application.definitionFor(clazz.superType);
    }
  }

  private void markMatchingFields(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      Map<Predicate<DexDefinition>, DexDefinition> preconditionSupplier) {
    clazz.forEachField(
        field -> {
          DexDefinition precondition = testAndGetPrecondition(field, preconditionSupplier);
          markField(field, memberKeepRules, rule, precondition);
        });
  }

  // TODO(b/67934426): Test this code.
  public static void writeSeeds(
      AppInfoWithLiveness appInfo, PrintStream out, Predicate<DexType> include) {
    for (DexReference seed : appInfo.getPinnedItems()) {
      if (seed.isDexType()) {
        if (include.test(seed.asDexType())) {
          out.println(seed.toSourceString());
        }
      } else if (seed.isDexField()) {
        DexField field = seed.asDexField();
        if (include.test(field.clazz)) {
          out.println(
              field.clazz.toSourceString()
                  + ": "
                  + field.type.toSourceString()
                  + " "
                  + field.name.toSourceString());
        }
      } else {
        assert seed.isDexMethod();
        DexMethod method = seed.asDexMethod();
        if (!include.test(method.holder)) {
          continue;
        }
        out.print(method.holder.toSourceString() + ": ");
        DexEncodedMethod encodedMethod = appInfo.definitionFor(method);
        if (encodedMethod.accessFlags.isConstructor()) {
          if (encodedMethod.accessFlags.isStatic()) {
            out.print(Constants.CLASS_INITIALIZER_NAME);
          } else {
            String holderName = method.holder.toSourceString();
            String constrName = holderName.substring(holderName.lastIndexOf('.') + 1);
            out.print(constrName);
          }
        } else {
          out.print(
              method.proto.returnType.toSourceString() + " " + method.name.toSourceString());
        }
        boolean first = true;
        out.print("(");
        for (DexType param : method.proto.parameters.values) {
          if (!first) {
            out.print(",");
          }
          first = false;
          out.print(param.toSourceString());
        }
        out.println(")");
      }
    }
    out.close();
  }

  private boolean satisfyClassType(ProguardConfigurationRule rule, DexClass clazz) {
    return rule.getClassType().matches(clazz) != rule.getClassTypeNegated();
  }

  private static boolean satisfyAccessFlag(ProguardConfigurationRule rule, DexClass clazz) {
    return rule.getClassAccessFlags().containsAll(clazz.accessFlags)
        && rule.getNegatedClassAccessFlags().containsNone(clazz.accessFlags);
  }

  private static boolean satisfyAnnotation(ProguardConfigurationRule rule, DexClass clazz) {
    return containsAnnotation(rule.getClassAnnotation(), clazz.annotations);
  }

  private boolean satisfyInheritanceRule(DexClass clazz, ProguardConfigurationRule rule) {
    boolean extendsExpected = satisfyExtendsRule(clazz, rule);
    boolean implementsExpected = false;
    if (!extendsExpected) {
      implementsExpected = satisfyImplementsRule(clazz, rule);
    }
    if (extendsExpected || implementsExpected) {
      // Warn if users got it wrong, but only warn once. Also, only warn if rule is actually
      // specific (there is no correct way to write "keep class X that extends or implements from
      // a class or interface in package Y").
      if (rule.getInheritanceClassName().matchesSpecificType()) {
        if (rule.getInheritanceIsExtends()) {
          if (implementsExpected && rulesThatUseExtendsOrImplementsWrong.add(rule)) {
            assert options.testing.allowProguardRulesThatUseExtendsOrImplementsWrong;
            options.reporter.warning(
                new StringDiagnostic(
                    "The rule `" + rule + "` uses extends but actually matches implements."));
          }
        } else if (extendsExpected && rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          assert options.testing.allowProguardRulesThatUseExtendsOrImplementsWrong;
          options.reporter.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses implements but actually matches extends."));
        }
      }
      return true;
    }
    return false;
  }

  private boolean satisfyExtendsRule(DexClass clazz, ProguardConfigurationRule rule) {
    if (anySuperTypeMatchesExtendsRule(clazz.superType, rule)) {
      return true;
    }
    // It is possible that this class used to inherit from another class X, but no longer does it,
    // because X has been merged into `clazz`.
    return anySourceMatchesInheritanceRuleDirectly(clazz, rule, false);
  }

  private boolean anySuperTypeMatchesExtendsRule(DexType type, ProguardConfigurationRule rule) {
    while (type != null) {
      DexClass clazz = application.definitionFor(type);
      if (clazz == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      // TODO(b/110141157): Should the vertical class merger move annotations from the source to
      // the target class? If so, it is sufficient only to apply the annotation-matcher to the
      // annotations of `class`.
      if (rule.getInheritanceClassName().matches(clazz.type, appView)
          && containsAnnotation(rule.getInheritanceAnnotation(), clazz.annotations)) {
        return true;
      }
      type = clazz.superType;
    }
    return false;
  }

  private boolean satisfyImplementsRule(DexClass clazz, ProguardConfigurationRule rule) {
    if (anyImplementedInterfaceMatchesImplementsRule(clazz, rule)) {
      return true;
    }
    // It is possible that this class used to implement an interface I, but no longer does it,
    // because I has been merged into `clazz`.
    return anySourceMatchesInheritanceRuleDirectly(clazz, rule, true);
  }

  private boolean anyImplementedInterfaceMatchesImplementsRule(
      DexClass clazz, ProguardConfigurationRule rule) {
    // TODO(herhut): Maybe it would be better to do this breadth first.
    if (clazz == null) {
      return false;
    }
    for (DexType iface : clazz.interfaces.values) {
      DexClass ifaceClass = application.definitionFor(iface);
      if (ifaceClass == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      // TODO(b/110141157): Should the vertical class merger move annotations from the source to
      // the target class? If so, it is sufficient only to apply the annotation-matcher to the
      // annotations of `ifaceClass`.
      if (rule.getInheritanceClassName().matches(iface, appView)
          && containsAnnotation(rule.getInheritanceAnnotation(), ifaceClass.annotations)) {
        return true;
      }
      if (anyImplementedInterfaceMatchesImplementsRule(ifaceClass, rule)) {
        return true;
      }
    }
    if (clazz.superType == null) {
      return false;
    }
    DexClass superClass = application.definitionFor(clazz.superType);
    if (superClass == null) {
      // TODO(herhut): Warn about broken supertype chain?
      return false;
    }
    return anyImplementedInterfaceMatchesImplementsRule(superClass, rule);
  }

  private boolean anySourceMatchesInheritanceRuleDirectly(
      DexClass clazz, ProguardConfigurationRule rule, boolean isInterface) {
    // TODO(b/110141157): Figure out what to do with annotations. Should the annotations of
    // the DexClass corresponding to `sourceType` satisfy the `annotation`-matcher?
    return appView.verticallyMergedClasses() != null
        && appView.verticallyMergedClasses().getSourcesFor(clazz.type).stream()
            .filter(
                sourceType ->
                    appView.appInfo().definitionFor(sourceType).accessFlags.isInterface()
                        == isInterface)
            .anyMatch(rule.getInheritanceClassName()::matches);
  }

  private boolean allRulesSatisfied(Collection<ProguardMemberRule> memberKeepRules,
      DexClass clazz) {
    for (ProguardMemberRule rule : memberKeepRules) {
      if (!ruleSatisfied(rule, clazz)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether the given rule is satisfied by this clazz, not taking superclasses into
   * account.
   */
  private boolean ruleSatisfied(ProguardMemberRule rule, DexClass clazz) {
    return ruleSatisfiedByMethods(rule, clazz.directMethods())
        || ruleSatisfiedByMethods(rule, clazz.virtualMethods())
        || ruleSatisfiedByFields(rule, clazz.staticFields())
        || ruleSatisfiedByFields(rule, clazz.instanceFields());
  }

  private boolean ruleSatisfiedByMethods(
      ProguardMemberRule rule, Iterable<DexEncodedMethod> methods) {
    if (rule.getRuleType().includesMethods()) {
      for (DexEncodedMethod method : methods) {
        if (rule.matches(method, appView, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByMethods(ProguardMemberRule rule, DexEncodedMethod[] methods) {
    return ruleSatisfiedByMethods(rule, Arrays.asList(methods));
  }

  private boolean ruleSatisfiedByFields(ProguardMemberRule rule, Iterable<DexEncodedField> fields) {
    if (rule.getRuleType().includesFields()) {
      for (DexEncodedField field : fields) {
        if (rule.matches(field, appView, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByFields(ProguardMemberRule rule, DexEncodedField[] fields) {
    return ruleSatisfiedByFields(rule, Arrays.asList(fields));
  }

  static boolean containsAnnotation(ProguardTypeMatcher classAnnotation,
      DexAnnotationSet annotations) {
    if (classAnnotation == null) {
      return true;
    }
    if (annotations.isEmpty()) {
      return false;
    }
    for (DexAnnotation annotation : annotations.annotations) {
      if (classAnnotation.matches(annotation.annotation.type)) {
        return true;
      }
    }
    return false;
  }

  private void markMethod(
      DexEncodedMethod method,
      Collection<ProguardMemberRule> rules,
      Set<Wrapper<DexMethod>> methodsMarked,
      ProguardConfigurationRule context,
      DexDefinition precondition) {
    if (methodsMarked != null
        && methodsMarked.contains(MethodSignatureEquivalence.get().wrap(method.method))) {
      // Ignore, method is overridden in sub class.
      return;
    }
    for (ProguardMemberRule rule : rules) {
      if (rule.matches(method, appView, dexStringCache)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking method `%s` due to `%s { %s }`.", method, context,
              rule);
        }
        if (methodsMarked != null) {
          methodsMarked.add(MethodSignatureEquivalence.get().wrap(method.method));
        }
        addItemToSets(method, context, rule, precondition);
      }
    }
  }

  private void markField(
      DexEncodedField field,
      Collection<ProguardMemberRule> rules,
      ProguardConfigurationRule context,
      DexDefinition precondition) {
    for (ProguardMemberRule rule : rules) {
      if (rule.matches(field, appView, dexStringCache)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking field `%s` due to `%s { %s }`.", field, context,
              rule);
        }
        addItemToSets(field, context, rule, precondition);
      }
    }
  }

  private void markClass(DexClass clazz, ProguardConfigurationRule rule) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking class `%s` due to `%s`.", clazz.type, rule);
    }
    addItemToSets(clazz, rule, null, null);
  }

  private void includeDescriptor(DexDefinition item, DexType type, ProguardKeepRule context) {
    if (type.isVoidType()) {
      return;
    }
    if (type.isArrayType()) {
      type = type.toBaseType(application.dexItemFactory);
    }
    if (type.isPrimitiveType()) {
      return;
    }
    DexClass definition = appView.appInfo().definitionFor(type);
    if (definition == null || definition.isLibraryClass()) {
      return;
    }
    // Keep the type if the item is also kept.
    dependentNoShrinking
        .computeIfAbsent(item, x -> new IdentityHashMap<>())
        .computeIfAbsent(definition, k -> new HashSet<>())
        .add(context);
    // Unconditionally add to no-obfuscation, as that is only checked for surviving items.
    noObfuscation.add(definition);
  }

  private void includeDescriptorClasses(DexDefinition item, ProguardKeepRule context) {
    if (item.isDexEncodedMethod()) {
      DexMethod method = item.asDexEncodedMethod().method;
      includeDescriptor(item, method.proto.returnType, context);
      for (DexType value : method.proto.parameters.values) {
        includeDescriptor(item, value, context);
      }
    } else if (item.isDexEncodedField()) {
      DexField field = item.asDexEncodedField().field;
      includeDescriptor(item, field.type, context);
    } else {
      assert item.isDexClass();
    }
  }

  private synchronized void addItemToSets(
      DexDefinition item,
      ProguardConfigurationRule context,
      ProguardMemberRule rule,
      DexDefinition precondition) {
    if (context instanceof ProguardKeepRule) {
      if (item.isDexEncodedMethod() && item.asDexEncodedMethod().accessFlags.isSynthetic()) {
        // Don't keep synthetic methods (see b/120971047 for additional details).
        return;
      }

      ProguardKeepRule keepRule = (ProguardKeepRule) context;
      ProguardKeepRuleModifiers modifiers = keepRule.getModifiers();
      if (!modifiers.allowsShrinking) {
        if (precondition != null) {
          dependentNoShrinking
              .computeIfAbsent(precondition, x -> new IdentityHashMap<>())
              .computeIfAbsent(item, i -> new HashSet<>())
              .add(keepRule);
        } else {
          noShrinking.computeIfAbsent(item, i -> new HashSet<>()).add(keepRule);
        }
      }
      if (!modifiers.allowsOptimization) {
        noOptimization.add(item);
      }
      if (!modifiers.allowsObfuscation) {
        noObfuscation.add(item);
      }
      if (modifiers.includeDescriptorClasses) {
        includeDescriptorClasses(item, keepRule);
      }
    } else if (context instanceof ProguardAssumeNoSideEffectRule) {
      noSideEffects.put(item, rule);
    } else if (context instanceof ProguardWhyAreYouKeepingRule) {
      reasonAsked.computeIfAbsent(item, i -> i);
    } else if (context instanceof ProguardKeepPackageNamesRule) {
      keepPackageName.add(item);
    } else if (context instanceof ProguardAssumeValuesRule) {
      assumedValues.put(item, rule);
    } else if (context instanceof ProguardCheckDiscardRule) {
      checkDiscarded.add(item);
    } else if (context instanceof InlineRule) {
      if (item.isDexEncodedMethod()) {
        switch (((InlineRule) context).getType()) {
          case ALWAYS:
            alwaysInline.add(item.asDexEncodedMethod().method);
            break;
          case FORCE:
            forceInline.add(item.asDexEncodedMethod().method);
            break;
          case NEVER:
            neverInline.add(item.asDexEncodedMethod().method);
            break;
          default:
            throw new Unreachable();
        }
      }
    } else if (context instanceof ClassInlineRule) {
      switch (((ClassInlineRule) context).getType()) {
        case NEVER:
          if (item.isDexClass()) {
            neverClassInline.add(item.asDexClass().type);
          }
          break;
        default:
          throw new Unreachable();
      }
    } else if (context instanceof ClassMergingRule) {
      switch (((ClassMergingRule) context).getType()) {
        case NEVER:
          if (item.isDexClass()) {
            neverMerge.add(item.asDexClass().type);
          }
          break;
        default:
          throw new Unreachable();
      }
    } else if (context instanceof ProguardIdentifierNameStringRule) {
      if (item.isDexEncodedField()) {
        identifierNameStrings.add(item.asDexEncodedField().field);
      } else if (item.isDexEncodedMethod()) {
        identifierNameStrings.add(item.asDexEncodedMethod().method);
      }
    } else if (context instanceof ConstantArgumentRule) {
      if (item.isDexEncodedMethod()) {
        keepParametersWithConstantValue.add(item.asDexEncodedMethod().method);
      }
    } else if (context instanceof UnusedArgumentRule) {
      if (item.isDexEncodedMethod()) {
        keepUnusedArguments.add(item.asDexEncodedMethod().method);
      }
    }
  }

  public static class RootSet {

    public final Map<DexDefinition, Set<ProguardKeepRule>> noShrinking;
    public final Set<DexDefinition> noOptimization;
    public final Set<DexDefinition> noObfuscation;
    public final ImmutableList<DexDefinition> reasonAsked;
    public final Set<DexDefinition> keepPackageName;
    public final Set<DexDefinition> checkDiscarded;
    public final Set<DexMethod> alwaysInline;
    public final Set<DexMethod> forceInline;
    public final Set<DexMethod> neverInline;
    public final Set<DexMethod> keepConstantArguments;
    public final Set<DexMethod> keepUnusedArguments;
    public final Set<DexType> neverClassInline;
    public final Set<DexType> neverMerge;
    public final Map<DexDefinition, ProguardMemberRule> noSideEffects;
    public final Map<DexDefinition, ProguardMemberRule> assumedValues;
    private final Map<DexDefinition, Map<DexDefinition, Set<ProguardKeepRule>>>
        dependentNoShrinking;
    public final Set<DexReference> identifierNameStrings;
    public final Set<ProguardIfRule> ifRules;

    private RootSet(
        Map<DexDefinition, Set<ProguardKeepRule>> noShrinking,
        Set<DexDefinition> noOptimization,
        Set<DexDefinition> noObfuscation,
        ImmutableList<DexDefinition> reasonAsked,
        Set<DexDefinition> keepPackageName,
        Set<DexDefinition> checkDiscarded,
        Set<DexMethod> alwaysInline,
        Set<DexMethod> forceInline,
        Set<DexMethod> neverInline,
        Set<DexMethod> keepConstantArguments,
        Set<DexMethod> keepUnusedArguments,
        Set<DexType> neverClassInline,
        Set<DexType> neverMerge,
        Map<DexDefinition, ProguardMemberRule> noSideEffects,
        Map<DexDefinition, ProguardMemberRule> assumedValues,
        Map<DexDefinition, Map<DexDefinition, Set<ProguardKeepRule>>> dependentNoShrinking,
        Set<DexReference> identifierNameStrings,
        Set<ProguardIfRule> ifRules) {
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = noOptimization;
      this.noObfuscation = noObfuscation;
      this.reasonAsked = reasonAsked;
      this.keepPackageName = Collections.unmodifiableSet(keepPackageName);
      this.checkDiscarded = Collections.unmodifiableSet(checkDiscarded);
      this.alwaysInline = Collections.unmodifiableSet(alwaysInline);
      this.forceInline = Collections.unmodifiableSet(forceInline);
      this.neverInline = neverInline;
      this.keepConstantArguments = keepConstantArguments;
      this.keepUnusedArguments = keepUnusedArguments;
      this.neverClassInline = neverClassInline;
      this.neverMerge = Collections.unmodifiableSet(neverMerge);
      this.noSideEffects = Collections.unmodifiableMap(noSideEffects);
      this.assumedValues = Collections.unmodifiableMap(assumedValues);
      this.dependentNoShrinking = dependentNoShrinking;
      this.identifierNameStrings = Collections.unmodifiableSet(identifierNameStrings);
      this.ifRules = Collections.unmodifiableSet(ifRules);
    }

    // Add dependent items that depend on -if rules.
    void addDependentItems(
        Map<DexDefinition, Map<DexDefinition, Set<ProguardKeepRule>>> dependentItems) {
      dependentItems.forEach(
          (def, dependence) ->
              dependentNoShrinking
                  .computeIfAbsent(def, x -> new IdentityHashMap<>())
                  .putAll(dependence));
    }

    Map<DexDefinition, Set<ProguardKeepRule>> getDependentItems(DexDefinition item) {
      return Collections
          .unmodifiableMap(dependentNoShrinking.getOrDefault(item, Collections.emptyMap()));
    }

    public boolean verifyKeptFieldsAreAccessedAndLive(AppInfoWithLiveness appInfo) {
      for (DexDefinition definition : noShrinking.keySet()) {
        if (definition.isDexEncodedField()) {
          DexEncodedField field = definition.asDexEncodedField();
          if (field.isStatic() || isKeptDirectlyOrIndirectly(field.field.clazz, appInfo)) {
            // TODO(b/121354886): Enable asserts for reads and writes.
            /*assert appInfo.fieldsRead.contains(field.field)
                : "Expected kept field `" + field.field.toSourceString() + "` to be read";
            assert appInfo.fieldsWritten.contains(field.field)
                : "Expected kept field `" + field.field.toSourceString() + "` to be written";*/
            assert appInfo.liveFields.contains(field.field)
                : "Expected kept field `" + field.field.toSourceString() + "` to be live";
          }
        }
      }
      return true;
    }

    public boolean verifyKeptMethodsAreTargetedAndLive(AppInfoWithLiveness appInfo) {
      for (DexDefinition definition : noShrinking.keySet()) {
        if (definition.isDexEncodedMethod()) {
          DexEncodedMethod method = definition.asDexEncodedMethod();
          assert appInfo.targetedMethods.contains(method.method)
              : "Expected kept method `" + method.method.toSourceString() + "` to be targeted";

          if (!method.accessFlags.isAbstract()
              && isKeptDirectlyOrIndirectly(method.method.holder, appInfo)) {
            assert appInfo.liveMethods.contains(method.method)
                : "Expected non-abstract kept method `"
                    + method.method.toSourceString()
                    + "` to be live";
          }
        }
      }
      return true;
    }

    public boolean verifyKeptTypesAreLive(AppInfoWithLiveness appInfo) {
      for (DexDefinition definition : noShrinking.keySet()) {
        if (definition.isDexClass()) {
          DexClass clazz = definition.asDexClass();
          assert appInfo.liveTypes.contains(clazz.type)
              : "Expected kept type `" + clazz.type.toSourceString() + "` to be live";
        }
      }
      return true;
    }

    private boolean isKeptDirectlyOrIndirectly(DexType type, AppInfoWithLiveness appInfo) {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz == null) {
        return false;
      }
      if (noShrinking.containsKey(clazz)) {
        return true;
      }
      if (clazz.superType != null) {
        return isKeptDirectlyOrIndirectly(clazz.superType, appInfo);
      }
      return false;
    }

    public boolean verifyKeptItemsAreKept(
        DexApplication application, AppInfo appInfo, InternalOptions options) {
      if (options.getProguardConfiguration().hasApplyMappingFile()) {
        // TODO(b/121295633): Root set is obsolete after mapping has been applied.
        return true;
      }

      boolean isInterfaceMethodDesugaringEnabled =
          options.enableDesugaring
              && options.interfaceMethodDesugaring == OffOrAuto.Auto
              && !options.canUseDefaultAndStaticInterfaceMethods();

      Set<DexReference> pinnedItems =
          appInfo.hasLiveness() ? appInfo.withLiveness().pinnedItems : null;

      // Create a mapping from each required type to the set of required members on that type.
      Map<DexType, Set<DexDefinition>> requiredDefinitionsPerType = new IdentityHashMap<>();
      for (DexDefinition definition : noShrinking.keySet()) {
        // Check that `pinnedItems` is a super set of the root set.
        assert pinnedItems == null || pinnedItems.contains(definition.toReference());
        if (definition.isDexClass()) {
          DexType type = definition.toReference().asDexType();
          requiredDefinitionsPerType.putIfAbsent(type, Sets.newIdentityHashSet());
        } else {
          assert definition.isDexEncodedField() || definition.isDexEncodedMethod();
          Descriptor<?, ?> descriptor = definition.toReference().asDescriptor();
          requiredDefinitionsPerType
              .computeIfAbsent(descriptor.getHolder(), key -> Sets.newIdentityHashSet())
              .add(definition);
        }
      }

      // Run through each class in the program and check that it has members it must have.
      for (DexProgramClass clazz : application.classes()) {
        Set<DexDefinition> requiredDefinitions =
            requiredDefinitionsPerType.getOrDefault(clazz.type, ImmutableSet.of());

        Set<DexField> fields = null;
        Set<DexMethod> methods = null;

        for (DexDefinition requiredDefinition : requiredDefinitions) {
          if (requiredDefinition.isDexEncodedField()) {
            DexEncodedField requiredField = requiredDefinition.asDexEncodedField();
            if (fields == null) {
              // Create a Set of the fields to avoid quadratic behavior.
              fields =
                  Streams.stream(clazz.fields())
                      .map(DexEncodedField::getKey)
                      .collect(Collectors.toSet());
            }
            assert fields.contains(requiredField.field);
          } else if (requiredDefinition.isDexEncodedMethod()) {
            DexEncodedMethod requiredMethod = requiredDefinition.asDexEncodedMethod();
            if (isInterfaceMethodDesugaringEnabled) {
              if (clazz.isInterface() && requiredMethod.hasCode()) {
                // TODO(b/121240523): Ideally, these default interface methods should not be in the
                // root set.
                continue;
              }
            }
            if (methods == null) {
              // Create a Set of the methods to avoid quadratic behavior.
              methods =
                  Streams.stream(clazz.methods())
                      .map(DexEncodedMethod::getKey)
                      .collect(Collectors.toSet());
            }
            assert methods.contains(requiredMethod.method);
          } else {
            assert false;
          }
        }
        requiredDefinitionsPerType.remove(clazz.type);
      }

      // If the map is non-empty, then a type in the root set was not in the application.
      if (!requiredDefinitionsPerType.isEmpty()) {
        DexType type = requiredDefinitionsPerType.keySet().iterator().next();
        DexClass clazz = application.definitionFor(type);
        assert clazz == null || clazz.isProgramClass()
            : "Unexpected library type in root set: `" + type + "`";
        assert requiredDefinitionsPerType.isEmpty()
            : "Expected type `" + type.toSourceString() + "` to be present";
      }

      return true;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("RootSet");

      builder.append("\nnoShrinking: " + noShrinking.size());
      builder.append("\nnoOptimization: " + noOptimization.size());
      builder.append("\nnoObfuscation: " + noObfuscation.size());
      builder.append("\nreasonAsked: " + reasonAsked.size());
      builder.append("\nkeepPackageName: " + keepPackageName.size());
      builder.append("\ncheckDiscarded: " + checkDiscarded.size());
      builder.append("\nnoSideEffects: " + noSideEffects.size());
      builder.append("\nassumedValues: " + assumedValues.size());
      builder.append("\ndependentNoShrinking: " + dependentNoShrinking.size());
      builder.append("\nidentifierNameStrings: " + identifierNameStrings.size());
      builder.append("\nifRules: " + ifRules.size());

      builder.append("\n\nNo Shrinking:");
      noShrinking.keySet().stream()
          .sorted(Comparator.comparing(DexDefinition::toSourceString))
          .forEach(a -> builder
              .append("\n").append(a.toSourceString()).append(" ").append(noShrinking.get(a)));
      builder.append("\n");
      return builder.toString();
    }
  }

  // A partial RootSet that becomes live due to the enabled -if rule.
  static class ConsequentRootSet {
    final Set<DexMethod> neverInline;
    final Set<DexType> neverClassInline;
    final Map<DexDefinition, Set<ProguardKeepRule>> noShrinking;
    final Set<DexDefinition> noOptimization;
    final Set<DexDefinition> noObfuscation;
    final Map<DexDefinition, Map<DexDefinition, Set<ProguardKeepRule>>> dependentNoShrinking;

    private ConsequentRootSet(
        Set<DexMethod> neverInline,
        Set<DexType> neverClassInline,
        Map<DexDefinition, Set<ProguardKeepRule>> noShrinking,
        Set<DexDefinition> noOptimization,
        Set<DexDefinition> noObfuscation,
        Map<DexDefinition, Map<DexDefinition, Set<ProguardKeepRule>>> dependentNoShrinking) {
      this.neverInline = Collections.unmodifiableSet(neverInline);
      this.neverClassInline = Collections.unmodifiableSet(neverClassInline);
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = Collections.unmodifiableSet(noOptimization);
      this.noObfuscation = Collections.unmodifiableSet(noObfuscation);
      this.dependentNoShrinking = Collections.unmodifiableMap(dependentNoShrinking);
    }
  }
}
