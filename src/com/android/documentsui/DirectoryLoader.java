/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import android.content.AsyncTaskLoader;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.OperationCanceledException;
import android.os.RemoteException;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import com.android.documentsui.base.DebugFlags;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.FilteringCursorWrapper;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.Shared;
import com.android.documentsui.roots.RootCursorWrapper;
import com.android.documentsui.sorting.SortModel;

import libcore.io.IoUtils;

public class DirectoryLoader extends AsyncTaskLoader<DirectoryResult> {

    private static final String TAG = "DirectoryLoader";

    private static final String[] SEARCH_REJECT_MIMES = new String[] { Document.MIME_TYPE_DIR };

    private final LockingContentObserver mObserver;
    private final RootInfo mRoot;
    private final Uri mUri;
    private final SortModel mModel;
    private final boolean mSearchMode;

    private DocumentInfo mDoc;
    private CancellationSignal mSignal;
    private DirectoryResult mResult;

    public DirectoryLoader(
            Context context,
            RootInfo root,
            DocumentInfo doc,
            Uri uri,
            SortModel model,
            DirectoryReloadLock lock,
            boolean inSearchMode) {

        super(context, ProviderExecutor.forAuthority(root.authority));
        mRoot = root;
        mUri = uri;
        mModel = model;
        mDoc = doc;
        mSearchMode = inSearchMode;
        mObserver = new LockingContentObserver(lock, this::onContentChanged);
    }

    @Override
    public final DirectoryResult loadInBackground() {
        synchronized (this) {
            if (isLoadInBackgroundCanceled()) {
                throw new OperationCanceledException();
            }
            mSignal = new CancellationSignal();
        }

        final ContentResolver resolver = getContext().getContentResolver();
        final String authority = mUri.getAuthority();

        final DirectoryResult result = new DirectoryResult();
        result.doc = mDoc;

        ContentProviderClient client = null;
        Cursor cursor;
        try {
            client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, authority);
            Bundle queryArgs = new Bundle();
            mModel.addQuerySortArgs(queryArgs);

            // TODO: At some point we don't want forced flags to override real paging...
            // and that point is when we have real paging.
            DebugFlags.addForcedPagingArgs(queryArgs);

            cursor = client.query(mUri, null, queryArgs, mSignal);
            if (cursor == null) {
                throw new RemoteException("Provider returned null");
            }

            Bundle extras = cursor.getExtras();
            if (extras.containsKey(ContentResolver.QUERY_RESULT_SIZE)) {
                Log.i(TAG, "[PAGING INDICATED] Cursor extras specify recordset size of: "
                        + extras.getInt(ContentResolver.QUERY_RESULT_SIZE));
            }

            cursor.registerContentObserver(mObserver);

            cursor = new RootCursorWrapper(mUri.getAuthority(), mRoot.rootId, cursor, -1);

            if (mSearchMode && !Shared.ENABLE_OMC_API_FEATURES) {
                // There is no findDocumentPath API. Enable filtering on folders in search mode.
                cursor = new FilteringCursorWrapper(cursor, null, SEARCH_REJECT_MIMES);
            }

            cursor = mModel.sortCursor(cursor);

            result.client = client;
            result.cursor = cursor;
        } catch (Exception e) {
            Log.w(TAG, "Failed to query", e);
            result.exception = e;
        } finally {
            synchronized (this) {
                mSignal = null;
            }
            ContentProviderClient.releaseQuietly(client);
        }

        return result;
    }

    @Override
    public void cancelLoadInBackground() {
        super.cancelLoadInBackground();

        synchronized (this) {
            if (mSignal != null) {
                mSignal.cancel();
            }
        }
    }

    @Override
    public void deliverResult(DirectoryResult result) {
        if (isReset()) {
            IoUtils.closeQuietly(result);
            return;
        }
        DirectoryResult oldResult = mResult;
        mResult = result;

        if (isStarted()) {
            super.deliverResult(result);
        }

        if (oldResult != null && oldResult != result) {
            IoUtils.closeQuietly(oldResult);
        }
    }

    @Override
    protected void onStartLoading() {
        if (mResult != null) {
            deliverResult(mResult);
        }
        if (takeContentChanged() || mResult == null) {
            forceLoad();
        }
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    public void onCanceled(DirectoryResult result) {
        IoUtils.closeQuietly(result);
    }

    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        IoUtils.closeQuietly(mResult);
        mResult = null;

        getContext().getContentResolver().unregisterContentObserver(mObserver);
    }

    private static final class LockingContentObserver extends ContentObserver {
        private final DirectoryReloadLock mLock;
        private final Runnable mContentChangedCallback;

        public LockingContentObserver(DirectoryReloadLock lock, Runnable contentChangedCallback) {
            super(new Handler());
            mLock = lock;
            mContentChangedCallback = contentChangedCallback;
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            mLock.tryUpdate(mContentChangedCallback);
        }
    }
}
