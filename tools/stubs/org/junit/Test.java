package org.junit;
import java.lang.annotation.*;
/** Compile-time stand-in for JUnit's @Test so kotlin.test.Test resolves without Maven access. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Test { Class<? extends Throwable> expected() default None.class; long timeout() default 0L; class None extends Throwable { private None() {} } }
