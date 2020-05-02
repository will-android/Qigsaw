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

package com.iqiyi.android.qigsaw.core.splitinstall;

import android.app.Activity;
import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import com.iqiyi.android.qigsaw.core.splitdownload.Downloader;
import com.iqiyi.android.qigsaw.core.splitinstall.remote.SplitInstallSupervisor;

import java.util.concurrent.atomic.AtomicReference;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

@RestrictTo(LIBRARY_GROUP)
public final class SplitApkInstaller {

    private static final AtomicReference<SplitInstallSupervisor> sSplitApkInstallerRef = new AtomicReference<>();

    private SplitApkInstaller() {

    }

    public static void install(Context context,
                               Downloader downloader,
                               Class<? extends Activity> obtainUserConfirmationActivityClass,
                               boolean verifySignature) {
        if (sSplitApkInstallerRef.get() == null) {
            sSplitApkInstallerRef.set(new SplitInstallSupervisorImpl(
                    context,
                    new SplitInstallSessionManagerImpl(context),
                    downloader,
                    obtainUserConfirmationActivityClass,
                    verifySignature)
            );
        }
    }

    @Nullable
    public static SplitInstallSupervisor getSplitInstallSupervisor() {
        return sSplitApkInstallerRef.get();
    }

    public static void startUninstallSplits(Context context) {
        if (sSplitApkInstallerRef.get() == null) {
            throw new RuntimeException("Have you install SplitApkInstaller?");
        }
        sSplitApkInstallerRef.get().startUninstall(context);
    }
}
