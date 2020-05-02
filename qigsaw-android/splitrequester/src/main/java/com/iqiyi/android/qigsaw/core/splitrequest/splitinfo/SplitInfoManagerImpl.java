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

package com.iqiyi.android.qigsaw.core.splitrequest.splitinfo;

import android.content.Context;
import android.content.res.Resources;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import com.iqiyi.android.qigsaw.core.common.AbiUtil;
import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

final class SplitInfoManagerImpl implements SplitInfoManager {

    private static final String TAG = "SplitInfoManagerImpl";

    private SplitDetails mSplitDetails;

    private SplitInfoVersionManager mVersionManager;

    void attach(SplitInfoVersionManager versionManager) {
        this.mVersionManager = versionManager;
    }

    private SplitInfoVersionManager getSplitInfoVersionManager() {
        return mVersionManager;
    }

    private SplitDetails getSplitDetails() {
        return mSplitDetails;
    }

    @Override
    @Nullable
    public String getBaseAppVersionName(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return mSplitDetails.getAppVersionName();
        }
        return null;
    }

    @Override
    @Nullable
    public String getQigsawId(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getQigsawId();
        }
        return null;
    }

    @Override
    @Nullable
    public List<String> getUpdateSplits(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getUpdateSplits();
        }
        return null;
    }

    @Override
    public SplitInfo getSplitInfo(Context context, String splitName) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            Collection<SplitInfo> splits = details.getSplitInfoListing().getSplitInfoMap().values();
            for (SplitInfo split : splits) {
                if (split.getSplitName().equals(splitName)) {
                    return split;
                }
            }
        }
        return null;
    }

    @Override
    public List<SplitInfo> getSplitInfos(Context context, Collection<String> splitNames) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            Collection<SplitInfo> splits = details.getSplitInfoListing().getSplitInfoMap().values();
            List<SplitInfo> splitInfos = new ArrayList<>(splitNames.size());
            for (SplitInfo split : splits) {
                if (splitNames.contains(split.getSplitName())) {
                    splitInfos.add(split);
                }
            }
            return splitInfos;
        }
        return null;
    }

    @Override
    public Collection<SplitInfo> getAllSplitInfo(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return mSplitDetails.getSplitInfoListing().getSplitInfoMap().values();
        }
        return null;
    }

    @Override
    @Nullable
    public SplitDetails createSplitDetailsForJsonFile(@NonNull String newSplitInfoPath) {
        File newSplitInfoFile = new File(newSplitInfoPath);
        if (newSplitInfoFile.exists()) {
            return createSplitDetailsForNewVersion(newSplitInfoFile);
        }
        return null;
    }

    @Override
    public String getCurrentSplitInfoVersion() {
        SplitInfoVersionManager versionManager = getSplitInfoVersionManager();
        return versionManager.getCurrentVersion();
    }

    @Override
    public boolean updateSplitInfoVersion(Context context, String newSplitInfoVersion, File newSplitInfoFile) {
        SplitInfoVersionManager versionManager = getSplitInfoVersionManager();
        return versionManager.updateVersion(context, newSplitInfoVersion, newSplitInfoFile);
    }

    private SplitDetails createSplitDetailsForDefaultVersion(Context context, String defaultVersion) {
        try {
            String defaultSplitInfoFileName = SplitConstants.QIGSAW_PREFIX + defaultVersion + SplitConstants.DOT_JSON;
            SplitLog.i(TAG, "Default split file name: " + defaultSplitInfoFileName);
            long currentTime = System.currentTimeMillis();
            SplitDetails details = parseSplitContentsForDefaultVersion(context, defaultSplitInfoFileName);
            SplitLog.i(TAG, "Cost %d mil-second to parse default split info", (System.currentTimeMillis() - currentTime));
            return details;
        } catch (Throwable e) {
            SplitLog.printErrStackTrace(TAG, e, "Failed to create default split info!");
        }
        return null;
    }

    private SplitDetails createSplitDetailsForNewVersion(File newSplitInfoFile) {
        try {
            SplitLog.i(TAG, "Updated split file path: " + newSplitInfoFile.getAbsolutePath());
            long currentTime = System.currentTimeMillis();
            SplitDetails details = parseSplitContentsForNewVersion(newSplitInfoFile);
            SplitLog.i(TAG, "Cost %d mil-second to parse updated split info", (System.currentTimeMillis() - currentTime));
            return details;
        } catch (Throwable e) {
            SplitLog.printErrStackTrace(TAG, e, "Failed to create updated split info!");
        }
        return null;
    }

    private synchronized SplitDetails getOrCreateSplitDetails(Context context) {
        SplitInfoVersionManager versionManager = getSplitInfoVersionManager();
        SplitDetails details = getSplitDetails();
        if (details == null) {
            String currentVersion = versionManager.getCurrentVersion();
            String defaultVersion = versionManager.getDefaultVersion();
            SplitLog.i(TAG, "currentVersion : %s defaultVersion : %s", currentVersion, defaultVersion);
            if (defaultVersion.equals(currentVersion)) {
                details = createSplitDetailsForDefaultVersion(context, defaultVersion);
            } else {
                File updatedSplitInfoFile = new File(versionManager.getRootDir(), SplitConstants.QIGSAW_PREFIX + currentVersion + SplitConstants.DOT_JSON);
                details = createSplitDetailsForNewVersion(updatedSplitInfoFile);
            }
            if (details != null) {
                if (TextUtils.isEmpty(details.getQigsawId())) {
                    return null;
                }
                if (!details.verifySplitInfoListing()) {
                    return null;
                }
            }
            mSplitDetails = details;
        }
        return details;
    }

    private static SplitDetails parseSplitContentsForDefaultVersion(Context context, String fileName)
            throws IOException, JSONException {
        String content = readInputStreamContent(createInputStreamFromAssets(context, fileName));
        return parseSplitsContent(content);
    }

    private SplitDetails parseSplitContentsForNewVersion(File newSplitInfoFile)
            throws IOException, JSONException {
        if (newSplitInfoFile != null && newSplitInfoFile.exists()) {
            return parseSplitsContent(readInputStreamContent(new FileInputStream(newSplitInfoFile)));
        }
        return null;
    }

    private static InputStream createInputStreamFromAssets(Context context, String fileName) {
        //using default
        InputStream is = null;
        Resources resources = context.getResources();
        if (resources != null) {
            try {
                is = resources.getAssets().open(fileName);
            } catch (IOException e) {
                //ignored
            }
        }
        return is;
    }

    private static String readInputStreamContent(InputStream is) throws IOException {
        if (is == null) {
            return null;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder stringBuffer = new StringBuilder();
        String str;
        while ((str = br.readLine()) != null) {
            stringBuffer.append(str);
        }
        FileUtil.closeQuietly(is);
        FileUtil.closeQuietly(br);
        return stringBuffer.toString();
    }

    private static SplitDetails parseSplitsContent(String content) throws JSONException {
        if (content == null) {
            return null;
        }
        LinkedHashMap<String, SplitInfo> splitInfoMap = new LinkedHashMap<>();
        JSONObject contentObj = new JSONObject(content);
        String qigsawId = contentObj.optString("qigsawId");
        String appVersionName = contentObj.optString("appVersionName");
        JSONArray abiFiltersArray = contentObj.optJSONArray("abiFilters");
        List<String> abiFilters = null;
        if (abiFiltersArray != null && abiFiltersArray.length() > 0) {
            abiFilters = new ArrayList<>(abiFiltersArray.length());
            for (int i = 0; i < abiFiltersArray.length(); i++) {
                String str = abiFiltersArray.getString(i);
                abiFilters.add(str);
            }
        }
        JSONArray updateSplitsArray = contentObj.optJSONArray("updateSplits");
        List<String> updateSplits = null;
        if (updateSplitsArray != null && updateSplitsArray.length() > 0) {
            updateSplits = new ArrayList<>(updateSplitsArray.length());
            for (int i = 0; i < updateSplitsArray.length(); i++) {
                String str = updateSplitsArray.getString(i);
                updateSplits.add(str);
            }
        }
        JSONArray array = contentObj.optJSONArray("splits");
        for (int i = 0; i < array.length(); i++) {
            JSONObject itemObj = array.getJSONObject(i);
            boolean builtIn = itemObj.optBoolean("builtIn");
            String splitName = itemObj.optString("splitName");
            String version = itemObj.optString("version");
            String url = itemObj.optString("url");
            String apkMd5 = itemObj.optString("md5");
            long size = itemObj.optLong("size");
            int minSdkVersion = itemObj.optInt("minSdkVersion");
            JSONArray nativeLibrariesArray = itemObj.optJSONArray("nativeLibraries");
            List<SplitInfo.LibInfo> nativeLibrariesList = null;
            List<String> splitAbis = null;
            if (nativeLibrariesArray != null && nativeLibrariesArray.length() > 0) {
                nativeLibrariesList = new ArrayList<>(nativeLibrariesArray.length());
                splitAbis = new ArrayList<>(nativeLibrariesArray.length());
                for (int j = 0; j < nativeLibrariesArray.length(); j++) {
                    JSONObject nlObj = nativeLibrariesArray.optJSONObject(j);
                    String cpuAbi = nlObj.optString("abi");
                    splitAbis.add(cpuAbi);
                    JSONArray jniLibsArray = nlObj.optJSONArray("jniLibs");
                    List<SplitInfo.LibInfo.Lib> jniLibs = new ArrayList<>();
                    if (jniLibsArray != null && jniLibsArray.length() > 0) {
                        for (int k = 0; k < jniLibsArray.length(); k++) {
                            JSONObject libObj = jniLibsArray.optJSONObject(k);
                            String name = libObj.optString("name");
                            String soMd5 = libObj.optString("md5");
                            long soSize = libObj.optLong("size");
                            SplitInfo.LibInfo.Lib lib = new SplitInfo.LibInfo.Lib(name, soMd5, soSize);
                            jniLibs.add(lib);
                        }
                    }
                    SplitInfo.LibInfo libInfo = new SplitInfo.LibInfo(cpuAbi, jniLibs);
                    nativeLibrariesList.add(libInfo);
                }
            }

            int dexNumber = itemObj.optInt("dexNumber");
            JSONArray processes = itemObj.optJSONArray("workProcesses");
            List<String> workProcesses = null;
            if (processes != null && processes.length() > 0) {
                workProcesses = new ArrayList<>(processes.length());
                for (int k = 0; k < processes.length(); k++) {
                    workProcesses.add(processes.optString(k));
                }
            }
            JSONArray dependenciesArray = itemObj.optJSONArray("dependencies");
            List<String> dependencies = null;
            if (dependenciesArray != null && dependenciesArray.length() > 0) {
                dependencies = new ArrayList<>(dependenciesArray.length());
                for (int m = 0; m < dependenciesArray.length(); m++) {
                    dependencies.add(dependenciesArray.optString(m));
                }
            }
            String basePrimaryAbi = AbiUtil.findBasePrimaryAbi(abiFilters);
            SplitInfo.LibInfo primaryLibInfo = null;
            if (splitAbis != null) {
                String splitPrimaryAbi = AbiUtil.findSplitPrimaryAbi(basePrimaryAbi, splitAbis);
                if (splitPrimaryAbi != null) {
                    for (SplitInfo.LibInfo libInfo : nativeLibrariesList) {
                        if (splitPrimaryAbi.contains(libInfo.getAbi())) {
                            primaryLibInfo = libInfo;
                            break;
                        }
                    }
                }
            }
            SplitInfo splitInfo = new SplitInfo(splitName, appVersionName, version, url, apkMd5,
                    size, builtIn, minSdkVersion, dexNumber, workProcesses, dependencies, splitAbis != null, primaryLibInfo);
            splitInfoMap.put(splitName, splitInfo);
        }
        SplitInfoListing splitInfoListing = new SplitInfoListing(splitInfoMap);
        return new SplitDetails(qigsawId, appVersionName, splitInfoListing, abiFilters, updateSplits);
    }
}
