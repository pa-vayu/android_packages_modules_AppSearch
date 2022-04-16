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
    private static final String CONTACTS_INDEXER_ENABLED = "contacts_indexer_enabled";
    private static final String CONTACTS_FULL_UPDATE_INTERVAL_MILLIS
            = "contacts_full_update_interval_millis";

    public static boolean isContactsIndexerEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_APPSEARCH, CONTACTS_INDEXER_ENABLED,
                /*defaultValue=*/ true);
    }

    public static long getContactsFullUpdateIntervalMillis() {
        return DeviceConfig.getLong(DeviceConfig.NAMESPACE_APPSEARCH,
                CONTACTS_FULL_UPDATE_INTERVAL_MILLIS,
                /*defaultValue=*/ TimeUnit.DAYS.toMillis(30));
    }
}