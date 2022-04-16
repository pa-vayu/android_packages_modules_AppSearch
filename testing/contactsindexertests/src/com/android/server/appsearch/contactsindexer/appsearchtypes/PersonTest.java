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

package com.android.server.appsearch.contactsindexer.appsearchtypes;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PersonTest {
    @Test
    public void testBuilder() {
        String namespace = "namespace";
        String id = "id";
        String name = "name";
        String givenName = "givenName";
        String middleName = "middleName";
        String lastName = "lastName";
        Uri externalUri = Uri.parse("http://external.com");
        Uri imageUri = Uri.parse("http://image.com");
        List<String> additionalNames = Arrays.asList("name1", "name2");
        List<String> affiliations = Arrays.asList("Org1", "Org2", "Org3");
        List<String> relations = Arrays.asList("relation1", "relation2");
        boolean isImportant = true;
        boolean isBot = true;
        String note = "note";
        ContactPoint contact1 = new ContactPoint.Builder(namespace, id + "1", "Home")
                .addAddress("addr1")
                .addPhone("phone1")
                .addEmail("email1")
                .addAppId("appId1")
                .build();
        ContactPoint contact2 = new ContactPoint.Builder(namespace, id + "2", "Work")
                .addAddress("addr2")
                .addPhone("phone2")
                .addEmail("email2")
                .addAppId("appId2")
                .build();

        Person person = new Person.Builder(namespace, id, name)
                .setGivenName(givenName)
                .setMiddleName(middleName)
                .setFamilyName(lastName)
                .setExternalUri(externalUri)
                .setImageUri(imageUri)
                .addAdditionalName(additionalNames.get(0))
                .addAdditionalName(additionalNames.get(1))
                .addAffiliation(affiliations.get(0))
                .addAffiliation(affiliations.get(1))
                .addAffiliation(affiliations.get(2))
                .addRelation(relations.get(0))
                .addRelation(relations.get(1))
                .setIsImportant(isImportant)
                .setIsBot(isBot)
                .setNote(note)
                .addContactPoint(contact1)
                .addContactPoint(contact2)
                .build();

        assertThat(person.getName()).isEqualTo(name);
        assertThat(person.getGivenName()).isEqualTo(givenName);
        assertThat(person.getMiddleName()).isEqualTo(middleName);
        assertThat(person.getFamilyName()).isEqualTo(lastName);
        assertThat(person.getExternalUri().toString()).isEqualTo(externalUri.toString());
        assertThat(person.getImageUri().toString()).isEqualTo(imageUri.toString());
        assertThat(person.getNote()).isEqualTo(note);
        assertThat(person.getAdditionalNames()).asList().isEqualTo(additionalNames);
        assertThat(person.getAffiliations()).asList().isEqualTo(affiliations);
        assertThat(person.getRelations()).asList().isEqualTo(relations);
        assertThat(person.getContactPoints()).asList().containsExactly(contact1, contact2);
    }
}