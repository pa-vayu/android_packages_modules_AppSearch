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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.util.ArraySet;

import com.android.server.appsearch.stats.AppSearchStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * The class to hold stats for DeltaUpdate or FullUpdate.
 *
 * <p>This will be used to populate
 * {@link AppSearchStatsLog#CONTACTS_INDEXER_UPDATE_STATS_REPORTED}.
 *
 * <p>This class is not thread-safe.
 *
 * @hide
 */
public class ContactsUpdateStats {
    @IntDef(
            value = {
                    UNKNOWN_UPDATE_TYPE,
                    DELTA_UPDATE,
                    FULL_UPDATE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UpdateType {
    }

    public static final int UNKNOWN_UPDATE_TYPE =
            AppSearchStatsLog.CONTACTS_INDEXER_UPDATE_STATS_REPORTED__UPDATE_TYPE__UNKNOWN;
    /** Incremental update reacting to CP2 change notifications. */
    public static final int DELTA_UPDATE =
            AppSearchStatsLog.CONTACTS_INDEXER_UPDATE_STATS_REPORTED__UPDATE_TYPE__DELTA;
    /** Complete update to bring AppSearch in sync with CP2. */
    public static final int FULL_UPDATE =
            AppSearchStatsLog.CONTACTS_INDEXER_UPDATE_STATS_REPORTED__UPDATE_TYPE__FULL;

    @UpdateType
    int mUpdateType = UNKNOWN_UPDATE_TYPE;
    // Status for updates.
    // In case of success, we will just have one success status stored.
    // In case of Error,  we store the unique error codes during the update.
    @AppSearchResult.ResultCode
    Set<Integer> mUpdateStatuses = new ArraySet<>();
    // Status for deletions.
    // In case of success, we will just have one success status stored.
    // In case of Error,  we store the unique error codes during the deletion.
    @AppSearchResult.ResultCode
    Set<Integer> mDeleteStatuses = new ArraySet<>();

    long mUpdateStartTimeMillis;

    // # of contacts failed to be inserted or updated.
    int mContactsUpdateFailedCount;
    // # of contacts failed to be deleted.
    int mContactsDeleteFailedCount;

    // # of new contacts inserted
    int mContactsInsertedCount;
    // # of contacts skipped due to no significant change
    int mContactsSkippedCount;
    // # of existing contacts updated
    int mContactsUpdateCount;

    // # of contacts deleted.
    int mContactsDeleteCount;

    public void clear() {
        mUpdateType = UNKNOWN_UPDATE_TYPE;
        mUpdateStatuses.clear();
        mDeleteStatuses.clear();
        mUpdateStartTimeMillis = 0;
        mContactsUpdateFailedCount = 0;
        mContactsDeleteFailedCount = 0;
        mContactsInsertedCount = 0;
        mContactsSkippedCount = 0;
        mContactsUpdateCount = 0;
        mContactsDeleteCount = 0;
    }

    @NonNull
    public String toString() {
        return "UpdateType: " + mUpdateType
                + ", UpdateStatus: " + mUpdateStatuses.toString()
                + ", DeleteStatus: " + mDeleteStatuses.toString()
                + ", UpdateStartTimeMillis: " + mUpdateStartTimeMillis
                + ", UpdateFailedCount: " + mContactsUpdateFailedCount
                + ", deleteFailedCount: " + mContactsDeleteFailedCount
                + ", ContactsInsertedCount: " + mContactsInsertedCount
                + ", ContactsSkippedCount: " + mContactsSkippedCount
                + ", ContactsUpdateCount: " + mContactsUpdateCount
                + ", ContactsDeleteCount: " + mContactsDeleteCount;
    }
}