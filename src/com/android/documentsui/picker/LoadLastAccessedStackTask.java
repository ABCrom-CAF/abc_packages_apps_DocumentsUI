/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.picker;

import static com.android.documentsui.base.Shared.DEBUG;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.android.documentsui.AbstractActionHandler.CommonAddons;
import com.android.documentsui.base.DurableUtils;
import com.android.documentsui.base.PairedTask;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.Shared;
import com.android.documentsui.base.State;
import com.android.documentsui.dirlist.AnimationView;
import com.android.documentsui.picker.LastAccessedProvider.Columns;
import com.android.documentsui.roots.RootsAccess;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;

/**
 * Loads the last used path (stack) from Recents (history).
 * The path selected is based on the calling package name. So the last
 * path for an app like Gmail can be different than the last path
 * for an app like DropBox.
 */
final class LoadLastAccessedStackTask<T extends Activity & CommonAddons>
        extends PairedTask<T, Void, Void> {

    private static final String TAG = "LoadLastAccessedStackTask";
    private volatile boolean mRestoredStack;
    private volatile boolean mExternal;
    private final State mState;
    private RootsAccess mRoots;

    public LoadLastAccessedStackTask(T activity, State state, RootsAccess roots) {
        super(activity);
        mState = state;
        mRoots = roots;
    }

    @Override
    protected Void run(Void... params) {
        if (DEBUG && !mState.stack.isEmpty()) {
            Log.w(TAG, "Overwriting existing stack.");
        }
        String callingPackage = Shared.getCallingPackageName(mOwner);
        Uri resumeUri = LastAccessedProvider.buildLastAccessed(
                callingPackage);
        Cursor cursor = mOwner.getContentResolver().query(resumeUri, null, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                mExternal = cursor.getInt(cursor.getColumnIndex(Columns.EXTERNAL)) != 0;
                final byte[] rawStack = cursor.getBlob(
                        cursor.getColumnIndex(Columns.STACK));
                DurableUtils.readFromArray(rawStack, mState.stack);
                mRestoredStack = true;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to resume: " + e);
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        if (mRestoredStack) {
            // Update the restored stack to ensure we have freshest data
            final Collection<RootInfo> matchingRoots = mRoots.getMatchingRootsBlocking(mState);
            try {
                mState.stack.updateRoot(matchingRoots);
                mState.stack.updateDocuments(mOwner.getContentResolver());
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to restore stack for package: " + callingPackage
                        + " because of error: "+ e);
                mState.stack.reset();
                mRestoredStack = false;
            }
        }

        return null;
    }

    @Override
    protected void finish(Void result) {
        mState.external = mExternal;
        mOwner.refreshCurrentRootAndDirectory(AnimationView.ANIM_NONE);
    }
}
