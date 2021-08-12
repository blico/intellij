/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.projectsystem;

import com.android.ide.common.util.PathString;
import com.android.projectmodel.ExternalAndroidLibrary;
import com.android.projectmodel.ExternalLibraryImpl;
import com.android.projectmodel.SelectiveResourceFolder;
import com.android.tools.idea.projectsystem.AndroidModuleSystem;
import com.android.tools.idea.projectsystem.DependencyScopeType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.android.libraries.UnpackedAars;
import com.google.idea.blaze.android.sync.model.AarLibrary;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.android.sync.model.BlazeAndroidSyncData;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.model.BlazeLibrary;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.sync.SyncCache;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.libraries.BlazeLibraryCollector;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.Collection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Blaze implementation of {@link AndroidModuleSystem}. */
public class BlazeModuleSystem extends BlazeModuleSystemBase {
  BlazeModuleSystem(Module module) {
    super(module);
  }

  public Collection<ExternalAndroidLibrary> getDependentLibraries() {
    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();

    if (blazeProjectData == null) {
      return ImmutableList.of();
    }

    if (isWorkspaceModule) {
      return SyncCache.getInstance(project)
          .get(BlazeModuleSystem.class, BlazeModuleSystem::getLibrariesForWorkspaceModule);
    }

    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    TargetIdeInfo target = blazeProjectData.getTargetMap().get(registry.getTargetKey(module));
    if (target == null) {
      // this can happen if the module points to the <android-resources>, <project-data-dir>
      // <project-data-dir> does not contain any resource
      // <android-resources> contains all external resources as module's local resources, so there's
      // no dependent libraries
      return ImmutableList.of();
    }

    BlazeAndroidSyncData androidSyncData =
        blazeProjectData.getSyncState().get(BlazeAndroidSyncData.class);
    if (androidSyncData == null) {
      return ImmutableList.of();
    }

    // It's possible to have duplicate ExternalLibrary when AarLibrary shared same library key.
    // Use set to avoid duplication.
    ImmutableSet.Builder<ExternalAndroidLibrary> libraries = ImmutableSet.builder();
    ExternalLibraryInterner externalLibraryInterner = ExternalLibraryInterner.getInstance(project);
    for (String libraryKey : registry.get(module).resourceLibraryKeys) {
      ImmutableMap<String, AarLibrary> aarLibraries = androidSyncData.importResult.aarLibraries;
      ExternalAndroidLibrary externalLibrary =
          toExternalLibrary(project, aarLibraries.get(libraryKey));
      if (externalLibrary != null) {
        libraries.add(externalLibraryInterner.intern(externalLibrary));
      }
    }
    return libraries.build();
  }

  private static ImmutableSet<ExternalAndroidLibrary> getLibrariesForWorkspaceModule(
      Project project, BlazeProjectData blazeProjectData) {
    ExternalLibraryInterner externalLibraryInterner = ExternalLibraryInterner.getInstance(project);
    ImmutableSet.Builder<ExternalAndroidLibrary> libraries = ImmutableSet.builder();
    for (BlazeLibrary library :
        BlazeLibraryCollector.getLibraries(
            ProjectViewManager.getInstance(project).getProjectViewSet(), blazeProjectData)) {
      if (library instanceof AarLibrary) {
        ExternalAndroidLibrary externalLibrary = toExternalLibrary(project, (AarLibrary) library);
        if (externalLibrary != null) {
          libraries.add(externalLibraryInterner.intern(externalLibrary));
        }
      }
    }
    return libraries.build();
  }

  @Nullable
  static ExternalAndroidLibrary toExternalLibrary(Project project, @Nullable AarLibrary library) {
    if (library == null) {
      return null;
    }
    UnpackedAars unpackedAars = UnpackedAars.getInstance(project);
    File aarFile = unpackedAars.getAarDir(library);
    if (aarFile == null) {
      logger.warn(
          String.format(
              "Fail to locate AAR file %s. Re-sync the project may solve the problem",
              library.aarArtifact));
      return null;
    }
    File resFolder = unpackedAars.getResourceDirectory(library);
    PathString resFolderPathString = resFolder == null ? null : new PathString(resFolder);
    return new ExternalLibraryImpl(library.key.toString())
        .withLocation(new PathString(aarFile))
        .withManifestFile(
            resFolderPathString == null
                ? null
                : resFolderPathString.getParentOrRoot().resolve("AndroidManifest.xml"))
        .withResFolder(
            resFolderPathString == null
                ? null
                : new SelectiveResourceFolder(resFolderPathString, null))
        .withSymbolFile(
            resFolderPathString == null
                ? null
                : resFolderPathString.getParentOrRoot().resolve("R.txt"))
        .withPackageName(library.resourcePackage);
  }

  @NotNull
  @Override
  public Collection<ExternalAndroidLibrary> getAndroidLibraryDependencies() {
    return getDependentLibraries();
  }

  @NotNull
  @Override
  public Collection<ExternalAndroidLibrary> getAndroidLibraryDependencies(
      @NotNull DependencyScopeType dependencyScopeType) {
    return getDependentLibraries();
  }
}
