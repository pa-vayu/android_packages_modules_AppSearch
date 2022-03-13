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

import android.annotation.NonNull;
import android.content.Context;

import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Helper class to manage the Person corpus in AppSearch.
 *
 * @hide
 */
public class AppSearchHelper {
    public static final int BASE_SCORE = 1;
    // Namespace needed to be used for ContactsIndexer to index the contacts
    public static final String NAMESPACE = "";

    private final Context mContext;

    AppSearchHelper(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
    }

    void indexContacts(@NonNull Executor executor, @NonNull Person... contacts) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(contacts);

        throw new UnsupportedOperationException("indexContact not implemented.");
    }

    void removeContactsById(@NonNull Executor executor, @NonNull String... ids) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(ids);

        throw new UnsupportedOperationException("removeContactsById not implemented.");
    }
}
