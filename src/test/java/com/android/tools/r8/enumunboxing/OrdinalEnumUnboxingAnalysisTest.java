// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.enumunboxing;

import com.android.tools.r8.R8TestRunResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class OrdinalEnumUnboxingAnalysisTest extends EnumUnboxingTestBase {

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return enumUnboxingTestParameters();
  }

  public OrdinalEnumUnboxingAnalysisTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  private static final Class<?> ENUM_CLASS = MyEnum.class;

  @Test
  public void testEnumUnboxing() throws Exception {
    Class<Ordinal> classToTest = Ordinal.class;
    R8TestRunResult run =
        testForR8(parameters.getBackend())
            .addProgramClasses(classToTest, ENUM_CLASS)
            .addKeepMainRule(classToTest)
            .addKeepRules(KEEP_ENUM)
            .addOptionsModification(this::enableEnumOptions)
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspectDiagnosticMessages(
                m -> assertEnumIsUnboxed(ENUM_CLASS, classToTest.getSimpleName(), m))
            .run(parameters.getRuntime(), classToTest)
            .assertSuccess();
    assertLines2By2Correct(run.getStdOut());
  }

  enum MyEnum {
    A,
    B,
    C
  }

  static class Ordinal {

    public static void main(String[] args) {
      System.out.println(MyEnum.A.ordinal());
      System.out.println(0);
    }
  }
}