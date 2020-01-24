// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.enumunboxing;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.R8TestRunResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.enumunboxing.FailingEnumUnboxingAnalysisTest.EnumInstanceFieldMain.EnumInstanceField;
import com.android.tools.r8.enumunboxing.FailingEnumUnboxingAnalysisTest.EnumInterfaceMain.EnumInterface;
import com.android.tools.r8.enumunboxing.FailingEnumUnboxingAnalysisTest.EnumStaticFieldMain.EnumStaticField;
import com.android.tools.r8.enumunboxing.FailingEnumUnboxingAnalysisTest.EnumStaticMethodMain.EnumStaticMethod;
import com.android.tools.r8.enumunboxing.FailingEnumUnboxingAnalysisTest.EnumVirtualMethodMain.EnumVirtualMethod;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FailingEnumUnboxingAnalysisTest extends EnumUnboxingTestBase {

  private static final Class<?>[] FAILURES = {
    EnumInterface.class,
    EnumStaticField.class,
    EnumInstanceField.class,
    EnumStaticMethod.class,
    EnumVirtualMethod.class
  };

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return enumUnboxingTestParameters();
  }

  public FailingEnumUnboxingAnalysisTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testEnumUnboxingFailure() throws Exception {
    R8FullTestBuilder r8FullTestBuilder =
        testForR8(parameters.getBackend()).addInnerClasses(FailingEnumUnboxingAnalysisTest.class);
    for (Class<?> failure : FAILURES) {
      r8FullTestBuilder.addKeepMainRule(failure.getEnclosingClass());
    }
    R8TestCompileResult compile =
        r8FullTestBuilder
            .noTreeShaking() // Disabled to avoid merging Itf into EnumInterface.
            .enableInliningAnnotations()
            .addKeepRules(KEEP_ENUM)
            .addOptionsModification(this::enableEnumOptions)
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspect(this::assertEnumsAsExpected);
    for (Class<?> failure : FAILURES) {
      R8TestRunResult run =
          compile
              .inspectDiagnosticMessages(
                  m -> assertEnumIsBoxed(failure, failure.getSimpleName(), m))
              .run(parameters.getRuntime(), failure.getEnclosingClass())
              .assertSuccess();
      assertLines2By2Correct(run.getStdOut());
    }
  }

  private void assertEnumsAsExpected(CodeInspector inspector) {
    assertEquals(1, inspector.clazz(EnumInterface.class).getDexClass().interfaces.size());

    assertTrue(inspector.clazz(EnumStaticField.class).uniqueFieldWithName("X").isPresent());
    assertTrue(inspector.clazz(EnumInstanceField.class).uniqueFieldWithName("a").isPresent());

    assertEquals(5, inspector.clazz(EnumStaticMethod.class).getDexClass().directMethods().size());
    assertEquals(1, inspector.clazz(EnumVirtualMethod.class).virtualMethods().size());
  }

  static class EnumInterfaceMain {

    public static void main(String[] args) {
      System.out.println(EnumInterface.A.ordinal());
      System.out.println(0);
    }

    enum EnumInterface implements Itf {
      A,
      B,
      C
    }

    interface Itf {

      default int ordinal() {
        return -1;
      }
    }
  }

  static class EnumStaticFieldMain {

    public static void main(String[] args) {
      System.out.println(EnumStaticField.A.ordinal());
      System.out.println(0);
      System.out.println(EnumStaticField.X.ordinal());
      System.out.println(0);
    }

    enum EnumStaticField {
      A,
      B,
      C;
      static EnumStaticField X = A;
    }
  }

  static class EnumInstanceFieldMain {

    enum EnumInstanceField {
      A(10),
      B(20),
      C(30);
      private int a;

      EnumInstanceField(int i) {
        this.a = i;
      }
    }

    public static void main(String[] args) {
      System.out.println(EnumInstanceField.A.ordinal());
      System.out.println(0);
      System.out.println(EnumInstanceField.A.a);
      System.out.println(10);
    }
  }

  static class EnumStaticMethodMain {

    enum EnumStaticMethod {
      A,
      B,
      C;

      // Enum cannot be unboxed if it has a static method, we do not inline so the method is
      // present.
      @NeverInline
      static int foo() {
        return Math.addExact(-1, 0);
      }
    }

    public static void main(String[] args) {
      System.out.println(EnumStaticMethod.A.ordinal());
      System.out.println(0);
      System.out.println(EnumStaticMethod.foo());
      System.out.println(-1);
    }
  }

  static class EnumVirtualMethodMain {

    public static void main(String[] args) {
      EnumVirtualMethod e1 = EnumVirtualMethod.A;
      System.out.println(e1.ordinal());
      System.out.println(0);
      System.out.println(e1.valueOf());
      System.out.println(-1);
    }

    enum EnumVirtualMethod {
      A,
      B,
      C;

      // Enum cannot be unboxed if it has a virtual method, we do not inline so the method is
      // present.
      @NeverInline
      int valueOf() {
        return Math.addExact(-1, 0);
      }
    }
  }
}