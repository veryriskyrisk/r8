// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.membervaluepropagation.fields;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class FieldInitializedByConstantArgumentForwardingConstructorTest extends TestBase {

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public FieldInitializedByConstantArgumentForwardingConstructorTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(FieldInitializedByConstantArgumentForwardingConstructorTest.class)
        .addKeepMainRule(TestClass.class)
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .setMinApi(parameters.getApiLevel())
        .compile()
        .inspect(this::inspect)
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutputLines("Live!", "Live!");
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject testClassSubject = inspector.clazz(TestClass.class);
    assertThat(testClassSubject, isPresent());
    assertThat(testClassSubject.uniqueMethodWithName("live"), isPresent());
    // TODO(b/147652121): Should be absent.
    assertThat(testClassSubject.uniqueMethodWithName("dead"), isPresent());
  }

  static class TestClass {

    public static void main(String[] args) {
      if (allocationSite1().x == 42) {
        live();
      } else {
        dead();
      }

      if (allocationSite2().x == 42) {
        live();
      } else {
        dead();
      }
    }

    @NeverInline
    static A allocationSite1() {
      return new A();
    }

    @NeverInline
    static A allocationSite2() {
      return new A(42);
    }

    @NeverInline
    static void live() {
      System.out.println("Live!");
    }

    @NeverInline
    static void dead() {
      System.out.println("Dead!");
    }
  }

  @NeverClassInline
  static class A {

    int x;

    @NeverInline
    A() {
      this(42);
    }

    @NeverInline
    A(int x) {
      this.x = x;
    }
  }
}