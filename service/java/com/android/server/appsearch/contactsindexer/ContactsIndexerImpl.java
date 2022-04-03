/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The class to sync the data from CP2 to AppSearch.
 *
 * <p>This class is NOT thread-safe.
 *
 * @hide
 */
public final class ContactsIndexerImpl {
    static final String TAG = "ContactsIndexerImpl";

    // TODO(b/203605504) have and read those flags in/from AppSearchConfig.
    static final int NUM_CONTACTS_PER_BATCH_FOR_CP2 = 100;
    static final int NUM_UPDATED_CONTACTS_PER_BATCH_FOR_APPSEARCH = 50;
    static final int NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH = 500;
    // Common columns needed for all kinds of mime types
    static final String[] COMMON_NEEDED_COLUMNS = {
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.PHOTO_THUMBNAIL_URI,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.PHONETIC_NAME,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.STARRED,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
    };
    // The order for the results returned from CP2.
    static final String ORDER_BY = ContactsContract.Data.CONTACT_ID
            // MUST sort by CONTACT_ID first for our iteration to work
            + ","
            // Whether this is the primary entry of its kind for the aggregate
            // contact it belongs to.
            + ContactsContract.Data.IS_SUPER_PRIMARY
            + " DESC"
            // Then rank by importance.
            + ","
            // Whether this is the primary entry of its kind for the raw contact it
            // belongs to.
            + ContactsContract.Data.IS_PRIMARY
            + " DESC"
            + ","
            + ContactsContract.Data.RAW_CONTACT_ID;

    private final Context mContext;
    private final ContactDataHandler mContactDataHandler;
    private final String[] mProjection;
    private final AppSearchHelper mAppSearchHelper;
    private final ContactsBatcher mBatcher;

    public ContactsIndexerImpl(@NonNull Context context, @NonNull AppSearchHelper appSearchHelper) {
        mContext = Objects.requireNonNull(context);
        mAppSearchHelper = Objects.requireNonNull(appSearchHelper);
        mContactDataHandler = new ContactDataHandler(mContext.getResources());

        Set<String> neededColumns = new ArraySet<>(Arrays.asList(COMMON_NEEDED_COLUMNS));
        neededColumns.addAll(mContactDataHandler.getNeededColumns());
        mProjection = neededColumns.toArray(new String[0]);
        mBatcher = new ContactsBatcher(mAppSearchHelper,
                NUM_UPDATED_CONTACTS_PER_BATCH_FOR_APPSEARCH);
    }

    /**
     * Syncs contacts in Person corpus in AppSearch, with the ones from CP2.
     *
     * <p>It deletes removed contacts, inserts newly-added ones, and updates existing ones in the
     * Person corpus in AppSearch.
     *
     * @param wantedContactIds ids for contacts to be updated.
     * @param unWantedIds      ids for contacts to be deleted.
     * @param updateStats      to hold the counters for the update.
     */
    public CompletableFuture<Void> updatePersonCorpusAsync(
            @NonNull List<String> wantedContactIds,
            @NonNull List<String> unWantedIds,
            @NonNull ContactsUpdateStats updateStats) {
        Objects.requireNonNull(wantedContactIds);
        Objects.requireNonNull(unWantedIds);
        Objects.requireNonNull(updateStats);

        return batchRemoveContactsAsync(unWantedIds, updateStats).exceptionally(t -> {
            // Since we update the timestamps no matter the update succeeds or fails, we can
            // always try to do the indexing. Updating lastDeltaUpdateTimestamps without doing
            // indexing seems odd.
            // So catch the exception here for deletion, and we can keep doing the indexing.
            Log.w(TAG, "Error occurs during batch delete:", t);
            return null;
        }).thenCompose(x -> batchUpdateContactsAsync(wantedContactIds, updateStats));
    }

    /**
     * Removes contacts in batches.
     *
     * @param updateStats to hold the counters for the remove.
     */
    @VisibleForTesting
    CompletableFuture<Void> batchRemoveContactsAsync(
            @NonNull final List<String> unWantedIds,
            @NonNull ContactsUpdateStats updateStats) {
        CompletableFuture<Void> batchRemoveFuture = CompletableFuture.completedFuture(null);
        int startIndex = 0;
        int unWantedSize = unWantedIds.size();
        while (startIndex < unWantedSize) {
            int endIndex = Math.min(startIndex + NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH,
                    unWantedSize);
            Collection<String> currentContactIds = unWantedIds.subList(startIndex, endIndex);
            batchRemoveFuture = batchRemoveFuture.thenCompose(
                    x -> mAppSearchHelper.removeContactsByIdAsync(currentContactIds, updateStats));

            startIndex = endIndex;
        }
        return batchRemoveFuture;
    }

    /**
     * Batch inserts newly-added contacts, and updates recently-updated contacts.
     *
     * @param updateStats to hold the counters for the update.
     */
    CompletableFuture<Void> batchUpdateContactsAsync(
            @NonNull final List<String> wantedContactIds,
            @NonNull ContactsUpdateStats updateStats) {
        int startIndex = 0;
        int wantedIdListSize = wantedContactIds.size();
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        //
        // Batch reading the contacts from CP2, and index the created documents to AppSearch
        //
        while (startIndex < wantedIdListSize) {
            int endIndex = Math.min(startIndex + NUM_CONTACTS_PER_BATCH_FOR_CP2,
                    wantedIdListSize);
            Collection<String> currentContactIds = wantedContactIds.subList(startIndex, endIndex);
            // Read NUM_CONTACTS_PER_BATCH contacts every time from CP2.
            // TODO(b/203605504) log the total latency for the query once we have the logger
            //  configured. Since a big "IN" might cause a slowdown. Also we can make
            //  NUM_CONTACTS_PER_BATCH configurable.
            String selection = ContactsContract.Data.CONTACT_ID + " IN (" + TextUtils.join(
                    /*delimiter=*/ ",", currentContactIds) + ")";
            startIndex = endIndex;
            try {
                // For our iteration work, we must sort the result by contact_id first.
                Cursor cursor = mContext.getContentResolver().query(
                        ContactsContract.Data.CONTENT_URI,
                        mProjection,
                        selection, /*selectionArgs=*/null,
                        ORDER_BY);
                if (cursor == null) {
                    updateStats.mUpdateStatuses.add(AppSearchResult.RESULT_INTERNAL_ERROR);
                    return CompletableFuture.failedFuture(
                            new IllegalStateException(
                                    "Cursor is returned as null while querying CP2."));
                } else {
                    future = future
                            .thenCompose(x -> {
                                CompletableFuture<Void> indexContactsFuture =
                                        indexContactsFromCursorAsync(cursor, updateStats);
                                cursor.close();
                                return indexContactsFuture;
                            });
                }
            } catch (RuntimeException e) {
                // The ContactsProvider sometimes propagates RuntimeExceptions to us
                // for when their database fails to open. Behave as if there was no
                // ContactsProvider, and flag that we were not successful.
                // TODO(b/203605504) log the error once we have the logger configured.
                Log.e(TAG, "ContentResolver.query threw an exception.", e);
                updateStats.mUpdateStatuses.add(AppSearchResult.RESULT_INTERNAL_ERROR);
                return CompletableFuture.failedFuture(e);
            }
        }

        return future;
    }

    /**
     * Reads through cursor, converts the contacts to AppSearch documents, and indexes the
     * documents into AppSearch.
     *
     * @param cursor      pointing to the contacts read from CP2.
     * @param updateStats to hold the counters for the update.
     */
    private CompletableFuture<Void> indexContactsFromCursorAsync(@NonNull Cursor cursor,
            @NonNull ContactsUpdateStats updateStats) {
        Objects.requireNonNull(cursor);

        try {
            int contactIdIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID);
            int lookupKeyIndex = cursor.getColumnIndex(ContactsContract.Data.LOOKUP_KEY);
            int thumbnailUriIndex = cursor.getColumnIndex(
                    ContactsContract.Data.PHOTO_THUMBNAIL_URI);
            int displayNameIndex = cursor.getColumnIndex(
                    ContactsContract.Data.DISPLAY_NAME_PRIMARY);
            int phoneticNameIndex = cursor.getColumnIndex(ContactsContract.Data.PHONETIC_NAME);
            int starredIndex = cursor.getColumnIndex(ContactsContract.Data.STARRED);
            long currentContactId = -1;
            Person.Builder personBuilder = null;
            PersonBuilderHelper personBuilderHelper = null;

            while (cursor.moveToNext()) {
                long contactId = cursor.getLong(contactIdIndex);
                if (contactId != currentContactId) {
                    // Either it is the very first row (currentContactId = -1), or a row for a new
                    // new contact_id.
                    if (currentContactId != -1) {
                        // It is the first row for a new contact_id. We can wrap up the
                        // ContactData for the previous contact_id.
                        mBatcher.add(personBuilderHelper.buildPerson(), updateStats);
                    }
                    // New set of builder and builderHelper for the new contact.
                    currentContactId = contactId;
                    String displayName = getStringFromCursor(cursor, displayNameIndex);
                    if (displayName == null) {
                        // For now, we don't abandon the data if displayName is missing. In the
                        // schema the name is required for building a person. It might look bad
                        // if there are contacts in CP2, but not in AppSearch, even though the
                        // name is missing.
                        displayName = "";
                    }
                    personBuilder = new Person.Builder(AppSearchHelper.NAMESPACE_NAME,
                            String.valueOf(contactId), displayName);
                    String imageUri = getStringFromCursor(cursor, thumbnailUriIndex);
                    String lookupKey = getStringFromCursor(cursor, lookupKeyIndex);
                    boolean starred = starredIndex != -1 && cursor.getInt(starredIndex) != 0;
                    Uri lookupUri = lookupKey != null ?
                            ContactsContract.Contacts.getLookupUri(currentContactId, lookupKey)
                            : null;
                    personBuilder.setIsImportant(starred);
                    if (lookupUri != null) {
                        personBuilder.setExternalUri(lookupUri);
                    }
                    if (imageUri != null) {
                        personBuilder.setImageUri(Uri.parse(imageUri));
                    }
                    personBuilderHelper = new PersonBuilderHelper(personBuilder);
                }
                if (personBuilderHelper != null) {
                    mContactDataHandler.convertCursorToPerson(cursor, personBuilderHelper);
                }
            }

            if (cursor.isAfterLast() && currentContactId != -1) {
                // The ContactData for the last contact has not been handled yet. So we need to
                // build and index it.
                if (personBuilderHelper != null) {
                    mBatcher.add(personBuilderHelper.buildPerson(), updateStats);
                }
            }
        } catch (Throwable t) {
            updateStats.mUpdateStatuses.add(AppSearchResult.RESULT_UNKNOWN_ERROR);
            // TODO(b/203605504) see if we could catch more specific exceptions/errors.
            Log.e(TAG, "Error while indexing documents from the cursor", t);
            return CompletableFuture.failedFuture(t);
        }

        // finally force flush all the remaining batched contacts.
        return mBatcher.flushAsync(updateStats);
    }

    /**
     * Helper method to read the value from a {@link Cursor} for {@code index}.
     *
     * @return A string value, or {@code null} if the value is missing, or {@code index} is -1.
     */
    @Nullable
    private static String getStringFromCursor(@NonNull Cursor cursor, int index) {
        Objects.requireNonNull(cursor);
        if (index != -1) {
            return cursor.getString(index);
        }
        return null;
    }

    /**
     * Class for helping batching the {@link Person} to be indexed.
     *
     * <p>This class is thread unsafe and all its methods must be called from the same thread.
     *
     */
    static class ContactsBatcher {
        private final List<Person> mBatchedContacts;
        private final int mBatchSize;
        private final AppSearchHelper mAppSearchHelper;

        private CompletableFuture<Void> mIndexContactsCompositeFuture =
                CompletableFuture.completedFuture(null);

        ContactsBatcher(@NonNull AppSearchHelper appSearchHelper, int batchSize) {
            mAppSearchHelper = Objects.requireNonNull(appSearchHelper);
            mBatchSize = batchSize;
            mBatchedContacts = new ArrayList<>(mBatchSize);
        }

        @VisibleForTesting
        int numOfBatchedContacts() {
            return mBatchedContacts.size();
        }

        public void add(@NonNull Person person, @NonNull ContactsUpdateStats updateStats) {
            Objects.requireNonNull(person);
            Objects.requireNonNull(updateStats);

            // TODO(b/203605504) Right now we always index the documents. Ideally we should just
            //  index the ones having changes in the fields we are interested at. We could save a
            //  fingerprint in the AppSearch document, or in a temporary data store. Whenever there
            //  is an update for a doc, we could get the fingerprint from AppSearch, or the
            //  temporary data store, and do the comparison.
            mBatchedContacts.add(person);
            if (mBatchedContacts.size() >= mBatchSize) {
                indexBatchedContactsAsync(updateStats);
            }
        }

        public CompletableFuture<Void> flushAsync(@NonNull ContactsUpdateStats updateStats) {
            if (!mBatchedContacts.isEmpty()) {
                indexBatchedContactsAsync(updateStats);
            }
            CompletableFuture<Void> flushFuture = mIndexContactsCompositeFuture;
            mIndexContactsCompositeFuture = CompletableFuture.completedFuture(null);
            return flushFuture;
        }

        /**
         * Indexes the batched contacts into AppSearch and extends the chain of futures in {@link
         * #mIndexContactsCompositeFuture}.
         *
         * @param updateStats to hold the counters for the update.
         */
        private void indexBatchedContactsAsync(@NonNull ContactsUpdateStats updateStats) {
            // Shallow copy before passing it to chained future stages.
            // mBatchedContacts is being cleared after passing them to the async completion
            // stage, and that leads to a race condition.
            List<Person> contactsToIndex = new ArrayList<>(mBatchedContacts);
            mBatchedContacts.clear();
            mIndexContactsCompositeFuture = mIndexContactsCompositeFuture
                    .thenCompose(
                            x -> mAppSearchHelper.indexContactsAsync(contactsToIndex, updateStats));
        }
    }
}
