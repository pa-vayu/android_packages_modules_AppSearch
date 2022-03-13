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

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Objects;

class TestUtils {
    static void assertEquals(@NonNull ContactPoint actual,
            @NonNull ContactPoint expected) {
        Objects.requireNonNull(actual);
        Objects.requireNonNull(expected);

        if (actual == expected) {
            return;
        }

        // TODO(b/203605504) use toBuilder to reset creationTimestamp so we can directly compare
        //  two GenericDocuments. This way, we won't miss adding any new properties in the future.
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getLabel()).isEqualTo(expected.getLabel());
        assertThat(actual.getAppIds()).isEqualTo(expected.getAppIds());
        assertThat(actual.getEmails()).isEqualTo(expected.getEmails());
        assertThat(actual.getAddresses()).isEqualTo(expected.getAddresses());
        assertThat(actual.getPhones()).isEqualTo(expected.getPhones());
    }

    static void assertEquals(@NonNull Person actual, @NonNull Person expected) {
        Objects.requireNonNull(actual);
        Objects.requireNonNull(expected);

        if (actual == expected) {
            return;
        }

        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getName()).isEqualTo(expected.getName());
        assertThat(actual.getGivenName()).isEqualTo(expected.getGivenName());
        assertThat(actual.getMiddleName()).isEqualTo(expected.getMiddleName());
        assertThat(actual.getFamilyName()).isEqualTo(expected.getFamilyName());
        assertThat(actual.getExternalUri()).isEqualTo(expected.getExternalUri());
        assertThat(actual.getImageUri()).isEqualTo(expected.getImageUri());
        assertThat(actual.isImportant()).isEqualTo(expected.isImportant());
        assertThat(actual.isBot()).isEqualTo(expected.isBot());
        assertThat(actual.getAdditionalNames()).isEqualTo(expected.getAdditionalNames());
        // TODO(b/203605504) use toBuilder to reset creationTimestamp so we can directly compare
        //  two GenericDocuments. This way, we won't miss adding any new properties in the future.
        // Compare two contact point arrays. We can't directly use assert(genericDoc1).isEqualTo
        // (genericDoc2) since the creationTimestamps are different, and they can't easily be
        // reset to 0.
        ContactPoint[] contactPointsActual = actual.getContactPoints();
        ContactPoint[] contactPointsExpected = expected.getContactPoints();
        assertThat(contactPointsActual.length).isEqualTo(contactPointsExpected.length);
        for (int i = 0; i < contactPointsActual.length; ++i) {
            assertEquals(contactPointsActual[i], contactPointsExpected[i]);
        }
    }
}
