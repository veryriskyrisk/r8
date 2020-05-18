// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.code.InvokeStaticRange;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.ResolutionResult.SingleResolutionResult;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.AnalysisAssumption;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.Query;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.DefaultInliningOracle;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.Inliner.InlineAction;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.ir.optimize.InliningConstraints;
import com.android.tools.r8.ir.optimize.inliner.WhyAreYouNotInliningReporter;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.function.Predicate;

public class InvokeStatic extends InvokeMethod {

  private final boolean isInterface;

  public InvokeStatic(DexMethod target, Value result, List<Value> arguments) {
    this(target, result, arguments, false);
    assert target.proto.parameters.size() == arguments.size();
  }

  public InvokeStatic(DexMethod target, Value result, List<Value> arguments, boolean isInterface) {
    super(target, result, arguments);
    this.isInterface = isInterface;
  }

  @Override
  public int opcode() {
    return Opcodes.INVOKE_STATIC;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Type getType() {
    return Type.STATIC;
  }

  @Override
  protected String getTypeString() {
    return "Static";
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new InvokeStaticRange(firstRegister, argumentRegisters, getInvokedMethod());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.android.tools.r8.code.InvokeStatic(
          argumentRegistersCount,
          getInvokedMethod(),
          individualArgumentRegisters[0],  // C
          individualArgumentRegisters[1],  // D
          individualArgumentRegisters[2],  // E
          individualArgumentRegisters[3],  // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeStatic() && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeStatic() {
    return true;
  }

  @Override
  public InvokeStatic asInvokeStatic() {
    return this;
  }

  @Override
  public DexEncodedMethod lookupSingleTarget(AppView<?> appView, ProgramMethod context) {
    DexMethod invokedMethod = getInvokedMethod();
    if (appView.appInfo().hasLiveness()) {
      AppInfoWithLiveness appInfo = appView.appInfo().withLiveness();
      DexEncodedMethod result = appInfo.lookupStaticTarget(invokedMethod, context);
      assert verifyD8LookupResult(
          result, appView.appInfo().lookupStaticTargetOnItself(invokedMethod, context));
      return result;
    }
    // Allow optimizing static library invokes in D8.
    DexClass clazz = appView.definitionFor(getInvokedMethod().holder);
    if (clazz != null
        && (clazz.isLibraryClass() || appView.libraryMethodOptimizer().isModeled(clazz.type))) {
      return appView.definitionFor(getInvokedMethod());
    }
    // In D8, we can treat invoke-static instructions as having a single target if the invoke is
    // targeting a method in the enclosing class.
    return appView.appInfo().lookupStaticTargetOnItself(invokedMethod, context);
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, ProgramMethod context) {
    return inliningConstraints.forInvokeStatic(getInvokedMethod(), context.getHolder());
  }

  @Override
  public InlineAction computeInlining(
      ProgramMethod singleTarget,
      Reason reason,
      DefaultInliningOracle decider,
      ClassInitializationAnalysis classInitializationAnalysis,
      WhyAreYouNotInliningReporter whyAreYouNotInliningReporter) {
    return decider.computeForInvokeStatic(
        this, singleTarget, reason, classInitializationAnalysis, whyAreYouNotInliningReporter);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(
        new CfInvoke(org.objectweb.asm.Opcodes.INVOKESTATIC, getInvokedMethod(), isInterface));
  }

  @Override
  public boolean definitelyTriggersClassInitialization(
      DexType clazz,
      ProgramMethod context,
      AppView<AppInfoWithLiveness> appView,
      Query mode,
      AnalysisAssumption assumption) {
    return ClassInitializationAnalysis.InstructionUtils.forInvokeStatic(
        this, clazz, context, appView, mode, assumption);
  }

  @Override
  public boolean instructionMayHaveSideEffects(
      AppView<?> appView, ProgramMethod context, SideEffectAssumption assumption) {
    if (!appView.enableWholeProgramOptimizations()) {
      return true;
    }

    if (appView.options().debug) {
      return true;
    }

    // Check if it is a call to one of library methods that are known to be side-effect free.
    Predicate<InvokeMethod> noSideEffectsPredicate =
        appView.dexItemFactory().libraryMethodsWithoutSideEffects.get(getInvokedMethod());
    if (noSideEffectsPredicate != null && noSideEffectsPredicate.test(this)) {
      return false;
    }

    // Find the target and check if the invoke may have side effects.
    if (!appView.appInfo().hasLiveness()) {
      return true;
    }

    AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
    AppInfoWithLiveness appInfoWithLiveness = appViewWithLiveness.appInfo();

    SingleResolutionResult resolutionResult =
        appViewWithLiveness
            .appInfo()
            .resolveMethod(getInvokedMethod(), isInterface)
            .asSingleResolution();

    // Verify that the target method is present.
    if (resolutionResult == null) {
      return true;
    }

    DexEncodedMethod singleTarget = resolutionResult.getSingleTarget();
    assert singleTarget != null;

    // Verify that the target method is static and accessible.
    if (!singleTarget.isStatic()
        || resolutionResult.isAccessibleFrom(context, appInfoWithLiveness).isPossiblyFalse()) {
      return true;
    }

    // Verify that the target method does not have side-effects.
    if (appViewWithLiveness.appInfo().noSideEffects.containsKey(singleTarget.getReference())) {
      return false;
    }

    if (singleTarget.getOptimizationInfo().mayHaveSideEffects()) {
      return true;
    }

    if (assumption.canAssumeClassIsAlreadyInitialized()) {
      return false;
    }

    return singleTarget
        .holder()
        .classInitializationMayHaveSideEffects(
            appView,
            // Types that are a super type of `context` are guaranteed to be initialized
            // already.
            type -> appInfoWithLiveness.isSubtype(context.getHolderType(), type),
            Sets.newIdentityHashSet());
  }

  @Override
  public boolean canBeDeadCode(AppView<?> appView, IRCode code) {
    return !instructionMayHaveSideEffects(appView, code.context());
  }
}
