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
import android.provider.ContactsContract;
import android.test.ProviderTestCase2;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.appsearch.contactsindexer.ContactsIndexerUserInstance.PersistedData;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Path;
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
    private Path mDataFilePath;
    private SearchSpec mSpecForQueryAllContacts;
    private ContactsIndexerUserInstance mInstance;

    public ContactsIndexerUserInstanceTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Setup the file path to the persisted data
        mContactsDir = new File(mTemporaryFolder.newFolder(), "appsearch/contacts");
        mDataFilePath = new File(mContactsDir, ContactsIndexerUserInstance.CONTACTS_INDEXER_STATE)
                .toPath();

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
    }

    @Override
    @After
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        AppSearchSessionShim db = AppSearchSessionShimImpl.createSearchSessionAsync(mContext,
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.DATABASE_NAME).build(),
                mSingleThreadedExecutor).get();
        db.setSchema(new SetSchemaRequest.Builder().setForceOverride(true).build()).get();
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
    public void testCreateInstance_initialRun_schedulesFullUpdateJob() throws Exception {
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
    public void testCreateInstance_subsequentRun_doesNotScheduleFullUpdateJob() throws Exception {
        executeAndWaitForCompletion(mInstance.doFullUpdateInternalAsync(new CancellationSignal()),
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

        executeAndWaitForCompletion(mInstance.doFullUpdateInternalAsync(new CancellationSignal()),
                mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(500);
    }

    @Test
    public void testDeltaUpdate_insertedContacts() throws Exception {
        // Trigger initial full update
        executeAndWaitForCompletion(mInstance.doFullUpdateInternalAsync(new CancellationSignal()),
                mSingleThreadedExecutor);

        long timeBeforeDeltaChangeNotification = System.currentTimeMillis();
        // Insert contacts to trigger delta update.
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 250; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(), mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(250);

        // TODO(b/222126568): verify state using logged events instead
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mContactsDir, mSingleThreadedExecutor);
        PersistedData data = instance.getPersistedStateForTest();
        assertThat(data.mLastDeltaUpdateTimestampMillis).isAtLeast(
                timeBeforeDeltaChangeNotification);
    }

    @Test
    public void testDeltaUpdate_deletedContacts() throws Exception {
        // Trigger initial full update
        executeAndWaitForCompletion(mInstance.doFullUpdateInternalAsync(new CancellationSignal()),
                mSingleThreadedExecutor);

        long timeBeforeDeltaChangeNotification = System.currentTimeMillis();
        // Insert contacts to trigger delta update.
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues dummyValues = new ContentValues();
        for (int i = 0; i < 10; i++) {
            resolver.insert(ContactsContract.Contacts.CONTENT_URI, dummyValues);
        }

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(), mSingleThreadedExecutor);

        // Delete a few contacts to trigger delta update.
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 2),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 3),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 5),
                /*extras=*/ null);
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 7),
                /*extras=*/ null);

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(), mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(6);
        assertThat(contactIds).containsNoneOf("2", "3", "5", "7");

        // TODO(b/222126568): verify state using logged events instead
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mContactsDir, mSingleThreadedExecutor);
        PersistedData data = instance.getPersistedStateForTest();
        assertThat(data.mLastDeltaDeleteTimestampMillis).isAtLeast(
                timeBeforeDeltaChangeNotification);
    }

    @Test
    public void testDeltaUpdate_insertedAndDeletedContacts() throws Exception {
        // Trigger initial full update
        executeAndWaitForCompletion(mInstance.doFullUpdateInternalAsync(new CancellationSignal()),
                mSingleThreadedExecutor);

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

        executeAndWaitForCompletion(mInstance.doDeltaUpdateAsync(), mSingleThreadedExecutor);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                mSingleThreadedExecutor);
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(6);
        assertThat(contactIds).containsNoneOf("2", "3", "5", "7");

        // TODO(b/222126568): verify state using logged events instead
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mContactsDir, mSingleThreadedExecutor);
        PersistedData data = instance.getPersistedStateForTest();
        assertThat(data.mLastDeltaUpdateTimestampMillis).isAtLeast(
                timeBeforeDeltaChangeNotification);
        assertThat(data.mLastDeltaDeleteTimestampMillis).isAtLeast(
                timeBeforeDeltaChangeNotification);
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
