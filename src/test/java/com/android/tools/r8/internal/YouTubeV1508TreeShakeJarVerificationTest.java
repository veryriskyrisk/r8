// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.internal;

import static com.android.tools.r8.ToolHelper.isLocalDevelopment;
import static com.android.tools.r8.ToolHelper.shouldRunSlowTests;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.analysis.ProtoApplicationStats;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class YouTubeV1508TreeShakeJarVerificationTest extends YouTubeCompilationBase {

  private static final int MAX_SIZE = 27500000;

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withNoneRuntime().build();
  }

  public YouTubeV1508TreeShakeJarVerificationTest(TestParameters parameters) {
    super(15, 8);
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws Exception {
    // TODO(b/141603168): Enable this on the bots.
    assumeTrue(isLocalDevelopment());
    assumeTrue(shouldRunSlowTests());

    LibrarySanitizer librarySanitizer =
        new LibrarySanitizer(temp)
            .addProgramFiles(getProgramFiles())
            .addLibraryFiles(getLibraryFiles())
            .sanitize()
            .assertSanitizedProguardConfigurationIsEmpty();

    R8TestCompileResult compileResult =
        testForR8(Backend.DEX)
            .addProgramFiles(getProgramFiles())
            .addLibraryFiles(librarySanitizer.getSanitizedLibrary())
            .addKeepRuleFiles(getKeepRuleFiles())
            .addMainDexRuleFiles(getMainDexRuleFiles())
            .addOptionsModification(
                options -> {
                  assert !options.applyInliningToInlinee;
                  options.applyInliningToInlinee = true;

                  assert !options.enableFieldBitAccessAnalysis;
                  options.enableFieldBitAccessAnalysis = true;

                  assert !options.protoShrinking().enableGeneratedExtensionRegistryShrinking;
                  options.protoShrinking().enableGeneratedExtensionRegistryShrinking = true;

                  assert !options.protoShrinking().enableGeneratedMessageLiteShrinking;
                  options.protoShrinking().enableGeneratedMessageLiteShrinking = true;

                  assert options.protoShrinking().traverseOneOfAndRepeatedProtoFields;
                  options.protoShrinking().traverseOneOfAndRepeatedProtoFields = false;

                  assert !options.enableStringSwitchConversion;
                  options.enableStringSwitchConversion = true;
                })
            .allowCheckDiscardedErrors()
            .allowDiagnosticMessages()
            .allowUnusedProguardConfigurationRules()
            .setMinApi(AndroidApiLevel.H_MR2)
            .compile();

    if (ToolHelper.isLocalDevelopment()) {
      DexItemFactory dexItemFactory = new DexItemFactory();
      ProtoApplicationStats original =
          new ProtoApplicationStats(dexItemFactory, new CodeInspector(getProgramFiles()));
      ProtoApplicationStats actual =
          new ProtoApplicationStats(dexItemFactory, compileResult.inspector(), original);
      ProtoApplicationStats baseline =
          new ProtoApplicationStats(
              dexItemFactory,
              new CodeInspector(getReleaseApk(), getReleaseProguardMap().toString()));
      System.out.println(actual.getStats(baseline));
    }

    int applicationSize = compileResult.app.applicationSize();
    System.out.println(applicationSize);

    assertTrue(
        "Expected max size of " + MAX_SIZE + ", got " + applicationSize,
        applicationSize < MAX_SIZE);
  }
}