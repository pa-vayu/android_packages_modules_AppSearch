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
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.ContactsContract;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
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
            @NonNull File contactsDir, @NonNull ExecutorService executorService) {
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

        mSingleThreadedExecutor.execute(() -> {
            // If this contacts indexer instance hasn't synced any CP2 changes into AppSearch,
            // or a configurable amount of time (default 30 days) has passed since the last
            // full sync, schedule a task to do a full update. That is, sync all CP2 contacts into
            // AppSearch.
            long fullUpdateIntervalMillis =
                    ContactsIndexerConfig.getContactsFullUpdateIntervalMillis();
            long lastFullUpdateTimestampMillis = mSettings.getLastFullUpdateTimestampMillis();
            if (lastFullUpdateTimestampMillis == 0
                    || lastFullUpdateTimestampMillis + fullUpdateIntervalMillis
                    <= System.currentTimeMillis()) {
                ContactsIndexerMaintenanceService.scheduleFullUpdateJob(
                        mContext, mContext.getUser().getIdentifier());
            }
        });
    }

    public void shutdown() throws InterruptedException {
        Log.d(TAG, "Unregistering ContactsObserver for " + mContext.getUser());
        mContext.getContentResolver().unregisterContentObserver(mContactsObserver);

        ContactsIndexerMaintenanceService.cancelFullUpdateJob(mContext,
                mContext.getUser().getIdentifier());
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
            Log.w(TAG, "Failed to perform full update", t);
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
                    mSettings.setLastFullUpdateTimestampMillis(currentTimeMillis);
                    mSettings.setLastDeltaUpdateTimestampMillis(currentTimeMillis);
                    mSettings.setLastDeltaDeleteTimestampMillis(currentTimeMillis);
                    persistSettings();
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
        if (mSettings.getLastFullUpdateTimestampMillis() == 0) {
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
            mSingleThreadedExecutor.execute(() -> doDeltaUpdateAsync().exceptionally(t -> {
                Log.w(TAG, "Failed to perform delta update", t);
                return null;
            }));
        }
    }

    /**
     * Does the delta update. It also resets {@link ContactsIndexerUserInstance#mDeltaUpdatePending}
     * to false.
     */
    @VisibleForTesting
    /*package*/ CompletableFuture<Void> doDeltaUpdateAsync() {
        // Reset the delta update pending flag at the top of this method. This allows the next
        // ContentObserver.onChange() notification to schedule another delta-update task on the
        // executor. Note that additional change notifications will not schedule more delta-update
        // tasks.
        // Resetting the delta update pending flag after calling ContentResolver.query() to get
        // the updated contact IDs and deleted contact IDs runs the risk of a race condition
        // where a change notification is sent and handled after the query() ends but before the
        // flag is reset.
        mDeltaUpdatePending.set(false);

        long lastDeltaUpdateTimestampMillis = mSettings.getLastDeltaUpdateTimestampMillis();
        long lastDeltaDeleteTimestampMillis = mSettings.getLastDeltaDeleteTimestampMillis();
        Log.d(TAG, "previous timestamps --"
                + " lastDeltaUpdateTimestampMillis: " + lastDeltaUpdateTimestampMillis
                + " lastDeltaDeleteTimestampMillis: " + lastDeltaDeleteTimestampMillis);
        Set<String> wantedIds = new ArraySet<>();
        Set<String> unWantedIds = new ArraySet<>();
        long mostRecentContactLastUpdateTimestampMillis =
                ContactsProviderUtil.getUpdatedContactIds(mContext, lastDeltaUpdateTimestampMillis,
                        wantedIds);
        long mostRecentContactDeletedTimestampMillis =
                ContactsProviderUtil.getDeletedContactIds(mContext, lastDeltaDeleteTimestampMillis,
                        unWantedIds);

        // Update the person corpus in AppSearch based on the changed contact
        // information we get from CP2. At this point mUpdateScheduled has been
        // reset, so a new task is allowed to catch any new changes in CP2.
        // TODO(b/203605504) report errors here so we can choose not to update the
        //  timestamps.
        return mContactsIndexerImpl.updatePersonCorpusAsync(wantedIds, unWantedIds)
                .thenAccept(x -> {
                    Log.d(TAG, "updated timestamps --"
                            + " lastDeltaUpdateTimestampMillis: "
                            + mostRecentContactLastUpdateTimestampMillis
                            + " lastDeltaDeleeteTimestampMillis: "
                            + mostRecentContactDeletedTimestampMillis);
                    mSettings.setLastDeltaUpdateTimestampMillis(
                            mostRecentContactLastUpdateTimestampMillis);
                    mSettings.setLastDeltaDeleteTimestampMillis(
                            mostRecentContactDeletedTimestampMillis);
                    persistSettings();
                });
    }

    private void loadSettingsAsync() {
        mSingleThreadedExecutor.execute(() -> {
            boolean unused = mDataDir.mkdirs();
            try {
                mSettings.load();
            } catch (IOException e) {
                Log.w(TAG, "Failed to load settings from disk", e);
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
