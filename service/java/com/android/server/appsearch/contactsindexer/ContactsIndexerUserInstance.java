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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final File mDataDir;
    private final ContactsObserver mContactsObserver;
    private final PersistedData mPersistedData = new PersistedData();
    // Used for batching/throttling the contact change notification so we won't schedule too many
    // delta updates.
    private final AtomicBoolean mDeltaUpdatePending = new AtomicBoolean(/*initialValue=*/ false);
    private final AppSearchHelper mAppSearchHelper;
    private final ContactsIndexerImpl mContactsIndexerImpl;

    /**
     * Single threaded executor to make sure there is only one active sync for this {@link
     * ContactsIndexerUserInstance}
     */
    private final ExecutorService mSingleThreadedExecutor;

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
     * <p>Heavy operations such as connecting to AppSearch are performed asynchronously.
     *
     * @param contactsDir data directory for ContactsIndexer.
     */
    @NonNull
    public static ContactsIndexerUserInstance createInstance(@NonNull Context userContext,
            @NonNull File contactsDir) {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(contactsDir);

        ExecutorService singleThreadedExecutor = Executors.newSingleThreadExecutor();
        return createInstance(userContext, contactsDir, singleThreadedExecutor);
    }

    @VisibleForTesting
    @NonNull
    /*package*/ static ContactsIndexerUserInstance createInstance(@NonNull Context context,
            @NonNull File contactsDir, @NonNull ExecutorService executorService) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(contactsDir);
        Objects.requireNonNull(executorService);

        AppSearchHelper appSearchHelper = AppSearchHelper.createAppSearchHelper(context,
                executorService);
        ContactsIndexerUserInstance indexer = new ContactsIndexerUserInstance(context,
                contactsDir, appSearchHelper, executorService);
        indexer.loadPersistedDataAsync();

        return indexer;
    }

    /**
     * Constructs a {@link ContactsIndexerUserInstance}.
     *
     * @param context                 Context object passed from
     *                                {@link ContactsIndexerManagerService}
     * @param dataDir                 data directory for storing contacts indexer state.
     * @param singleThreadedExecutor  an {@link ExecutorService} with at most one thread to ensure
     *                                the thread safety of this class.
     */
    private ContactsIndexerUserInstance(@NonNull Context context, @NonNull File dataDir,
            @NonNull AppSearchHelper appSearchHelper,
            @NonNull ExecutorService singleThreadedExecutor) {
        mContext = Objects.requireNonNull(context);
        mDataDir = Objects.requireNonNull(dataDir);
        mAppSearchHelper = Objects.requireNonNull(appSearchHelper);
        mSingleThreadedExecutor = Objects.requireNonNull(singleThreadedExecutor);
        mContactsObserver = new ContactsObserver();
        mContactsIndexerImpl = new ContactsIndexerImpl(context, appSearchHelper);
    }

    public void startAsync() {
        Log.d(TAG, "Registering ContactsObserver for " + mContext.getUser());
        mContext.getContentResolver()
                .registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI,
                        /*notifyForDescendants=*/ true,
                        mContactsObserver);

        mSingleThreadedExecutor.execute(() -> {
            // If this contacts indexer instance hasn't synced any CP2 changes into AppSearch,
            // or a configurable amount of time (default 30 days) has passed since the last
            // full sync, schedule a task to do a full update. That is, sync all CP2 contacts into
            // AppSearch.
            long fullUpdateIntervalMillis =
                    ContactsIndexerConfig.getContactsFullUpdateIntervalMillis();
            if (mPersistedData.mLastFullUpdateTimestampMillis == 0
                    || mPersistedData.mLastFullUpdateTimestampMillis + fullUpdateIntervalMillis
                    <= System.currentTimeMillis()) {
                ContactsIndexerMaintenanceService.scheduleFullUpdateJob(
                        mContext, mContext.getUser().getIdentifier());
            }
        });
    }

    public void shutdown() throws InterruptedException {
        Log.d(TAG, "Unregistering ContactsObserver for " + mContext.getUser());
        mContext.getContentResolver().unregisterContentObserver(mContactsObserver);

        mSingleThreadedExecutor.shutdown();
        mSingleThreadedExecutor.awaitTermination(30L, TimeUnit.SECONDS);
    }

    private class ContactsObserver extends ContentObserver {
        public ContactsObserver() {
            super(/*handler=*/ null);
        }

        @Override
        public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags) {
            if (!selfChange) {
                mSingleThreadedExecutor.execute(
                        ContactsIndexerUserInstance.this::handleDeltaUpdate);
            }
        }
    }

    /**
     * Performs a full sync of CP2 contacts to AppSearch builtin:Person corpus.
     *
     * @param signal Used to indicate if the full update task should be cancelled.
     */
    public void doFullUpdateAsync(@NonNull CancellationSignal signal) {
        Objects.requireNonNull(signal);
        // TODO(b/222126568): log stats
        mSingleThreadedExecutor.execute(() -> doFullUpdateInternalAsync(signal).exceptionally(t -> {
            Log.w("Failed to perform full update", t);
            return null;
        }));
    }

    @VisibleForTesting
    CompletableFuture<Void> doFullUpdateInternalAsync(@NonNull CancellationSignal signal) {
        // TODO(b/203605504): handle cancellation signal to abort the job.
        long currentTimeMillis = System.currentTimeMillis();
        Set<String> cp2ContactIds = new ArraySet<>();
        // Get a list of all contact IDs from CP2. Ignore the return value which denotes the
        // most recent updated timestamp. TODO(b/203605504): reconsider whether the most recent
        //  updated and deleted timestamps are useful.
        ContactsProviderUtil.getUpdatedContactIds(mContext, /*sinceFilter=*/ 0, cp2ContactIds);
        return mAppSearchHelper.getAllContactIdsAsync()
                .thenCompose(appsearchContactIds -> {
                    Set<String> unwantedContactIds = new ArraySet<>(appsearchContactIds);
                    unwantedContactIds.removeAll(cp2ContactIds);
                    Log.d(TAG, "Performing a full sync (updated:" + cp2ContactIds.size()
                            + ", deleted:" + unwantedContactIds.size()
                            + ") of CP2 contacts in AppSearch");
                    return mContactsIndexerImpl.updatePersonCorpusAsync(
                            /*wantedContactIds=*/ cp2ContactIds, unwantedContactIds);
                }).thenAccept(x -> {
                    persistTimestamps(
                            /*lastDeltaUpdateTimestampMillis=*/ currentTimeMillis,
                            /*lastDeltaDeleteTimestampMillis=*/ currentTimeMillis,
                            /*lastFullUpdateTimestampMillis=*/ currentTimeMillis);
                });
    }

    /**
     * Does the delta/instant update to sync the contacts from CP2 to AppSearch.
     *
     * <p>{@link #mDeltaUpdatePending} is being used to avoid scheduling any update BEFORE an active
     * update is being processed.
     *
     * <p>{@link #mSingleThreadedExecutor} is being used to make sure there is one and only one
     * running update, and at most one pending update is queued while the current active update is
     * running.
     */
    private void handleDeltaUpdate() {
        // Schedule delta updates only if a full update has been performed at least once to sync
        // all of CP2 contacts into AppSearch.
        if (mPersistedData.mLastFullUpdateTimestampMillis == 0) {
            Log.v(TAG, "Deferring delta updates until the first full update is complete");
            return;
        } else if (!ContentResolver.getCurrentSyncs().isEmpty()) {
            // TODO(b/221905367): make sure that the delta update is scheduled as soon
            //  as the current sync is completed.
            Log.v(TAG, "Deferring delta updates until the current sync is complete");
            return;
        }

        // We want to batch (trigger only one update) on all Contact Updates for the associated
        // user within the time window(delaySec). And we hope the query to CP2 "Give me all the
        // contacts from timestamp T" would catch all the unhandled contact change notifications.
        if (!mDeltaUpdatePending.getAndSet(true)) {
            mSingleThreadedExecutor.execute(() -> {
                try {
                    doDeltaUpdate();
                } catch (Throwable t) {
                    Log.e(TAG, "Error during doDeltaUpdate", t);
                }
            });
        }
    }

    /**
     * Performs delta update bypassing any constraints in {@link #handleDeltaUpdate()}
     *
     * <p>The operation is performed on the {@link #mSingleThreadedExecutor} to ensure
     * thread safety.
     */
    @WorkerThread
    @VisibleForTesting
    /*package*/ void doDeltaUpdateForTest() throws InterruptedException, ExecutionException {
        mSingleThreadedExecutor.submit(this::doDeltaUpdate).get();
    }

    /**
     * Does the delta update. It also resets {@link ContactsIndexerUserInstance#mDeltaUpdatePending}
     * to false.
     */
    private void doDeltaUpdate() {
        // Reset the delta update pending flag at the top of this method. This allows the next
        // ContentObserver.onChange() notification to schedule another delta-update task on the
        // executor. Note that additional change notifications will not schedule more delta-update
        // tasks.
        // Resetting the delta update pending flag after calling ContentResolver.query() to get
        // the updated contact IDs and deleted contact IDs runs the risk of a race condition
        // where a change notification is sent and handled after the query() ends but before the
        // flag is reset.
        mDeltaUpdatePending.set(false);

        Log.d(TAG, "previous timestamps -- lastDeltaUpdateTimestampMillis: "
                + mPersistedData.mLastDeltaUpdateTimestampMillis
                + " lastDeltaDeleteTimestampMillis: "
                + mPersistedData.mLastDeltaDeleteTimestampMillis);
        Set<String> wantedIds = new ArraySet<>();
        Set<String> unWantedIds = new ArraySet<>();
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

        // Update the person corpus in AppSearch based on the changed contact
        // information we get from CP2. At this point mUpdateScheduled has been
        // reset, so a new task is allowed to catch any new changes in CP2.
        // TODO(b/203605504) report errors here so we can choose not to update the
        //  timestamps.
        mContactsIndexerImpl.updatePersonCorpusAsync(wantedIds, unWantedIds);

        // Persist the timestamps.
        // TODO(b/221892152): persist timestamps after the documents are flush to AppSearch
        persistTimestamps(mPersistedData.mLastDeltaUpdateTimestampMillis,
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
    private void loadPersistedDataAsync() {
        mSingleThreadedExecutor.execute(() -> {
            boolean unused = mDataDir.mkdirs();
            Path path = new File(mDataDir, CONTACTS_INDEXER_STATE).toPath();

            boolean isLoadingDataFailed = false;
            try (
                    BufferedReader reader = Files.newBufferedReader(
                            path,
                            StandardCharsets.UTF_8);
            ) {
                // right now we store everything in one line.
                // So we just need to read the first line.
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
                    // Resets all the values in case there are some values set from corrupted data.
                    mPersistedData.reset();
                }
            }

            Log.d(TAG, "Load timestamps from disk: "
                    + "delta-update: " + mPersistedData.mLastDeltaUpdateTimestampMillis
                    + ", delta-delete: " + mPersistedData.mLastDeltaDeleteTimestampMillis
                    + ", full-update: " + mPersistedData.mLastFullUpdateTimestampMillis);
        });
    }

    /** Persists the timestamps to disk. */
    private void persistTimestamps(long lastDeltaUpdateTimestampMillis,
            long lastDeltaDeleteTimestampMillis, long lastFullUpdateTimestampMillis) {
        Objects.requireNonNull(mPersistedData);

        mPersistedData.mLastDeltaUpdateTimestampMillis = lastDeltaUpdateTimestampMillis;
        mPersistedData.mLastDeltaDeleteTimestampMillis = lastDeltaDeleteTimestampMillis;
        mPersistedData.mLastFullUpdateTimestampMillis = lastFullUpdateTimestampMillis;

        Path path = new File(mDataDir, CONTACTS_INDEXER_STATE).toPath();
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
     * Returns a copy of the current {@link #mPersistedData}.
     *
     * <p>Performs the copy operation on the {@link #mSingleThreadedExecutor} to ensure
     * thread safe access to the data.
     */
    @WorkerThread
    @VisibleForTesting
    @NonNull
    PersistedData getPersistedStateForTest() throws ExecutionException, InterruptedException {
        return mSingleThreadedExecutor.submit(() -> {
            PersistedData data = new PersistedData();
            data.mLastDeltaUpdateTimestampMillis = mPersistedData.mLastDeltaUpdateTimestampMillis;
            data.mLastDeltaDeleteTimestampMillis = mPersistedData.mLastDeltaDeleteTimestampMillis;
            data.mLastFullUpdateTimestampMillis = mPersistedData.mLastFullUpdateTimestampMillis;
            return data;
        }).get();
    }
}
