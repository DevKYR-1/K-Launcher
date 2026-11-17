package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.HttpStatusException;
import net.kdt.pojavlaunch.utils.LauncherLog;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MinecraftDownloader {
    private static final double ONE_MEGABYTE = (1024d * 1024d);
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private HashSet<String> mScheduledTargets; // Paths of the files that already have a task, to never download one file twice
    private ArrayList<File> mDeclaredNatives;
    private AtomicLong mProcessedFileCounter;
    private AtomicLong mProcessedSizeCounter; // Total bytes of processed files (passed SHA1 or downloaded)
    private AtomicLong mInternetUsageCounter; // How many bytes downloaded over Internet
    private long mTotalFileCount;
    private long mTotalSize;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.
    private boolean mUseFileCounter; // Whether a file counter or a size counter should be used for progress

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();

    // Two game downloads at the same time would write the same files. Only one is allowed to run.
    private static final AtomicBoolean sDownloadRunning = new AtomicBoolean(false);

    private boolean isLocalProfile = false;

    /** @return true while a game download is running */
    public static boolean isRunning() {
        return sDownloadRunning.get();
    }

    /**
     * Start the game version download process on the global executor service.
     * <p>
     * This is the "check and download the files" part of pressing PLAY: the account type decides what is
     * allowed, then the files of the version are checked, the missing or damaged ones are downloaded
     * (Microsoft accounts only) and verified, and only after that the listener is told that the game can start.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion, // this was there for a reason
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        if(!sDownloadRunning.compareAndSet(false, true)) {
            listener.onDownloadFailed(new DownloadPreconditionException(
                    DownloadPreconditionException.Reason.BUSY, "Another game download is already in progress"));
            return;
        }
        try {
            if(activity != null){
                isLocalProfile = Tools.isLocalProfile(activity);
                Tools.switchDemo(Tools.isDemoProfile(activity));
            } else {
                isLocalProfile = true;
                Tools.switchDemo(true);
            }

            sExecutorService.execute(() -> {
                try {
                    LauncherLog.info("Download", "Checking the files of " + realVersion
                            + " (account type: " + (isLocalProfile ? "local" : "microsoft") + ")");
                    downloadGame(activity, version, realVersion);
                    LauncherLog.info("Download", "All files of " + realVersion + " are ready");
                    listener.onDownloadDone();
                }catch (InterruptedException e) {
                    // Cancelled: nothing failed, so there is nothing to tell the user, but the game does not start
                    Thread.currentThread().interrupt();
                    LauncherLog.info("Download", "Preparing " + realVersion + " was cancelled");
                }catch (Exception e) {
                    // The user gets a short message. The technical details go to the launcher log.
                    LauncherLog.error("Download", "Preparing " + realVersion + " failed", e);
                    listener.onDownloadFailed(e);
                } finally {
                    ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
                    sDownloadRunning.set(false);
                }
            });
        }catch (RuntimeException e) {
            // The task was not even started, so nothing will release the lock
            sDownloadRunning.set(false);
            throw e;
        }
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private void downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
        SpeedCalculator speedCalculator = new SpeedCalculator();

        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mScheduledTargets = new HashSet<>();
        mDeclaredNatives = new ArrayList<>();
        mProcessedFileCounter = new AtomicLong(0);
        mProcessedSizeCounter = new AtomicLong(0);
        mInternetUsageCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);
        mUseFileCounter = false;

        // Steps 1-3 of PLAY (account type, profile, version): read the version and plan the files it needs
        if(!downloadAndProcessMetadata(activity, verInfo, versionName)) {
            // Step 8: there is no Java runtime that can run this version
            throw new DownloadPreconditionException(DownloadPreconditionException.Reason.JAVA_RUNTIME,
                    activity.getString(R.string.exception_failed_to_unpack_jre17));
        }

        // Step 4: find out what is missing. A Local account must not download anything, so it is told
        // now, before any work is done, instead of failing at the first missing file.
        if(isLocalProfile) ensureLocalProfileHasAllFiles();

        // Steps 5-7: download what is missing, verify everything with SHA-1, prepare the libraries
        ArrayBlockingQueue<Runnable> taskQueue =
                new ArrayBlockingQueue<>(Math.max(1, mScheduledDownloadTasks.size()), false);
        ThreadPoolExecutor downloaderPool =
                new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS, taskQueue);

        // I have tried pre-filling the queue directly instead of doing this, but it didn't work.
        // What a shame.
        for(DownloaderTask scheduledTask : mScheduledDownloadTasks) downloaderPool.execute(scheduledTask);
        downloaderPool.shutdown();

        try {
            while (mDownloaderThreadException.get() == null &&
                    !downloaderPool.awaitTermination(33, TimeUnit.MILLISECONDS)) {
                double speed = speedCalculator.feed(mInternetUsageCounter.get()) / ONE_MEGABYTE;
                if(mUseFileCounter) reportProgressFileCounter(speed);
                else reportProgressSizeCounter(speed);
            }
            Exception thrownException = mDownloaderThreadException.get();
            if(thrownException != null) {
                // The game must not start, and the files that are still waiting must not keep downloading
                // (or being checked) in the background after the user has been told that it failed.
                downloaderPool.shutdownNow();
                throw thrownException;
            } else {
                ensureJarFileCopy();
                extractNatives(versionName);
            }
        }catch (InterruptedException e) {
            // Interrupted while waiting, which means that the download was cancelled.
            // Kill all downloading threads immediately, and ignore any exceptions thrown by them.
            // The game must not start with half of its files, so the cancellation is passed on
            // instead of being reported as a finished download.
            downloaderPool.shutdownNow();
            throw e;
        }
    }

    /**
     * A Local account is only allowed to start a version that is completely installed: it never downloads
     * game files. See {@link LocalAccountDownloadException}.
     * @throws LocalAccountDownloadException if anything that the version needs is missing
     */
    private void ensureLocalProfileHasAllFiles() throws LocalAccountDownloadException {
        long missingFiles = 0;
        String firstMissing = null;
        for(DownloaderTask task : mScheduledDownloadTasks) {
            // Optional files are skipped when they are not there
            if(task.mSkipIfFailed || task.mTargetPath.exists()) continue;
            missingFiles++;
            if(firstMissing == null) firstMissing = task.mTargetPath.getName();
        }
        // The natives archives are optional downloads, but the game cannot be prepared without them
        for(File nativesArchive : mDeclaredNatives) {
            if(nativesArchive.exists()) continue;
            missingFiles++;
            if(firstMissing == null) firstMissing = nativesArchive.getName();
        }
        if(missingFiles > 0) throw new LocalAccountDownloadException(missingFiles, firstMissing, false);
    }

    private void reportProgressFileCounter(double speed) {
        long dlFileCounter = mProcessedFileCounter.get();
        int progress = mTotalFileCount > 0 ? (int)((dlFileCounter * 100L) / mTotalFileCount) : 0;
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, clampPercent(progress),
                R.string.newdl_downloading_game_files, dlFileCounter,
                mTotalFileCount, speed);
    }

    private void reportProgressSizeCounter(double speed) {
        long dlFileSize = mProcessedSizeCounter.get();
        double dlSizeMegabytes = (double) dlFileSize / ONE_MEGABYTE;
        double dlTotalMegabytes = (double) mTotalSize / ONE_MEGABYTE;
        int progress = mTotalSize > 0 ? (int)((dlFileSize * 100L) / mTotalSize) : 0;
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, clampPercent(progress),
                R.string.newdl_downloading_game_files_size, dlSizeMegabytes, dlTotalMegabytes, speed);
    }

    private static int clampPercent(int progress) {
        return Math.max(0, Math.min(100, progress));
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.exists()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private void extractNatives(String versionName) throws IOException {
        if(mDeclaredNatives.isEmpty()) return;
        int totalCount = mDeclaredNatives.size();

        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                R.string.newdl_extracting_native_libraries, 0, totalCount);

        File targetDirectory = new File(Tools.DIR_CACHE, "natives/"+versionName);
        FileUtils.ensureDirectory(targetDirectory);
        NativesExtractor nativesExtractor = new NativesExtractor(targetDirectory);
        int extractedCount = 0;
        for(File source : mDeclaredNatives) {
            nativesExtractor.extractFromAar(source);
            extractedCount++;
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, extractedCount * 100 / totalCount,
                    R.string.newdl_extracting_native_libraries, extractedCount, totalCount);
        }
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        if(verInfo.sha1 == null && targetFile.canRead() && targetFile.isFile())
            return targetFile;
        FileUtils.ensureParentDirectory(targetFile);
        try {
            DownloadUtils.ensureSha1(targetFile, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null, () -> {
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, targetFile);
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.isMirrored()) throw new MirrorTamperedException();
            else throw e;
        }
        return targetFile;
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return null;
        });
        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }
    
    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException {
        File versionJsonFile;
        if(verInfo != null) versionJsonFile = downloadGameJson(verInfo);
        else versionJsonFile = createGameJsonPath(versionName);
        if(versionJsonFile.canRead())  {
            try {
                verInfo = Tools.GLOBAL_GSON.fromJson(Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);
            }catch (JsonParseException e) {
                throw new DownloadPreconditionException(DownloadPreconditionException.Reason.UNSUPPORTED_VERSION,
                        "The version file of " + versionName + " is damaged", e);
            }
            if(verInfo == null) {
                throw new DownloadPreconditionException(DownloadPreconditionException.Reason.UNSUPPORTED_VERSION,
                        "The version file of " + versionName + " is empty");
            }
        } else {
            throw new DownloadPreconditionException(DownloadPreconditionException.Reason.UNSUPPORTED_VERSION,
                    "Unable to read Version JSON for version " + versionName);
        }

        if(activity != null && !NewJREUtil.installNewJreIfNeeded(activity, verInfo)){
            return false;
        }

        JAssets assets = downloadAssetsIndex(verInfo);
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        // Several entries can point to the same file (identical assets, a library that two versions of
        // an inheritance chain both need). Two threads must never write one file at the same time.
        if(!mScheduledTargets.add(targetFile.getAbsolutePath())) return;
        FileUtils.ensureParentDirectory(targetFile);
        mTotalFileCount++;
        // Only attempt to check size if we still use the size counter and didn't switch to file counter.
        // A Local account never downloads, so it has no use for the size.
        if(size <= 0 && !mUseFileCounter && !isLocalProfile) {
            try {
                size = DownloadMirror.getContentLengthMirrored(downloadClass, url);
            }catch (IOException e) {
                // Not knowing the size is not a reason to stop. If the server really cannot be reached, the
                // download of the file reports it with a proper message.
                Log.w("MinecraftDownloader", "Unable to ask for the size of " + targetFile.getName(), e);
                size = -1;
            }
        }
        if(size < 0) {
            // If we were unable to get the content length ourselves, we automatically fall back
            // to tracking the progress using the file counter.
            size = 0;
            mUseFileCounter = true;
            Log.i("MinecraftDownloader", "Failed to determine size of "+targetFile.getName()+", switching to file counter");
        }else {
            mTotalSize += size;
        }
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, url, sha1, size, skipIfFailed)
        );
    }

    /**
     * Schedule the download of an AAR library containing the required natives, for later extraction
     * and adding to the library path.
     * @param baseRepository the source Maven repository to download from.
     * @param dependentLibrary the DependentLibrary to get the path from
     * @throws IOException in case if download scheduling fails.
     */
    private void scheduleNativeLibraryDownload(String baseRepository, DependentLibrary dependentLibrary) throws IOException {
        String path = FileUtils.removeExtension(Tools.artifactToPath(dependentLibrary)) + ".aar";
        String downloadUrl = baseRepository + path;
        File targetPath = new File(Tools.DIR_HOME_LIBRARY, path);
        mDeclaredNatives.add(targetPath);
        scheduleDownload(targetPath, DownloadMirror.DOWNLOAD_CLASS_LIBRARIES, downloadUrl, null, 0, true);
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(DependentLibrary dependentLibrary : dependentLibraries) {
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;
            // Special handling for JNA Android natives
            if(dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(MAVEN_CENTRAL_REPO1, dependentLibrary);
            }
            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            boolean skipIfFailed = false;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
                skipIfFailed = true;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    url, sha1, size, skipIfFailed
            );
        }
    }
    
    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                minecraftClientInfo.url,
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[32768];
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;

        DownloaderTask(File targetPath, int downloadClass, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mTargetUrl = targetUrl;
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        private String downloadSha1() throws IOException {
            String downloadedHash = DownloadMirror.downloadStringMirrored(
                    mDownloadClass, mTargetUrl + ".sha1"
            );
            if(!Tools.isValidString(downloadedHash)) return null;
            // Ensure that we don't have leading/trailing whitespaces before checking hash length
            downloadedHash = downloadedHash.trim();
            // SHA1 is made up of 20 bytes, which means 40 hexadecimal digits, which means 40 chars
            if(downloadedHash.length() != 40) return null;
            return downloadedHash;
        }

        /*
         * Maven repositories usually have the hash of a library near it, like:
         * .../libraryName-1.0.jar
         * .../libraryName.1.0.jar.sha1
         * Since Minecraft libraries are stored in maven repositories, try to use
         * this when downloading libraries without hashes in the json.
         */
        private void tryGetLibrarySha1() {
            String resultHash = null;
            try {
                resultHash = downloadSha1();
                // The hash is a 40-byte download.
                mInternetUsageCounter.getAndAdd(40);
            }catch (IOException e) {
                Log.i("MinecraftDownloader", "Failed to download hash", e);
            }
            if(resultHash != null) {
                Log.i("MinecraftDownloader", "Got hash: "+resultHash+ " for "+FileUtils.getFileName(mTargetUrl));
                mTargetSha1 = resultHash;
            }
        }

        @Override
        public void run() {
            try {
                runCatching();
            }catch (Exception e) {
                // Keep the first failure: it is the cause, the ones that follow are often only
                // its consequence (like tasks being interrupted after the pool was stopped)
                mDownloaderThreadException.compareAndSet(null, e);
            }
        }

        private void runCatching() throws Exception {
            if(mDownloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES && !Tools.isValidString(mTargetSha1)) {
                // If we're downloading a library, try to get sha1 since it might be available as a file
                tryGetLibrarySha1();
            }
            if(Tools.isValidString(mTargetSha1)) {
                verifyFileSha1();
            }else {
                mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                                    // not for string validity
                if(mTargetPath.exists()) finishWithoutDownloading();
                else downloadFile();
            }
        }
        
        private void verifyFileSha1() throws Exception {
            if(mTargetPath.isFile() && mTargetPath.canRead() && Tools.compareSHA1(mTargetPath, mTargetSha1)) {
                finishWithoutDownloading();
            } else {
                // Rely on the download function to throw an IOE in case if the file is not
                // writable/not a file/etc...
                downloadFile();
            }
        }
        
        private void downloadFile() throws Exception {
            if(isLocalProfile){
                // The licence check stays in place: a Local account never downloads game files.
                // It must not pretend to own the game, it can only use what is already installed.
                if(mSkipIfFailed) {
                    // An optional file that is not there is skipped, like when its download fails
                    finishWithoutDownloading();
                    return;
                }
                throw new LocalAccountDownloadException(1, mTargetPath.getName(), mTargetPath.exists());
            }

            try {
                DownloadUtils.ensureSha1(mTargetPath, mTargetSha1, () -> {
                    DownloadMirror.downloadFileMirrored(mDownloadClass, mTargetUrl, mTargetPath,
                            getLocalBuffer(), this);
                    return null;
                });
            }catch (Exception e) {
                // An optional file may simply not exist on the server. Nothing else is a reason to go on
                // without it: with no internet, a server error or a damaged download the game would
                // start with missing files, so these failures are reported.
                if(!mSkipIfFailed || !isMissingOnServer(e)) throw e;
                Log.i("MinecraftDownloader", "Skipped the optional file " + mTargetPath.getName()
                        + ", it is not available on the server");
            }
            mProcessedFileCounter.incrementAndGet();
        }

        private boolean isMissingOnServer(Exception e) {
            int status = HttpStatusException.statusOf(e);
            return status == 404 || status == 410;
        }

        private void finishWithoutDownloading() {
            mProcessedFileCounter.incrementAndGet();
            mProcessedSizeCounter.addAndGet(mDownloadSize);
        }

        @Override
        public void updateProgress(int curr, int max) {
            int delta = curr - mLastCurr;
            mLastCurr = curr;
            // A new try after a failure starts counting from zero again, then the progress of this
            // file goes back with it and the bar never shows data that is not there.
            mProcessedSizeCounter.addAndGet(delta);
            // The traffic (for the speed) only counts what was really transferred
            if(delta > 0) mInternetUsageCounter.addAndGet(delta);
        }
    }
}
