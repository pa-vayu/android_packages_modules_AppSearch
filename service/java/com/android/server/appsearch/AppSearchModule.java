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

package com.android.server.appsearch;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.appsearch.contactsindexer.ContactsIndexerConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerManagerService;

public class AppSearchModule {
    private static final String TAG = "AppSearchModule";

    public static final class Lifecycle extends SystemService {
        private AppSearchManagerService mAppSearchManagerService;
        @Nullable private ContactsIndexerManagerService mContactsIndexerManagerService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mAppSearchManagerService = new AppSearchManagerService(getContext());

            try {
                mAppSearchManagerService.onStart();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start AppSearch service", e);
                // If AppSearch service fails to start, skip starting ContactsIndexer service
                // since it indexes CP2 contacts into AppSearch builtin:Person corpus
                return;
            }

            if (DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_APPSEARCH,
                    ContactsIndexerConfig.CONTACTS_INDEXER_ENABLED,
                    /*defaultValue=*/ false)) {
                mContactsIndexerManagerService = new ContactsIndexerManagerService(getContext());
                try {
                    mContactsIndexerManagerService.onStart();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start Contacts Indexer service", e);
                    // Release the Contacts Indexer instance as it won't be started until the next
                    // system_server restart on a device reboot.
                    mContactsIndexerManagerService = null;
                }
            }
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            mAppSearchManagerService.onBootPhase(phase);
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mAppSearchManagerService.onUserUnlocking(user);
            if (mContactsIndexerManagerService != null) {
                mContactsIndexerManagerService.onUserUnlocking(user);
            }
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mAppSearchManagerService.onUserStopping(user);
            if (mContactsIndexerManagerService != null) {
                mContactsIndexerManagerService.onUserStopping(user);
            }
        }
    }
}
