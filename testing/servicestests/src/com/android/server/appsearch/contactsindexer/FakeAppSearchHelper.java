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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

public final class FakeAppSearchHelper extends AppSearchHelper {
    public FakeAppSearchHelper(@NonNull Context context) {
        super(context);
    }

    List<String> mRemovedIds = new ArrayList<>();
    List<Person> mIndexedContacts = new ArrayList<>();

    void clear() {
        mRemovedIds.clear();
        mIndexedContacts.clear();
    }

    @Override
    void indexContacts(@NonNull Executor executor, @NonNull Person... contacts) {
        mIndexedContacts.addAll(Arrays.asList(contacts));
    }

    @Override
    void removeContactsById(@NonNull Executor executor, @NonNull String... ids) {
        mRemovedIds.addAll(Arrays.asList(ids));
    }
}
