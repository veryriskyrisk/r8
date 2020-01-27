// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.resolution.interfacetargets;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NeverMerge;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.ResolutionResult;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** This is a reproduction of b/148168065 */
@RunWith(Parameterized.class)
public class DefaultMethodAsOverrideWithLambdaTest extends TestBase {

  private static final String[] EXPECTED = new String[] {"Lambda.foo", "J.bar"};

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public DefaultMethodAsOverrideWithLambdaTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testResolution() throws Exception {
    assumeTrue(parameters.useRuntimeAsNoneRuntime());
    AppInfoWithLiveness appInfo =
        computeAppViewWithLiveness(buildClasses(I.class, A.class, Main.class).build(), Main.class)
            .appInfo();
    DexMethod method = buildNullaryVoidMethod(I.class, "bar", appInfo.dexItemFactory());
    ResolutionResult resolutionResult = appInfo.resolveMethodOnInterface(method.holder, method);
    Set<String> targets =
        resolutionResult.lookupInterfaceTargets(appInfo.withSubtyping()).stream()
            .map(DexEncodedMethod::qualifiedName)
            .collect(Collectors.toSet());
    ImmutableSet<String> expected =
        ImmutableSet.of(A.class.getTypeName() + ".bar", J.class.getTypeName() + ".bar");
    // TODO(b/148168065): Correct incorrect target lookup.
    // assertEquals(expected, targets);
  }

  @Test
  public void testRuntime() throws IOException, CompilationFailedException, ExecutionException {
    testForRuntime(parameters)
        .addInnerClasses(DefaultMethodAsOverrideWithLambdaTest.class)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  @Test
  public void testR8() throws IOException, CompilationFailedException, ExecutionException {
    testForR8(parameters.getBackend())
        .addInnerClasses(DefaultMethodAsOverrideWithLambdaTest.class)
        .enableInliningAnnotations()
        .enableMergeAnnotations()
        .enableNeverClassInliningAnnotations()
        .addKeepMainRule(Main.class)
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), Main.class)
        .assertFailureWithErrorThatMatches(containsString("AbstractMethodError"));
    // TODO(b/148168065): Correct incorrect target lookup.
    //    .assertSuccessWithOutputLines(EXPECTED);
  }

  @FunctionalInterface
  @NeverMerge
  public interface I {
    void foo();

    default void bar() {
      System.out.println("I.bar");
    }
  }

  @NeverMerge
  public interface J extends I {

    @Override
    default void bar() {
      System.out.println("J.bar");
    }
  }

  @NeverClassInline
  public static class A implements I {

    @Override
    @NeverInline
    public void foo() {
      System.out.println("A.foo");
    }

    @Override
    public void bar() {
      System.out.println("A.bar");
    }
  }

  public static class Main {

    public static void main(String[] args) {
      if (args.length == 0) {
        callI((J) () -> System.out.println("Lambda.foo"));
      } else {
        callI(new A());
      }
    }

    private static void callI(I i) {
      i.foo();
      i.bar();
    }
  }
}