/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.filters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to assign a small test size qualifier to a test. This annotation can be used at the
 * method or class level.
 *
 * <p>Test size qualifiers are a great way to structure test code and are used to assign a test to a
 * suite of test that have similar run times.
 *
 * <p>Execution time: &lt;200ms
 *
 * <p>Small tests should be run very frequently, focused on units of code to verify specific logical
 * conditions. Tests should run in an isolated environment and use fake objects for external
 * dependencies. Resource access (for example, to the file system, network, or databases) is not
 * permitted. Tests that interact with hardware, make binder calls, or facilitate android
 * instrumentation should not use this annotation.
 *
 * <p><b>Note:</b> This class replaces the deprecated Android platform size qualifier <a
 * href="{@docRoot}reference/android/test/suitebuilder/annotation/SmallTest.html"><code>
 * android.test.suitebuilder.annotation.SmallTest</code></a> and is the recommended way to annotate
 * tests written with the AndroidX Test libraries.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SmallTest {}
