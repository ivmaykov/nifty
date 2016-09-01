/*
 * Copyright (C) 2012-2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.nifty.ssl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.airlift.log.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A class that watches one or more files for changes and calls a method when any of the files change.
 * Currently implemented as a poller. Could be optimized to use a file system watcher instead, if we decide that
 * it's worth the effort.
 */
public abstract class AbstractMultiFileWatcher {
    private final List<File> files;
    private final AtomicReference<Map<File, FileMetadata>> metadataCacheRef;
    private final long initialDelay;
    private final long interval;
    private final TimeUnit timeUnit;
    private ListeningScheduledExecutorService executorService;
    private ListenableScheduledFuture<?> future;

    private static final Logger log = Logger.get(AbstractMultiFileWatcher.class);

    /**
     * Creates a new watcher. This API is not stable and may change in the future if we implement this as a
     * proper FS watcher rather than a poller. The watcher doesn't start scanning files on disk until the
     * {@code start()} method is called.
     *
     * @param files list of files to watch.
     * @param initialDelay how long to wait until the first scan of the files.
     * @param interval how often to rescan the files.
     * @param timeUnit time unit for {@code initialDelay} and {@code interval}.
     */
    public AbstractMultiFileWatcher(List<File> files, long initialDelay, long interval, TimeUnit timeUnit) {
        checkArgument(files != null && files.size() > 0, "must specify at least one file");

        this.files = ImmutableList.copyOf(files);
        this.metadataCacheRef = new AtomicReference<>(ImmutableMap.of());
        this.initialDelay = initialDelay;
        this.interval = interval;
        this.timeUnit = timeUnit;
        this.executorService = null;
        this.future = null;
    }

    /**
     * Starts polling the watched files for changes.
     */
    public void start() {
        if (executorService == null) {
            executorService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
            future = executorService.scheduleAtFixedRate(
                this::scanFilesForChanges,
                initialDelay,
                interval,
                timeUnit);
        }
    }

    /**
     * @return true if polling thread has been started and shutdown has not been called.
     */
    public boolean isStarted() {
        return executorService != null;
    }

    /**
     * Stops polling the files for changes. Should be called during server shutdown or when this poller is no
     * longer needed to make sure the background thread is stopped.
     */
    public void shutdown() {
        if (future != null) {
            future.cancel(true);
            future = null;
        }
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
        metadataCacheRef.set(ImmutableMap.of());
    }

    @Override
    protected void finalize() {
        // Implement finalize() so we'll try to stop the background thread if caller forgets to do it.
        // This provides no guarantees so don't rely on it.
        shutdown();
    }

    /**
     * Subclasses should override this to receive notifications when the watched files are modified.
     *
     * @param modifiedFiles a set of files that were modified since the last polling cycle. Note that when a
     *                      watched file is deleted, it will not be considered modified.
     */
    protected abstract void onFilesUpdated(Set<File> modifiedFiles);

    /**
     * Scans the watched files for changes. If any changes are detected, calls {@code onFileUpdated()} with the
     * set of all files that were modified since the last successful update attempt.
     * Changes are tracked by watching certain file attributes (mtime, ctime, inode) and SHA-256 hashes of file
     * contents and comparing against last known values. If a file is deleted or an I/O or permission error occurs
     * while trying to stat or read it, the error is ignored, but the file's metadata is removed from the metadata
     * cache so next time it is read successfully it will be considered an update.
     */
    private void scanFilesForChanges() {
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            // This never happens, all JVM implementations must support SHA-256 according to Oracle docs.
        }
        ImmutableSet.Builder<File> modifiedFilesBuilder = ImmutableSet.builder();
        Map<File, FileMetadata> metadataCache = Maps.newHashMap(metadataCacheRef.get());
        for (File file : files) {
            try {
                FileMetadata meta = new FileMetadata(file, digest);
                if (!metadataCache.containsKey(file) || !metadataCache.get(file).equals(meta)) {
                    metadataCache.put(file, meta);
                    modifiedFilesBuilder.add(file);
                }
            }
            catch (IOException | SecurityException e) {
                // I/O error, file not found, or access to file not allowed
                log.warn(
                    "Error trying to stat or read file %s: %s: %s",
                    file.toString(),
                    e.getClass().getName(),
                    e.getMessage());
                metadataCache.remove(file);
                continue;
            }
        }

        // We need to swallow exceptions from onFileUpdated(), otherwise a careless subclass could throw one and
        // kill the poller thread.
        try {
            Set<File> modifiedFiles = modifiedFilesBuilder.build();
            if (!modifiedFiles.isEmpty()) {
                onFilesUpdated(modifiedFiles);
            }
            // Only update the metadata cache if onFilesUpdated() succeeded.
            metadataCacheRef.set(ImmutableMap.copyOf(metadataCache));
        }
        catch (Exception e) {
            log.warn("Error from subclass while calling onFilesUpdated(): %s: %s",
                e.getClass().toString(),
                e.getMessage());
        }
    }

    /**
     * Computes a hash of the given file's contents using the provided MessageDigest.
     * @param file the file.
     * @param md the message digest.
     * @return the hash of the file contents.
     * @throws IOException if the file contents cannot be read.
     */
    private static String computeFileHash(File file, MessageDigest md) throws IOException {
        md.reset();
        return BaseEncoding.base16().encode(md.digest(Files.toByteArray(file)));
    }

    /**
     * Encapsulates some known metadata about a file. This is used to detect file changes. We consider two
     * metadata objects to be equal when the file path, creation time, modification time, inode, and contents of
     * the two files are equals. If any of these things change, we will consider the file updated and call the
     * {@code onFilesUpdated()} callback.
     *
     * Tracking only mtime is insufficient: some file systems may not support it, it often has a coarse
     * granularity (1 second on Linux at the time of this writing), and it can be explicitly set to any value by
     * users, including the old value even when file contents have changed.
     *
     * Tracking only file contents would probably be good enough in practice, but we want to allow triggering the
     * update callback even when the contents have not changed (for testing) with a simple command like
     * {@code touch /path/to/file}, which changes the mtime but not the contents.
     */
    private static class FileMetadata {
        private final File filePath;
        private final FileTime ctime;
        private final FileTime mtime;
        private final Object fileKey;
        private final String contentsHash;

        FileMetadata(File file, FileTime ctime, FileTime mtime, Object fileKey, String contentsHash) {
            this.filePath = requireNonNull(file);
            this.ctime = requireNonNull(ctime);
            this.mtime = requireNonNull(mtime);
            this.fileKey = fileKey; // not necessarily supported on all file systems and may be null per JavaDocs.
            this.contentsHash = requireNonNull(contentsHash);
        }

        FileMetadata(File file, BasicFileAttributes attrs, String contentsHash) {
            this(file, attrs.creationTime(), attrs.lastModifiedTime(), attrs.fileKey(), contentsHash);
        }

        FileMetadata(File file, MessageDigest digest) throws IOException {
            this(file,
                java.nio.file.Files.readAttributes(file.toPath(), BasicFileAttributes.class),
                computeFileHash(file, digest));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            else if (!(obj instanceof FileMetadata)) {
                return false;
            }
            FileMetadata that = (FileMetadata) obj;
            return Objects.equals(filePath, that.filePath) &&
                Objects.equals(ctime, that.ctime) &&
                Objects.equals(mtime, that.mtime) &&
                Objects.equals(fileKey, that.fileKey) &&
                Objects.equals(contentsHash, that.contentsHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(filePath, ctime, mtime, fileKey, contentsHash);
        }
    }
}
