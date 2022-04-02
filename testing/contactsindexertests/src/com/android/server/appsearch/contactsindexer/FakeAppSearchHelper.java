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
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;

import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class FakeAppSearchHelper extends AppSearchHelper {
    final int mDocLimit;
    final int mDeleteLimit;
    List<String> mRemovedIds = new ArrayList<>();
    List<Person> mIndexedContacts = new ArrayList<>();
    public FakeAppSearchHelper(@NonNull Context context) {
        this(context, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public FakeAppSearchHelper(@NonNull Context context, int docLimit, int deleteLimit) {
        super(context, Runnable::run);
        mDocLimit = docLimit;
        mDeleteLimit = deleteLimit;
    }

    void clear() {
        mRemovedIds.clear();
        mIndexedContacts.clear();
    }

    @Override
    public CompletableFuture<Void> indexContactsAsync(@NonNull Collection<Person> contacts,
            @NonNull ContactsUpdateStats updateStats) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (mIndexedContacts.size() + contacts.size() <= mDocLimit) {
            mIndexedContacts.addAll(contacts);
            future.complete(null);
        } else {
            for (Person person : contacts) {
                if (mIndexedContacts.size() >= mDocLimit) {
                    future.completeExceptionally(new AppSearchException(
                            AppSearchResult.RESULT_OUT_OF_SPACE, "Reached document limit."));
                    break;
                } else {
                    mIndexedContacts.add(person);
                }
            }
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> removeContactsByIdAsync(@NonNull Collection<String> ids,
            @NonNull ContactsUpdateStats updateStats) {
        mRemovedIds.addAll(ids);
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.complete(null);
        return future;
    }
}
