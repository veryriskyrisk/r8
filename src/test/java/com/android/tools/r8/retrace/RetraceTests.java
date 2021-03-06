// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import static com.android.tools.r8.retrace.internal.StackTraceRegularExpressionParser.DEFAULT_REGULAR_EXPRESSION;
import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestDiagnosticMessagesImpl;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.retrace.internal.RetraceAbortException;
import com.android.tools.r8.retrace.stacktraces.ActualBotStackTraceBase;
import com.android.tools.r8.retrace.stacktraces.ActualIdentityStackTrace;
import com.android.tools.r8.retrace.stacktraces.ActualRetraceBotStackTrace;
import com.android.tools.r8.retrace.stacktraces.AmbiguousMissingLineStackTrace;
import com.android.tools.r8.retrace.stacktraces.AmbiguousStackTrace;
import com.android.tools.r8.retrace.stacktraces.AmbiguousWithMultipleLineMappingsStackTrace;
import com.android.tools.r8.retrace.stacktraces.AmbiguousWithSignatureNonVerboseStackTrace;
import com.android.tools.r8.retrace.stacktraces.AutoStackTrace;
import com.android.tools.r8.retrace.stacktraces.CircularReferenceStackTrace;
import com.android.tools.r8.retrace.stacktraces.ColonInFileNameStackTrace;
import com.android.tools.r8.retrace.stacktraces.FileNameExtensionStackTrace;
import com.android.tools.r8.retrace.stacktraces.InlineFileNameStackTrace;
import com.android.tools.r8.retrace.stacktraces.InlineFileNameWithInnerClassesStackTrace;
import com.android.tools.r8.retrace.stacktraces.InlineNoLineNumberStackTrace;
import com.android.tools.r8.retrace.stacktraces.InlineSourceFileContextStackTrace;
import com.android.tools.r8.retrace.stacktraces.InlineWithLineNumbersStackTrace;
import com.android.tools.r8.retrace.stacktraces.InvalidStackTrace;
import com.android.tools.r8.retrace.stacktraces.MemberFieldOverlapStackTrace;
import com.android.tools.r8.retrace.stacktraces.MultipleDotsInFileNameStackTrace;
import com.android.tools.r8.retrace.stacktraces.NamedModuleStackTrace;
import com.android.tools.r8.retrace.stacktraces.NoObfuscationRangeMappingWithStackTrace;
import com.android.tools.r8.retrace.stacktraces.NullStackTrace;
import com.android.tools.r8.retrace.stacktraces.ObfucatedExceptionClassStackTrace;
import com.android.tools.r8.retrace.stacktraces.ObfuscatedRangeToSingleLineStackTrace;
import com.android.tools.r8.retrace.stacktraces.RetraceAssertionErrorStackTrace;
import com.android.tools.r8.retrace.stacktraces.SourceFileNameSynthesizeStackTrace;
import com.android.tools.r8.retrace.stacktraces.SourceFileWithNumberAndEmptyStackTrace;
import com.android.tools.r8.retrace.stacktraces.StackTraceForTest;
import com.android.tools.r8.retrace.stacktraces.SuppressedStackTrace;
import com.android.tools.r8.retrace.stacktraces.UnicodeInFileNameStackTrace;
import com.android.tools.r8.retrace.stacktraces.UnknownSourceStackTrace;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RetraceTests extends TestBase {

  @Parameters(name = "{0}, use regular expression: {1}, external: {2}")
  public static Collection<Object[]> data() {
    return buildParameters(
        getTestParameters().withCfRuntimes().build(), BooleanUtils.values(), BooleanUtils.values());
  }

  private final TestParameters testParameters;
  private final boolean useRegExpParsing;
  private final boolean external;

  public RetraceTests(TestParameters parameters, boolean useRegExpParsing, boolean external) {
    this.testParameters = parameters;
    this.useRegExpParsing = useRegExpParsing;
    this.external = external;
  }

  @Test
  public void testCanMapExceptionClass() throws Exception {
    runRetraceTest(new ObfucatedExceptionClassStackTrace());
  }

  @Test
  public void testSuppressedStackTrace() throws Exception {
    runRetraceTest(new SuppressedStackTrace());
  }

  @Test
  public void testFileNameStackTrace() throws Exception {
    runRetraceTest(new FileNameExtensionStackTrace());
  }

  @Test
  public void testInlineFileNameStackTrace() throws Exception {
    runRetraceTest(new InlineFileNameStackTrace());
  }

  @Test
  public void testInlineFileNameWithInnerClassesStackTrace() throws Exception {
    runRetraceTest(new InlineFileNameWithInnerClassesStackTrace());
  }

  @Test
  public void testNoObfuscationRangeMappingWithStackTrace() throws Exception {
    runRetraceTest(new NoObfuscationRangeMappingWithStackTrace());
  }

  @Test
  public void testNullLineTrace() {
    TestDiagnosticMessagesImpl diagnosticsHandler = new TestDiagnosticMessagesImpl();
    NullStackTrace nullStackTrace = new NullStackTrace();
    RetraceCommand retraceCommand =
        RetraceCommand.builder(diagnosticsHandler)
            .setProguardMapProducer(nullStackTrace::mapping)
            .setStackTrace(nullStackTrace.obfuscatedStackTrace())
            .setRetracedStackTraceConsumer(retraced -> fail())
            .build();
    try {
      Retrace.run(retraceCommand);
      fail();
    } catch (RetraceAbortException e) {
      diagnosticsHandler.assertOnlyErrors();
      diagnosticsHandler.assertErrorsCount(1);
      assertThat(
          diagnosticsHandler.getErrors().get(0).getDiagnosticMessage(),
          containsString("The stack trace line is <null>"));
    }
  }

  @Test
  public void testInvalidStackTraceLineWarnings() throws Exception {
    InvalidStackTrace invalidStackTraceTest = new InvalidStackTrace();
    runRetraceTest(invalidStackTraceTest).assertNoMessages();
  }

  @Test
  public void testAssertionErrorInRetrace() throws Exception {
    runRetraceTest(new RetraceAssertionErrorStackTrace());
  }

  @Test
  public void testActualStackTraces() throws Exception {
    List<ActualBotStackTraceBase> stackTraces =
        ImmutableList.of(new ActualIdentityStackTrace(), new ActualRetraceBotStackTrace());
    for (ActualBotStackTraceBase stackTrace : stackTraces) {
      runRetraceTest(stackTrace)
          .assertWarningsCount(useRegExpParsing ? 0 : stackTrace.expectedWarnings());
    }
  }

  @Test
  public void testAmbiguousStackTrace() throws Exception {
    runRetraceTest(new AmbiguousStackTrace());
  }

  @Test
  public void testAmbiguousMissingLineStackTrace() throws Exception {
    runRetraceTest(new AmbiguousMissingLineStackTrace());
  }

  @Test
  public void testAmbiguousMissingLineNotVerbose() throws Exception {
    runRetraceTest(new AmbiguousWithSignatureNonVerboseStackTrace());
  }

  @Test
  public void testAmbiguousMultipleMappingsTest() throws Exception {
    runRetraceTest(new AmbiguousWithMultipleLineMappingsStackTrace());
  }

  @Test
  public void testInliningWithLineNumbers() throws Exception {
    runRetraceTest(new InlineWithLineNumbersStackTrace());
  }

  @Test
  public void testInliningNoLineNumberInfoStackTraces() throws Exception {
    runRetraceTest(new InlineNoLineNumberStackTrace());
  }

  @Test
  public void testCircularReferenceStackTrace() throws Exception {
    // Proguard retrace (and therefore the default regular expression) will not retrace circular
    // reference exceptions.
    assumeFalse(useRegExpParsing);
    runRetraceTest(new CircularReferenceStackTrace());
  }

  @Test
  public void testObfuscatedRangeToSingleLine() throws Exception {
    runRetraceTest(new ObfuscatedRangeToSingleLineStackTrace());
  }

  @Test
  @Ignore("b/170293908")
  public void testBootLoaderAndNamedModulesStackTrace() throws Exception {
    assumeFalse(useRegExpParsing);
    runRetraceTest(new NamedModuleStackTrace());
  }

  @Test
  public void testUnknownSourceStackTrace() throws Exception {
    runRetraceTest(new UnknownSourceStackTrace());
  }

  @Test
  public void testInlineSourceFileContext() throws Exception {
    runRetraceTest(new InlineSourceFileContextStackTrace());
  }

  @Test
  public void testColonInSourceFileNameStackTrace() throws Exception {
    runRetraceTest(new ColonInFileNameStackTrace());
  }

  @Test
  public void testMultipleDotsInFileNameStackTrace() throws Exception {
    runRetraceTest(new MultipleDotsInFileNameStackTrace());
  }

  @Test
  public void testUnicodeInFileNameStackTrace() throws Exception {
    runRetraceTest(new UnicodeInFileNameStackTrace());
  }

  @Test
  public void testMemberFieldOverlapStackTrace() throws Exception {
    MemberFieldOverlapStackTrace stackTraceForTest = new MemberFieldOverlapStackTrace();
    runRetraceTest(stackTraceForTest);
    inspectRetraceTest(stackTraceForTest, stackTraceForTest::inspectField);
  }

  @Test
  public void testSourceFileWithNumberAndEmptyStackTrace() throws Exception {
    runRetraceTest(new SourceFileWithNumberAndEmptyStackTrace());
  }

  @Test
  public void testSourceFileNameSynthesizeStackTrace() throws Exception {
    runRetraceTest(new SourceFileNameSynthesizeStackTrace());
  }

  @Test
  public void testAutoStackTrace() throws Exception {
    runRetraceTest(new AutoStackTrace());
  }

  private void inspectRetraceTest(
      StackTraceForTest stackTraceForTest, Consumer<Retracer> inspection) {
    inspection.accept(
        Retracer.createDefault(stackTraceForTest::mapping, new TestDiagnosticMessagesImpl()));
  }

  private TestDiagnosticMessagesImpl runRetraceTest(StackTraceForTest stackTraceForTest)
      throws Exception {
    if (external) {
      assumeTrue(useRegExpParsing);
      assumeTrue(testParameters.isCfRuntime());
      // The external dependency is built on top of R8Lib. If test.py is run with
      // no r8lib, do not try and run the external R8 Retrace since it has not been built.
      assumeTrue(Files.exists(ToolHelper.R8LIB_JAR));
      Path path = temp.newFolder().toPath();
      Path mappingFile = path.resolve("mapping");
      Files.write(mappingFile, stackTraceForTest.mapping().getBytes());
      Path stackTraceFile = path.resolve("stacktrace.txt");
      Files.write(
          stackTraceFile,
          StringUtils.joinLines(stackTraceForTest.obfuscatedStackTrace())
              .getBytes(StandardCharsets.UTF_8));

      List<String> command = new ArrayList<>();
      command.add(testParameters.getRuntime().asCf().getJavaExecutable().toString());
      command.add("-ea");
      command.add("-cp");
      command.add(ToolHelper.R8_RETRACE_JAR.toString());
      command.add("com.android.tools.r8.retrace.Retrace");
      command.add(mappingFile.toString());
      command.add(stackTraceFile.toString());
      command.add("-quiet");
      ProcessBuilder builder = new ProcessBuilder(command);
      ProcessResult processResult = ToolHelper.runProcess(builder);
      assertEquals(
          StringUtils.joinLines(stackTraceForTest.retracedStackTrace())
              + StringUtils.LINE_SEPARATOR,
          processResult.stdout);
      // TODO(b/177204438): Parse diagnostics from stdErr
      return new TestDiagnosticMessagesImpl();
    } else {
      TestDiagnosticMessagesImpl diagnosticsHandler = new TestDiagnosticMessagesImpl();
      RetraceCommand retraceCommand =
          RetraceCommand.builder(diagnosticsHandler)
              .setProguardMapProducer(stackTraceForTest::mapping)
              .setStackTrace(stackTraceForTest.obfuscatedStackTrace())
              .setRegularExpression(useRegExpParsing ? DEFAULT_REGULAR_EXPRESSION : null)
              .setRetracedStackTraceConsumer(
                  retraced -> assertEquals(stackTraceForTest.retracedStackTrace(), retraced))
              .build();
      Retrace.run(retraceCommand);
      return diagnosticsHandler;
    }
  }
}
