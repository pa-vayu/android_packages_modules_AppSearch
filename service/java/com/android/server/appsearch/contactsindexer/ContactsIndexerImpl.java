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

    @VisibleForTesting
    void batchRemoveUnwantedContact(@NonNull Set<String> unWantedIds) {
        Objects.requireNonNull(unWantedIds);

        int startIndex = 0;
        final List<String> unWantedIdList = new ArrayList<>(unWantedIds);
        int unWantedSize = unWantedIdList.size();
        while (startIndex < unWantedSize) {
            int endIndex = Math.min(startIndex + NUM_DELETED_CONTACTS_PER_BATCH_FOR_APPSEARCH,
                    unWantedSize);
            Collection<String> currentContactIds = unWantedIdList.subList(startIndex, endIndex);
            mAppSearchHelper.removeContactsByIdAsync(currentContactIds);
            startIndex = endIndex;
        }
    }

    /**
     * Updates Person corpus in AppSearch.
     *
     * @param wantedContactIds   ids for contacts to be updated.
     * @param unWantedContactIds ids for contacts to be deleted.
     */
    void updatePersonCorpus(@NonNull Set<String> wantedContactIds,
            @NonNull Set<String> unWantedContactIds) {
        Objects.requireNonNull(wantedContactIds);
        Objects.requireNonNull(unWantedContactIds);

        // batch removing unwanted contacts first
        batchRemoveUnwantedContact(unWantedContactIds);

        int startIndex = 0;
        final List<String> wantedIdList = new ArrayList<>(wantedContactIds);
        int wantedIdListSize = wantedIdList.size();
        //
        // Batch reading the contacts from CP2, and index the created documents to AppSearch
        //
        while (startIndex < wantedIdListSize) {
            int endIndex = Math.min(startIndex + NUM_CONTACTS_PER_BATCH_FOR_CP2,
                    wantedIdListSize);
            Collection<String> currentContactIds = wantedIdList.subList(startIndex, endIndex);
            // Read NUM_CONTACTS_PER_BATCH contacts every time from CP2.
            // TODO(b/203605504) log the total latency for the query once we have the logger
            //  configured. Since a big "IN" might cause a slowdown. Also we can make
            //  NUM_CONTACTS_PER_BATCH configurable.
            String selection = ContactsContract.Data.CONTACT_ID + " IN (" + TextUtils.join(
                    /*delimiter=*/ ",", currentContactIds) + ")";
            startIndex = endIndex;
            Cursor cursor = null;
            try {
                // For our iteration work, we must sort the result by contact_id first.
                cursor = mContext.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                        mProjection,
                        selection, /*selectionArgs=*/null,
                        ORDER_BY);
                if (cursor == null) {
                    startIndex = wantedIdListSize; // Ensures we don't retry
                } else {
                    indexContactsFromCursorToAppSearch(cursor);
                }
            } catch (RuntimeException e) {
                // The ContactsProvider sometimes propagates RuntimeExceptions to us
                // for when their database fails to open. Behave as if there was no
                // ContactsProvider, and flag that we were not successful.
                // TODO(b/203605504) log the error once we have the logger configured.
                Log.e(TAG, "ContentResolver.query threw an exception.", e);
                cursor = null;
                startIndex = wantedIdListSize; // Ensures we don't retry
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    /**
     * Reads through cursor, converts the contacts to AppSearch documents, and indexes the
     * documents into AppSearch.
     *
     * @param cursor pointing to the contacts read from CP2.
     */
    private void indexContactsFromCursorToAppSearch(@NonNull Cursor cursor) {
        Objects.requireNonNull(cursor);

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
        try {
            while (cursor != null && cursor.moveToNext()) {
                long contactId = cursor.getLong(contactIdIndex);
                if (contactId != currentContactId) {
                    // Either it is the very first row (currentContactId = -1), or a row for a new
                    // new contact_id.
                    if (currentContactId != -1) {
                        // It is the first row for a new contact_id. We can wrap up the
                        // ContactData for the previous contact_id.
                        mBatcher.add(personBuilderHelper.buildPerson());
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
                    String phoneticName = getStringFromCursor(cursor, phoneticNameIndex);
                    String lookupKey = getStringFromCursor(cursor, lookupKeyIndex);
                    boolean starred = starredIndex != -1 ?
                            cursor.getInt(starredIndex) != 0 : false;
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
                    if (phoneticName != null) {
                        personBuilder.addAdditionalName(phoneticName);
                    }

                    personBuilderHelper = new PersonBuilderHelper(personBuilder);
                }
                if (personBuilderHelper != null) {
                    mContactDataHandler.convertCursorToPerson(cursor, personBuilderHelper);
                }
            }
        } catch (Throwable t) {
            // TODO(b/203605504) see if we could catch more specific exceptions/errors.
            Log.e(TAG, "Error while indexing documents from the cursor", t);
        }

        if (cursor.isAfterLast() && currentContactId != -1) {
            // The ContactData for the last contact has not been handled yet. So we need to
            // build and index it.
            if (personBuilderHelper != null) {
                mBatcher.add(personBuilderHelper.buildPerson());
            }
        }

        // finally force flush all the remaining batched contacts.
        mBatcher.flush();
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
     */
    static class ContactsBatcher {
        private final List<Person> mBatchedContacts;
        private final int mBatchSize;
        private final AppSearchHelper mAppSearchHelper;

        ContactsBatcher(@NonNull AppSearchHelper appSearchHelper, int batchSize) {
            mAppSearchHelper = Objects.requireNonNull(appSearchHelper);
            mBatchSize = batchSize;
            mBatchedContacts = new ArrayList<>(mBatchSize);
        }

        @VisibleForTesting
        int numOfBatchedContacts() {
            return mBatchedContacts.size();
        }

        public void add(@NonNull Person person) {
            Objects.requireNonNull(person);

            // TODO(b/203605504) Right now we always index the documents. Ideally we should just
            //  index the ones having changes in the fields we are interested at. We could save a
            //  fingerprint in the AppSearch document, or in a temporary data store. Whenever there
            //  is an update for a doc, we could get the fingerprint from AppSearch, or the
            //  temporary data store, and do the comparison.
            mBatchedContacts.add(person);
            if (mBatchedContacts.size() >= mBatchSize) {
                flush();
            }
        }

        public void flush() {
            if (mBatchedContacts.isEmpty()) {
                return;
            }

            mAppSearchHelper.indexContactsAsync(mBatchedContacts);
            mBatchedContacts.clear();
        }
    }
}
