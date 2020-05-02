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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitBaseInfoProvider;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitAABInfoProvider;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitdownload.DownloadRequest;
import com.iqiyi.android.qigsaw.core.splitdownload.Downloader;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManager;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManagerService;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;
import com.iqiyi.android.qigsaw.core.splitinstall.remote.SplitInstallSupervisor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SplitInstallSupervisorImpl extends SplitInstallSupervisor {

    private static final String TAG = "Split:SplitInstallSupervisorImpl";

    private final Context appContext;

    private final SplitInstallSessionManager sessionManager;

    private final Downloader userDownloader;

    private final long downloadSizeThresholdValue;

    private final Set<String> installedSplitForAAB;

    private final Class<?> obtainUserConfirmationActivityClass;

    private final SplitInstaller splitInstaller;

    private final boolean verifySignature;

    private final List<String> dynamicFeatures;

    SplitInstallSupervisorImpl(Context appContext,
                               SplitInstallSessionManager sessionManager,
                               Downloader userDownloader,
                               Class<? extends Activity> obtainUserConfirmationActivityClass,
                               boolean verifySignature) {
        this.appContext = appContext;
        this.sessionManager = sessionManager;
        this.userDownloader = userDownloader;
        long downloadSizeThreshold = userDownloader.getDownloadSizeThresholdWhenUsingMobileData();
        this.downloadSizeThresholdValue = downloadSizeThreshold < 0 ? Long.MAX_VALUE : downloadSizeThreshold;
        this.installedSplitForAAB = new SplitAABInfoProvider(this.appContext).getInstalledSplitsForAAB();
        this.obtainUserConfirmationActivityClass = obtainUserConfirmationActivityClass;
        this.splitInstaller = new SplitInstallerImpl(appContext, verifySignature);
        this.verifySignature = verifySignature;
        String[] dynamicFeaturesArray = SplitBaseInfoProvider.getDynamicFeatures();
        this.dynamicFeatures = dynamicFeaturesArray == null ? null : Arrays.asList(dynamicFeaturesArray);
        if (dynamicFeatures == null) {
            SplitLog.w(TAG, "Can't read dynamicFeatures from SplitBaseInfoProvider");
        }
    }

    @Override
    public void startInstall(List<Bundle> moduleNames, Callback callback) {
        List<String> moduleNameList = unBundleModuleNames(moduleNames);
        int errorCode = onPreInstallSplits(moduleNameList);
        if (errorCode != SplitInstallInternalErrorCode.NO_ERROR) {
            callback.onError(bundleErrorCode(errorCode));
        } else {
            List<SplitInfo> needInstallSplits = getNeed2BeInstalledSplits(moduleNameList);
            //check network status
            if (!isAllSplitsBuiltIn(needInstallSplits) && !isNetworkAvailable(appContext)) {
                callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.NETWORK_ERROR));
                return;
            }
            startDownloadSplits(moduleNameList, needInstallSplits, callback);
        }
    }

    @Override
    public void deferredInstall(List<Bundle> moduleNames, Callback callback) {
        List<String> moduleNameList = unBundleModuleNames(moduleNames);
        int errorCode = onPreInstallSplits(moduleNameList);
        if (errorCode == SplitInstallInternalErrorCode.NO_ERROR) {
            if (!getInstalledSplitForAAB().isEmpty()) {
                if (getInstalledSplitForAAB().containsAll(moduleNameList)) {
                    callback.onDeferredInstall(null);
                }
            } else {
                List<SplitInfo> needInstallSplits = getNeed2BeInstalledSplits(moduleNameList);
                deferredDownloadSplits(needInstallSplits, callback);
            }
        } else {
            callback.onError(bundleErrorCode(errorCode));
        }
    }

    @Override
    public void deferredUninstall(List<Bundle> moduleNames, Callback callback) {
        if (!getInstalledSplitForAAB().isEmpty()) {
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.UNINSTALLATION_UNSUPPORTED));
            return;
        }
        List<String> moduleNameList = unBundleModuleNames(moduleNames);
        int errorCode = checkInternalErrorCode();
        if (errorCode != SplitInstallInternalErrorCode.NO_ERROR) {
            callback.onError(bundleErrorCode(errorCode));
            return;
        }
        if (isRequestInvalid(moduleNameList)) {
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.INVALID_REQUEST));
            return;
        }
        SplitPendingUninstallManager uninstallInfoManager = new SplitPendingUninstallManager();
        boolean result = uninstallInfoManager.recordPendingUninstallSplits(moduleNameList);
        if (result) {
            SplitLog.w(TAG, "Succeed to record pending uninstall splits %s!", moduleNameList.toString());
            callback.onDeferredUninstall(null);
        } else {
            SplitLog.w(TAG, "Failed to record pending uninstall splits!");
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.INTERNAL_ERROR));
        }
    }

    @Override
    public void cancelInstall(int sessionId, Callback callback) {
        SplitLog.i(TAG, "start to cancel session id %d installation", sessionId);
        SplitInstallInternalSessionState sessionState = sessionManager.getSessionState(sessionId);
        if (sessionState == null) {
            SplitLog.i(TAG, "Session id is not found!");
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.SESSION_NOT_FOUND));
            return;
        }
        if (sessionState.status() == SplitInstallInternalSessionStatus.PENDING
                || sessionState.status() == SplitInstallInternalSessionStatus.DOWNLOADING) {
            boolean ret = userDownloader.cancelDownloadSync(sessionId);
            SplitLog.d(TAG, "result of cancel request : " + ret);
            if (ret) {
                callback.onCancelInstall(sessionId, null);
            } else {
                callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.INVALID_REQUEST));
            }
        } else {
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.INVALID_REQUEST));
        }
    }

    @Override
    public void getSessionState(int sessionId, Callback callback) {
        SplitInstallInternalSessionState sessionStateVariant = sessionManager.getSessionState(sessionId);
        if (sessionStateVariant == null) {
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.SESSION_NOT_FOUND));
            return;
        }
        callback.onGetSession(sessionId, SplitInstallInternalSessionState.transform2Bundle(sessionStateVariant));
    }

    @Override
    public void getSessionStates(Callback callback) {
        List<SplitInstallInternalSessionState> sessionStateVariantList = sessionManager.getSessionStates();
        if (sessionStateVariantList.isEmpty()) {
            callback.onGetSessionStates(Collections.<Bundle>emptyList());
        } else {
            List<Bundle> sessionStates = new ArrayList<>(0);
            for (SplitInstallInternalSessionState sessionStateVariant : sessionStateVariantList) {
                sessionStates.add(SplitInstallInternalSessionState.transform2Bundle(sessionStateVariant));
            }
            callback.onGetSessionStates(sessionStates);
        }
    }

    @Override
    public boolean continueInstallWithUserConfirmation(int sessionId) {
        SplitInstallInternalSessionState sessionState = sessionManager.getSessionState(sessionId);
        if (sessionState != null) {
            StartDownloadCallback downloadCallback = new StartDownloadCallback(
                    splitInstaller, sessionId, sessionManager, sessionState.needInstalledSplits);
            sessionManager.changeSessionState(sessionId, SplitInstallInternalSessionStatus.PENDING);
            sessionManager.emitSessionState(sessionState);
            userDownloader.startDownload(sessionState.sessionId(), sessionState.downloadRequests, downloadCallback);
            return true;
        }
        return false;
    }

    @Override
    public boolean cancelInstallWithoutUserConfirmation(int sessionId) {
        SplitInstallInternalSessionState sessionState = sessionManager.getSessionState(sessionId);
        if (sessionState != null) {
            sessionManager.changeSessionState(sessionState.sessionId(), SplitInstallInternalSessionStatus.CANCELED);
            sessionManager.emitSessionState(sessionState);
            return true;
        }
        return false;
    }

    private boolean isAllSplitsBuiltIn(List<SplitInfo> needInstallSplits) {
        for (SplitInfo info : needInstallSplits) {
            if (!info.isBuiltIn()) {
                return false;
            }
        }
        return true;
    }

    private int onPreInstallSplits(List<String> moduleNames) {
        if (!getInstalledSplitForAAB().isEmpty()) {
            if (!getInstalledSplitForAAB().containsAll(moduleNames)) {
                return SplitInstallInternalErrorCode.INVALID_REQUEST;
            }
        } else {
            int errorCode = checkInternalErrorCode();
            if (errorCode == SplitInstallInternalErrorCode.NO_ERROR) {
                errorCode = checkRequestErrorCode(moduleNames);
            }
            return errorCode;
        }
        return SplitInstallInternalErrorCode.NO_ERROR;
    }

    private int checkRequestErrorCode(List<String> moduleNames) {
        if (isRequestInvalid(moduleNames)) {
            return SplitInstallInternalErrorCode.INVALID_REQUEST;
        }
        if (!isModuleAvailable(moduleNames)) {
            return SplitInstallInternalErrorCode.MODULE_UNAVAILABLE;
        }
        return SplitInstallInternalErrorCode.NO_ERROR;
    }

    private int checkInternalErrorCode() {
        SplitInfoManager manager = SplitInfoManagerService.getInstance();
        if (manager == null) {
            SplitLog.w(TAG, "Failed to fetch SplitInfoManager instance!");
            return SplitInstallInternalErrorCode.INTERNAL_ERROR;
        }
        Collection<SplitInfo> allSplits = manager.getAllSplitInfo(appContext);
        if (allSplits == null || allSplits.isEmpty()) {
            SplitLog.w(TAG, "Failed to parse json file of split info!");
            return SplitInstallInternalErrorCode.INTERNAL_ERROR;
        }
        String baseAppVersionName = manager.getBaseAppVersionName(appContext);
        String versionName = SplitBaseInfoProvider.getVersionName();
        if (TextUtils.isEmpty(baseAppVersionName) || !baseAppVersionName.equals(versionName)) {
            SplitLog.w(TAG, "Failed to match base app version-name excepted base app version %s but %s!", versionName, baseAppVersionName);
            return SplitInstallInternalErrorCode.INTERNAL_ERROR;
        }
        String qigsawId = manager.getQigsawId(appContext);
        String baseAppQigsawId = SplitBaseInfoProvider.getQigsawId();
        if (TextUtils.isEmpty(qigsawId) || !qigsawId.equals(baseAppQigsawId)) {
            SplitLog.w(TAG, "Failed to match base app qigsaw-version excepted %s but %s!", baseAppQigsawId, qigsawId);
            return SplitInstallInternalErrorCode.INTERNAL_ERROR;
        }
        return SplitInstallInternalErrorCode.NO_ERROR;
    }

    private Set<String> getInstalledSplitForAAB() {
        return installedSplitForAAB;
    }

    private List<SplitInfo> getNeed2BeInstalledSplits(List<String> moduleNames) {
        SplitInfoManager manager = SplitInfoManagerService.getInstance();
        assert manager != null;
        List<SplitInfo> needInstallSplitInfos = manager.getSplitInfos(appContext, moduleNames);
        Set<String> dependenciesSplits = new HashSet<>(0);
        for (SplitInfo info : needInstallSplitInfos) {
            if (info.getDependencies() != null) {
                dependenciesSplits.addAll(info.getDependencies());
            }
        }
        if (!dependenciesSplits.isEmpty()) {
            dependenciesSplits.removeAll(moduleNames);
            SplitLog.i(TAG, "Add dependencies %s automatically for install splits %s!", dependenciesSplits.toString(), moduleNames.toString());
            List<SplitInfo> dependenciesSplitInfos = manager.getSplitInfos(appContext, dependenciesSplits);
            dependenciesSplitInfos.addAll(needInstallSplitInfos);
            return dependenciesSplitInfos;
        }
        return needInstallSplitInfos;
    }

    private void deferredDownloadSplits(final List<SplitInfo> needInstallSplits, final Callback callback) {
        try {
            long[] result = onPreDownloadSplits(needInstallSplits);
            callback.onDeferredInstall(null);
            long realTotalBytesNeedToDownload = result[1];
            int sessionId = createSessionId(needInstallSplits);
            SplitLog.d(TAG, "DeferredInstall session id: " + sessionId);
            DeferredDownloadCallback downloadCallback = new DeferredDownloadCallback(splitInstaller, needInstallSplits);
            if (realTotalBytesNeedToDownload == 0) {
                SplitLog.d(TAG, "Splits have been downloaded, install them directly!");
                downloadCallback.onCompleted();
            } else {
                boolean usingMobileDataPermitted = realTotalBytesNeedToDownload < downloadSizeThresholdValue && !userDownloader.isDeferredDownloadOnlyWhenUsingWifiData();

                userDownloader.deferredDownload(sessionId, createDownloadRequests(needInstallSplits), downloadCallback, usingMobileDataPermitted);
            }
        } catch (IOException e) {
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.BUILTIN_SPLIT_APK_COPIED_FAILED));
            SplitLog.printErrStackTrace(TAG, e, "Failed to copy builtin split apks(%s)", "onDeferredInstall");
        }
    }

    private void startDownloadSplits(final List<String> moduleNames,
                                     final List<SplitInfo> needInstallSplits,
                                     final Callback callback) {
        if (sessionManager.isActiveSessionsLimitExceeded()) {
            SplitLog.w(TAG, "Start install request error code: ACTIVE_SESSIONS_LIMIT_EXCEEDED");
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.ACTIVE_SESSIONS_LIMIT_EXCEEDED));
            return;
        }
        int sessionId = createSessionId(needInstallSplits);
        List<DownloadRequest> downloadRequests = createDownloadRequests(needInstallSplits);
        SplitLog.d(TAG, "startInstall session id: " + sessionId);
        SplitInstallInternalSessionState sessionState = sessionManager.getSessionState(sessionId);
        boolean needUserConfirmation = false;
        if (sessionState != null) {
            needUserConfirmation = sessionState.status() == SplitInstallInternalSessionStatus.REQUIRES_USER_CONFIRMATION;
        } else {
            sessionState = new SplitInstallInternalSessionState(sessionId, moduleNames, needInstallSplits, downloadRequests);
        }
        if (!needUserConfirmation && sessionManager.isIncompatibleWithExistingSession(moduleNames)) {
            SplitLog.w(TAG, "Start install request error code: INCOMPATIBLE_WITH_EXISTING_SESSION");
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.INCOMPATIBLE_WITH_EXISTING_SESSION));
            return;
        }
        try {
            //1.copy built-in apk if need
            //2.check signature
            //3.create list of download request
            long[] result = onPreDownloadSplits(needInstallSplits);
            //wait util builtin splits are copied completely.
            callback.onStartInstall(sessionId, null);
            sessionManager.setSessionState(sessionId, sessionState);
            //calculate bytes to download
            long totalBytesToDownload = result[0];
            long realTotalBytesNeedToDownload = result[1];
            SplitLog.d(TAG, "totalBytesToDownload: %d, realTotalBytesNeedToDownload: %d ", totalBytesToDownload, realTotalBytesNeedToDownload);
            sessionState.setTotalBytesToDownload(totalBytesToDownload);
            StartDownloadCallback downloadCallback = new StartDownloadCallback(splitInstaller, sessionId, sessionManager, needInstallSplits);
            if (realTotalBytesNeedToDownload <= 0) {
                SplitLog.d(TAG, "Splits have been downloaded, install them directly!");
                downloadCallback.onCompleted();
            } else {
                if (isMobileAvailable(appContext)) {
                    if (realTotalBytesNeedToDownload > downloadSizeThresholdValue) {
                        startUserConfirmationActivity(sessionState, realTotalBytesNeedToDownload, downloadRequests);
                        return;
                    }
                }
                sessionManager.changeSessionState(sessionId, SplitInstallInternalSessionStatus.PENDING);
                sessionManager.emitSessionState(sessionState);
                userDownloader.startDownload(sessionId, downloadRequests, downloadCallback);
            }
        } catch (IOException e) {
            //copy local split file failed!
            SplitLog.w(TAG, "Failed to copy internal splits", e);
            callback.onError(bundleErrorCode(SplitInstallInternalErrorCode.BUILTIN_SPLIT_APK_COPIED_FAILED));
        }
    }

    private void startUserConfirmationActivity(SplitInstallInternalSessionState sessionState,
                                               long realTotalBytesNeedToDownload,
                                               List<DownloadRequest> requests) {
        Intent intent = new Intent();
        intent.putExtra("sessionId", sessionState.sessionId());
        intent.putParcelableArrayListExtra("downloadRequests", (ArrayList<? extends Parcelable>) requests);
        intent.putExtra("realTotalBytesNeedToDownload", realTotalBytesNeedToDownload);
        intent.putStringArrayListExtra("moduleNames", (ArrayList<String>) sessionState.moduleNames());
        intent.setClass(appContext, obtainUserConfirmationActivityClass);
        PendingIntent pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        sessionState.setUserConfirmationIntent(pendingIntent);
        sessionManager.changeSessionState(sessionState.sessionId(), SplitInstallInternalSessionStatus.REQUIRES_USER_CONFIRMATION);
        sessionManager.emitSessionState(sessionState);
    }

    private boolean isRequestInvalid(List<String> moduleNames) {
        return moduleNames == null || moduleNames.isEmpty() || dynamicFeatures == null || !dynamicFeatures.containsAll(moduleNames);
    }

    private boolean isModuleAvailable(List<String> moduleNames) {
        SplitInfoManager manager = SplitInfoManagerService.getInstance();
        assert manager != null;
        Collection<SplitInfo> splitInfoList = manager.getAllSplitInfo(appContext);
        for (String moduleName : moduleNames) {
            for (SplitInfo info : splitInfoList) {
                if (info.getSplitName().equals(moduleName)) {
                    if (!checkSplitInfo(info)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean checkSplitInfo(SplitInfo info) {
        return isCPUArchMatched(info) && isMinSdkVersionMatched(info);
    }

    /**
     * check whether split apk info is available for current app version.
     */
    private boolean isMinSdkVersionMatched(SplitInfo splitInfo) {
        return splitInfo.getMinSdkVersion() <= Build.VERSION.SDK_INT;
    }

    /**
     * If split has lib files, we need to check whether it supports current cpu arch.
     */
    private boolean isCPUArchMatched(SplitInfo splitInfo) {
        if (splitInfo.hasLibs()) {
            try {
                splitInfo.getLibInfo();
            } catch (Throwable e) {
                return false;
            }
        }
        return true;
    }

    private List<DownloadRequest> createDownloadRequests(Collection<SplitInfo> splitInfoList) {
        List<DownloadRequest> requests = new ArrayList<>(splitInfoList.size());
        for (SplitInfo splitInfo : splitInfoList) {
            File splitDir = SplitPathManager.require().getSplitDir(splitInfo);
            String fileName = splitInfo.getSplitName() + SplitConstants.DOT_APK;
            //create download request
            DownloadRequest request = DownloadRequest.newBuilder()
                    .url(splitInfo.getUrl())
                    .fileDir(splitDir.getAbsolutePath())
                    .fileName(fileName)
                    .fileMD5(splitInfo.getMd5())
                    .moduleName(splitInfo.getSplitName())
                    .build();
            requests.add(request);
        }
        return requests;
    }

    private long[] onPreDownloadSplits(Collection<SplitInfo> splitInfoList) throws IOException {
        long totalBytesToDownload = 0L;
        long realTotalBytesNeedToDownload = 0L;
        for (SplitInfo splitInfo : splitInfoList) {
            File splitDir = SplitPathManager.require().getSplitDir(splitInfo);
            String fileName = splitInfo.getSplitName() + SplitConstants.DOT_APK;
            File splitApk;
            if (splitInfo.getUrl().startsWith(SplitConstants.URL_NATIVE)) {
                splitApk = new File(appContext.getApplicationInfo().nativeLibraryDir, System.mapLibraryName(SplitConstants.SPLIT_PREFIX + splitInfo.getSplitName()));
            } else {
                splitApk = new File(splitDir, fileName);
            }
            SplitDownloadPreprocessor processor = new SplitDownloadPreprocessor(splitDir, splitApk);
            try {
                processor.load(appContext, splitInfo, verifySignature);
            } finally {
                FileUtil.closeQuietly(processor);
            }
            //calculate splits total download size.
            totalBytesToDownload = totalBytesToDownload + splitInfo.getSize();
            if (!splitApk.exists()) {
                realTotalBytesNeedToDownload = realTotalBytesNeedToDownload + splitInfo.getSize();
            }
        }
        return new long[]{totalBytesToDownload, realTotalBytesNeedToDownload};
    }
}
