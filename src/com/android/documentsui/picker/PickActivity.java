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

package com.android.documentsui.picker;

import static com.android.documentsui.base.Shared.DEBUG;
import static com.android.documentsui.base.State.ACTION_CREATE;
import static com.android.documentsui.base.State.ACTION_GET_CONTENT;
import static com.android.documentsui.base.State.ACTION_OPEN;
import static com.android.documentsui.base.State.ACTION_OPEN_TREE;
import static com.android.documentsui.base.State.ACTION_PICK_COPY_DESTINATION;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.support.annotation.CallSuper;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;

import com.android.documentsui.ActionModeController;
import com.android.documentsui.BaseActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.FocusManager;
import com.android.documentsui.Injector;
import com.android.documentsui.MenuManager.DirectoryDetails;
import com.android.documentsui.ProviderExecutor;
import com.android.documentsui.R;
import com.android.documentsui.SharedInputHandler;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.Features;
import com.android.documentsui.base.MimeTypes;
import com.android.documentsui.base.PairedTask;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.Shared;
import com.android.documentsui.base.State;
import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.picker.LastAccessedProvider.Columns;
import com.android.documentsui.prefs.ScopedPreferences;
import com.android.documentsui.selection.SelectionManager;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.sidebar.RootsFragment;
import com.android.documentsui.ui.DialogController;
import com.android.documentsui.ui.MessageBuilder;
import com.android.documentsui.ui.Snackbars;

import java.util.Arrays;
import java.util.List;

public class PickActivity extends BaseActivity implements ActionHandler.Addons {

    static final String PREFERENCES_SCOPE = "picker";

    private static final String TAG = "PickActivity";
    private static final int CODE_FORWARD = 42;

    private Injector<ActionHandler<PickActivity>> mInjector;
    private SharedInputHandler mSharedInputHandler;

    public PickActivity() {
        super(R.layout.documents_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {

        mInjector = new Injector<>(
                Features.create(getResources()),
                new Config(),
                ScopedPreferences.create(this, PREFERENCES_SCOPE),
                new MessageBuilder(this),
                DialogController.STUB);

        super.onCreate(icicle);

        mInjector.selectionMgr = new SelectionManager(
                mState.allowMultiple
                        ? SelectionManager.MODE_MULTIPLE
                        : SelectionManager.MODE_SINGLE);

        mInjector.focusManager = new FocusManager(
                mInjector.features,
                mInjector.selectionMgr,
                mDrawer,
                this::focusSidebar,
                getColor(R.color.accent_dark));

        mInjector.menuManager = new MenuManager(mSearchManager, mState, new DirectoryDetails(this));

        mInjector.actionModeController = new ActionModeController(
                this,
                mInjector.selectionMgr,
                mInjector.menuManager,
                mInjector.messages);

        mInjector.actions = new ActionHandler<>(
                this,
                mState,
                mRoots,
                mDocs,
                mSearchManager,
                ProviderExecutor::forAuthority,
                mInjector);

        mInjector.searchManager = mSearchManager;

        Intent intent = getIntent();

        mSharedInputHandler =
                new SharedInputHandler(mInjector.focusManager, this::popDir, mInjector.features);
        setupLayout(intent);
        mInjector.actions.initLocation(intent);
    }

    private void setupLayout(Intent intent) {
        if (mState.action == ACTION_CREATE) {
            final String mimeType = intent.getType();
            final String title = intent.getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        } else if (mState.action == ACTION_OPEN_TREE ||
                   mState.action == ACTION_PICK_COPY_DESTINATION) {
            PickFragment.show(getFragmentManager());
        }

        if (mState.action == ACTION_GET_CONTENT) {
            final Intent moreApps = new Intent(intent);
            moreApps.setComponent(null);
            moreApps.setPackage(null);
            RootsFragment.show(getFragmentManager(), moreApps);
        } else if (mState.action == ACTION_OPEN ||
                   mState.action == ACTION_CREATE ||
                   mState.action == ACTION_OPEN_TREE ||
                   mState.action == ACTION_PICK_COPY_DESTINATION) {
            RootsFragment.show(getFragmentManager(), (Intent) null);
        }
    }

    @Override
    protected void includeState(State state) {
        final Intent intent = getIntent();

        String defaultMimeType = (intent.getType() == null) ? "*/*" : intent.getType();
        state.initAcceptMimes(intent, defaultMimeType);

        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            state.action = ACTION_OPEN;
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            state.action = ACTION_CREATE;
        } else if (Intent.ACTION_GET_CONTENT.equals(action)) {
            state.action = ACTION_GET_CONTENT;
        } else if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            state.action = ACTION_OPEN_TREE;
        } else if (Shared.ACTION_PICK_COPY_DESTINATION.equals(action)) {
            state.action = ACTION_PICK_COPY_DESTINATION;
        }

        if (state.action == ACTION_OPEN || state.action == ACTION_GET_CONTENT) {
            state.allowMultiple = intent.getBooleanExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE, false);
        }

        if (state.action == ACTION_OPEN || state.action == ACTION_GET_CONTENT
                || state.action == ACTION_CREATE) {
            state.openableOnly = intent.hasCategory(Intent.CATEGORY_OPENABLE);
        }

        if (state.action == ACTION_PICK_COPY_DESTINATION) {
            // Indicates that a copy operation (or move) includes a directory.
            // Why? Directory creation isn't supported by some roots (like Downloads).
            // This allows us to restrict available roots to just those with support.
            state.directoryCopy = intent.getBooleanExtra(
                    Shared.EXTRA_DIRECTORY_COPY, false);
            state.copyOperationSubType = intent.getIntExtra(
                    FileOperationService.EXTRA_OPERATION_TYPE,
                    FileOperationService.OPERATION_COPY);
        }
    }

    @Override
    public void onAppPicked(ResolveInfo info) {
        final Intent intent = new Intent(getIntent());
        intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.setComponent(new ComponentName(
                info.activityInfo.applicationInfo.packageName, info.activityInfo.name));
        startActivityForResult(intent, CODE_FORWARD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (DEBUG) Log.d(TAG, "onActivityResult() code=" + resultCode);

        // Only relay back results when not canceled; otherwise stick around to
        // let the user pick another app/backend.
        if (requestCode == CODE_FORWARD && resultCode != RESULT_CANCELED) {

            // Remember that we last picked via external app
            final String packageName = Shared.getCallingPackageName(this);
            final ContentValues values = new ContentValues();
            values.put(Columns.EXTERNAL, 1);
            getContentResolver().insert(LastAccessedProvider.buildLastAccessed(packageName), values);

            // Pass back result to original caller
            setResult(resultCode, data);
            finish();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawer.update();
        mNavigator.update();
    }

    @Override
    public String getDrawerTitle() {
        String title = getIntent().getStringExtra(DocumentsContract.EXTRA_PROMPT);
        if (title == null) {
            if (mState.action == ACTION_OPEN ||
                mState.action == ACTION_GET_CONTENT ||
                mState.action == ACTION_OPEN_TREE) {
                title = getResources().getString(R.string.title_open);
            } else if (mState.action == ACTION_CREATE ||
                       mState.action == ACTION_PICK_COPY_DESTINATION) {
                title = getResources().getString(R.string.title_save);
            } else {
                // If all else fails, just call it "Documents".
                title = getResources().getString(R.string.app_label);
            }
        }

        return title;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        mInjector.menuManager.updateOptionMenu(menu);

        final DocumentInfo cwd = getCurrentDirectory();

        if (mState.action == ACTION_CREATE) {
            final FragmentManager fm = getFragmentManager();
            SaveFragment.get(fm).prepareForDirectory(cwd);
        }

        return true;
    }

    @Override
    protected void refreshDirectory(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        if (mState.stack.isRecents()) {
            DirectoryFragment.showRecentsOpen(fm, anim);

            // In recents we pick layout mode based on the mimetype,
            // picking GRID for visual types. We intentionally don't
            // consult a user's saved preferences here since they are
            // set per root (not per root and per mimetype).
            boolean visualMimes = MimeTypes.mimeMatches(
                    MimeTypes.VISUAL_MIMES, mState.acceptMimes);
            mState.derivedMode = visualMimes ? State.MODE_GRID : State.MODE_LIST;
        } else {
                // Normal boring directory
                DirectoryFragment.showDirectory(fm, root, cwd, anim);
        }

        // Forget any replacement target
        if (mState.action == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        if (mState.action == ACTION_OPEN_TREE ||
            mState.action == ACTION_PICK_COPY_DESTINATION) {
            final PickFragment pick = PickFragment.get(fm);
            if (pick != null) {
                pick.setPickTarget(mState.action, mState.copyOperationSubType, cwd);
            }
        }
    }

    void onSaveRequested(DocumentInfo replaceTarget) {
        new ExistingFinishTask(this, replaceTarget.derivedUri)
                .executeOnExecutor(getExecutorForCurrentDirectory());
    }

    @Override
    public void setPending(boolean pending) {
        final SaveFragment save = SaveFragment.get(getFragmentManager());
        if (save != null) {
            save.setPending(pending);
        }
    }

    @Override
    protected void onDirectoryCreated(DocumentInfo doc) {
        assert(doc.isDirectory());
        mInjector.actions.openContainerDocument(doc);
    }

    void onSaveRequested(String mimeType, String displayName) {
        new CreateFinishTask(this, mimeType, displayName)
                .executeOnExecutor(getExecutorForCurrentDirectory());
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc) {
        final FragmentManager fm = getFragmentManager();
        // Do not inline-open archives, as otherwise it would be impossible to pick
        // archive files. Note, that picking files inside archives is not supported.
        if (doc.isDirectory()) {
            mInjector.actions.openContainerDocument(doc);
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            // Explicit file picked, return
            new ExistingFinishTask(this, doc.derivedUri)
                    .executeOnExecutor(getExecutorForCurrentDirectory());
        } else if (mState.action == ACTION_CREATE) {
            // Replace selected file
            SaveFragment.get(fm).setReplaceTarget(doc);
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).derivedUri;
            }
            new ExistingFinishTask(this, uris)
                    .executeOnExecutor(getExecutorForCurrentDirectory());
        }
    }

    public void onPickRequested(DocumentInfo pickTarget) {
        Uri result;
        if (mState.action == ACTION_OPEN_TREE) {
            result = DocumentsContract.buildTreeDocumentUri(
                    pickTarget.authority, pickTarget.documentId);
        } else if (mState.action == ACTION_PICK_COPY_DESTINATION) {
            result = pickTarget.derivedUri;
        } else {
            // Should not be reached.
            throw new IllegalStateException("Invalid mState.action.");
        }
        new PickFinishTask(this, result).executeOnExecutor(getExecutorForCurrentDirectory());
    }

    void updateLastAccessed() {
        LastAccessedProvider.setLastAccessed(
                getContentResolver(), Shared.getCallingPackageName(this), mState.stack);
    }

    private void onTaskFinished(Uri... uris) {
        if (DEBUG) Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        if (mState.action == ACTION_GET_CONTENT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (mState.action == ACTION_OPEN_TREE) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        } else if (mState.action == ACTION_PICK_COPY_DESTINATION) {
            // Picking a copy destination is only used internally by us, so we
            // don't need to extend permissions to the caller.
            intent.putExtra(Shared.EXTRA_STACK, (Parcelable) mState.stack);
            intent.putExtra(FileOperationService.EXTRA_OPERATION_TYPE, mState.copyOperationSubType);
        } else {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    @CallSuper
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mSharedInputHandler.onKeyDown(keyCode, event)
                || super.onKeyDown(keyCode, event);
    }

    public static PickActivity get(Fragment fragment) {
        return (PickActivity) fragment.getActivity();
    }

    private static final class PickFinishTask extends PairedTask<PickActivity, Void, Void> {
        private final Uri mUri;

        public PickFinishTask(PickActivity activity, Uri uri) {
            super(activity);
            mUri = uri;
        }

        @Override
        protected Void run(Void... params) {
            mOwner.updateLastAccessed();
            return null;
        }

        @Override
        protected void finish(Void result) {
            mOwner.onTaskFinished(mUri);
        }
    }

    private static final class ExistingFinishTask extends PairedTask<PickActivity, Void, Void> {
        private final Uri[] mUris;

        public ExistingFinishTask(PickActivity activity, Uri... uris) {
            super(activity);
            mUris = uris;
        }

        @Override
        protected Void run(Void... params) {
            mOwner.updateLastAccessed();
            return null;
        }

        @Override
        protected void finish(Void result) {
            mOwner.onTaskFinished(mUris);
        }
    }

    /**
     * Task that creates a new document in the background.
     */
    private static final class CreateFinishTask extends PairedTask<PickActivity, Void, Uri> {
        private final String mMimeType;
        private final String mDisplayName;

        public CreateFinishTask(PickActivity activity, String mimeType, String displayName) {
            super(activity);
            mMimeType = mimeType;
            mDisplayName = displayName;
        }

        @Override
        protected void prepare() {
            mOwner.setPending(true);
        }

        @Override
        protected Uri run(Void... params) {
            final ContentResolver resolver = mOwner.getContentResolver();
            final DocumentInfo cwd = mOwner.getCurrentDirectory();

            ContentProviderClient client = null;
            Uri childUri = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, cwd.derivedUri.getAuthority());
                childUri = DocumentsContract.createDocument(
                        client, cwd.derivedUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create document", e);
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }

            if (childUri != null) {
                mOwner.updateLastAccessed();
            }

            return childUri;
        }

        @Override
        protected void finish(Uri result) {
            if (result != null) {
                mOwner.onTaskFinished(result);
            } else {
                Snackbars.makeSnackbar(
                        mOwner, R.string.save_error, Snackbar.LENGTH_SHORT).show();
            }

            mOwner.setPending(false);
        }
    }

    @Override
    public Injector<ActionHandler<PickActivity>> getInjector() {
        return mInjector;
    }
}
