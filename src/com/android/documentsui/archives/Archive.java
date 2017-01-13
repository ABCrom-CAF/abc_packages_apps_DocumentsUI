/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui.archives;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;
import android.util.jar.StrictJarFile;
import android.webkit.MimeTypeMap;

import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;

/**
 * Provides basic implementation for creating, extracting and accessing
 * files within archives exposed by a document provider.
 *
 * <p>This class is thread safe.
 */
public class Archive implements Closeable {
    private static final String TAG = "Archive";

    public static final String[] DEFAULT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS
    };

    private final Context mContext;
    private final Uri mArchiveUri;
    private final Uri mNotificationUri;
    private final StrictJarFile mZipFile;
    private final ThreadPoolExecutor mExecutor;
    private final Map<String, ZipEntry> mEntries;
    private final Map<String, List<ZipEntry>> mTree;

    private Archive(
            Context context,
            @Nullable File file,
            @Nullable FileDescriptor fd,
            Uri archiveUri,
            @Nullable Uri notificationUri)
            throws IOException {
        mContext = context;
        mArchiveUri = archiveUri;
        mNotificationUri = notificationUri;
        mZipFile = file != null ?
                new StrictJarFile(file.getPath(), false /* verify */, false /* signatures */) :
                new StrictJarFile(fd, false /* verify */, false /* signatures */);

        // At most 8 active threads. All threads idling for more than a minute will
        // be closed.
        mExecutor = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        mExecutor.allowCoreThreadTimeOut(true);

        // Build the tree structure in memory.
        mTree = new HashMap<>();

        mEntries = new HashMap<>();
        ZipEntry entry;
        String entryPath;
        final Iterator<ZipEntry> it = mZipFile.iterator();
        final Stack<ZipEntry> stack = new Stack<>();
        while (it.hasNext()) {
            entry = it.next();
            if (entry.isDirectory() != entry.getName().endsWith("/")) {
                throw new IOException(
                        "Directories must have a trailing slash, and files must not.");
            }
            entryPath = getEntryPath(entry);
            if (mEntries.containsKey(entryPath)) {
                throw new IOException("Multiple entries with the same name are not supported.");
            }
            mEntries.put(entryPath, entry);
            if (entry.isDirectory()) {
                mTree.put(entryPath, new ArrayList<ZipEntry>());
            }
            if (!"/".equals(entryPath)) { // Skip root, as it doesn't have a parent.
                stack.push(entry);
            }
        }

        int delimiterIndex;
        String parentPath;
        ZipEntry parentEntry;
        List<ZipEntry> parentList;

        // Go through all directories recursively and build a tree structure.
        while (stack.size() > 0) {
            entry = stack.pop();

            entryPath = getEntryPath(entry);
            delimiterIndex = entryPath.lastIndexOf('/', entry.isDirectory()
                    ? entryPath.length() - 2 : entryPath.length() - 1);
            parentPath = entryPath.substring(0, delimiterIndex) + "/";

            parentList = mTree.get(parentPath);

            if (parentList == null) {
                // The ZIP file doesn't contain all directories leading to the entry.
                // It's rare, but can happen in a valid ZIP archive. In such case create a
                // fake ZipEntry and add it on top of the stack to process it next.
                parentEntry = new ZipEntry(parentPath);
                parentEntry.setSize(0);
                parentEntry.setTime(entry.getTime());
                mEntries.put(parentPath, parentEntry);

                if (!"/".equals(parentPath)) {
                    stack.push(parentEntry);
                }

                parentList = new ArrayList<>();
                mTree.put(parentPath, parentList);
            }

            parentList.add(entry);
        }
    }

    /**
     * Returns a valid, normalized path for an entry.
     */
    public static String getEntryPath(ZipEntry entry) {
        Preconditions.checkArgument(entry.isDirectory() == entry.getName().endsWith("/"),
                "Ill-formated ZIP-file.");
        if (entry.getName().startsWith("/")) {
            return entry.getName();
        } else {
            return "/" + entry.getName();
        }
    }

    /**
     * Returns true if the file descriptor is seekable.
     * @param descriptor File descriptor to check.
     */
    @VisibleForTesting
    public static boolean canSeek(ParcelFileDescriptor descriptor) {
        try {
            return Os.lseek(descriptor.getFileDescriptor(), 0,
                    OsConstants.SEEK_CUR) == 0;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /**
     * Creates a DocumentsArchive instance for opening, browsing and accessing
     * documents within the archive passed as a file descriptor.
     *
     * If the file descriptor is not seekable, then a snapshot will be created.
     *
     * This method takes ownership for the passed descriptor. The caller must
     * not close it.
     *
     * @param context Context of the provider.
     * @param descriptor File descriptor for the archive's contents.
     * @param archiveUri Uri of the archive document.
     * @param Uri notificationUri Uri for notifying that the archive file has changed.
     */
    public static Archive createForParcelFileDescriptor(
            Context context, ParcelFileDescriptor descriptor, Uri archiveUri,
            @Nullable Uri notificationUri)
            throws IOException {
        FileDescriptor fd = null;
        try {
            if (canSeek(descriptor)) {
                fd = new FileDescriptor();
                fd.setInt$(descriptor.detachFd());
                return new Archive(context, null, fd, archiveUri,
                        notificationUri);
            }

            // Fallback for non-seekable file descriptors.
            File snapshotFile = null;
            try {
                // Create a copy of the archive, as ZipFile doesn't operate on streams.
                // Moreover, ZipInputStream would be inefficient for large files on
                // pipes.
                snapshotFile = File.createTempFile("com.android.documentsui.snapshot{",
                        "}.zip", context.getCacheDir());

                try (
                    final FileOutputStream outputStream =
                            new ParcelFileDescriptor.AutoCloseOutputStream(
                                    ParcelFileDescriptor.open(
                                            snapshotFile, ParcelFileDescriptor.MODE_WRITE_ONLY));
                    final ParcelFileDescriptor.AutoCloseInputStream inputStream =
                            new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                ) {
                    final byte[] buffer = new byte[32 * 1024];
                    int bytes;
                    while ((bytes = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytes);
                    }
                    outputStream.flush();
                }
                return new Archive(context, snapshotFile, null, archiveUri,
                        notificationUri);
            } finally {
                // On UNIX the file will be still available for processes which opened it, even
                // after deleting it. Remove it ASAP, as it won't be used by anyone else.
                if (snapshotFile != null) {
                    snapshotFile.delete();
                }
            }
        } catch (Exception e) {
            // Since the method takes ownership of the passed descriptor, close it
            // on exception.
            IoUtils.closeQuietly(descriptor);
            IoUtils.closeQuietly(fd);
            throw e;
        }
    }

    /**
     * Lists child documents of an archive or a directory within an
     * archive. Must be called only for archives with supported mime type,
     * or for documents within archives.
     *
     * @see DocumentsProvider.queryChildDocuments(String, String[], String)
     */
    public Cursor queryChildDocuments(String documentId, @Nullable String[] projection,
            @Nullable String sortOrder) throws FileNotFoundException {
        final ArchiveId parsedParentId = ArchiveId.fromDocumentId(documentId);
        MorePreconditions.checkArgumentEquals(mArchiveUri, parsedParentId.mArchiveUri,
                "Mismatching archive Uri. Expected: %s, actual: %s.");

        final MatrixCursor result = new MatrixCursor(
                projection != null ? projection : DEFAULT_PROJECTION);
        if (mNotificationUri != null) {
            result.setNotificationUri(mContext.getContentResolver(), mNotificationUri);
        }

        final List<ZipEntry> parentList = mTree.get(parsedParentId.mPath);
        if (parentList == null) {
            throw new FileNotFoundException();
        }
        for (final ZipEntry entry : parentList) {
            addCursorRow(result, entry);
        }
        return result;
    }

    /**
     * Returns a MIME type of a document within an archive.
     *
     * @see DocumentsProvider.getDocumentType(String)
     */
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final ArchiveId parsedId = ArchiveId.fromDocumentId(documentId);
        MorePreconditions.checkArgumentEquals(mArchiveUri, parsedId.mArchiveUri,
                "Mismatching archive Uri. Expected: %s, actual: %s.");

        final ZipEntry entry = mEntries.get(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }
        return getMimeTypeForEntry(entry);
    }

    /**
     * Returns true if a document within an archive is a child or any descendant of the archive
     * document or another document within the archive.
     *
     * @see DocumentsProvider.isChildDocument(String, String)
     */
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        final ArchiveId parsedParentId = ArchiveId.fromDocumentId(parentDocumentId);
        final ArchiveId parsedId = ArchiveId.fromDocumentId(documentId);
        MorePreconditions.checkArgumentEquals(mArchiveUri, parsedParentId.mArchiveUri,
                "Mismatching archive Uri. Expected: %s, actual: %s.");

        final ZipEntry entry = mEntries.get(parsedId.mPath);
        if (entry == null) {
            return false;
        }

        final ZipEntry parentEntry = mEntries.get(parsedParentId.mPath);
        if (parentEntry == null || !parentEntry.isDirectory()) {
            return false;
        }

        // Add a trailing slash even if it's not a directory, so it's easy to check if the
        // entry is a descendant.
        String pathWithSlash = entry.isDirectory() ? getEntryPath(entry)
                : getEntryPath(entry) + "/";

        return pathWithSlash.startsWith(parsedParentId.mPath) &&
                !parsedParentId.mPath.equals(pathWithSlash);
    }

    /**
     * Returns metadata of a document within an archive.
     *
     * @see DocumentsProvider.queryDocument(String, String[])
     */
    public Cursor queryDocument(String documentId, @Nullable String[] projection)
            throws FileNotFoundException {
        final ArchiveId parsedId = ArchiveId.fromDocumentId(documentId);
        MorePreconditions.checkArgumentEquals(mArchiveUri, parsedId.mArchiveUri,
                "Mismatching archive Uri. Expected: %s, actual: %s.");

        final ZipEntry entry = mEntries.get(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }

        final MatrixCursor result = new MatrixCursor(
                projection != null ? projection : DEFAULT_PROJECTION);
        if (mNotificationUri != null) {
            result.setNotificationUri(mContext.getContentResolver(), mNotificationUri);
        }
        addCursorRow(result, entry);
        return result;
    }

    /**
     * Opens a file within an archive.
     *
     * @see DocumentsProvider.openDocument(String, String, CancellationSignal))
     */
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, @Nullable final CancellationSignal signal)
            throws FileNotFoundException {
        MorePreconditions.checkArgumentEquals("r", mode,
                "Invalid mode. Only reading \"r\" supported, but got: \"%s\".");
        final ArchiveId parsedId = ArchiveId.fromDocumentId(documentId);
        MorePreconditions.checkArgumentEquals(mArchiveUri, parsedId.mArchiveUri,
                "Mismatching archive Uri. Expected: %s, actual: %s.");

        final ZipEntry entry = mEntries.get(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }

        ParcelFileDescriptor[] pipe;
        InputStream inputStream = null;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
            inputStream = mZipFile.getInputStream(entry);
        } catch (IOException e) {
            if (inputStream != null) {
                IoUtils.closeQuietly(inputStream);
            }
            // Ideally we'd simply throw IOException to the caller, but for consistency
            // with DocumentsProvider::openDocument, converting it to IllegalStateException.
            throw new IllegalStateException("Failed to open the document.", e);
        }
        final ParcelFileDescriptor outputPipe = pipe[1];
        final InputStream finalInputStream = inputStream;
        mExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        try (final ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                                new ParcelFileDescriptor.AutoCloseOutputStream(outputPipe)) {
                            try {
                                final byte buffer[] = new byte[32 * 1024];
                                int bytes;
                                while ((bytes = finalInputStream.read(buffer)) != -1) {
                                    if (Thread.interrupted()) {
                                        throw new InterruptedException();
                                    }
                                    if (signal != null) {
                                        signal.throwIfCanceled();
                                    }
                                    outputStream.write(buffer, 0, bytes);
                                }
                            } catch (IOException | InterruptedException e) {
                                // Catch the exception before the outer try-with-resource closes the
                                // pipe with close() instead of closeWithError().
                                try {
                                    outputPipe.closeWithError(e.getMessage());
                                } catch (IOException e2) {
                                    Log.e(TAG, "Failed to close the pipe after an error.", e2);
                                }
                            }
                        } catch (OperationCanceledException e) {
                            // Cancelled gracefully.
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to close the output stream gracefully.", e);
                        } finally {
                            IoUtils.closeQuietly(finalInputStream);
                        }
                    }
                });

        return pipe[0];
    }

    /**
     * Opens a thumbnail of a file within an archive.
     *
     * @see DocumentsProvider.openDocumentThumbnail(String, Point, CancellationSignal))
     */
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, final CancellationSignal signal)
            throws FileNotFoundException {
        final ArchiveId parsedId = ArchiveId.fromDocumentId(documentId);
        MorePreconditions.checkArgumentEquals(mArchiveUri, parsedId.mArchiveUri,
                "Mismatching archive Uri. Expected: %s, actual: %s.");
        Preconditions.checkArgument(getDocumentType(documentId).startsWith("image/"),
                "Thumbnails only supported for image/* MIME type.");

        final ZipEntry entry = mEntries.get(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }

        InputStream inputStream = null;
        try {
            inputStream = mZipFile.getInputStream(entry);
            final ExifInterface exif = new ExifInterface(inputStream);
            if (exif.hasThumbnail()) {
                Bundle extras = null;
                switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        extras = new Bundle(1);
                        extras.putInt(DocumentsContract.EXTRA_ORIENTATION, 90);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        extras = new Bundle(1);
                        extras.putInt(DocumentsContract.EXTRA_ORIENTATION, 180);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        extras = new Bundle(1);
                        extras.putInt(DocumentsContract.EXTRA_ORIENTATION, 270);
                        break;
                }
                final long[] range = exif.getThumbnailRange();
                return new AssetFileDescriptor(
                        openDocument(documentId, "r", signal), range[0], range[1], extras);
            }
        } catch (IOException e) {
            // Ignore the exception, as reading the EXIF may legally fail.
            Log.e(TAG, "Failed to obtain thumbnail from EXIF.", e);
        } finally {
            IoUtils.closeQuietly(inputStream);
        }

        return new AssetFileDescriptor(
                openDocument(documentId, "r", signal), 0, entry.getSize(), null);
    }

    /**
     * Closes an archive.
     *
     * <p>This method does not block until shutdown. Once called, other methods should not be
     * called. Any active pipes will be terminated.
     */
    @Override
    public void close() {
        mExecutor.shutdownNow();
        try {
            mZipFile.close();
        } catch (IOException e) {
            // Silent close.
        }
    }

    private void addCursorRow(MatrixCursor cursor, ZipEntry entry) {
        final MatrixCursor.RowBuilder row = cursor.newRow();
        final ArchiveId parsedId = new ArchiveId(mArchiveUri, getEntryPath(entry));
        row.add(Document.COLUMN_DOCUMENT_ID, parsedId.toDocumentId());

        final File file = new File(entry.getName());
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_SIZE, entry.getSize());

        final String mimeType = getMimeTypeForEntry(entry);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);

        final int flags = mimeType.startsWith("image/") ? Document.FLAG_SUPPORTS_THUMBNAIL : 0;
        row.add(Document.COLUMN_FLAGS, flags);
    }

    private String getMimeTypeForEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }

        final int lastDot = entry.getName().lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = entry.getName().substring(lastDot + 1).toLowerCase(Locale.US);
            final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }

        return "application/octet-stream";
    }

    // TODO: Upstream to the Preconditions class.
    private static class MorePreconditions {
        static void checkArgumentEquals(String expected, @Nullable String actual,
                String message) {
            if (!TextUtils.equals(expected, actual)) {
                throw new IllegalArgumentException(String.format(message,
                        String.valueOf(expected), String.valueOf(actual)));
            }
        }

        static void checkArgumentEquals(Uri expected, @Nullable Uri actual,
                String message) {
            checkArgumentEquals(expected.toString(), actual.toString(), message);
        }
    }
};
