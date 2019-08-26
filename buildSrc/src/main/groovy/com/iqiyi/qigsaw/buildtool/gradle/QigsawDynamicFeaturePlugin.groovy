/*
 * MIT License
 *
 * Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.iqiyi.qigsaw.buildtool.gradle

import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApplicationVariant
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.AGPCompat
import com.iqiyi.qigsaw.buildtool.gradle.transform.SplitComponentTransform
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task

class QigsawDynamicFeaturePlugin extends QigsawPlugin {

    @Override
    void apply(Project project) {
        if (!project.getPlugins().hasPlugin("com.android.dynamic-feature")) {
            throw new GradleException("generateQigsawApk: Dynamic-feature plugin required")
        }
        AppExtension android = project.extensions.getByType(AppExtension)
        SplitComponentTransform transform
        if (hasQigsawTask(project)) {
            transform = new SplitComponentTransform(project)
            android.registerTransform(transform)
        }
        project.afterEvaluate {
            android.applicationVariants.all { variant ->
                ApplicationVariant appVariant = variant
                File manifestOutputDirectory = AGPCompat.getMergedManifestDirCompat(project, appVariant.name.capitalize())
                File manifestFile = new File(manifestOutputDirectory, "AndroidManifest.xml")
                Task splitComponentTransformTask = getSplitComponentTransformTask(project, appVariant.name.capitalize())
                if (splitComponentTransformTask != null) {
                    splitComponentTransformTask.doFirst {
                        if (transform != null) {
                            transform.setManifest(manifestFile)
                        }
                    }
                }
            }
        }
    }

    static Task getSplitComponentTransformTask(Project project, String variantName) {
        return project.tasks.findByName("transformClassesWithSplitComponentTransformFor${variantName}")
    }
}
