/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.linkerissues

import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.linkage.KotlinIrLinkerInternalException
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.ResolvedDependency
import org.jetbrains.kotlin.utils.ResolvedDependencyId

abstract class KotlinIrLinkerIssue(protected val userVisibleIrModulesSupport: UserVisibleIrModulesSupport) {
    protected abstract val message: String

    fun raiseIssue(messageLogger: IrMessageLogger): KotlinIrLinkerInternalException {
        messageLogger.report(IrMessageLogger.Severity.ERROR, message, null)
        throw KotlinIrLinkerInternalException
    }

    protected fun StringBuilder.appendIrModules(
        allModules: Map<ResolvedDependencyId, ResolvedDependency>,
        errorModuleId: ResolvedDependencyId? = null
    ) {
        append("\n\nProject dependencies:")
        if (allModules.isEmpty()) {
            append(" <empty>")
            return
        }

        val dependeeIdToDependencies: MutableMap<ResolvedDependencyId, MutableCollection<ResolvedDependency>> = mutableMapOf()
        allModules.values.forEach { module ->
            module.requestedVersionsByDependees.keys.forEach { dependeeId ->
                dependeeIdToDependencies.getOrPut(dependeeId) { mutableListOf() } += module
            }
        }

        val renderedModules: MutableSet<ResolvedDependencyId> = mutableSetOf()
        var everDependenciesOmitted = false

        fun renderModules(modules: Collection<ResolvedDependency>, parentData: Data?) {
            val sortedModules: List<ResolvedDependency> = modules.sortedWith { a, b ->
                userVisibleIrModulesSupport.moduleIdComparator.compare(a.id, b.id)
            }

            var passed = 0
            sortedModules.forEach { module ->
                passed++
                val data = Data(
                    parent = parentData,
                    dependeeId = module.id, // For children.
                    isLast = passed == modules.size
                )

                append('\n').append(data.regularLinePrefix)
                module.id.uniqueNames.joinTo(this)

                val dependeeId: ResolvedDependencyId = parentData?.dependeeId ?: ResolvedDependencyId.ROOT
                val requestedVersion: String = module.requestedVersionsByDependees.getValue(dependeeId)
                if (requestedVersion.isNotEmpty() || module.selectedVersion.isNotEmpty()) {
                    append(": ")
                    append(requestedVersion.ifEmpty { UNKNOWN_VERSION })
                    if (requestedVersion != module.selectedVersion) {
                        append(" -> ")
                        append(module.selectedVersion.ifEmpty { UNKNOWN_VERSION })
                    }
                }

                if (errorModuleId == module.id) {
                    append('\n').append(data.errorLinePrefix)
                    append("^^^ This module has an error.")
                }

                val dependencies: Collection<ResolvedDependency>? = dependeeIdToDependencies[module.id]
                val renderedFirstTime = renderedModules.add(module.id)
                if (renderedFirstTime) {
                    // Rendered for the first time => also render dependencies.
                    if (dependencies != null) {
                        renderModules(dependencies, data)
                    }
                } else if (!dependencies.isNullOrEmpty()) {
                    everDependenciesOmitted = true
                    append(" (*)")
                }
            }
        }

        // Find roots. I.e. the modules that are depended directly by the source code module.
        val roots: Collection<ResolvedDependency> = dependeeIdToDependencies.getValue(ResolvedDependencyId.ROOT)

        renderModules(roots, parentData = null)

        if (everDependenciesOmitted) {
            append("\n\n(*) - dependencies omitted (listed previously)")
        }
    }

    private class Data(val parent: Data?, val dependeeId: ResolvedDependencyId, val isLast: Boolean) {
        val regularLinePrefix: String
            get() {
                return generateSequence(this) { it.parent }.map {
                    if (it === this) {
                        if (it.isLast) "\u2514\u2500\u2500\u2500 " /* └─── */ else "\u251C\u2500\u2500\u2500 " /* ├─── */
                    } else {
                        if (it.isLast) "     " else "\u2502    " /* │ */
                    }
                }.toList().asReversed().joinToString(separator = "")
            }

        val errorLinePrefix: String
            get() {
                return generateSequence(this) { it.parent }.map {
                    if (it.isLast) "     " else "\u2502    " /* │ */
                }.toList().asReversed().joinToString(separator = "")
            }
    }

    companion object {
        private const val UNKNOWN_VERSION = "unknown"
    }
}

class SignatureIdNotFoundInModuleWithDependencies(
    idSignature: IdSignature,
    currentModule: ModuleDescriptor,
    allModules: Collection<ModuleDescriptor>,
    userVisibleIrModulesSupport: UserVisibleIrModulesSupport
) : KotlinIrLinkerIssue(userVisibleIrModulesSupport) {
    override val message = buildString {
        val currentModuleId: ResolvedDependencyId = userVisibleIrModulesSupport.getUserVisibleModuleId(currentModule)
        val allVisibleModules: Map<ResolvedDependencyId, ResolvedDependency> = userVisibleIrModulesSupport.getUserVisibleModules(allModules)

        // cause:
        append("Module $currentModuleId has a reference to symbol ${idSignature.render()}.")
        append(" Neither the module itself nor its dependencies contain such declaration.")

        // explanation:
        append("\n\nThis could happen if the required dependency is missing in the project.")
        append(" Or if there are two (or more) dependency libraries, where one library ($currentModuleId)")
        append(" was compiled against the different version of the other library")
        append(" than the one currently used in the project.")

        // action items:
        append(" Please check that the project configuration is correct and has consistent versions of all required dependencies.")

        // the tree of dependencies:
        appendIrModules(
            allModules = allVisibleModules,
            errorModuleId = currentModuleId
        )
    }
}

class NoDeserializerForModule(moduleName: Name, idSignature: IdSignature?) : KotlinIrLinkerIssue(DefaultUserVisibleIrModulesSupport) {
    override val message = buildString {
        append("Could not load module ${moduleName.asString()}")
        if (idSignature != null) append(" in an attempt to find deserializer for symbol ${idSignature.render()}.")
    }
}

class SymbolTypeMismatch(
    cause: IrSymbolTypeMismatchException,
    allModules: Collection<ModuleDescriptor>,
    userVisibleIrModulesSupport: UserVisibleIrModulesSupport
) : KotlinIrLinkerIssue(userVisibleIrModulesSupport) {
    override val message: String = buildString {
        // cause:
        append(cause.message)

        // explanation:
        append("\n\nThis could happen if there are two (or more) dependency libraries,")
        append(" where one library was compiled against the different version of the other library")
        append(" than the one currently used in the project.")

        // action items:
        append(" Please check that the project configuration is correct and has consistent versions of dependencies.")

        // the tree of dependencies:
        appendIrModules(allModules = userVisibleIrModulesSupport.getUserVisibleModules(allModules))
    }
}
