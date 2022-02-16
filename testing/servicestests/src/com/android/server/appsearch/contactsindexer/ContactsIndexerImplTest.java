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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.media.audio.common.Int;
import android.provider.ContactsContract;
import android.test.ProviderTestCase2;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.appsearch.contactsindexer.FakeContactsProvider;
import com.android.server.appsearch.contactsindexer.FakeAppSearchHelper;
import com.android.server.appsearch.contactsindexer.TestUtils;
import com.android.server.appsearch.contactsindexer.ContactsIndexerImpl.ContactsBatcher;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// TODO(b/203605504) this is a junit3 tests but we should use junit4. Right now I can't make
//  ProviderTestRule work so we stick to ProviderTestCase2 for now.
public class ContactsIndexerImplTest extends ProviderTestCase2<FakeContactsProvider> {
    FakeAppSearchHelper mAppSearchHelper;

    public ContactsIndexerImplTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    public void setUp() throws Exception {
        super.setUp();
        mContext = mock(Context.class);
        mAppSearchHelper = new FakeAppSearchHelper(mContext);

        doReturn(getMockContentResolver()).when(mContext).getContentResolver();
        doReturn(ApplicationProvider.getApplicationContext().getResources()).when(
                mContext).getResources();
    }

    public void testBatcher_noFlushBeforeReachingLimit() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize, Runnable::run);

        for (int i = 0; i < batchSize - 1; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build());
        }

        assertThat(mAppSearchHelper.mIndexedContacts).isEmpty();
    }

    public void testBatcher_autoFlush() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize, Runnable::run);

        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build());
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
    }

    public void testBatcher_batchedContactClearedAfterFlush() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize, Runnable::run);

        // First batch
        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build());
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
        assertThat(batcher.numOfBatchedContacts()).isEqualTo(0);


        mAppSearchHelper.mIndexedContacts.clear();
        // Second batch. Make sure the first batch has been cleared.
        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build());
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
        assertThat(batcher.numOfBatchedContacts()).isEqualTo(0);
    }

    public void testContactsIndexerImpl_batchRemoveContacts_largerThanBatchSize() {
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper, Runnable::run);
        int totalNum = ContactsIndexerImpl.NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH + 1;
        Set<String> removedIds = new ArraySet<>(totalNum);
        for (int i = 0; i < totalNum; ++i) {
            removedIds.add(String.valueOf(i));
        }

        contactsIndexerImpl.batchRemoveUnwantedContact(removedIds);

        assertThat(mAppSearchHelper.mRemovedIds).hasSize(removedIds.size());
        assertThat(new ArraySet<>(mAppSearchHelper.mRemovedIds)).isEqualTo(removedIds);
    }

    public void testContactsIndexerImpl_batchRemoveContacts_smallerThanBatchSize() {
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper, Runnable::run);
        int totalNum = ContactsIndexerImpl.NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH - 1;
        Set<String> removedIds = new ArraySet<>(totalNum);
        for (int i = 0; i < totalNum; ++i) {
            removedIds.add(String.valueOf(i));
        }

        contactsIndexerImpl.batchRemoveUnwantedContact(removedIds);

        assertThat(mAppSearchHelper.mRemovedIds).hasSize(removedIds.size());
        assertThat(new ArraySet<>(mAppSearchHelper.mRemovedIds)).isEqualTo(removedIds);
    }

    // Run update with 0 for both lastUpdatedTime and lastDeletedTime, which results in fetching
    // all updated and deleted contacts available from CP2.
    public void testContactsIndexerImpl_deltaUpdate_firstTime() {
        // In FakeContactsIndexer, we have contacts array [1, 2, ... NUM_TOTAL_CONTACTS], among
        // them, [1, NUM_EXISTED_CONTACTS] is for updated contacts, and [NUM_EXISTED_CONTACTS +
        // 1, NUM_TOTAL_CONTACTS] is for deleted contacts.
        // And the number here is same for both contact_id, and last update/delete timestamp.
        // So, if we have [1, 50] for updated, and [51, 100] for deleted, and lastUpdatedTime is
        // 40, [41, 50] needs to be updated. Likewise, if lastDeletedTime is 55, we would delete
        // [56, 100
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper, Runnable::run);
        long lastUpdatedTimestamp = 0;
        long lastDeletedTimestamp = 0;
        int expectedUpdatedContactsFirstId = 1;
        int expectedUpdatedContactsLastId = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        int expectedUpdatedContactsNum =
                expectedUpdatedContactsLastId - expectedUpdatedContactsFirstId + 1;
        int expectedDeletedContactsFirstId = FakeContactsProvider.NUM_EXISTED_CONTACTS + 1;
        int expectedDeletedContactsLastId = FakeContactsProvider.NUM_TOTAL_CONTACTS;
        long expectedNewLastUpdatedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastDeletedTimestamp = FakeContactsProvider.NUM_TOTAL_CONTACTS;

        Pair<Long, Long> result = contactsIndexerImpl.doDeltaUpdate(lastUpdatedTimestamp,
                lastDeletedTimestamp);

        // Check return result
        assertThat(result.first).isEqualTo(expectedNewLastUpdatedTimestamp);
        assertThat(result.second).isEqualTo(expectedNewLastDeletedTimestamp);

        // Check updated contacts
        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(expectedUpdatedContactsNum);
        for (int id = expectedUpdatedContactsFirstId; id <= expectedUpdatedContactsLastId; ++id) {
            TestUtils.assertEquals(
                    mAppSearchHelper.mIndexedContacts.get(id - expectedUpdatedContactsFirstId),
                    getProvider().getContactData(id));
        }

        // Check removed contacts
        List<String> expectedDeletedIds = new ArrayList<>();
        for (int i = expectedDeletedContactsFirstId;
                i <= expectedDeletedContactsLastId; ++i) {
            expectedDeletedIds.add(String.valueOf(i));
        }
        assertThat(mAppSearchHelper.mRemovedIds).isEqualTo(expectedDeletedIds);
    }

    public void testContactsIndexerImpl_deltaUpdate() {
        // In FakeContactsIndexer, we have contacts array [1, 2, ... NUM_TOTAL_CONTACTS], among
        // them, [1, NUM_EXISTED_CONTACTS] is for updated contacts, and [NUM_EXISTED_CONTACTS +
        // 1, NUM_TOTAL_CONTACTS] is for deleted contacts.
        // And the number here is same for both contact_id, and last update/delete timestamp.
        // So, if we have [1, 50] for updated, and [51, 100] for deleted, and lastUpdatedTime is
        // 40, [41, 50] needs to be updated. Likewise, if lastDeletedTime is 55, we would delete
        // [56, 100]
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper, Runnable::run);
        long lastUpdatedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS / 2;
        long lastDeletedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS +
                (FakeContactsProvider.NUM_TOTAL_CONTACTS
                        - FakeContactsProvider.NUM_EXISTED_CONTACTS) / 2;
        int expectedUpdatedContactsFirstId = (int) (lastUpdatedTimestamp + 1);
        int expectedUpdatedContactsLastId = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        int expectedUpdatedContactsNum =
                expectedUpdatedContactsLastId - expectedUpdatedContactsFirstId + 1;
        int expectedDeletedContactsFirstId = (int) (lastDeletedTimestamp + 1);
        int expectedDeletedContactsLastId = FakeContactsProvider.NUM_TOTAL_CONTACTS;
        long expectedNewLastUpdatedTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS;
        long expectedNewLastDeletedTimestamp = FakeContactsProvider.NUM_TOTAL_CONTACTS;


        Pair<Long, Long> result = contactsIndexerImpl.doDeltaUpdate(lastUpdatedTimestamp,
                lastDeletedTimestamp);

        // Check return result
        assertThat(result.first).isEqualTo(expectedNewLastUpdatedTimestamp);
        assertThat(result.second).isEqualTo(expectedNewLastDeletedTimestamp);

        // Check updated contacts
        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(expectedUpdatedContactsNum);
        for (int id = expectedUpdatedContactsFirstId; id <= expectedUpdatedContactsLastId; ++id) {
            TestUtils.assertEquals(
                    mAppSearchHelper.mIndexedContacts.get(id - expectedUpdatedContactsFirstId),
                    getProvider().getContactData(id));
        }

        // Check removed contacts
        List<String> expectedDeletedIds = new ArrayList<>();
        for (int i = expectedDeletedContactsFirstId;
                i <= expectedDeletedContactsLastId; ++i) {
            expectedDeletedIds.add(String.valueOf(i));
        }
        assertThat(mAppSearchHelper.mRemovedIds).isEqualTo(expectedDeletedIds);
    }
}
