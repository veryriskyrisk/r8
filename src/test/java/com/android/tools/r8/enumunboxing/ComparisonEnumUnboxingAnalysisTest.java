// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.enumunboxing;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.R8TestRunResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ComparisonEnumUnboxingAnalysisTest extends EnumUnboxingTestBase {

  private final TestParameters parameters;
  private final Class<?>[] INPUTS = new Class<?>[] {NullCheck.class, EnumComparison.class};

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return enumUnboxingTestParameters();
  }

  public ComparisonEnumUnboxingAnalysisTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testEnumUnboxing() throws Exception {
    R8TestCompileResult compile =
        testForR8(parameters.getBackend())
            .addInnerClasses(ComparisonEnumUnboxingAnalysisTest.class)
            .addKeepMainRules(INPUTS)
            .addKeepRules(KEEP_ENUM)
            .enableInliningAnnotations()
            .addOptionsModification(this::enableEnumOptions)
            .allowDiagnosticInfoMessages()
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspect(
                inspector -> {
                  assertThat(
                      inspector.clazz(NullCheck.class).uniqueMethodWithName("nullCheck"),
                      isPresent());
                  assertThat(
                      inspector.clazz(EnumComparison.class).uniqueMethodWithName("check"),
                      isPresent());
                });
    for (Class<?> input : INPUTS) {
      R8TestRunResult run =
          compile
              .inspectDiagnosticMessages(
                  m -> assertEnumIsUnboxed(input.getDeclaredClasses()[0], input.getSimpleName(), m))
              .run(parameters.getRuntime(), input)
              .assertSuccess();
      assertLines2By2Correct(run.getStdOut());
    }
  }

  @SuppressWarnings("ConstantConditions")
  static class NullCheck {

    enum MyEnum {
      A,
      B
    }

    public static void main(String[] args) {
      System.out.println(nullCheck(MyEnum.A));
      System.out.println(false);
      System.out.println(nullCheck(MyEnum.B));
      System.out.println(false);
      System.out.println(nullCheck(null));
      System.out.println(true);
    }

    // Do not resolve the == with constants after inlining.
    @NeverInline
    static boolean nullCheck(MyEnum e) {
      return e == null;
    }
  }

  static class EnumComparison {

    enum MyEnum {
      A,
      B
    }

    public static void main(String[] args) {
      System.out.println(check(MyEnum.A));
      System.out.println(false);
      System.out.println(check(MyEnum.B));
      System.out.println(true);
    }

    // Do not resolve the == with constants after inlining.
    @NeverInline
    static boolean check(MyEnum e) {
      return e == MyEnum.B;
    }
  }
}
