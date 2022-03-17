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
import android.util.ArrayMap;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Map;
import java.util.Objects;

/**
 * Helper class to help build the {@link Person}.
 *
 * <p>It takes a {@link Person.Builder} with a map to help handle and aggregate {@link
 * ContactPoint}s, and put them in the {@link Person} during the build.
 *
 * <p>This class is not thread safe.
 *
 * @hide
 */
// TODO(b/203605504) We can also make it only generates a list of contact points. And move the
//  building of a Person out to the caller.
public final class PersonBuilderHelper {
    final private Person.Builder mBuilder;
    private Map<String, ContactPoint.Builder> mContactPointBuilders = new ArrayMap<>();

    public PersonBuilderHelper(@NonNull Person.Builder builder) {
        Objects.requireNonNull(builder);
        mBuilder = builder;
    }

    @NonNull
    public Person buildPerson() {
        for (ContactPoint.Builder builder : mContactPointBuilders.values()) {
            mBuilder.addContactPoint(builder.build());
        }
        return mBuilder.build();
    }

    @NonNull
    public Person.Builder getPersonBuilder() {
        return mBuilder;
    }

    @NonNull
    private ContactPoint.Builder getOrCreateContactPointBuilder(@NonNull String label) {
        ContactPoint.Builder builder = mContactPointBuilders.get(Objects.requireNonNull(label));
        if (builder == null) {
            builder = new ContactPoint.Builder(AppSearchHelper.NAMESPACE_NAME,
                    /*id=*/"", // doesn't matter for this nested type.
                    label);
            mContactPointBuilders.put(label, builder);
        }

        return builder;
    }

    @NonNull
    public PersonBuilderHelper addAppIdToPerson(@NonNull String label, @NonNull String appId) {
        getOrCreateContactPointBuilder(Objects.requireNonNull(label))
                .addAppId(Objects.requireNonNull(appId));
        return this;
    }

    public PersonBuilderHelper addEmailToPerson(@NonNull String label, @NonNull String email) {
        getOrCreateContactPointBuilder(Objects.requireNonNull(label))
                .addEmail(Objects.requireNonNull(email));
        return this;
    }

    @NonNull
    public PersonBuilderHelper addAddressToPerson(@NonNull String label, @NonNull String address) {
        getOrCreateContactPointBuilder(Objects.requireNonNull(label))
                .addAddress(Objects.requireNonNull(address));
        return this;
    }

    @NonNull
    public PersonBuilderHelper addPhoneToPerson(@NonNull String label, @NonNull String phone) {
        getOrCreateContactPointBuilder(Objects.requireNonNull(label))
                .addPhone(Objects.requireNonNull(phone));
        return this;
    }
}
