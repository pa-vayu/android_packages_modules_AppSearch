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
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.ContactsContract;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.stats.AppSearchStatsLog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

    private static final String TAG = "ContactsIndexerUserInstance";

    private final Context mContext;
    private final File mDataDir;
    private final ContactsIndexerSettings mSettings;
    private final ContactsObserver mContactsObserver;
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
            @NonNull File contactsDir,
            @NonNull ExecutorService executorService) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(contactsDir);
        Objects.requireNonNull(executorService);

        AppSearchHelper appSearchHelper = AppSearchHelper.createAppSearchHelper(context,
                executorService);
        ContactsIndexerUserInstance indexer = new ContactsIndexerUserInstance(context,
                contactsDir, appSearchHelper, executorService);
        indexer.loadSettingsAsync();

        return indexer;
    }

    /**
     * Constructs a {@link ContactsIndexerUserInstance}.
     *
     * @param context                Context object passed from
     *                               {@link ContactsIndexerManagerService}
     * @param dataDir                data directory for storing contacts indexer state.
     * @param singleThreadedExecutor an {@link ExecutorService} with at most one thread to ensure
     *                               the thread safety of this class.
     */
    private ContactsIndexerUserInstance(@NonNull Context context, @NonNull File dataDir,
            @NonNull AppSearchHelper appSearchHelper,
            @NonNull ExecutorService singleThreadedExecutor) {
        mContext = Objects.requireNonNull(context);
        mDataDir = Objects.requireNonNull(dataDir);
        mSettings = new ContactsIndexerSettings(mDataDir);
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

        mSingleThreadedExecutor.execute(this::maybeUpdateContacts);
    }

    public void shutdown() throws InterruptedException {
        Log.d(TAG, "Unregistering ContactsObserver for " + mContext.getUser());
        mContext.getContentResolver().unregisterContentObserver(mContactsObserver);

        ContactsIndexerMaintenanceService.cancelFullUpdateJob(mContext,
                mContext.getUser().getIdentifier());
        mSingleThreadedExecutor.shutdown();
        boolean unused = mSingleThreadedExecutor.awaitTermination(30L, TimeUnit.SECONDS);
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

    private void maybeUpdateContacts() {
        // If no CP2 changes have been observed (bootstrap case), force a delta (or instant) update
        // to sync a configurable number of CP2 contacts into AppSearch.
        if (mSettings.getLastDeltaUpdateTimestampMillis() == 0) {
            // TODO(b/222126568): refactor doDeltaUpdateAsync() to return a future value of
            // ContactsUpdateStats so that it can be checked and logged here, instead of the
            // placeholder exceptionally() block that only logs to the console.
            doDeltaUpdateAsync(ContactsIndexerConfig.getContactsInstantIndexingLimit(),
                    new ContactsUpdateStats()).exceptionally(t -> {
                Log.d(TAG, "Failed to bootstrap Person corpus with CP2 contacts", t);
                return null;
            });
        }

        // If a configurable amount of time has passed since the last full sync, schedule a job
        // to do a full update. That is, sync all CP2 contacts into AppSearch.
        long fullUpdateIntervalMillis = ContactsIndexerConfig.getContactsFullUpdateIntervalMillis();
        long lastFullUpdateTimestampMillis = mSettings.getLastFullUpdateTimestampMillis();
        if (lastFullUpdateTimestampMillis + fullUpdateIntervalMillis
                <= System.currentTimeMillis()) {
            ContactsIndexerMaintenanceService.scheduleFullUpdateJob(
                    mContext, mContext.getUser().getIdentifier());
        }
    }

    /**
     * Performs a full sync of CP2 contacts to AppSearch builtin:Person corpus.
     *
     * @param signal Used to indicate if the full update task should be cancelled.
     */
    public void doFullUpdateAsync(@Nullable CancellationSignal signal) {
        mSingleThreadedExecutor.execute(() -> {
            ContactsUpdateStats updateStats = new ContactsUpdateStats();
            doFullUpdateInternalAsync(signal, updateStats);
        });
    }

    @VisibleForTesting
    CompletableFuture<Void> doFullUpdateInternalAsync(
            @Nullable CancellationSignal signal, @NonNull ContactsUpdateStats updateStats) {
        // TODO(b/203605504): handle cancellation signal to abort the job.
        long currentTimeMillis = System.currentTimeMillis();
        updateStats.mUpdateType = ContactsUpdateStats.FULL_UPDATE;
        updateStats.mUpdateAndDeleteStartTimeMillis = currentTimeMillis;

        List<String> cp2ContactIds = new ArrayList<>();
        // Get a list of all contact IDs from CP2. Ignore the return value which denotes the
        // most recent updated timestamp.
        // TODO(b/203605504): reconsider whether the most recent
        //  updated and deleted timestamps are useful.
        ContactsProviderUtil.getUpdatedContactIds(mContext, /*sinceFilter=*/ 0,
                ContactsIndexerConfig.getContactsFullUpdateLimit(), cp2ContactIds,
                updateStats);
        return mAppSearchHelper.getAllContactIdsAsync()
                .thenCompose(appsearchContactIds -> {
                    // all_contacts_from_AppSearch - all_contacts_from_cp2 =
                    // contacts_needs_to_be_removed_from_AppSearch.
                    appsearchContactIds.removeAll(cp2ContactIds);
                    Log.d(TAG, "Performing a full sync (updated:" + cp2ContactIds.size()
                            + ", deleted:" + appsearchContactIds.size()
                            + ") of CP2 contacts in AppSearch");
                    return mContactsIndexerImpl.updatePersonCorpusAsync(/*wantedContactIds=*/
                            cp2ContactIds, /*unwantedContactIds=*/ appsearchContactIds,
                            updateStats);
                }).handle((x, t) -> {
                    if (t != null) {
                        Log.w(TAG, "Failed to perform full update", t);
                        // Just clear all the remaining contacts in case of error.
                        mContactsIndexerImpl.cancelUpdatePersonCorpus();
                        if (updateStats.mUpdateStatuses.isEmpty()
                                && updateStats.mDeleteStatuses.isEmpty()) {
                            // Somehow this error is not reflected in the stats, and
                            // unfortunately we don't know what part is wrong. Just add an error
                            // code for the update.
                            updateStats.mUpdateStatuses.add(
                                    AppSearchResult.RESULT_UNKNOWN_ERROR);
                        }
                    }

                    // Always persist the current timestamps for full update for both success and
                    // failure. Right now we are taking the best effort to keep CP2 and AppSearch
                    // in sync, without any retry in case of failure. We don't want an unexpected
                    // error, like a bad document, prevent the timestamps getting updated, which
                    // will make the indexer fetch a lot of contacts for EACH delta update.
                    // TODO(b/226078966) Also finding the update timestamps for last success is
                    //  not trivial, and we should think more about how to do that correctly.
                    mSettings.setLastFullUpdateTimestampMillis(currentTimeMillis);
                    mSettings.setLastDeltaUpdateTimestampMillis(currentTimeMillis);
                    mSettings.setLastDeltaDeleteTimestampMillis(currentTimeMillis);
                    persistSettings();
                    logStats(updateStats);
                    return null;
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
        if (!ContentResolver.getCurrentSyncs().isEmpty()) {
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
                ContactsUpdateStats updateStats = new ContactsUpdateStats();
                // TODO(b/226489369): apply instant indexing limit on CP2 changes also?
                // TODO(b/222126568): refactor doDeltaUpdateAsync() to return a future value of
                //  ContactsUpdateStats so that it can be checked and logged here, instead of the
                //  placeholder exceptionally() block that only logs to the console.
                doDeltaUpdateAsync(ContactsIndexerConfig.getContactsDeltaUpdateLimit(),
                        updateStats).exceptionally(t -> {
                    Log.d(TAG, "Failed to index CP2 change", t);
                    return null;
                });
            });
        }
    }

    /**
     * Does the delta update. It also resets {@link ContactsIndexerUserInstance#mDeltaUpdatePending}
     * to false.
     */
    @VisibleForTesting
    /*package*/ CompletableFuture<Void> doDeltaUpdateAsync(
            int indexingLimit, @NonNull ContactsUpdateStats updateStats) {
        // Reset the delta update pending flag at the top of this method. This allows the next
        // ContentObserver.onChange() notification to schedule another delta-update task on the
        // executor. Note that additional change notifications will not schedule more
        // delta-update
        // tasks.
        // Resetting the delta update pending flag after calling ContentResolver.query() to get
        // the updated contact IDs and deleted contact IDs runs the risk of a race condition
        // where a change notification is sent and handled after the query() ends but before the
        // flag is reset.
        mDeltaUpdatePending.set(false);
        updateStats.mUpdateType = ContactsUpdateStats.DELTA_UPDATE;
        updateStats.mUpdateAndDeleteStartTimeMillis = System.currentTimeMillis();
        long lastDeltaUpdateTimestampMillis = mSettings.getLastDeltaUpdateTimestampMillis();
        long lastDeltaDeleteTimestampMillis = mSettings.getLastDeltaDeleteTimestampMillis();
        Log.d(TAG, "previous timestamps --"
                + " lastDeltaUpdateTimestampMillis: " + lastDeltaUpdateTimestampMillis
                + " lastDeltaDeleteTimestampMillis: " + lastDeltaDeleteTimestampMillis);

        List<String> wantedIds = new ArrayList<>();
        List<String> unWantedIds = new ArrayList<>();
        long mostRecentContactLastUpdateTimestampMillis =
                ContactsProviderUtil.getUpdatedContactIds(mContext, lastDeltaUpdateTimestampMillis,
                        indexingLimit, wantedIds, updateStats);
        long mostRecentContactDeletedTimestampMillis =
                ContactsProviderUtil.getDeletedContactIds(mContext, lastDeltaDeleteTimestampMillis,
                        unWantedIds, updateStats);

        // Update the person corpus in AppSearch based on the changed contact
        // information we get from CP2. At this point mUpdateScheduled has been
        // reset, so a new task is allowed to catch any new changes in CP2.
        // TODO(b/203605504) for future improvement. Report errors here and persist the right
        //  timestamps for last successful deletion and update. This requires the ids from CP2
        //  are sorted in last_update_timestamp ascending order, and the code would become a
        //  little complicated.
        return mContactsIndexerImpl.updatePersonCorpusAsync(wantedIds, unWantedIds, updateStats)
                .handle((x, t) -> {
                    if (t != null) {
                        Log.w(TAG, "Failed to perform delta update", t);
                        // Just clear all the remaining contacts in case of error.
                        mContactsIndexerImpl.cancelUpdatePersonCorpus();
                        if (updateStats.mUpdateStatuses.isEmpty()
                                && updateStats.mDeleteStatuses.isEmpty()) {
                            // Somehow this error is not reflected in the stats, and
                            // unfortunately we don't know which part is wrong. Just add an error
                            // code for the update.
                            updateStats.mUpdateStatuses.add(
                                    AppSearchResult.RESULT_UNKNOWN_ERROR);
                        }
                    }
                    // Persisting timestamping and logging, no matter if update succeeds or not.
                    Log.d(TAG, "updated timestamps --"
                            + " lastDeltaUpdateTimestampMillis: "
                            + mostRecentContactLastUpdateTimestampMillis
                            + " lastDeltaDeleteTimestampMillis: "
                            + mostRecentContactDeletedTimestampMillis);
                    mSettings.setLastDeltaUpdateTimestampMillis(
                            mostRecentContactLastUpdateTimestampMillis);
                    mSettings.setLastDeltaDeleteTimestampMillis(
                            mostRecentContactDeletedTimestampMillis);
                    persistSettings();
                    logStats(updateStats);
                    if (updateStats.mUpdateStatuses.contains(AppSearchResult.RESULT_OUT_OF_SPACE)) {
                        // Some indexing failed due to OUT_OF_SPACE from AppSearch. We can simply
                        // schedule a full update so we can trim the Person corpus in AppSearch
                        // to make some room for delta update. We need to monitor the failure
                        // count and reasons for indexing during full update to see if that limit
                        // (10,000) is too big right now, considering we are sharing this limit
                        // with any AppSearch clients, e.g. ShortcutManager, in the system server.
                        ContactsIndexerMaintenanceService.scheduleFullUpdateJob(mContext,
                                mContext.getUser().getIdentifier());
                    }

                    return null;
                });
    }

    // Logs the stats to Westworld.
    private void logStats(@NonNull ContactsUpdateStats updateStats) {
        int totalUpdateLatency =
                (int) (System.currentTimeMillis()
                        - updateStats.mUpdateAndDeleteStartTimeMillis);
        // Finalize status code for update and delete.
        if (updateStats.mUpdateStatuses.isEmpty()) {
            // SUCCESS if no error found.
            updateStats.mUpdateStatuses.add(AppSearchResult.RESULT_OK);
        }
        if (updateStats.mDeleteStatuses.isEmpty()) {
            // SUCCESS if no error found.
            updateStats.mDeleteStatuses.add(AppSearchResult.RESULT_OK);
        }

        // Get the accurate count for failed cases. The current failed count doesn't include
        // the contacts skipped due to failures in previous batches. Once a batch fails, all the
        // following batches will be skipped. The contacts in those batches should be counted as
        // failure as well.
        updateStats.mContactsUpdateFailedCount =
                updateStats.mTotalContactsToBeUpdated - updateStats.mContactsUpdateSucceededCount
                        - updateStats.mContactsUpdateSkippedCount;
        updateStats.mContactsDeleteFailedCount =
                updateStats.mTotalContactsToBeDeleted - updateStats.mContactsDeleteSucceededCount;

        int[] updateStatusArr = new int[updateStats.mUpdateStatuses.size()];
        int[] deleteStatusArr = new int[updateStats.mDeleteStatuses.size()];
        int updateIdx = 0;
        int deleteIdx = 0;
        for (int updateStatus : updateStats.mUpdateStatuses) {
            updateStatusArr[updateIdx] = updateStatus;
            ++updateIdx;
        }
        for (int deleteStatus : updateStats.mUpdateStatuses) {
            deleteStatusArr[deleteIdx] = deleteStatus;
            ++deleteIdx;
        }
        AppSearchStatsLog.write(
                AppSearchStatsLog.CONTACTS_INDEXER_UPDATE_STATS_REPORTED,
                updateStats.mUpdateType,
                totalUpdateLatency,
                updateStatusArr,
                deleteStatusArr,
                updateStats.mNewContactsToBeUpdated,
                updateStats.mContactsUpdateSucceededCount,
                updateStats.mContactsDeleteSucceededCount,
                updateStats.mContactsUpdateSkippedCount,
                updateStats.mContactsUpdateFailedCount,
                updateStats.mContactsDeleteFailedCount);
    }

    /**
     * Loads the persisted data from disk.
     *
     * <p>It doesn't throw here. If it fails to load file, ContactsIndexer would always use the
     * timestamps persisted in the memory.
     */
    private void loadSettingsAsync() {
        mSingleThreadedExecutor.execute(() -> {
            boolean unused = mDataDir.mkdirs();
            try {
                mSettings.load();
            } catch (IOException e) {
                // Ignore file not found errors (bootstrap case)
                if (!(e instanceof FileNotFoundException)) {
                    Log.w(TAG, "Failed to load settings from disk", e);
                }
            }
        });
    }

    private void persistSettings() {
        try {
            mSettings.persist();
        } catch (IOException e) {
            Log.w(TAG, "Failed to save settings to disk", e);
        }
    }
}
