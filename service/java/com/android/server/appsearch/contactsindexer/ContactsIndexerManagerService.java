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
import android.os.Handler;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Service to sync contacts data from CP2 to Person corpus in AppSearch.
 *
 * <p>It manages one {@link ContactsPerUserIndexer} for each user to sync the data.
 */
public final class ContactsIndexerManagerService {
    static final String TAG = "ContactsIndexerManagerService";

    /**
     * Executor to dispatch the contact change notifications to different {@link
     * ContactsPerUserIndexer}.
     */
    private final ThreadPoolExecutor mSingleThreadExecutor;

    private final Context mContext;
    private final ContactsContentObserver mContactsContentObserver;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<UserHandle, ContactsPerUserIndexer> mContactsIndexersLocked =
            new ArrayMap<>();

    /** Whether {@link ContactsContentObserver} is registered. */
    @GuardedBy("mLock")
    private boolean mObserverRegisteredLocked = false;

    /** Constructs a {@link ContactsIndexerManagerService}. */
    public ContactsIndexerManagerService(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        Handler handler = new Handler(mContext.getMainLooper());
        mContactsContentObserver = new ContactsContentObserver(handler, this);

        // Set the executor. It has maximum one thread in the pool.
        mSingleThreadExecutor = new ThreadPoolExecutor(/*corePoolSize=*/1,
                /*maximumPoolSize=*/ 1,
                /*keepAliveTime=*/ 60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        mSingleThreadExecutor.allowCoreThreadTimeOut(true);
    }

    /** Registers the service to get notified for contact changes from CP2. */
    public void registerContactsContentObserver() {
        synchronized (mLock) {
            if (mObserverRegisteredLocked) {
                return;
            }
            // Starts the observation to get notifications from CP2
            mContext.getContentResolver().registerContentObserverAsUser(
                    ContactsContract.Contacts.CONTENT_URI,
                    /*notifyForDescendants=*/ true,
                    mContactsContentObserver,
                    UserHandle.ALL);
            mObserverRegisteredLocked = true;
        }
    }

    /** Unregisters the {@link ContactsContentObserver}. */
    public void unregisterContactsContentObserver() {
        synchronized (mLock) {
            if (mObserverRegisteredLocked) {
                mContext.getContentResolver().unregisterContentObserver(mContactsContentObserver);
                mObserverRegisteredLocked = false;
            }
        }
    }

    /** Does the delta/instant update once getting notified from CP2. */
    void handleContactChange(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);

        mSingleThreadExecutor.execute(() -> {
            try {
                // TODO(b/203605504) make those delay value configurable.
                int delaySeconds = 2;

                // TODO(b/203605504) we can, and probably should wait longer if there is an
                //  active sync running
                // if (!ContentResolver.getCurrentSyncs().isEmpty()) {
                //     delaySeconds = 30;
                // }

                // Create context as the user for which the notification is targeted
                Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                ContactsPerUserIndexer indexer = null;
                synchronized (mLock) {
                    indexer = mContactsIndexersLocked.get(userContext.getUser());
                    if (indexer == null) {
                        indexer = new ContactsPerUserIndexer(userContext);
                        indexer.initialize();
                        mContactsIndexersLocked.put(userContext.getUser(), indexer);
                        Log.d(TAG, "Create a new ContactIndexerPerUser for user "
                                + userContext.getUser().toString());
                    }
                }
                // Async call. The update will be scheduled on the SINGLE_SCHEDULED_EXECUTOR in
                // each per-user indexer.
                indexer.doDeltaUpdate(delaySeconds);
            } catch (Exception e) {
                Log.e(TAG,
                        "Failed to setup Delta Update for user " + userHandle.toString(),
                        e);
            }
        });
    }
}