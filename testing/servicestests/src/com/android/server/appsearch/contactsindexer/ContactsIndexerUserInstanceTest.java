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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.content.Context;
import android.os.CancellationSignal;
import android.test.ProviderTestCase2;

import static org.junit.Assert.assertThrows;

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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

// TODO(b/203605504) this is a junit3 test(ProviderTestCase2) but we run it with junit4 to use
//  some utilities like temporary folder. Right now I can't make ProviderTestRule work so we
//  stick to ProviderTestCase2 for now.
@RunWith(AndroidJUnit4.class)
public class ContactsIndexerUserInstanceTest extends ProviderTestCase2<FakeContactsProvider> {
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private Path mDataFilePath;
    private SearchSpec mSpecForQueryAllContacts;

    public ContactsIndexerUserInstanceTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Setup the file path to the persisted data.
        mDataFilePath = new File(mTemporaryFolder.newFolder(),
                ContactsIndexerUserInstance.CONTACTS_INDEXER_STATE).toPath();

        Context appContext = ApplicationProvider.getApplicationContext();
        mContext = spy(appContext);
        doReturn(getMockContentResolver()).when(mContext).getContentResolver();
        mSpecForQueryAllContacts = new SearchSpec.Builder().addFilterSchemas(
                Person.SCHEMA_TYPE).addProjection(Person.SCHEMA_TYPE,
                Arrays.asList(Person.PERSON_PROPERTY_NAME))
                .setResultCountPerPage(100)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.DATABASE_NAME).build();
        CompletableFuture<AppSearchResult<AppSearchSession>> future = new CompletableFuture<>();
        appSearchManager.createSearchSession(searchContext, Runnable::run, future::complete);
        AppSearchSession session = future.get().getResultValue();
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .setForceOverride(true).build();
        CompletableFuture<AppSearchResult<SetSchemaResponse>> futureSetSchema =
                new CompletableFuture<>();
        session.setSchema(setSchemaRequest, Runnable::run, Runnable::run,
                futureSetSchema::complete);
    }

    @Test
    public void testCreateInstance_initialLastTimestamps_zero()
            throws Exception {
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());

        PersistedData data = instance.getPersistedStateForTest();

        assertThat(data.mLastFullUpdateTimestampMillis).isEqualTo(0);
        assertThat(data.mLastDeltaUpdateTimestampMillis).isEqualTo(0);
        assertThat(data.mLastDeltaDeleteTimestampMillis).isEqualTo(0);
    }

    @Test
    public void testCreateInstance_lastTimestamps_readFromDiskCorrectly()
            throws Exception {
        PersistedData newData = new PersistedData();
        newData.mLastFullUpdateTimestampMillis = 1;
        newData.mLastDeltaUpdateTimestampMillis = 2;
        newData.mLastDeltaDeleteTimestampMillis = 3;
        clearAndWriteDataToTempFile(newData.toString(), mDataFilePath);

        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());

        PersistedData loadedData = instance.getPersistedStateForTest();
        assertThat(loadedData.mLastFullUpdateTimestampMillis).isEqualTo(
                newData.mLastFullUpdateTimestampMillis);
        assertThat(loadedData.mLastDeltaUpdateTimestampMillis).isEqualTo(
                newData.mLastDeltaUpdateTimestampMillis);
        assertThat(loadedData.mLastDeltaDeleteTimestampMillis).isEqualTo(
                newData.mLastDeltaDeleteTimestampMillis);
    }

    @Test
    public void testContactsIndexerUserInstance_lastTimestamps_writeToDiskCorrectly()
            throws Exception {
        // Simulate a full update to enable non-deferred delta updates
        PersistedData newData = new PersistedData();
        newData.mLastFullUpdateTimestampMillis = 1;
        clearAndWriteDataToTempFile(newData.toString(), mDataFilePath);

        // In FakeContactsIndexer, we have contacts array [1, 2, ... NUM_TOTAL_CONTACTS], among
        // them, [1, NUM_EXISTED_CONTACTS] is for updated contacts, and [NUM_EXISTED_CONTACTS +
        // 1, NUM_TOTAL_CONTACTS] is for deleted contacts.
        // And the number here is same for both contact_id, and last update/delete timestamp.
        // So, if we have [1, 50] for updated, and [51, 100] for deleted, and lastUpdatedTime is
        // 40, [41, 50] needs to be updated. Likewise, if lastDeletedTime is 55, we would delete
        // [56, 100
        long expectedNewLastUpdatedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastDeletedTimestamp = FakeContactsProvider.NUM_TOTAL_CONTACTS;
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());

        instance.doDeltaUpdateForTest();

        PersistedData loadedData = instance.getPersistedStateForTest();
        assertThat(loadedData.mLastDeltaUpdateTimestampMillis).isEqualTo(
                expectedNewLastUpdatedTimestamp);
        assertThat(loadedData.mLastDeltaDeleteTimestampMillis).isEqualTo(
                expectedNewLastDeletedTimestamp);

        // Create another indexer to load data from disk.
        instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());
        loadedData = instance.getPersistedStateForTest();
        assertThat(loadedData.mLastDeltaUpdateTimestampMillis).isEqualTo(
                expectedNewLastUpdatedTimestamp);
        assertThat(loadedData.mLastDeltaDeleteTimestampMillis).isEqualTo(
                expectedNewLastDeletedTimestamp);
    }

    @Test
    public void testContactsIndexerUserInstance_deltaUpdate_firstTime() throws Exception {
        // Simulate a full update to enable non-deferred delta updates
        PersistedData newData = new PersistedData();
        newData.mLastFullUpdateTimestampMillis = 1;
        clearAndWriteDataToTempFile(newData.toString(), mDataFilePath);

        // In FakeContactsIndexer, we have contacts array [1, 2, ... NUM_TOTAL_CONTACTS], among
        // them, [1, NUM_EXISTED_CONTACTS] is for updated contacts, and [NUM_EXISTED_CONTACTS +
        // 1, NUM_TOTAL_CONTACTS] is for deleted contacts.
        // And the number here is same for both contact_id, and last update/delete timestamp.
        // So, if we have [1, 50] for updated, and [51, 100] for deleted, and lastUpdatedTime is
        // 40, [41, 50] needs to be updated. Likewise, if lastDeletedTime is 55, we would delete
        // [56, 100
        int expectedUpdatedContactsFirstId = 1;
        int expectedUpdatedContactsLastId = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastUpdatedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastDeletedTimestamp = FakeContactsProvider.NUM_TOTAL_CONTACTS;
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());

        instance.doDeltaUpdateForTest();

        PersistedData loadedData = instance.getPersistedStateForTest();
        assertThat(loadedData.mLastDeltaUpdateTimestampMillis).isEqualTo(
                expectedNewLastUpdatedTimestamp);
        assertThat(loadedData.mLastDeltaDeleteTimestampMillis).isEqualTo(
                expectedNewLastDeletedTimestamp);
    }

    @Test
    public void testContactsIndexerUserInstance_deltaUpdate() throws Exception {
        // In FakeContactsIndexer, we have contacts array [1, 2, ... NUM_TOTAL_CONTACTS], among
        // them, [1, NUM_EXISTED_CONTACTS] is for updated contacts, and [NUM_EXISTED_CONTACTS +
        // 1, NUM_TOTAL_CONTACTS] is for deleted contacts.
        // And the number here is same for both contact_id, and last update/delete timestamp.
        // So, if we have [1, 50] for updated, and [51, 100] for deleted, and lastUpdatedTime is
        // 40, [41, 50] needs to be updated. Likewise, if lastDeletedTime is 55, we would delete
        // [56, 100
        int lastUpdatedTimestamp = 19;
        int lastDeletedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS + 1;
        int expectedUpdatedContactsFirstId = lastUpdatedTimestamp + 1;
        int expectedUpdatedContactsLastId = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastUpdatedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastDeletedTimestamp = FakeContactsProvider.NUM_TOTAL_CONTACTS;
        PersistedData persistedData = new PersistedData();
        persistedData.mLastFullUpdateTimestampMillis = 1;
        persistedData.mLastDeltaUpdateTimestampMillis = lastUpdatedTimestamp;
        persistedData.mLastDeltaDeleteTimestampMillis = lastDeletedTimestamp;
        clearAndWriteDataToTempFile(persistedData.toString(), mDataFilePath);
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());

        instance.doDeltaUpdateForTest();

        PersistedData loadedData = instance.getPersistedStateForTest();
        assertThat(loadedData.mLastDeltaUpdateTimestampMillis).isEqualTo(
                expectedNewLastUpdatedTimestamp);
        assertThat(loadedData.mLastDeltaDeleteTimestampMillis).isEqualTo(
                expectedNewLastDeletedTimestamp);
    }

    @Test
    public void testFullUpdate() throws Exception {
        ContactsIndexerUserInstance instance = ContactsIndexerUserInstance.createInstance(mContext,
                mDataFilePath.getParent().toFile());

        instance.doFullUpdateInternalAsync(new CancellationSignal()).get();

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext,
                Executors.newSingleThreadExecutor());
        List<String> contactIds = searchHelper.getAllContactIdsAsync().get();
        assertThat(contactIds.size()).isEqualTo(FakeContactsProvider.NUM_EXISTED_CONTACTS);
    }

    private void clearAndWriteDataToTempFile(String data, Path dataFilePath) throws IOException {
        try (
                BufferedWriter writer = Files.newBufferedWriter(
                        dataFilePath,
                        StandardCharsets.UTF_8);
        ) {
            // This would override the previous line. Since we won't delete deprecated fields, we
            // don't need to clear the old content before doing this.
            writer.write(data);
            writer.flush();
        }
    }
}
