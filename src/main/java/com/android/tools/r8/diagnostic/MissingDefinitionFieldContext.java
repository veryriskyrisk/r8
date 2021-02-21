// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.diagnostic;

import com.android.tools.r8.Keep;
import com.android.tools.r8.references.FieldReference;

@Keep
public interface MissingDefinitionFieldContext extends MissingDefinitionContext {

  /** Returns the reference of the field context. */
  FieldReference getFieldReference();

  @Override
  default boolean isFieldContext() {
    return true;
  }

  @Override
  default MissingDefinitionFieldContext asFieldContext() {
    return this;
  }
}
