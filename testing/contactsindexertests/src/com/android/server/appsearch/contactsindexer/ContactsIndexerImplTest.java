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
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.content.ContentResolver;
import android.content.Context;
import android.test.ProviderTestCase2;
import android.content.ContextWrapper;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.contactsindexer.ContactsIndexerImpl.ContactsBatcher;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class ContactsIndexerImplTest extends ProviderTestCase2<FakeContactsProvider> {
    // TODO(b/203605504) we could just use AppSearchHelper.
    private FakeAppSearchHelper mAppSearchHelper;
    private ContactsUpdateStats mUpdateStats;

    public ContactsIndexerImplTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        Context context = ApplicationProvider.getApplicationContext();
        mContext = new ContextWrapper(context) {
            @Override
            public ContentResolver getContentResolver() {
                return getMockContentResolver();
            }
        };
        mAppSearchHelper = new FakeAppSearchHelper(mContext);
        mUpdateStats = new ContactsUpdateStats();
    }

    @Override
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.DATABASE_NAME).build();
        AppSearchSessionShim db = AppSearchSessionShimImpl.createSearchSessionAsync(
                searchContext).get();
        SetSchemaRequest setSchemaRequest = new SetSchemaRequest.Builder()
                .setForceOverride(true).build();
        db.setSchemaAsync(setSchemaRequest).get();
    }

    /**
     * Helper method to run a delta update in the test.
     *
     * <p> Get is called on the futures to make this helper method synchronous.
     *
     * @param lastUpdatedTimestamp used as the "since" filter for updating the contacts.
     * @param lastDeletedTimestamp used as the "since" filter for deleting the contacts.
     * @return new (lastUpdatedTimestamp, lastDeletedTimestamp) pair after the update and deletion.
     */
    private Pair<Long, Long> runDeltaUpdateOnContactsIndexerImpl(
            @NonNull ContactsIndexerImpl indexerImpl,
            long lastUpdatedTimestamp,
            long lastDeletedTimestamp,
            @NonNull ContactsUpdateStats updateStats)
            throws ExecutionException, InterruptedException {
        Objects.requireNonNull(indexerImpl);
        Objects.requireNonNull(updateStats);
        List<String> wantedContactIds = new ArrayList<>();
        List<String> unWantedContactIds = new ArrayList<>();

        lastUpdatedTimestamp = ContactsProviderUtil.getUpdatedContactIds(mContext,
                lastUpdatedTimestamp, wantedContactIds, /*stats=*/ null);
        lastDeletedTimestamp = ContactsProviderUtil.getDeletedContactIds(mContext,
                lastDeletedTimestamp, unWantedContactIds, /*stats=*/ null);
        indexerImpl.updatePersonCorpusAsync(wantedContactIds, unWantedContactIds,
                updateStats).get();

        return new Pair<>(lastUpdatedTimestamp, lastDeletedTimestamp);
    }

    public void testBatcher_noFlushBeforeReachingLimit() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize);

        for (int i = 0; i < batchSize - 1; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build(), mUpdateStats);
        }

        assertThat(mAppSearchHelper.mIndexedContacts).isEmpty();
    }

    public void testBatcher_autoFlush() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize);

        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build(), mUpdateStats);
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
    }

    public void testBatcher_batchedContactClearedAfterFlush() {
        int batchSize = 5;
        ContactsBatcher batcher = new ContactsBatcher(mAppSearchHelper, batchSize);

        // First batch
        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build(), mUpdateStats);
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
        assertThat(batcher.numOfBatchedContacts()).isEqualTo(0);


        mAppSearchHelper.mIndexedContacts.clear();
        // Second batch. Make sure the first batch has been cleared.
        for (int i = 0; i < batchSize; ++i) {
            batcher.add(new Person.Builder("namespace", /*id=*/ String.valueOf(i), /*name=*/
                    String.valueOf(i)).build(), mUpdateStats);
        }

        assertThat(mAppSearchHelper.mIndexedContacts).hasSize(batchSize);
        assertThat(batcher.numOfBatchedContacts()).isEqualTo(0);
    }

    public void testContactsIndexerImpl_batchRemoveContacts_largerThanBatchSize() throws Exception {
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper);
        int totalNum = ContactsIndexerImpl.NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH + 1;
        List<String> removedIds = new ArrayList<>(totalNum);
        for (int i = 0; i < totalNum; ++i) {
            removedIds.add(String.valueOf(i));
        }

        contactsIndexerImpl.batchRemoveContactsAsync(removedIds, mUpdateStats).get();

        assertThat(mAppSearchHelper.mRemovedIds).hasSize(removedIds.size());
        assertThat(mAppSearchHelper.mRemovedIds).isEqualTo(removedIds);
    }

    public void testContactsIndexerImpl_batchRemoveContacts_smallerThanBatchSize()
            throws Exception {
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(mContext,
                mAppSearchHelper);
        int totalNum = ContactsIndexerImpl.NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH - 1;
        List<String> removedIds = new ArrayList<>(totalNum);
        for (int i = 0; i < totalNum; ++i) {
            removedIds.add(String.valueOf(i));
        }

        contactsIndexerImpl.batchRemoveContactsAsync(removedIds, mUpdateStats).get();

        assertThat(mAppSearchHelper.mRemovedIds).hasSize(removedIds.size());
        assertThat(mAppSearchHelper.mRemovedIds).isEqualTo(removedIds);
    }
}
