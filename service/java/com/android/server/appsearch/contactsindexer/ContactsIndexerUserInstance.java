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
import android.annotation.WorkerThread;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.ContactsContract;
import android.util.AndroidRuntimeException;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Contacts Indexer for a single user.
 *
 * <p>It reads the updated/newly-inserted/deleted contacts from CP2, and sync the changes into
 * AppSearch.
 *
 * <p>This class is thread safe.
 *
 * @hide
 */
public final class ContactsIndexerUserInstance {
    static final String TAG = "ContactsIndexerUserInstance";
    static final String CONTACTS_INDEXER_STATE = "contacts_indexer_state";

    private final Context mContext;
    private final ContactsObserver mContactsObserver;
    private final PersistedData mPersistedData = new PersistedData();
    // Used for batching/throttling the contact change notification so we won't schedule too many
    // delta updates.
    private final AtomicBoolean mUpdateScheduled = new AtomicBoolean(/*initialValue=*/ false);
    private final ContactsIndexerImpl mContactsIndexerImpl;

    // Path to persist timestamp data.
    private final Path mPath;

    /**
     * Single executor to make sure there is only one active sync for this {@link
     * ContactsIndexerUserInstance}
     */
    private final ScheduledThreadPoolExecutor mSingleScheduledExecutor;

    /**
     * Class to hold the persisted data.
     */
    public static final class PersistedData {
        static final String DELIMITER = ",";
        private static final int NUMBER_OF_SERIALIZED_FIELDS = 4;

        // Fields need to be serialized.
        private static final int PERSISTED_DATA_VERSION = 1;
        volatile long mLastDeltaUpdateTimestampMillis = 0;
        volatile long mLastDeltaDeleteTimestampMillis = 0;
        volatile long mLastFullUpdateTimestampMillis = 0;

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
                    + mLastDeltaDeleteTimestampMillis + DELIMITER
                    + mLastFullUpdateTimestampMillis;
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
                    Log.d(TAG, "Read a past version of persisted data.");
                } else if (versionNum > PERSISTED_DATA_VERSION) {
                    Log.d(TAG, "Read a future version of persisted data.");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Failed to parse the version number for " + serializedPersistedData, e);
            }

            try {
                mLastDeltaUpdateTimestampMillis = Long.parseLong(fields[1]);
                mLastDeltaDeleteTimestampMillis = Long.parseLong(fields[2]);
                mLastFullUpdateTimestampMillis = Long.parseLong(fields[3]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Failed to parse the timestamps", e);
            }
        }

        /** Resets all members. */
        public void reset() {
            mLastDeltaUpdateTimestampMillis = 0;
            mLastDeltaDeleteTimestampMillis = 0;
            mLastFullUpdateTimestampMillis = 0;
        }
    }

    /**
     * Constructs and initializes a {@link ContactsIndexerUserInstance}.
     *
     * @param contactsDir data directory for ContactsIndexer.
     */
    @WorkerThread
    @NonNull
    public static ContactsIndexerUserInstance createInstance(@NonNull Context userContext,
            @NonNull File contactsDir) throws InterruptedException, ExecutionException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(contactsDir);
        if (!contactsDir.exists()) {
            boolean result = contactsDir.mkdirs();
            if (!result) {
                throw new AndroidRuntimeException(
                        "Failed to create contacts indexer directory " + contactsDir.getPath());
            }
        }
        // We choose to go ahead here even if we can't create the directory. The indexer will
        // still function correctly except every time the system reboots, the data is not
        // persisted and reset to default.
        Path path = new File(contactsDir, CONTACTS_INDEXER_STATE).toPath();
        ScheduledThreadPoolExecutor singleScheduledExecutor =
                new ScheduledThreadPoolExecutor(/*corePoolSize=*/ 1);
        singleScheduledExecutor.setMaximumPoolSize(1);
        singleScheduledExecutor.setKeepAliveTime(60L, TimeUnit.SECONDS);
        singleScheduledExecutor.allowCoreThreadTimeOut(true);
        singleScheduledExecutor.setRemoveOnCancelPolicy(true);
        // TODO(b/203605504) Check to see if we need a dedicated executor for handling
        //  AppSearch callbacks. Right now this executor is being used for both schedule delta
        //  updates, and handle AppSearch callbacks.
        AppSearchHelper appSearchHelper = AppSearchHelper.createAppSearchHelper(userContext,
                singleScheduledExecutor);
        ContactsIndexerImpl contactsIndexerImpl = new ContactsIndexerImpl(userContext,
                appSearchHelper);
        ContactsIndexerUserInstance indexer = new ContactsIndexerUserInstance(userContext,
                contactsIndexerImpl, path, singleScheduledExecutor);
        indexer.loadPersistedData(path);

        return indexer;
    }

    /**
     * Constructs a {@link ContactsIndexerUserInstance}.
     *
     * @param context                 Context object passed from
     *                                {@link ContactsIndexerManagerService}
     * @param path                    the path to the file to store the meta data for contacts
     *                                indexer.
     * @param singleScheduledExecutor a {@link ScheduledThreadPoolExecutor} with at most one
     *                                executor is expected to ensure the thread safety of this
     *                                class.
     */
    private ContactsIndexerUserInstance(@NonNull Context context,
            @NonNull ContactsIndexerImpl contactsIndexerImpl, @NonNull Path path,
            @NonNull ScheduledThreadPoolExecutor singleScheduledExecutor) {
        mContext = Objects.requireNonNull(context);
        mContactsIndexerImpl = Objects.requireNonNull(contactsIndexerImpl);
        mPath = Objects.requireNonNull(path);
        mSingleScheduledExecutor = Objects.requireNonNull(singleScheduledExecutor);
        mContactsObserver = new ContactsObserver();
    }

    public void onStart() {
        Log.d(TAG, "Registering ContactsObserver for " + mContext.getUser());

        // If this contacts indexer instance hasn't synced any CP2 changes into AppSearch,
        // schedule a one-off task to do a full update. That is, sync all CP2 contacts into
        // AppSearch.
        if (mPersistedData.mLastFullUpdateTimestampMillis == 0) {
            ContactsIndexerMaintenanceService.scheduleOneOffFullUpdateJob(
                    mContext, mContext.getUser().getIdentifier());
        }

        mContext.getContentResolver()
                .registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI,
                        /*notifyForDescendants=*/ true,
                        mContactsObserver);
    }

    public void onStop() {
        Log.d(TAG, "Unregistering ContactsObserver for " + mContext.getUser());
        mContext.getContentResolver().unregisterContentObserver(mContactsObserver);
    }

    private class ContactsObserver extends ContentObserver {
        public ContactsObserver() {
            super(/*handler=*/ null);
        }

        @Override
        public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags) {
            if (!selfChange) {
                int delaySeconds = 2;

                // TODO(b/203605504): make sure that the delta update is scheduled as soon as the
                //  current sync is completed and not after an arbitrary delay.
                if (!ContentResolver.getCurrentSyncs().isEmpty()) {
                    delaySeconds = 30;
                }

                // TODO(b/203605504): make delay configurable
                scheduleDeltaUpdate(delaySeconds);
            }
        }
    }

    /**
     * Performs a full sync of CP2 contacts to AppSearch builtin:Person corpus.
     *
     * @param signal Used to indicate if the full update task should be cancelled.
     */
    public void doFullUpdate(CancellationSignal signal) {
        mSingleScheduledExecutor.schedule(() -> {
            // TODO(b/203605504)
            // 1. Handle cancellation signal to abort the job.
            // 2. Clear AppSearch Person corpus first.
            Log.d(TAG, "Performing a full update of CP2 contacts in AppSearch");
            long currentTimeMillis = System.currentTimeMillis();
            Set<String> allContactIds = new ArraySet<>();
            // Get a list of all contact IDs from CP2. Ignore the return value which denotes the
            // most recent updated timestamp. TODO(b/203605504): reconsider whether the most recent
            //  updated and deleted timestamps are useful.
            ContactsProviderUtil.getUpdatedContactIds(mContext, /*sinceFilter=*/ 0, allContactIds);
            mContactsIndexerImpl.updatePersonCorpus(allContactIds,
                    /*unWantedContactIds=*/ Collections.emptySet());
            Log.d(TAG, "Indexed " + allContactIds.size() + " contacts into AppSearch");
            persistTimestamps(mPath,
                    /*lastDeltaUpdateTimestampMillis=*/ currentTimeMillis,
                    /*lastDeltaDeleteTimestampMillis=*/ currentTimeMillis,
                    /*lastFullUpdateTimestampMillis=*/ currentTimeMillis);
        }, /*delay=*/ 0, TimeUnit.SECONDS);
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
    public void scheduleDeltaUpdate(int delaySec) {
        // Schedule delta updates only if a full update has been performed at least once to sync
        // all of CP2 contacts into AppSearch.
        if (mPersistedData.mLastFullUpdateTimestampMillis == 0) {
            Log.v(TAG, "Deferring delta updates until the first full update is complete");
            return;
        }

        // We want to batch (trigger only one update) on all Contact Updates for the associated
        // user within the time window(delaySec). And we hope the query to CP2 "Give me all the
        // contacts from timestamp T" would catch all the unhandled contact change notifications.
        if (!mUpdateScheduled.getAndSet(true)) {
            mSingleScheduledExecutor.schedule(() -> {
                try {
                    doDeltaUpdate();
                } catch (Throwable t) {
                    Log.e(TAG, "Error during doDeltaUpdate", t);
                }
            }, delaySec, TimeUnit.SECONDS);
        }
    }

    /**
     * Does the delta update. It also resets {@link ContactsIndexerUserInstance#mUpdateScheduled} to
     * false.
     */
    @VisibleForTesting
    // TODO(b/203605504) make this private once we have end to end tests to cover current test
    //  cases. So it shouldn't be used externally, and it's not thread safe.
    void doDeltaUpdate() {
        Log.d(TAG, "previous timestamps -- lastDeltaUpdateTimestampMillis: "
                + mPersistedData.mLastDeltaUpdateTimestampMillis
                + " lastDeltaDeleteTimestampMillis: "
                + mPersistedData.mLastDeltaDeleteTimestampMillis);
        Set<String> wantedIds = new ArraySet<>();
        Set<String> unWantedIds = new ArraySet<>();
        try {
            mPersistedData.mLastDeltaUpdateTimestampMillis =
                    ContactsProviderUtil.getUpdatedContactIds(mContext,
                            mPersistedData.mLastDeltaUpdateTimestampMillis, wantedIds);
            mPersistedData.mLastDeltaDeleteTimestampMillis =
                    ContactsProviderUtil.getDeletedContactIds(mContext,
                            mPersistedData.mLastDeltaDeleteTimestampMillis, unWantedIds);
            Log.d(TAG, "updated timestamps -- lastDeltaUpdateTimestampMillis: "
                    + mPersistedData.mLastDeltaUpdateTimestampMillis
                    + " lastDeltaDeleteTimestampMillis: "
                    + mPersistedData.mLastDeltaDeleteTimestampMillis);
        } finally {
            //  We reset the flag before doing the update to AppSearch, but
            //  after we get the contact changes from CP2. This way, we won't miss any
            //  notification in case the update in AppSearch takes a while.
            mUpdateScheduled.set(false);
        }

        // Update the person corpus in AppSearch based on the changed contact
        // information we get from CP2. At this point mUpdateScheduled has been
        // reset, so a new task is allowed to catch any new changes in CP2.
        // TODO(b/203605504) report errors here so we can choose not to update the
        //  timestamps.
        mContactsIndexerImpl.updatePersonCorpus(wantedIds, unWantedIds);

        // Persist the timestamps.
        persistTimestamps(mPath, mPersistedData.mLastDeltaUpdateTimestampMillis,
                mPersistedData.mLastDeltaDeleteTimestampMillis,
                mPersistedData.mLastFullUpdateTimestampMillis);
    }

    /**
     * Loads the persisted data from disk.
     *
     * <p>It doesn't throw here. If it fails to load file, ContactsIndexer would always use the
     * timestamps persisted in the memory.
     */
    @NonNull
    private void loadPersistedData(@NonNull Path path) {
        Objects.requireNonNull(path);
        boolean isLoadingDataFailed = false;
        try (
                BufferedReader reader = Files.newBufferedReader(
                        path,
                        StandardCharsets.UTF_8);
        ) {
            // right now we store everything in one line. So we just need to read the first line.
            String content = reader.readLine();
            mPersistedData.fromString(content);
        } catch (NoSuchFileException e) {
            // ignore bootstrap errors
        } catch (IOException e) {
            Log.e(TAG, "Failed to load persisted data from disk.", e);
            isLoadingDataFailed = true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to parse the loaded data.", e);
            isLoadingDataFailed = true;
        } finally {
            if (isLoadingDataFailed) {
                // Resets all the values here in case there are some values set from corrupted data.
                mPersistedData.reset();
            }
        }

        Log.d(TAG, "Load timestamps from disk: "
                + "delta-update: " + mPersistedData.mLastDeltaUpdateTimestampMillis
                + ", delta-delete: " + mPersistedData.mLastDeltaDeleteTimestampMillis
                + ", full-update: " + mPersistedData.mLastFullUpdateTimestampMillis);
    }

    /** Persists the timestamps to disk. */
    private void persistTimestamps(@NonNull Path path, long lastDeltaUpdateTimestampMillis,
            long lastDeltaDeleteTimestampMillis, long lastFullUpdateTimestampMillis) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(mPersistedData);

        mPersistedData.mLastDeltaUpdateTimestampMillis = lastDeltaUpdateTimestampMillis;
        mPersistedData.mLastDeltaDeleteTimestampMillis = lastDeltaDeleteTimestampMillis;
        mPersistedData.mLastFullUpdateTimestampMillis = lastFullUpdateTimestampMillis;
        try (
                BufferedWriter writer = Files.newBufferedWriter(
                        path,
                        StandardCharsets.UTF_8);
        ) {
            // This would override the previous line. Since we won't delete deprecated fields, we
            // don't need to clear the old content before doing this.
            writer.write(mPersistedData.toString());
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist timestamps for Delta Update on the disk.", e);
        }
    }

    /**
     * Gets a copy of the current {@link #mPersistedData}. This method is not thread safe, and
     * should be used for test only.
     */
    @VisibleForTesting
    @NonNull
    PersistedData getPersistedStateCopy() {
        PersistedData data = new PersistedData();
        data.mLastDeltaUpdateTimestampMillis = mPersistedData.mLastDeltaUpdateTimestampMillis;
        data.mLastDeltaDeleteTimestampMillis = mPersistedData.mLastDeltaDeleteTimestampMillis;
        data.mLastFullUpdateTimestampMillis = mPersistedData.mLastFullUpdateTimestampMillis;
        return data;
    }
}
