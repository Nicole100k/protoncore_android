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

import studio.forface.easygradle.dsl.*
import util.libVersion

plugins {
    `kotlin-dsl`
    `kotlin-library`
}

libVersion = Version(0, 1, 10)

dependencies {

    implementation(
        `kotlin-jdk7`,

        `kotlin-gradle-plugin`,
        `detekt-gradle-plugin`,
        `dokka-gradle-plugin`
    )
    api(`easyGradle-android`)

    testImplementation(project(Module.kotlinTest))
}
