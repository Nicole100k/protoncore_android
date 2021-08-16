/*
 * Copyright (c) 2020 Proton Technologies AG
 * This file is part of Proton Technologies AG and ProtonCore.
 *
 * ProtonCore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonCore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonCore.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.android.build.gradle.TestedExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import studio.forface.easygradle.dsl.*
import studio.forface.easygradle.dsl.android.*

/** Default value for `sharedTest` modules */
private val testVersion = Version(0, 0, 0)

/**
 * Dsl for apply the android configuration to a library or application module
 * @author Davide Farella
 */
@Suppress("UnstableApiUsage")
fun org.gradle.api.Project.android(

    version: Version = testVersion,
    appId: String? = null,
    minSdk: Int = ProtonCore.minSdk,
    targetSdk: Int = ProtonCore.targetSdk,
    useDataBinding: Boolean = false,
    config: ExtraConfig = {}

) = (this as ExtensionAware).extensions.configure<TestedExtension> {

    compileSdkVersion(targetSdk)
    defaultConfig {

        // Params
        appId?.let { applicationId = it }
        this.version = version

        // SDK
        minSdkVersion(minSdk)
        targetSdkVersion(targetSdk)
        buildToolsVersion = "30.0.2"
        ndkVersion = "21.3.6528147"

        // Other
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
    }

    // Data/View Binding turned off by default to prevent unneeded generation.
    // You must turn it on if you need it in your module:  android(useDataBinding = true).
    buildFeatures.viewBinding = useDataBinding
    dataBinding.isEnabled = useDataBinding

    // Add support for `src/x/kotlin` instead of `src/x/java` only
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }

    compileOptions {
        sourceCompatibility = ProtonCore.jdkVersion
        targetCompatibility = sourceCompatibility
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lintOptions {
        isAbortOnError = false
        textReport = true
        textOutput("stdout")
    }

    packagingOptions {
        exclude("go/*.java")
        exclude("licenses/*.txt")
        exclude("licenses/*.TXT")
        exclude("licenses/*.xml")
        exclude("META-INF/*.txt")
        exclude("META-INF/AL2.0")
        exclude("META-INF/LGPL2.1")
        exclude("META-INF/licenses/ASM")
        exclude("META-INF/plexus/*.xml")
        exclude("org/apache/maven/project/*.xml")
        exclude("org/codehaus/plexus/*.xml")
        exclude("org/cyberneko/html/res/*.txt")
        exclude("org/cyberneko/html/res/*.properties")
        pickFirst("lib/armeabi-v7a/libgojni.so")
        pickFirst("lib/arm64-v8a/libgojni.so")
        pickFirst("lib/x86/libgojni.so")
        pickFirst("lib/x86_64/libgojni.so")
        pickFirst("win32-x86-64/attach_hotspot_windows.dll")
        pickFirst("win32-x86/attach_hotspot_windows.dll")
    }

    apply(config)
}

typealias ExtraConfig = TestedExtension.() -> Unit
