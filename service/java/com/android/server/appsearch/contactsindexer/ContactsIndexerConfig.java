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

import android.provider.DeviceConfig;

import java.util.concurrent.TimeUnit;

/**
 * Contains all the keys for flags related to Contacts Indexer.
 *
 * @hide
 */
public class ContactsIndexerConfig {
    // LIMIT of -1 means no upper bound (see https://www.sqlite.org/lang_select.html)
    public static final int UPDATE_LIMIT_NONE = -1;

    private static final String KEY_CONTACTS_INSTANT_INDEXING_LIMIT =
            "contacts_instant_indexing_limit";
    public static final String KEY_CONTACTS_INDEXER_ENABLED = "contacts_indexer_enabled";
    public static final String KEY_CONTACTS_FULL_UPDATE_INTERVAL_MILLIS
            = "contacts_full_update_interval_millis";
    public static final String KEY_CONTACTS_FULL_UPDATE_LIMIT =
            "contacts_indexer_full_update_limit";
    public static final String KEY_CONTACTS_DELTA_UPDATE_LIMIT =
            "contacts_indexer_delta_update_limit";

    public static boolean isContactsIndexerEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_CONTACTS_INDEXER_ENABLED,
                /*defaultValue=*/ true);
    }

    public static int getContactsInstantIndexingLimit() {
        return DeviceConfig.getInt(DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_CONTACTS_INSTANT_INDEXING_LIMIT, /*defaultValue=*/ 1000);
    }

    public static long getContactsFullUpdateIntervalMillis() {
        return DeviceConfig.getLong(DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_CONTACTS_FULL_UPDATE_INTERVAL_MILLIS,
                /*defaultValue=*/ TimeUnit.DAYS.toMillis(30));
    }

    /**
     * Returns the maximum number of CP2 contacts indexed during a full update.
     *
     * <p>The value will be used as a LIMIT for querying CP2 during full update.
     */
    public static int getContactsFullUpdateLimit() {
        return DeviceConfig.getInt(DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_CONTACTS_FULL_UPDATE_LIMIT,
                /*defaultValue=*/ 10_000);
    }

    /**
     * Returns the maximum number of CP2 contacts indexed during a delta update.
     *
     * <p>The value will be used as a LIMIT for querying CP2 during the delta update.
     */
    public static int getContactsDeltaUpdateLimit() {
        // TODO(b/227419499) Based on the metrics, we can tweak this number. Right now it is same
        //  as the instant indexing limit, which is 1,000. From our stats in GMSCore, 95th
        //  percentile for number of contacts on the device is around 2000 contacts.
        return DeviceConfig.getInt(DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_CONTACTS_DELTA_UPDATE_LIMIT, /*defaultValue=*/ 1000);
    }
}
