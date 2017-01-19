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

package com.android.documentsui.files;

import static com.android.documentsui.testing.IntentAsserts.assertHasAction;
import static com.android.documentsui.testing.IntentAsserts.assertHasExtraIntent;
import static com.android.documentsui.testing.IntentAsserts.assertHasExtraList;
import static com.android.documentsui.testing.IntentAsserts.assertHasExtraUri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.R;
import com.android.documentsui.TestActionModeAddons;
import com.android.documentsui.archives.ArchivesProvider;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.DocumentStack;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.Shared;
import com.android.documentsui.selection.Selection;
import com.android.documentsui.testing.Roots;
import com.android.documentsui.testing.TestConfirmationCallback;
import com.android.documentsui.testing.TestEnv;
import com.android.documentsui.testing.TestRootsAccess;
import com.android.documentsui.ui.TestDialogController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class ActionHandlerTest {

    private TestEnv mEnv;
    private TestActivity mActivity;
    private TestActionModeAddons mActionModeAddons;
    private TestDialogController mDialogs;
    private TestConfirmationCallback mCallback;
    private ActionHandler<TestActivity> mHandler;
    private boolean refreshAnswer = false;

    @Before
    public void setUp() {
        mEnv = TestEnv.create();
        mActivity = TestActivity.create();
        mActionModeAddons = new TestActionModeAddons();
        mDialogs = new TestDialogController();
        mCallback = new TestConfirmationCallback();
        mEnv.roots.configurePm(mActivity.packageMgr);

        mHandler = new ActionHandler<>(
                mActivity,
                mEnv.state,
                mEnv.roots,
                mEnv.docs,
                mEnv.focusHandler,
                mEnv.selectionMgr,
                mEnv.searchViewManager,
                mEnv::lookupExecutor,
                mActionModeAddons,
                mDialogs,
                null,  // tuner, not currently used.
                null,  // clipper, only used in drag/drop
                null  // clip storage, not utilized unless we venture into *jumbo* clip terratory.
                );

        mDialogs.confirmNext();

        mEnv.selectDocument(TestEnv.FILE_GIF);

        mHandler.reset(mEnv.model);
    }

    @Test
    public void testOpenSelectedInNewWindow() {
        mHandler.openSelectedInNewWindow();

        DocumentStack path = new DocumentStack(Roots.create("123"), mEnv.model.getDocument("1"));

        Intent expected = LauncherActivity.createLaunchIntent(mActivity);
        expected.putExtra(Shared.EXTRA_STACK, (Parcelable) path);

        Intent actual = mActivity.startActivity.getLastValue();
        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    public void testSpringOpenDirectory() {
        mHandler.springOpenDirectory(TestEnv.FOLDER_0);
        assertTrue(mActionModeAddons.finishActionModeCalled);
        assertEquals(TestEnv.FOLDER_0, mEnv.state.stack.peek());
    }

    @Test
    public void testCutSelectedDocuments_NoGivenSelection() {
        mEnv.populateStack();

        mEnv.selectionMgr.clearSelection();
        mHandler.cutToClipboard();
        mDialogs.assertDocumentsClippedNotShown();
    }

    @Test
    public void testCopySelectedDocuments_NoGivenSelection() {
        mEnv.populateStack();

        mEnv.selectionMgr.clearSelection();
        mHandler.copyToClipboard();
        mDialogs.assertDocumentsClippedNotShown();
    }

    @Test
    public void testDeleteSelectedDocuments_NoSelection() {
        mEnv.populateStack();

        mEnv.selectionMgr.clearSelection();
        mHandler.deleteSelectedDocuments();
        mDialogs.assertNoFileFailures();
        mActivity.startService.assertNotCalled();
        mActionModeAddons.finishOnConfirmed.assertNeverCalled();
    }

    @Test
    public void testDeleteSelectedDocuments_Cancelable() {
        mEnv.populateStack();

        mDialogs.rejectNext();
        mHandler.deleteSelectedDocuments();
        mDialogs.assertNoFileFailures();
        mActivity.startService.assertNotCalled();
        mActionModeAddons.finishOnConfirmed.assertRejected();
    }

    // Recents root means when deleting the srcParent will be null.
    @Test
    public void testDeleteSelectedDocuments_RecentsRoot() {
        mEnv.state.stack.changeRoot(TestRootsAccess.RECENTS);

        mHandler.deleteSelectedDocuments();
        mDialogs.assertNoFileFailures();
        mActivity.startService.assertCalled();
        mActionModeAddons.finishOnConfirmed.assertCalled();
    }

    @Test
    public void testShareSelectedDocuments_ShowsChooser() {
        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mHandler.shareSelectedDocuments();

        mActivity.assertActivityStarted(Intent.ACTION_CHOOSER);
    }

    @Test
    public void testShareSelectedDocuments_Single() {
        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mHandler.shareSelectedDocuments();

        Intent intent = assertHasExtraIntent(mActivity.startActivity.getLastValue());
        assertHasAction(intent, Intent.ACTION_SEND);
        assertFalse(intent.hasCategory(Intent.CATEGORY_TYPED_OPENABLE));
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertHasExtraUri(intent, Intent.EXTRA_STREAM);
    }

    @Test
    public void testShareSelectedDocuments_ArchivedFile() {
        mEnv = TestEnv.create(ArchivesProvider.AUTHORITY);
        mHandler.reset(mEnv.model);

        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mEnv.selectionMgr.clearSelection();
        mEnv.selectDocument(TestEnv.FILE_PDF);
        mHandler.shareSelectedDocuments();

        Intent intent = mActivity.startActivity.getLastValue();
        assertNull(intent);
    }

    @Test
    public void testShareSelectedDocuments_Multiple() {
        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mEnv.selectDocument(TestEnv.FILE_PDF);
        mHandler.shareSelectedDocuments();

        Intent intent = assertHasExtraIntent(mActivity.startActivity.getLastValue());
        assertHasAction(intent, Intent.ACTION_SEND_MULTIPLE);
        assertFalse(intent.hasCategory(Intent.CATEGORY_TYPED_OPENABLE));
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertHasExtraList(intent, Intent.EXTRA_STREAM, 2);
    }

    @Test
    public void testShareSelectedDocuments_VirtualFiles() {
        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mEnv.selectionMgr.clearSelection();
        mEnv.selectDocument(TestEnv.FILE_VIRTUAL);
        mHandler.shareSelectedDocuments();

        Intent intent = assertHasExtraIntent(mActivity.startActivity.getLastValue());
        assertHasAction(intent, Intent.ACTION_SEND);
        assertTrue(intent.hasCategory(Intent.CATEGORY_TYPED_OPENABLE));
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertHasExtraUri(intent, Intent.EXTRA_STREAM);
    }

    @Test
    public void testShareSelectedDocuments_RegularAndVirtualFiles() {
        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mEnv.selectDocument(TestEnv.FILE_PNG);
        mEnv.selectDocument(TestEnv.FILE_VIRTUAL);
        mHandler.shareSelectedDocuments();

        Intent intent = assertHasExtraIntent(mActivity.startActivity.getLastValue());
        assertHasAction(intent, Intent.ACTION_SEND_MULTIPLE);
        assertTrue(intent.hasCategory(Intent.CATEGORY_TYPED_OPENABLE));
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertHasExtraList(intent, Intent.EXTRA_STREAM, 3);
    }

    @Test
    public void testShareSelectedDocuments_OmitsPartialFiles() {
        mActivity.resources.strings.put(R.string.share_via, "Sharezilla!");
        mEnv.selectDocument(TestEnv.FILE_PARTIAL);
        mEnv.selectDocument(TestEnv.FILE_PNG);
        mHandler.shareSelectedDocuments();

        Intent intent = assertHasExtraIntent(mActivity.startActivity.getLastValue());
        assertHasAction(intent, Intent.ACTION_SEND_MULTIPLE);
        assertFalse(intent.hasCategory(Intent.CATEGORY_TYPED_OPENABLE));
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertHasExtraList(intent, Intent.EXTRA_STREAM, 2);
    }

    @Test
    public void testDocumentPicked_DefaultsToView() throws Exception {
        mActivity.currentRoot = TestRootsAccess.HOME;

        mHandler.onDocumentPicked(TestEnv.FILE_GIF);
        mActivity.assertActivityStarted(Intent.ACTION_VIEW);
    }

    @Test
    public void testDocumentPicked_PreviewsWhenResourceSet() throws Exception {
        mActivity.resources.setQuickViewerPackage("corptropolis.viewer");
        mActivity.currentRoot = TestRootsAccess.HOME;

        mHandler.onDocumentPicked(TestEnv.FILE_GIF);
        mActivity.assertActivityStarted(Intent.ACTION_QUICK_VIEW);
    }

    @Test
    public void testDocumentPicked_Downloads_ManagesApks() throws Exception {
        mActivity.currentRoot = TestRootsAccess.DOWNLOADS;

        mHandler.onDocumentPicked(TestEnv.FILE_APK);
        mActivity.assertActivityStarted(DocumentsContract.ACTION_MANAGE_DOCUMENT);
    }

    @Test
    public void testDocumentPicked_Downloads_ManagesPartialFiles() throws Exception {
        mActivity.currentRoot = TestRootsAccess.DOWNLOADS;

        mHandler.onDocumentPicked(TestEnv.FILE_PARTIAL);
        mActivity.assertActivityStarted(DocumentsContract.ACTION_MANAGE_DOCUMENT);
    }

    @Test
    public void testDocumentPicked_OpensArchives() throws Exception {
        mActivity.currentRoot = TestRootsAccess.HOME;
        mEnv.docs.nextDocument = TestEnv.FILE_ARCHIVE;

        mHandler.onDocumentPicked(TestEnv.FILE_ARCHIVE);
        assertEquals(TestEnv.FILE_ARCHIVE, mEnv.state.stack.peek());
    }

    @Test
    public void testDocumentPicked_OpensDirectories() throws Exception {
        mActivity.currentRoot = TestRootsAccess.HOME;

        mHandler.onDocumentPicked(TestEnv.FOLDER_1);
        assertEquals(TestEnv.FOLDER_1, mEnv.state.stack.peek());
    }

    @Test
    public void testShowChooser() throws Exception {
        mActivity.currentRoot = TestRootsAccess.DOWNLOADS;

        mHandler.showChooserForDoc(TestEnv.FILE_PDF);
        mActivity.assertActivityStarted(Intent.ACTION_CHOOSER);
    }

    @Test
    public void testInitLocation_DefaultsToDownloads() throws Exception {
        mActivity.resources.bools.put(R.bool.productivity_device, false);

        mHandler.initLocation(mActivity.getIntent());
        assertRootPicked(TestRootsAccess.DOWNLOADS.getUri());
    }

    @Test
    public void testInitLocation_ProductivityDefaultsToHome() throws Exception {
        mActivity.resources.bools.put(R.bool.productivity_device, true);

        mHandler.initLocation(mActivity.getIntent());
        assertRootPicked(TestRootsAccess.HOME.getUri());
    }

    @Test
    public void testInitLocation_BrowseRoot() throws Exception {
        Intent intent = mActivity.getIntent();
        intent.setAction(DocumentsContract.ACTION_BROWSE);
        intent.setData(TestRootsAccess.PICKLES.getUri());

        mHandler.initLocation(intent);
        assertRootPicked(TestRootsAccess.PICKLES.getUri());
    }

    @Test
    public void testRefresh_nullUri() throws Exception {
        refreshAnswer = true;
        mHandler.refreshDocument(null, (boolean answer) -> {
            refreshAnswer = answer;
        });

        mEnv.beforeAsserts();
        assertFalse(refreshAnswer);
    }

    @Test
    public void testRefresh_emptyStack() throws Exception {
        refreshAnswer = true;
        assertTrue(mEnv.state.stack.isEmpty());
        mHandler.refreshDocument(new DocumentInfo(), (boolean answer) -> {
            refreshAnswer = answer;
        });

        mEnv.beforeAsserts();
        assertFalse(refreshAnswer);
    }

    @Test
    public void testRefresh() throws Exception {
        refreshAnswer = false;
        mEnv.populateStack();
        mHandler.refreshDocument(mEnv.model.getDocument("1"), (boolean answer) -> {
            refreshAnswer = answer;
        });

        mEnv.beforeAsserts();
        assertTrue(refreshAnswer);
    }

    private void assertRootPicked(Uri expectedUri) throws Exception {
        mEnv.beforeAsserts();

        mActivity.rootPicked.assertCalled();
        RootInfo root = mActivity.rootPicked.getLastValue();
        assertNotNull(root);
        assertEquals(expectedUri, root.getUri());
    }
}
