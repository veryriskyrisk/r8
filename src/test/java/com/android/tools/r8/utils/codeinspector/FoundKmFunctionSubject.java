// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils.codeinspector;

import kotlinx.metadata.KmFunction;

public class FoundKmFunctionSubject extends KmFunctionSubject {
  private final CodeInspector codeInspector;
  private final KmFunction kmFunction;

  FoundKmFunctionSubject(CodeInspector codeInspector, KmFunction kmFunction) {
    this.codeInspector = codeInspector;
    this.kmFunction = kmFunction;
  }

  @Override
  public boolean isPresent() {
    return true;
  }

  @Override
  public boolean isRenamed() {
    // TODO(b/70169921): need to know the corresponding DexEncodedMethod.
    return false;
  }

  @Override
  public boolean isSynthetic() {
    // TODO(b/70169921): This should return `true` conditionally if we start synthesizing @Metadata
    //   from scratch.
    return false;
  }

  @Override
  public boolean isExtension() {
    return isExtension(kmFunction);
  }
}
