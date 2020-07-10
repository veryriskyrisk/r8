// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.dexsplitter;

import static com.android.tools.r8.rewrite.ServiceLoaderRewritingTest.getServiceLoaderLoads;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.DataEntryResource;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.Pair;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class R8FeatureSplitServiceLoaderTest extends SplitterTestBase {

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withDexRuntimes().withAllApiLevels().build();
  }

  public R8FeatureSplitServiceLoaderTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testR8AllServiceConfigurationInBase() throws Exception {
    Path base = temp.newFile("base.zip").toPath();
    Path feature1Path = temp.newFile("feature1.zip").toPath();
    Path feature2Path = temp.newFile("feature2.zip").toPath();
    testForR8(parameters.getBackend())
        .addProgramClasses(Base.class, I.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepMainRule(Base.class)
        .addFeatureSplit(
            builder -> simpleSplitProvider(builder, feature1Path, temp, Feature1I.class))
        .addFeatureSplit(
            builder -> simpleSplitProvider(builder, feature2Path, temp, Feature2I.class))
        .addDataEntryResources(
            DataEntryResource.fromBytes(
                StringUtils.lines(Feature1I.class.getTypeName(), Feature2I.class.getTypeName())
                    .getBytes(),
                "META-INF/services/" + I.class.getTypeName(),
                Origin.unknown()))
        .compile()
        .inspect(
            inspector -> {
              // TODO(b/157426812): This should be 1.
              assertEquals(0, getServiceLoaderLoads(inspector, Base.class));
            })
        .writeToZip(base)
        .addRunClasspathFiles(feature1Path, feature2Path)
        .run(parameters.getRuntime(), Base.class)
        .assertSuccessWithOutputLines("Feature1I.foo()", "Feature2I.foo()");
  }

  @Test
  public void testR8AllLoaded() throws Exception {
    Path base = temp.newFile("base.zip").toPath();
    Path feature1Path = temp.newFile("feature1.zip").toPath();
    Path feature2Path = temp.newFile("feature2.zip").toPath();
    testForR8(parameters.getBackend())
        .addProgramClasses(Base.class, I.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepMainRule(Base.class)
        .addFeatureSplit(
            builder ->
                splitWithNonJavaFile(
                    builder,
                    feature1Path,
                    temp,
                    ImmutableList.of(
                        new Pair<>(
                            "META-INF/services/" + I.class.getTypeName(),
                            StringUtils.lines(Feature1I.class.getTypeName()))),
                    true,
                    Feature1I.class))
        .addFeatureSplit(
            builder ->
                splitWithNonJavaFile(
                    builder,
                    feature2Path,
                    temp,
                    ImmutableList.of(
                        new Pair<>(
                            "META-INF/services/" + I.class.getTypeName(),
                            StringUtils.lines(Feature2I.class.getTypeName()))),
                    true,
                    Feature2I.class))
        .compile()
        .inspect(
            inspector -> {
              assertEquals(1, getServiceLoaderLoads(inspector, Base.class));
            })
        .writeToZip(base)
        .addRunClasspathFiles(feature1Path, feature2Path)
        .run(parameters.getRuntime(), Base.class)
        // TODO(b/157426812): Should output
        // .assertSuccessWithOutputLines("Feature1I.foo()", "Feature2I.foo()");
        .assertSuccessWithOutput("");
  }

  @Test
  public void testR8WithServiceFileInSeparateFeature() throws Exception {
    Path base = temp.newFile("base.zip").toPath();
    Path feature1Path = temp.newFile("feature1.zip").toPath();
    Path feature2Path = temp.newFile("feature2.zip").toPath();
    Path feature3Path = temp.newFile("feature3.zip").toPath();
    testForR8(parameters.getBackend())
        .addProgramClasses(Base.class, I.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepMainRule(Base.class)
        .addFeatureSplit(
            builder -> simpleSplitProvider(builder, feature1Path, temp, Feature1I.class))
        .addFeatureSplit(
            builder -> simpleSplitProvider(builder, feature2Path, temp, Feature2I.class))
        .addFeatureSplit(
            builder ->
                splitWithNonJavaFile(
                    builder,
                    feature3Path,
                    temp,
                    ImmutableList.of(
                        new Pair<>(
                            "META-INF/services/" + I.class.getTypeName(),
                            StringUtils.lines(
                                Feature1I.class.getTypeName(), Feature2I.class.getTypeName()))),
                    true,
                    Feature3Dummy.class))
        .compile()
        .inspect(
            inspector -> {
              assertEquals(1, getServiceLoaderLoads(inspector, Base.class));
            })
        .writeToZip(base)
        .addRunClasspathFiles(feature1Path, feature2Path, feature3Path)
        .run(parameters.getRuntime(), Base.class)
        // TODO(b/157426812): Should output
        // .assertSuccessWithOutputLines("Feature1I.foo()", "Feature2I.foo()");
        .assertSuccessWithOutput("");
  }

  @Test
  public void testR8NotFound() throws Exception {
    Path base = temp.newFile("base.zip").toPath();
    Path feature1Path = temp.newFile("feature1.zip").toPath();
    Path feature2Path = temp.newFile("feature2.zip").toPath();
    testForR8(parameters.getBackend())
        .addProgramClasses(Base.class, I.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepMainRule(Base.class)
        .addFeatureSplit(
            builder ->
                splitWithNonJavaFile(
                    builder,
                    feature1Path,
                    temp,
                    ImmutableList.of(
                        new Pair<>(
                            "META-INF/services/" + I.class.getTypeName(),
                            StringUtils.lines(Feature1I.class.getTypeName()))),
                    true,
                    Feature1I.class))
        .addFeatureSplit(
            builder ->
                splitWithNonJavaFile(
                    builder,
                    feature2Path,
                    temp,
                    ImmutableList.of(
                        new Pair<>(
                            "META-INF/services/" + I.class.getTypeName(),
                            StringUtils.lines(Feature2I.class.getTypeName()))),
                    true,
                    Feature2I.class))
        .compile()
        .inspect(
            inspector -> {
              assertEquals(1, getServiceLoaderLoads(inspector, Base.class));
            })
        .writeToZip(base)
        .addRunClasspathFiles(feature2Path)
        .run(parameters.getRuntime(), Base.class)
        // TODO(b/157426812): Should output
        // .assertSuccessWithOutputLines("Feature2I.foo()");
        .assertSuccessWithOutput("");
  }

  public interface I {
    void foo();
  }

  public static class Base {

    public static void main(String[] args) {
      for (I i : ServiceLoader.load(I.class, null)) {
        i.foo();
      }
    }
  }

  public static class Feature1I implements I {

    @Override
    public void foo() {
      System.out.println("Feature1I.foo()");
    }
  }

  public static class Feature2I implements I {

    @Override
    public void foo() {
      System.out.println("Feature2I.foo()");
    }
  }

  public static class Feature3Dummy {}
}
