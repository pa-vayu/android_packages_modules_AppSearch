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
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;

import com.android.internal.util.Preconditions;

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
    static final String TAG = "PersonBuilderHelper";
    static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    // We want to store id separately even if we do have it set in the builder, since we
    // can't get its value out of the builder, which will be used to fetch fingerprints.
    final private String mId;
    final private Person.Builder mBuilder;
    private long mCreationTimestampMillis = -1;
    private Map<String, ContactPoint.Builder> mContactPointBuilders = new ArrayMap<>();

    public PersonBuilderHelper(@NonNull String id, @NonNull Person.Builder builder) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(builder);
        mId = id;
        mBuilder = builder;
    }

    /**
     * A {@link Person} is built and returned based on the current properties set in this helper.
     *
     * <p>A fingerprint is automatically generated and set.
     */
    @NonNull
    public Person buildPerson() {
        Preconditions.checkState(mCreationTimestampMillis >= 0,
                "creationTimestamp must be explicitly set in the PersonBuilderHelper.");

        for (ContactPoint.Builder builder : mContactPointBuilders.values()) {
            // We don't need to reset it for generating fingerprint. But still set it 0 here to
            // avoid creationTimestamp automatically generated using current time. So our testing
            // could be easier.
            builder.setCreationTimestampMillis(0);
            mBuilder.addContactPoint(builder.build());
        }
        // Set the fingerprint and creationTimestamp to 0 to calculate the actual fingerprint.
        mBuilder.setFingerprint(EMPTY_BYTE_ARRAY);
        mBuilder.setCreationTimestampMillis(0);
        // Build a person for generating the fingerprint.
        Person contactForFingerPrint = mBuilder.build();
        try {
            byte[] fingerprint = generateFingerprintMD5(contactForFingerPrint);
            mBuilder.setFingerprint(fingerprint);
            mBuilder.setCreationTimestampMillis(mCreationTimestampMillis);
        } catch (NoSuchAlgorithmException e) {
            // debug logging here to avoid flooding the log.
            Log.d(TAG,
                    "Failed to generate fingerprint for contact " + contactForFingerPrint.getId(),
                    e);
        }
        // Build a final person with fingerprint set.
        return mBuilder.build();
    }

    /** Gets the ID of this {@link Person}. */
    @NonNull
    String getId() {
        return mId;
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
    public PersonBuilderHelper setCreationTimestampMillis(long creationTimestampMillis) {
        mCreationTimestampMillis = creationTimestampMillis;
        return this;
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

    @NonNull
    static byte[] generateFingerprintMD5(@NonNull Person person) throws NoSuchAlgorithmException {
        Objects.requireNonNull(person);

        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(person.toString().getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }
}
