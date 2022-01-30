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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.Collection;
import java.util.Objects;

/**
 * ContentObserver for {@link ContactsIndexerManagerService} to listen on the contact changes from
 * CP2.
 */
final class ContactsContentObserver extends ContentObserver {
    private static final String TAG = "ContactsContentObserver";
    private final ContactsIndexerManagerService mContactsIndexerManagerService;

    /** Constructs a {@link ContactsContentObserver}. */
    public ContactsContentObserver(
            @Nullable Handler handler,
            @NonNull ContactsIndexerManagerService contactsIndexerManagerService) {
        super(handler);

        mContactsIndexerManagerService = Objects.requireNonNull(contactsIndexerManagerService);
    }

    @Override
    public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags,
            UserHandle userHandle) {
        // uris and flags are ignored:
        //  - uris: since we only listen on the root uri: content://com.android.contacts, we will
        //  always get this uri. So it is not useful for us.
        //  - flags: it can be used for some optimization (e.g. know if the notification is for an
        //  insert or delete) but right now we don't use it.
        // selfChange should always be false:
        //  - Since Contacts Indexer only reads from CP2
        if (selfChange) {
            return;
        }

        mContactsIndexerManagerService.handleContactChange(userHandle);
    }
}
