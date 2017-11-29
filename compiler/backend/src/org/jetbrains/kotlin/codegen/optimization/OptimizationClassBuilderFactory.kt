/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.optimization

import org.jetbrains.kotlin.codegen.ClassBuilder
import org.jetbrains.kotlin.codegen.ClassBuilderFactory
import org.jetbrains.kotlin.codegen.DelegatingClassBuilder
import org.jetbrains.kotlin.codegen.DelegatingClassBuilderFactory
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.org.objectweb.asm.MethodVisitor

class OptimizationClassBuilderFactory(
        delegate: ClassBuilderFactory,
        private val generationState: GenerationState
) : DelegatingClassBuilderFactory(delegate) {

    override fun newClassBuilder(origin: JvmDeclarationOrigin): DelegatingClassBuilder =
            OptimizationClassBuilder(delegate.newClassBuilder(origin), generationState)

    class OptimizationClassBuilder(
            private val delegate: ClassBuilder,
            private val generationState: GenerationState
    ) : DelegatingClassBuilder() {

        public override fun getDelegate(): ClassBuilder = delegate

        override fun newMethod(
                origin: JvmDeclarationOrigin,
                access: Int,
                name: String,
                desc: String,
                signature: String?,
                exceptions: Array<String>?
        ): MethodVisitor =
                OptimizationMethodVisitor(
                        super.newMethod(origin, access, name, desc, signature, exceptions),
                        generationState,
                        access, name, desc, signature, exceptions
                )
    }

}

