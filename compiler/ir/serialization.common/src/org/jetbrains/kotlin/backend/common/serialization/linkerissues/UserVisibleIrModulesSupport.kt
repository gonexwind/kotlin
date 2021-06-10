/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.linkerissues

import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.utils.ResolvedDependency
import org.jetbrains.kotlin.utils.ResolvedDependencyId

abstract class UserVisibleIrModulesSupport {
    // TODO: use IR entities instead of descriptors
    abstract fun getUserVisibleModuleId(descriptor: ModuleDescriptor): ResolvedDependencyId
    abstract fun getUserVisibleModules(descriptors: Collection<ModuleDescriptor>): Map<ResolvedDependencyId, ResolvedDependency>

    val moduleIdComparator: Comparator<ResolvedDependencyId> = Comparator { a, b ->
        when {
            a == b -> 0
            // Kotlin libs go lower.
            a.isKotlinLibrary && !b.isKotlinLibrary -> 1
            !a.isKotlinLibrary && b.isKotlinLibrary -> -1
            // Modules with simple names go upper as they are most likely user-made libs.
            a.hasSimpleName && !b.hasSimpleName -> -1
            !a.hasSimpleName && b.hasSimpleName -> 1
            // Else: just compare by names.
            else -> {
                val aUniqueNames = a.uniqueNames.iterator()
                val bUniqueNames = b.uniqueNames.iterator()

                while (aUniqueNames.hasNext() && bUniqueNames.hasNext()) {
                    val diff = aUniqueNames.next().compareTo(bUniqueNames.next())
                    if (diff != 0) return@Comparator diff
                }

                when {
                    aUniqueNames.hasNext() -> 1
                    bUniqueNames.hasNext() -> -1
                    else -> 0
                }
            }
        }
    }

    protected open val ResolvedDependencyId.isKotlinLibrary: Boolean
        get() = uniqueNames.any { uniqueName -> uniqueName.startsWith(KOTLIN_LIBRARY_PREFIX) }

    protected open val ResolvedDependencyId.hasSimpleName: Boolean
        get() = uniqueNames.all { uniqueName -> uniqueName.none { it == '.' || it == ':' } }

    protected data class ModuleWithUninitializedDependencies(
        val module: ResolvedDependency,
        val dependencyIds: List<ResolvedDependencyId>
    )

    protected fun Map<ResolvedDependencyId, ModuleWithUninitializedDependencies>.stampDependenciesWithRequestedVersionEqualToSelectedVersion(): Map<ResolvedDependencyId, ResolvedDependency> {
        return mapValues { (moduleId, moduleWithUninitializedDependencies) ->
            val (module, dependencyIds) = moduleWithUninitializedDependencies
            dependencyIds.forEach { dependencyId ->
                val dependencyModule = getValue(dependencyId).module
                dependencyModule.requestedVersionsByDependees[moduleId] = dependencyModule.selectedVersion
            }
            module
        }
    }

    companion object {
        const val KOTLIN_LIBRARY_PREFIX = "org.jetbrains.kotlin"
    }
}

object DefaultUserVisibleIrModulesSupport : UserVisibleIrModulesSupport() {
    override fun getUserVisibleModuleId(descriptor: ModuleDescriptor): ResolvedDependencyId = descriptor.moduleId

    override fun getUserVisibleModules(descriptors: Collection<ModuleDescriptor>): Map<ResolvedDependencyId, ResolvedDependency> {
        val modules = descriptors.associate { descriptor ->
            val moduleId = descriptor.moduleId
            val module = ResolvedDependency(
                id = moduleId,
                // TODO: support extracting all the necessary details for non-Native libs: selectedVersion, requestedVersions, artifacts
                selectedVersion = "",
                requestedVersionsByDependees = mutableMapOf(), // To be filled below.
                artifactPaths = mutableSetOf()
            )

            val dependencyIds = descriptor.allDependencyModules.mapNotNull { dependencyDescriptor ->
                if (dependencyDescriptor == descriptor) {
                    // Don't show the module itself in the list of own dependencies.
                    return@mapNotNull null
                }

                dependencyDescriptor.moduleId
            }

            moduleId to ModuleWithUninitializedDependencies(module, dependencyIds)
        }

        return modules.stampDependenciesWithRequestedVersionEqualToSelectedVersion()
    }

    private val ModuleDescriptor.moduleId: ResolvedDependencyId
        get() = ResolvedDependencyId(name.asStringStripSpecialMarkers())
}
