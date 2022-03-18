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
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.content.Context;
import android.test.ProviderTestCase2;
import android.util.ArraySet;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.contactsindexer.ContactsIndexerImpl.ContactsBatcher;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Objects;
import java.util.Set;

// TODO(b/203605504) this is a junit3 test but we should use junit4. Right now I can't make
//  ProviderTestRule work so we stick to ProviderTestCase2 for now.
public class ContactsIndexerImplTest extends ProviderTestCase2<FakeContactsProvider> {
    // TODO(b/203605504) we could just use AppSearchHelper.
    FakeAppSearchHelper mAppSearchHelper;

    public ContactsIndexerImplTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    private Pair<Long, Long> runDeltaUpdateOnContactsIndexerImpl(
            @NonNull ContactsIndexerImpl indexerImpl,
            long lastUpdatedTimestamp,
            long lastDeletedTimestamp) {
        Objects.requireNonNull(indexerImpl);
        Set<String> wantedContactIds = new ArraySet<>();
        Set<String> unWantedContactIds = new ArraySet<>();

        lastUpdatedTimestamp = ContactsProviderUtil.getUpdatedContactIds(mContext,
                lastUpdatedTimestamp, wantedContactIds);
        lastDeletedTimestamp = ContactsProviderUtil.getDeletedContactIds(mContext,
                lastDeletedTimestamp, unWantedContactIds);
        indexerImpl.updatePersonCorpusAsync(wantedContactIds, unWantedContactIds);

        return new Pair<>(lastUpdatedTimestamp, lastDeletedTimestamp);
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
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize);

        for (int i = 0; i < batchSize - 1; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build());
        }

        assertThat(mAppSearchHelper.mIndexedContacts).isEmpty();
    }

    public void testBatcher_autoFlush() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize);

        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build());
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
    }

    public void testBatcher_batchedContactClearedAfterFlush() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize);

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
                mAppSearchHelper);
        int totalNum = ContactsIndexerImpl.NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH + 1;
        Set<String> removedIds = new ArraySet<>(totalNum);
        for (int i = 0; i < totalNum; ++i) {
            removedIds.add(String.valueOf(i));
        }

        contactsIndexerImpl.batchRemoveContactsAsync(removedIds);

        assertThat(mAppSearchHelper.mRemovedIds).hasSize(removedIds.size());
        assertThat(new ArraySet<>(mAppSearchHelper.mRemovedIds)).isEqualTo(removedIds);
    }

    public void testContactsIndexerImpl_batchRemoveContacts_smallerThanBatchSize() {
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper);
        int totalNum = ContactsIndexerImpl.NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH - 1;
        Set<String> removedIds = new ArraySet<>(totalNum);
        for (int i = 0; i < totalNum; ++i) {
            removedIds.add(String.valueOf(i));
        }

        contactsIndexerImpl.batchRemoveContactsAsync(removedIds);

        assertThat(mAppSearchHelper.mRemovedIds).hasSize(removedIds.size());
        assertThat(new ArraySet<>(mAppSearchHelper.mRemovedIds)).isEqualTo(removedIds);
    }
}
