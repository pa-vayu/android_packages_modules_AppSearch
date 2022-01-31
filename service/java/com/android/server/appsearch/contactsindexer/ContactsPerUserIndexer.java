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
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.AppSearchUserInstanceManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Contacts Indexer for a single user.
 *
 * <p>It reads the updated/newly-inserted/deleted contacts from CP2, and sync the changes into
 * AppSearch.
 */
public final class ContactsPerUserIndexer {
    static final String TAG = "ContactsPerUserIndexer";
    static final String CONTACTS_INDEXER_STATE = "contacts_indexer_state";

    private final Context mContext;
    private PersistedData mPersistedData;
    // Used for batching/throttling the contact change notification so we won't schedule too many
    // delta updates.
    private final AtomicBoolean mUpdateScheduled;
    private final Object mLock = new Object();

    /**
     * Single executor to make sure there is only one active sync for this {@link
     * ContactsPerUserIndexer}
     */
    private final ScheduledThreadPoolExecutor mSingleScheduledExecutor;
    /**
     * Class to hold the persisted data.
     */
    public final static class PersistedData {
        static final String DELIMITER = ",";
        private static final int NUMBER_OF_SERIALIZED_FIELDS = 3;

        // Fields need to be serialized.
        private static final int PERSISTED_DATA_VERSION = 1;
        long mLastDeltaUpdateTimestampMillis = 0;
        long mLastDeltaDeleteTimestampMillis = 0;

        /**
         * Serializes the fields into a {@link String}.
         *
         * <p>Format would be:
         * VERSION,mLastDeltaUpdatedTimestampMillis,mLastDeltaDeleteTimestampMillis
         */
        @Override
        @NonNull
        public String toString() {
            return PERSISTED_DATA_VERSION + DELIMITER
                    + mLastDeltaUpdateTimestampMillis + DELIMITER
                    + mLastDeltaDeleteTimestampMillis;
        }

        /**
         * Reads the fields from the {@link String}.
         *
         * @param serializedPersistedData String in expected format.
         * @throws IllegalArgumentException If the serialized string is not in expected format.
         */
        public void fromString(@NonNull String serializedPersistedData)
                throws IllegalArgumentException {
            String[] fields = serializedPersistedData.split(DELIMITER);
            if (fields.length < NUMBER_OF_SERIALIZED_FIELDS) {
                throw new IllegalArgumentException(
                        "At least " + NUMBER_OF_SERIALIZED_FIELDS + " of fields is expected in "
                                + serializedPersistedData);
            }

            // Print the information about version number. It is only for logging purpose and
            // future usage. Right now values should still be valid even if the version doesn't
            // match.
            // To keep this assumption true, we would just keep appending the fields.
            // The version will be reset to the one matching the current version of
            // ContactsIndexer during the next time data is persisted.
            try {
                int versionNum = Integer.parseInt(fields[0]);
                if (versionNum < PERSISTED_DATA_VERSION) {
                    Log.i(TAG, "Read a past version of persisted data.");
                } else if (versionNum > PERSISTED_DATA_VERSION) {
                    Log.i(TAG, "Read a future version of persisted data.");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Failed to parse the version number for " + serializedPersistedData, e);
            }

            try {
                mLastDeltaUpdateTimestampMillis = Long.parseLong(fields[1]);
                mLastDeltaDeleteTimestampMillis = Long.parseLong(fields[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Failed to parse the timestamps", e);
            }
        }

        /** Resets all members. */
        public void reset() {
            mLastDeltaUpdateTimestampMillis = 0;
            mLastDeltaDeleteTimestampMillis = 0;
        }
    }

    /**
     * Constructs a {@link ContactsPerUserIndexer}.
     *
     * @param context              Context object passed from
     *                             {@link com.android.server.appsearch.AppSearchManagerService}
     */
    public ContactsPerUserIndexer(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mUpdateScheduled = new AtomicBoolean(/*initialValue=*/ false);
        mSingleScheduledExecutor = new ScheduledThreadPoolExecutor(/*corePoolSize=*/ 1);
        mSingleScheduledExecutor.setMaximumPoolSize(1);
        mSingleScheduledExecutor.setKeepAliveTime(60L, TimeUnit.SECONDS);
        mSingleScheduledExecutor.allowCoreThreadTimeOut(true);
        mSingleScheduledExecutor.setRemoveOnCancelPolicy(true);
    }

    /** Initializes this {@link ContactsPerUserIndexer}. */
    public void initialize() {
        synchronized (mLock) {
            mPersistedData = loadPersistedDataLocked(
                    new File(AppSearchUserInstanceManager.getAppSearchDir(
                            mContext.getUser()),
                            CONTACTS_INDEXER_STATE).toPath());
        }
    }

    /**
     * Does the delta/instant update to sync the contacts from CP2 to AppSearch.
     *
     * <p>{@code mUpdateScheduled} is being used to avoid scheduling any update BEFORE an active
     * update is being processed.
     *
     * <p>{@code SINGLE_SCHEDULED_EXECUTOR} is being used to make sure there is one and only one
     * running update, and at most one pending update is queued while the current active update is
     * running.
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    // TODO(b/203605504) right now we capture and report the exceptions inside the scheduled task.
    //  We should revisit this once we have the end-to-end change for Contacts Indexer to see if
    //  we can remove this suppress.
    public void doDeltaUpdate(int delaySec) {
        // We want to batch (trigger only one update) on all Contact Updates for the associated
        // user within the time window(delaySec). And we hope the query to CP2 "Give me all the
        // contacts from timestamp T" would catch all the unhandled contact change notifications.
        if (!mUpdateScheduled.getAndSet(true)) {
            mSingleScheduledExecutor.schedule(() -> {
                try {
                    // TODO(b/203605504) once we have the call to do the
                    //  update, make sure it is reset before doing the update to AppSearch, but
                    //  after we get the contact changes from CP2. This way, we won't miss any
                    //  notification in case the update takes a while.
                    mUpdateScheduled.set(false);

                    // TODO(b/203605504) Simply update and persist those two timestamps for now.
                    //  1) Querying CP2 and updating AppSearch will be added in the followup
                    //  changes.
                    //  2) Reset mUpdateScheduled BEFORE doing the update to allow one pending
                    //  update queued, so we won't miss any notification while doing the update
                    //  (It may take some time).
                    long lastDeltaUpdateTimestampMillis = System.currentTimeMillis();
                    long lastDeltaDeleteTimestampMillis = System.currentTimeMillis();
                    synchronized (mLock) {
                        persistTimestampsLocked(
                                new File(AppSearchUserInstanceManager.getAppSearchDir(
                                        mContext.getUser()),
                                        CONTACTS_INDEXER_STATE).toPath(),
                                lastDeltaUpdateTimestampMillis,
                                lastDeltaDeleteTimestampMillis);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during doDeltaUpdate", e);
                }
            }, delaySec, TimeUnit.SECONDS);
        }
    }

    /** Loads the persisted data from disk. */
    @VisibleForTesting
    @GuardedBy("mLock")
    @NonNull
    PersistedData loadPersistedDataLocked(@NonNull Path path) {
        Objects.requireNonNull(path);
        PersistedData persistedData = null;
        boolean isLoadingDataFailed = false;
        try (
                BufferedReader reader = Files.newBufferedReader(
                        path,
                        StandardCharsets.UTF_8);
        ) {
            // right now we store everything in one line. So we just need to read the first line.
            String content = reader.readLine();
            persistedData = new PersistedData();
            persistedData.fromString(content);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load persisted data from disk.", e);
            isLoadingDataFailed = true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to parse the loaded data.", e);
            isLoadingDataFailed = true;
        } finally {
            if (persistedData == null) {
                // Somehow we can't load the persisted data from disk. It can happen if there is
                // some I/O error, or a rollback happens, so an older version of ContactsIndexer
                // would try to read a new version of persisted file. In this case, it is OK for us
                // to reset those persisted data, and do a full update like what we would
                // do for the very first time.
                persistedData = new PersistedData();
                // TODO(b/203605504) do a full update and set both timestamp be currentTime.
            } else if (isLoadingDataFailed) {
                // Resets all the values here in case there are some values set from corrupted data.
                persistedData.reset();
            }
        }

        Log.d(TAG, "Load timestamps from disk: update: "
                + persistedData.mLastDeltaUpdateTimestampMillis
                + ", deletion: " + persistedData.mLastDeltaDeleteTimestampMillis);

        return persistedData;
    }

    /** Persists the timestamps to disk. */
    @VisibleForTesting
    @GuardedBy("mLock")
    void persistTimestampsLocked(@NonNull Path path, long lastDeltaUpdateTimestampMillis,
            long lastDeltaDeleteTimestampMillis) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(mPersistedData);

        mPersistedData.mLastDeltaUpdateTimestampMillis = lastDeltaUpdateTimestampMillis;
        mPersistedData.mLastDeltaDeleteTimestampMillis = lastDeltaDeleteTimestampMillis;
        try (
                BufferedWriter writer = Files.newBufferedWriter(
                        path,
                        StandardCharsets.UTF_8);
        ) {
            // This would override the previous line. Since we won't delete deprecated fields, we
            // don't need to clear the old content before doing this.
            writer.write(mPersistedData.toString());
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist timestamps for Delta Update on the disk.", e);
        }
    }
}