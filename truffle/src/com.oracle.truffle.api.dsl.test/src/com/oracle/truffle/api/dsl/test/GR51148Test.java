/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.dsl.test;

import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/*
 * This test fails by emitting a warning in the generated code.
 * It passes if no warning is emitted.
 */
public class GR51148Test {

    @SuppressWarnings({"truffle", "unused"})
    abstract static class TestNode extends Node {

        abstract Object execute(Object arg);

        @Specialization(guards = "arg0 == 0")
        Object s0(int arg0,
                        @Bind Node node,
                        @Cached @Shared InlinedConditionProfile profile0,
                        @Cached @Exclusive InlinedConditionProfile cachedProfile0,
                        // use a number of cached profiles to force trigger a specialization data
                        // class
                        @Cached @Exclusive ConditionProfile cachedProfile1,
                        @Cached @Exclusive ConditionProfile cachedProfile2,
                        @Cached @Exclusive ConditionProfile cachedProfile3,
                        @Cached @Exclusive ConditionProfile cachedProfile4,
                        @Cached @Exclusive ConditionProfile cachedProfile5,
                        @Cached @Exclusive ConditionProfile cachedProfile6,
                        @Cached @Exclusive ConditionProfile cachedProfile7,
                        @Cached @Exclusive ConditionProfile cachedProfile8,
                        @Cached @Exclusive ConditionProfile cachedProfile9,
                        @Cached @Exclusive ConditionProfile cachedProfile10,
                        @Cached @Exclusive ConditionProfile cachedProfile11) {
            profile0.profile(this, arg0 == 0);
            return arg0;
        }

        /*
         * This specialization triggers a data class, before the fix this was emitting an error
         * requiring @Bind("this") to bind the inlining target. But in this case "this" can safely
         * be used.
         */
        @SuppressWarnings("truffle-interpreted-performance")
        @Specialization(guards = "arg0 == 1")
        Object s1(int arg0,
                        @Bind Node node,
                        @Cached @Shared InlinedConditionProfile profile0,
                        @Cached @Exclusive InlinedConditionProfile cachedProfile0,
                        @Cached @Exclusive ConditionProfile cachedProfile1,
                        @Cached @Exclusive ConditionProfile cachedProfile2,
                        @Cached @Exclusive ConditionProfile cachedProfile3,
                        @Cached @Exclusive ConditionProfile cachedProfile4,
                        @Cached @Exclusive ConditionProfile cachedProfile5) {
            profile0.profile(this, arg0 == 1);
            return arg0;
        }

        /*
         * This specialization triggers a data class, before the fix this was emitting an error
         * requiring @Bind("this") to bind the inlining target. But in this case "this" can safely
         * be used.
         */
        @SuppressWarnings("truffle-interpreted-performance")
        @Specialization(guards = "arg0 == 2")
        Object s2(int arg0,
                        @Bind Node node,
                        @Cached @Shared InlinedConditionProfile profile0,
                        @Cached @Exclusive InlinedConditionProfile cachedProfile0,
                        @Cached @Exclusive ConditionProfile cachedProfile1,
                        @Cached @Exclusive ConditionProfile cachedProfile2,
                        @Cached @Exclusive ConditionProfile cachedProfile3,
                        @Cached @Exclusive ConditionProfile cachedProfile4,
                        @Cached @Exclusive ConditionProfile cachedProfile5) {
            profile0.profile(this, arg0 == 1);
            return arg0;
        }
    }

}
