// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import java.util.List;

public class ClassInlineRule extends ProguardConfigurationRule {

  public enum Type {
    ALWAYS,
    NEVER
  }

  public static class Builder extends ProguardConfigurationRule.Builder<ClassInlineRule, Builder> {

    private Builder() {
      super();
    }

    Type type;

    @Override
    public Builder self() {
      return this;
    }

    public Builder setType(Type type) {
      this.type = type;
      return this;
    }

    @Override
    public ClassInlineRule build() {
      return new ClassInlineRule(
          origin,
          getPosition(),
          source,
          classAnnotation,
          classAccessFlags,
          negatedClassAccessFlags,
          classTypeNegated,
          classType,
          classNames,
          inheritanceAnnotation,
          inheritanceClassName,
          inheritanceIsExtends,
          memberRules,
          type);
    }
  }

  private final Type type;

  protected ClassInlineRule(
      Origin origin,
      Position position,
      String source,
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<ProguardMemberRule> memberRules,
      Type type) {
    super(
        origin,
        position,
        source,
        classAnnotation,
        classAccessFlags,
        negatedClassAccessFlags,
        classTypeNegated,
        classType,
        classNames,
        inheritanceAnnotation,
        inheritanceClassName,
        inheritanceIsExtends,
        memberRules);
    this.type = type;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Type getType() {
    return type;
  }

  @Override
  public boolean isClassInlineRule() {
    return true;
  }

  @Override
  public ClassInlineRule asClassInlineRule() {
    return this;
  }

  @Override
  String typeString() {
    switch (type) {
      case ALWAYS:
        return "alwaysclassinline";
      case NEVER:
        return "neverclassinline";
    }
    throw new Unreachable("Unknown class inline type " + type);
  }
}
