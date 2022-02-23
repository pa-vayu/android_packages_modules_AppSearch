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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    public CompletableFuture<Void> indexContactsAsync(@NonNull Collection<Person> contacts,
            @NonNull Executor executor) {
        mIndexedContacts.addAll(contacts);
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.complete(null);
        return future;
    }

    @Override
    public CompletableFuture<Void> removeContactsByIdAsync(@NonNull Collection<String> ids,
            @NonNull Executor executor) {
        mRemovedIds.addAll(ids);
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.complete(null);
        return future;
    }
}
