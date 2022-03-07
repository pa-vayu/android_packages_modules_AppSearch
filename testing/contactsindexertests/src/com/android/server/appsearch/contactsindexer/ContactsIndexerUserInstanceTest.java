/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appsearch.contactsindexer;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.provider.ContactsContract;
import android.test.ProviderTestCase2;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

// TODO(b/203605504) this is a junit3 test(ProviderTestCase2) but we run it with junit4 to use
//  some utilities like temporary folder. Right now I can't make ProviderTestRule work so we
//  stick to ProviderTestCase2 for now.
@RunWith(AndroidJUnit4.class)
public class ContactsIndexerUserInstanceTest extends ProviderTestCase2<FakeContactsProvider> {

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();

    private ContextWrapper mContextWrapper;
    private File mContactsDir;
    private File mSettingsFile;
    private SearchSpec mSpecForQueryAllContacts;
    private ContactsIndexerUserInstance mInstance;
    private ContactsUpdateStats mUpdateStats;

    public ContactsIndexerUserInstanceTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Setup the file path to the persisted data
        mContactsDir = new File(mTemporaryFolder.newFolder(), "appsearch/contacts");
        mSettingsFile = new File(mContactsDir, ContactsIndexerSettings.SETTINGS_FILE_NAME);

        mContextWrapper = new ContextWrapper(ApplicationProvider.getApplicationContext());
        mContextWrapper.setContentResolver(getMockContentResolver());
        mContext = mContextWrapper;

        mSpecForQueryAllContacts = new SearchSpec.Builder().addFilterSchemas(
                Person.SCHEMA_TYPE).addProjection(Person.SCHEMA_TYPE,
                Arrays.asList(Person.PERSON_PROPERTY_NAME))
                .setResultCountPerPage(100)
                .build();

        mInstance = ContactsIndexerUserInstance.createInstance(mContext, mContactsDir,
                mSingleThreadedExecutor);

        mUpdateStats = new ContactsUpdateStats();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.DATABASE_NAME).build();
        AppSearchSessionShim db = AppSearchSessionShimImpl.createSearchSessionAsync(
                searchContext).get();
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .setForceOverride(true).build();
        db.setSchemaAsync(setSchemaRequest).get();
        super.tearDown();
    }

    @Test
    public void testCreateInstance_dataDirectoryCreatedAsynchronously() throws Exception {
        File dataDir = new File(mTemporaryFolder.newFolder(), "contacts");
        boolean isDataDirectoryCreatedSynchronously = mSingleThreadedExecutor.submit(() -> {
            ContactsIndexerUserInstance unused =
                    ContactsIndexerUserInstance.createInstance(mContext, dataDir,
                            mSingleThreadedExecutor);
            // Data directory shouldn't have been created synchronously in createInstance()
            return dataDir.exists();
        }).get();
        assertFalse(isDataDirectoryCreatedSynchronously);
        boolean isDataDirectoryCreatedAsynchronously = mSingleThreadedExecutor.submit(
                dataDir::exists).get();
        assertTrue(isDataDirectoryCreatedAsynchronously);
    }

    @Test
    public void testStart_initialRun_schedulesFullUpdateJob() throws Exception {
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mContextWrapper.setJobScheduler(mockJobScheduler);
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mContactsDir, mSingleThreadedExecutor);

        instance.startAsync();

        // Wait for all async tasks to complete
        mSingleThreadedExecutor.submit(() -> {}).get();

        verify(mockJobScheduler).schedule(any());
    }

    @Test
    public void testStart_subsequentRun_doesNotScheduleFullUpdateJob() throws Exception {
        executeAndWaitForCompletion(
                mInstance.doFullUpdateInternalAsync(new CancellationSignal(), mUpdateStats),
                mSingleThreadedExecutor);

        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mContextWrapper.setJobScheduler(mockJobScheduler);
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mContactsDir, mSingleThreadedExecutor);

        instance.startAsync();

        // Wait for all async tasks to complete
        mSingleThreadedExecutor.submit(() -> {}).get();

        verifyZeroInteractions(mockJobScheduler);
    }

    @Test
    public void testFullUpdate() throws Exception {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 500; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        executeAndWaitForCompletion(
                mInstance.doFullUpdateInternalAsync(new CancellationSignal(), mUpdateStats),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(500);
    }

    @Test
    public void testDeltaUpdate_insertedContacts() throws Exception {
        long timeBeforeDeltaChangeNotification = System.currentTimeMillis();
        // Insert contacts to trigger delta update.
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 250; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(/*indexingLimit=*/ -1,
                        mUpdateStats),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(250);

        PersistableBundle settingsBundle = ContactsIndexerSettings.readBundle(mSettingsFile);
        assertThat(settingsBundle.getLong(ContactsIndexerSettings.LAST_DELTA_UPDATE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeDeltaChangeNotification);
        // check stats
        assertThat(mUpdateStats.mUpdateType).isEqualTo(ContactsUpdateStats.DELTA_UPDATE);
        assertThat(mUpdateStats.mUpdateStatuses).hasSize(1);
        assertThat(mUpdateStats.mUpdateStatuses).containsExactly(AppSearchResult.RESULT_OK);
        assertThat(mUpdateStats.mDeleteStatuses).hasSize(1);
        assertThat(mUpdateStats.mDeleteStatuses).containsExactly(AppSearchResult.RESULT_OK);
        assertThat(mUpdateStats.mContactsUpdateFailedCount).isEqualTo(0);
        // NOT_FOUND does not count as error.
        assertThat(mUpdateStats.mContactsDeleteFailedCount).isEqualTo(0);
        assertThat(mUpdateStats.mContactsInsertedCount).isEqualTo(250);
        assertThat(mUpdateStats.mContactsSkippedCount).isEqualTo(0);
        assertThat(mUpdateStats.mContactsUpdateCount).isEqualTo(250);
        assertThat(mUpdateStats.mContactsDeleteCount).isEqualTo(0);
    }

    @Test
    public void testDeltaUpdateWithLimit_fewerContactsIndexed() throws Exception {
        // Insert contacts to trigger delta update.
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 250; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(/*indexingLimit=*/ 100,
                        mUpdateStats),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(100);
    }

    @Test
    public void testDeltaUpdate_deletedContacts() throws Exception {
        long timeBeforeDeltaChangeNotification = System.currentTimeMillis();
        // Insert contacts to trigger delta update.
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 10; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(/*indexingLimit=*/ -1,
                        mUpdateStats),
                mSingleThreadedExecutor);

        // Delete a few contacts to trigger delta update.
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 2),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 3),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 5),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 7),
                /*extras=*/ null);

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(/*indexingLimit=*/ -1,
                        mUpdateStats),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(6);
        assertThat(contactIds).containsNoneOf("2", "3", "5", "7");

        // TODO(b/222126568): verify state using logged events instead
        PersistableBundle settingsBundle = ContactsIndexerSettings.readBundle(mSettingsFile);
        assertThat(settingsBundle.getLong(ContactsIndexerSettings.LAST_DELTA_UPDATE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeDeltaChangeNotification);
    }

    @Test
    public void testDeltaUpdate_insertedAndDeletedContacts() throws Exception {
        long timeBeforeDeltaChangeNotification = System.currentTimeMillis();
        // Insert contacts to trigger delta update.
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 10; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        // Delete a few contacts to trigger delta update.
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 2),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 3),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 5),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 7),
                /*extras=*/ null);

        mUpdateStats.clear();
        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(/*indexingLimit=*/ -1,
                        mUpdateStats),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(6);
        assertThat(contactIds).containsNoneOf("2", "3", "5", "7");

        PersistableBundle settingsBundle = ContactsIndexerSettings.readBundle(mSettingsFile);
        assertThat(settingsBundle.getLong(ContactsIndexerSettings.LAST_DELTA_UPDATE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeDeltaChangeNotification);
        assertThat(settingsBundle.getLong(ContactsIndexerSettings.LAST_DELTA_DELETE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeDeltaChangeNotification);
        // check stats
        assertThat(mUpdateStats.mUpdateType).isEqualTo(ContactsUpdateStats.DELTA_UPDATE);
        assertThat(mUpdateStats.mUpdateStatuses).hasSize(1);
        assertThat(mUpdateStats.mUpdateStatuses).containsExactly(AppSearchResult.RESULT_OK);
        assertThat(mUpdateStats.mDeleteStatuses).hasSize(1);
        assertThat(mUpdateStats.mDeleteStatuses).containsExactly(AppSearchResult.RESULT_OK);
        assertThat(mUpdateStats.mContactsUpdateFailedCount).isEqualTo(0);
        // NOT_FOUND does not count as error.
        assertThat(mUpdateStats.mContactsDeleteFailedCount).isEqualTo(0);
        assertThat(mUpdateStats.mContactsInsertedCount).isEqualTo(6);
        assertThat(mUpdateStats.mContactsSkippedCount).isEqualTo(0);
        assertThat(mUpdateStats.mContactsUpdateCount).isEqualTo(6);
        assertThat(mUpdateStats.mContactsDeleteCount).isEqualTo(0);
    }

    @Test
    public void testDeltaUpdate_insertedAndDeletedContacts_withDeletionSucceed() throws Exception {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        // Index 10 documents before testing.
        for (int i = 0; i < 10; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }
        executeAndWaitForCompletion(
                mInstance.doFullUpdateInternalAsync(new CancellationSignal(), mUpdateStats),
                mSingleThreadedExecutor);

        long timeBeforeDeltaChangeNotification = System.currentTimeMillis();
        // Insert additional 5 contacts to trigger delta update.
        for (int i = 0; i < 5; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }
        // Delete a few contacts to trigger delta update.
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 2),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 3),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 5),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 7),
                /*extras=*/ null);

        mUpdateStats.clear();
        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(/*indexingLimit=*/ -1,
                        mUpdateStats),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(11);
        assertThat(contactIds).containsNoneOf("2", "3", "5", "7");

        PersistableBundle settingsBundle = ContactsIndexerSettings.readBundle(mSettingsFile);
        assertThat(settingsBundle.getLong(ContactsIndexerSettings.LAST_DELTA_UPDATE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeDeltaChangeNotification);
        assertThat(settingsBundle.getLong(ContactsIndexerSettings.LAST_DELTA_DELETE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeDeltaChangeNotification);
        // check stats
        assertThat(mUpdateStats.mUpdateType).isEqualTo(ContactsUpdateStats.DELTA_UPDATE);
        assertThat(mUpdateStats.mUpdateStatuses).hasSize(1);
        assertThat(mUpdateStats.mUpdateStatuses).containsExactly(AppSearchResult.RESULT_OK);
        assertThat(mUpdateStats.mDeleteStatuses).hasSize(1);
        assertThat(mUpdateStats.mDeleteStatuses).containsExactly(AppSearchResult.RESULT_OK);
        assertThat(mUpdateStats.mContactsUpdateFailedCount).isEqualTo(0);
        // NOT_FOUND does not count as error.
        assertThat(mUpdateStats.mContactsDeleteFailedCount).isEqualTo(0);
        assertThat(mUpdateStats.mContactsInsertedCount).isEqualTo(5);
        assertThat(mUpdateStats.mContactsSkippedCount).isEqualTo(0);
        assertThat(mUpdateStats.mContactsUpdateCount).isEqualTo(5);
        assertThat(mUpdateStats.mContactsDeleteCount).isEqualTo(4);
    }

    /**
     * Executes given {@link CompletionStage} on the {@code executor} and waits for its completion.
     *
     * <p>There are 2 steps in this implementation. The first step is to execute the stage on the
     * executor, and wait for its execution. The second step is to wait for the completion of the
     * stage itself.
     */
    private void executeAndWaitForCompletion(CompletionStage<Void> stage, ExecutorService executor)
            throws Exception {
        AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>(
                CompletableFuture.completedFuture(null));
        executor.submit(() -> {
            // Chain the given stage inside the runnable task so that it executes on the executor.
            CompletableFuture<Void> chainedFuture = future.get().thenCompose(x -> stage);
            future.set(chainedFuture);
        }).get();
        // Wait for the task to complete on the executor, and wait for the stage to complete also.
        future.get().get();
    }

    static final class ContextWrapper extends android.content.ContextWrapper {

        @Nullable ContentResolver mResolver;
        @Nullable JobScheduler mScheduler;

        public ContextWrapper(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ContentResolver getContentResolver() {
            if (mResolver != null) {
                return mResolver;
            }
            return getBaseContext().getContentResolver();
        }

        @Override
        @Nullable
        public Object getSystemService(String name) {
            if (mScheduler != null && Context.JOB_SCHEDULER_SERVICE.equals(name)) {
                return mScheduler;
            }
            return getBaseContext().getSystemService(name);
        }

        public void setContentResolver(ContentResolver resolver) {
            mResolver = resolver;
        }

        public void setJobScheduler(JobScheduler scheduler) {
            mScheduler = scheduler;
        }
    }
}
