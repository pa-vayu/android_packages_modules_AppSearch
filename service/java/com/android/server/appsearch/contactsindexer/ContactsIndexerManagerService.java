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
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.appsearch.AppSearchModule;

import java.io.File;
import java.util.Objects;

/**
 * Manages the per device-user ContactsIndexer instance to index CP2 contacts into AppSearch.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public final class ContactsIndexerManagerService extends SystemService {
    static final String TAG = "ContactsIndexerManagerService";

    private final Context mContext;
    // Sparse array of ContactsIndexerUserInstance indexed by the device-user ID.
    private final SparseArray<ContactsIndexerUserInstance> mContactsIndexersLocked =
            new SparseArray<>();

    /** Constructs a {@link ContactsIndexerManagerService}. */
    public ContactsIndexerManagerService(@NonNull Context context) {
        super(context);
        mContext = Objects.requireNonNull(context);
    }

    @Override
    public void onStart() {
        LocalManagerRegistry.addManager(LocalService.class, new LocalService());
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Objects.requireNonNull(user);
        UserHandle userHandle = user.getUserHandle();
        synchronized (mContactsIndexersLocked) {
            int userId = userHandle.getIdentifier();
            ContactsIndexerUserInstance instance = mContactsIndexersLocked.get(userId);
            if (instance == null) {
                Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                File appSearchDir = AppSearchModule.getAppSearchDir(userHandle);
                File contactsDir = new File(appSearchDir, "contacts");
                try {
                    instance = ContactsIndexerUserInstance.createInstance(userContext, contactsDir);
                    Log.d(TAG, "Created Contacts Indexer instance for user " + userHandle);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to create Contacts Indexer instance for user "
                            + userHandle, t);
                    return;
                }
                mContactsIndexersLocked.put(userId, instance);
            }
            instance.startAsync();
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        Objects.requireNonNull(user);
        UserHandle userHandle = user.getUserHandle();
        synchronized (mContactsIndexersLocked) {
            int userId = userHandle.getIdentifier();
            ContactsIndexerUserInstance instance = mContactsIndexersLocked.get(userId);
            if (instance != null) {
                mContactsIndexersLocked.delete(userId);
                try {
                    instance.shutdown();
                } catch (InterruptedException e) {
                    Log.w(TAG, "Failed to shutdown contacts indexer for " + userHandle, e);
                }
            }
        }
    }

    class LocalService {
        void doFullUpdateForUser(@UserIdInt int userId, CancellationSignal signal) {
            synchronized (mContactsIndexersLocked) {
                ContactsIndexerUserInstance instance = mContactsIndexersLocked.get(userId);
                if (instance != null) {
                    instance.doFullUpdateAsync(signal);
                }
            }
        }
    }
}
