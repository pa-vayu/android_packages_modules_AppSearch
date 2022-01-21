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

package com.android.server.appsearch.visibilitystore;

import static android.Manifest.permission.READ_GLOBAL_APP_SEARCH_DATA;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.VisibilityDocument;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;
import com.android.server.appsearch.external.localstorage.UnlimitedLimitConfig;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.util.Map;

public class VisibilityCheckerImplTest {
    /**
     * Always trigger optimize in this class. OptimizeStrategy will be tested in its own test class.
     */
    private static final OptimizeStrategy ALWAYS_OPTIMIZE = optimizeInfo -> true;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private final Map<UserHandle, PackageManager> mMockPackageManagers = new ArrayMap<>();
    private Context mContext;
    private VisibilityCheckerImpl mVisibilityChecker;
    private VisibilityStore mVisibilityStore;
    private int mUid;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        mContext = new ContextWrapper(context) {
            @Override
            public Context createContextAsUser(UserHandle user, int flags) {
                return new ContextWrapper(super.createContextAsUser(user, flags)) {
                    @Override
                    public PackageManager getPackageManager() {
                        return getMockPackageManager(user);
                    }
                };
            }

            @Override
            public PackageManager getPackageManager() {
                return createContextAsUser(getUser(), /*flags=*/ 0).getPackageManager();
            }
        };

        mVisibilityChecker = new VisibilityCheckerImpl(mContext);
        // Give ourselves global query permissions
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                mTemporaryFolder.newFolder(),
                new UnlimitedLimitConfig(),
                /*initStatsBuilder=*/ null,
                ALWAYS_OPTIMIZE,
                mVisibilityChecker);
        mVisibilityStore = new VisibilityStore(appSearchImpl);
        mUid = mContext.getPackageManager().getPackageUid(mContext.getPackageName(), /*flags=*/ 0);
    }

    @Test
    public void testDoesCallerHaveSystemAccess() {
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        assertThat(mVisibilityChecker.doesCallerHaveSystemAccess(mContext.getPackageName()))
                .isTrue();

        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_DENIED);
        assertThat(mVisibilityChecker.doesCallerHaveSystemAccess(mContext.getPackageName()))
                .isFalse();
    }

    @Test
    public void testSetVisibility_displayedBySystem() throws Exception {
        // Make sure we have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);

        // Create two VisibilityDocument that are not displayed by system.
        VisibilityDocument
                visibilityDocument1 = new VisibilityDocument.Builder(/*id=*/"prefix/Schema1")
                .setNotDisplayedBySystem(true).build();
        VisibilityDocument
                visibilityDocument2 = new VisibilityDocument.Builder(/*id=*/"prefix/Schema2")
                .setNotDisplayedBySystem(true).build();
        mVisibilityStore.setVisibility(
                ImmutableList.of(visibilityDocument1, visibilityDocument2));

        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/Schema1",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore))
                .isFalse();
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/Schema2",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore))
                .isFalse();

        // Rewrite Visibility Document 1 to let it accessible to the system.
        visibilityDocument1 = new VisibilityDocument.Builder(/*id=*/"prefix/Schema1").build();
        mVisibilityStore.setVisibility(
                ImmutableList.of(visibilityDocument1, visibilityDocument2));
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/Schema1",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore))
                .isTrue();
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/Schema2",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testSetVisibility_visibleToPackages() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[32];
        int uidFoo = 1;

        // Values for a "bar" client
        String packageNameBar = "packageBar";
        byte[] sha256CertBar = new byte[32];
        int uidBar = 2;

        // Can't be the same value as uidFoo nor uidBar
        int uidNotFooOrBar = 3;

        // Make sure none of them have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameBar))
                .thenReturn(PERMISSION_DENIED);

        // Grant package access
        VisibilityDocument
                visibilityDocument1 = new VisibilityDocument.Builder(/*id=*/"prefix/SchemaFoo")
                .addVisibleToPackage(new PackageIdentifier(packageNameFoo, sha256CertFoo)).build();
        VisibilityDocument
                visibilityDocument2 = new VisibilityDocument.Builder(/*id=*/"prefix/SchemaBar")
                .addVisibleToPackage(new PackageIdentifier(packageNameBar, sha256CertBar)).build();
        mVisibilityStore.setVisibility(
                ImmutableList.of(visibilityDocument1, visibilityDocument2));

        // Should fail if PackageManager doesn't see that it has the proper certificate
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(false);
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isFalse();

        // Should fail if PackageManager doesn't think the package belongs to the uid
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidNotFooOrBar);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isFalse();

        // But if uid and certificate match, then we should have access
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isTrue();

        when(mockPackageManager.getPackageUid(eq(packageNameBar), /*flags=*/ anyInt()))
                .thenReturn(uidBar);
        when(mockPackageManager.hasSigningCertificate(
                packageNameBar, sha256CertBar, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaBar",
                uidBar,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isTrue();

        // Save default document and, then we shouldn't have access
        visibilityDocument1 = new VisibilityDocument.Builder(/*id=*/"prefix/SchemaFoo").build();
        visibilityDocument2 = new VisibilityDocument.Builder(/*id=*/"prefix/SchemaBar").build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument1, visibilityDocument2));
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isFalse();
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaBar",
                uidBar,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testIsSchemaSearchableByCaller_packageAccessibilityHandlesNameNotFoundException()
            throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Pretend we can't find the Foo package.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());

        // Make sure "foo" doesn't have global query privileges
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        VisibilityDocument
                visibilityDocument1 = new VisibilityDocument.Builder(/*id=*/"prefix/SchemaFoo")
                .addVisibleToPackage(new PackageIdentifier(packageNameFoo, sha256CertFoo)).build();
        // Grant package access
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument1));

        // If we can't verify the Foo package that has access, assume it doesn't have access.
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                "prefix/SchemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testEmptyPrefix() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Set it up such that the test package has global query privileges, but "foo" doesn't.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        VisibilityDocument
                visibilityDocument = new VisibilityDocument.Builder(/*id=*/"$/Schema")
                .addVisibleToPackage(new PackageIdentifier(packageNameFoo, sha256CertFoo)).build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));

        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                /*packageName=*/ "",
                "$/Schema",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore)).isTrue();

        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                /*packageName=*/ "",
                "$/Schema",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore)).isTrue();
    }

    @Test
    public void testSetSchema_defaultPlatformVisible() throws Exception {
        // Make sure we have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        VisibilityDocument visibilityDocument = new VisibilityDocument.Builder(
                /*id=*/prefix + "Schema").build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));
        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                prefix + "Schema",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore))
                .isTrue();
    }

    @Test
    public void testSetSchema_platformHidden() throws Exception {
        // Make sure we have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        VisibilityDocument visibilityDocument = new VisibilityDocument.Builder(
                /*id=*/prefix + "Schema")
                .setNotDisplayedBySystem(true).build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));

        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                prefix + "Schema",
                mUid,
                /*callerHasSystemAccess=*/ true,
                mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testSetSchema_defaultNotVisibleToPackages() throws Exception {
        String packageName = "com.package";

        // Make sure package doesn't global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, packageName)).thenReturn(PERMISSION_DENIED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        VisibilityDocument visibilityDocument = new VisibilityDocument.Builder(
                /*id=*/prefix + "Schema").build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));

        assertThat(mVisibilityChecker
                .isSchemaSearchableByCaller(
                        "package",
                        prefix + "Schema",
                        /*callerUid=*/ 42,
                        /*callerHasSystemAccess=*/ false,
                        mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testSetSchema_visibleToPackages() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);

        // Make sure foo doesn't have global query privileges
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        String prefix = PrefixUtil.createPrefix("package", "database");

        VisibilityDocument visibilityDocument = new VisibilityDocument.Builder(
                /*id=*/prefix + "Schema")
                .addVisibleToPackage(new PackageIdentifier(packageNameFoo, sha256CertFoo)).build();
        mVisibilityStore.setVisibility(ImmutableList.of(visibilityDocument));

        assertThat(mVisibilityChecker.isSchemaSearchableByCaller(
                "package",
                prefix + "Schema",
                uidFoo,
                /*callerHasSystemAccess=*/ false,
                mVisibilityStore))
                .isTrue();
    }

    @NonNull
    private PackageManager getMockPackageManager(@NonNull UserHandle user) {
        PackageManager pm = mMockPackageManagers.get(user);
        if (pm == null) {
            pm = Mockito.mock(PackageManager.class);
            mMockPackageManagers.put(user, pm);
        }
        return pm;
    }
}
