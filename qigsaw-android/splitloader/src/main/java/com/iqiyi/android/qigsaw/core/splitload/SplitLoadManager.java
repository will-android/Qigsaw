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

package com.iqiyi.android.qigsaw.core.splitload;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.v4.util.ArraySet;

import com.iqiyi.android.qigsaw.core.common.ProcessUtil;
import com.iqiyi.android.qigsaw.core.splitload.listener.OnSplitLoadListener;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import dalvik.system.PathClassLoader;

import static android.support.annotation.RestrictTo.Scope.LIBRARY_GROUP;

@RestrictTo(LIBRARY_GROUP)
public abstract class SplitLoadManager {

    private final Context context;

    private final Set<Split> loadedSplits = new ArraySet<>();

    private final Set<String> loadedSplitNames = new ArraySet<>();

    private final String currentProcessName;

    private final Set<String> loadedSplitApkPaths = new ArraySet<>();

    private final String[] processes;

    SplitLoadManager(Context context, String[] processes) {
        this.context = context;
        this.currentProcessName = ProcessUtil.getProcessName(context);
        this.processes = processes;
    }

    /**
     * Hook PathClassloader if need
     */
    public abstract void injectPathClassloaderIfNeed(boolean needHookClassLoader);

    /**
     * Called this method in {@link Application#onCreate()}.
     */
    public abstract void onCreate();

    /**
     * Called this method in {@link Application#getResources()}.
     * Check whether splits resources are loaded.
     *
     * @param resources refer to {@link Resources}
     */
    public abstract void getResources(Resources resources);

    /**
     * Create a runnable to load installed splits.
     *
     * @param splitFileIntents a list of installed splits details.
     * @param loadListener     a callback to be invoked when splits loaded.
     * @return load splits runnable.
     */
    public abstract Runnable createSplitLoadTask(List<Intent> splitFileIntents, @Nullable OnSplitLoadListener loadListener);

    /**
     * Get names of loaded splits
     *
     * @return a list of loaded split names.
     */
    public Set<String> getLoadedSplitNames() {
        return loadedSplitNames;
    }

    /**
     * Get path of loaded split apk files
     *
     * @return a list of loaded split apk file path.
     */
    Set<String> getLoadedSplitApkPaths() {
        return loadedSplitApkPaths;
    }

    public abstract void loadInstalledSplits();

    abstract PathClassLoader getInjectedClassloader();

    String getCurrentProcessName() {
        return currentProcessName;
    }

    boolean hasWorkProcess() {
        return processes != null && processes.length > 0;
    }

    String[] getWorkProcesses() {
        return processes;
    }

    Context getContext() {
        return context;
    }

    final void putSplits(Collection<Split> splits) {
        loadedSplits.addAll(splits);
        for (Split split : splits) {
            loadedSplitNames.add(split.splitName);
            loadedSplitApkPaths.add(split.splitApkPath);
        }
    }

    final Set<Split> getLoadedSplits() {
        return loadedSplits;
    }


}
