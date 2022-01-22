/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.visibilitystore;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.VisibilityDocument;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;
import com.android.server.appsearch.external.localstorage.UnlimitedLimitConfig;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class VisibilityStoreTest {

    /**
     * Always trigger optimize in this class. OptimizeStrategy will be tested in its own test class.
     */
    private static final OptimizeStrategy ALWAYS_OPTIMIZE = optimizeInfo -> true;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private File mAppSearchDir;
    private AppSearchImpl mAppSearchImpl;
    private VisibilityStore mVisibilityStore;

    @Before
    public void setUp() throws Exception {
        mAppSearchDir = mTemporaryFolder.newFolder();
        mAppSearchImpl =
                AppSearchImpl.create(
                        mAppSearchDir,
                        new UnlimitedLimitConfig(),
                        /*initStatsBuilder=*/ null,
                        ALWAYS_OPTIMIZE,
                        /*visibilityChecker=*/ null);
        mVisibilityStore = new VisibilityStore(mAppSearchImpl);
    }

    @After
    public void tearDown() {
        mAppSearchImpl.close();
    }

    /**
     * Make sure that we don't conflict with any special characters that AppSearchImpl has reserved.
     */
    @Test
    public void testValidPackageName() {
        assertThat(VisibilityStore.VISIBILITY_PACKAGE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.PACKAGE_DELIMITER));
        assertThat(VisibilityStore.VISIBILITY_PACKAGE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.DATABASE_DELIMITER));
    }

    /**
     * Make sure that we don't conflict with any special characters that AppSearchImpl has reserved.
     */
    @Test
    public void testValidDatabaseName() {
        assertThat(VisibilityStore.VISIBILITY_DATABASE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.PACKAGE_DELIMITER));
        assertThat(VisibilityStore.VISIBILITY_DATABASE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.DATABASE_DELIMITER));
    }

    @Test
    public void testSetAndGetVisibility() throws Exception {
        String prefix = PrefixUtil.createPrefix("packageName", "databaseName");
        VisibilityDocument visibilityDocument =
                new VisibilityDocument.Builder(prefix + "Email")
                        .setNotDisplayedBySystem(true)
                        .addVisibleToPackage(new PackageIdentifier("pkgBar", new byte[32]))
                        .build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));

        assertThat(mVisibilityStore.getVisibility(prefix + "Email")).isEqualTo(visibilityDocument);
    }

    @Test
    public void testRemoveVisibility() throws Exception {
        VisibilityDocument visibilityDocument =
                new VisibilityDocument.Builder("Email")
                        .setNotDisplayedBySystem(true)
                        .addVisibleToPackage(new PackageIdentifier("pkgBar", new byte[32]))
                        .build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));

        assertThat(mVisibilityStore.getVisibility("Email")).isEqualTo(visibilityDocument);

        mVisibilityStore.removeVisibility(ImmutableSet.of(visibilityDocument.getId()));
        assertThat(mVisibilityStore.getVisibility("Email")).isNull();
    }
}
