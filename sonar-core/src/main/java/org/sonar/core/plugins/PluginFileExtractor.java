/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2008-2011 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.core.plugins;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.Plugin;
import org.sonar.api.utils.SonarException;
import org.sonar.api.utils.ZipUtils;
import org.sonar.updatecenter.common.PluginKeyUtils;
import org.sonar.updatecenter.common.PluginManifest;

import javax.swing.plaf.metal.MetalTabbedPaneUI;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.zip.ZipEntry;

public class PluginFileExtractor {

  public DefaultPluginMetadata installInSameLocation(File pluginFile, boolean isCore) {
    return install(pluginFile, isCore, null);
  }

  public DefaultPluginMetadata install(File pluginFile, boolean isCore, File toDir) {
    DefaultPluginMetadata metadata = extractMetadata(pluginFile, isCore);
    return install(metadata, toDir);
  }

  public DefaultPluginMetadata install(DefaultPluginMetadata metadata, File toDir) {
    try {
      File pluginFile = metadata.getFile();
      File pluginBasedir;
      if (toDir != null) {
        pluginBasedir = toDir;
        FileUtils.forceMkdir(pluginBasedir);
        File targetFile = new File(pluginBasedir, pluginFile.getName());
        FileUtils.copyFile(pluginFile, targetFile);
        metadata.addDeployedFile(targetFile);
      } else {
        pluginBasedir = pluginFile.getParentFile();
        metadata.addDeployedFile(pluginFile);
      }

      if (metadata.getPathsToInternalDeps().length > 0) {
        // needs to unzip the jar
        ZipUtils.unzip(pluginFile, pluginBasedir, new ZipUtils.ZipEntryFilter() {
          public boolean accept(ZipEntry entry) {
            return entry.getName().startsWith("META-INF/lib");
          }
        });
        for (String depPath : metadata.getPathsToInternalDeps()) {
          File dependency = new File(pluginBasedir, depPath);
          if (!dependency.isFile() || !dependency.exists()) {
            throw new IllegalArgumentException("Dependency " + depPath + " can not be found in " + pluginFile.getName());
          }
          metadata.addDeployedFile(dependency);
        }
      }

      for (File extension : metadata.getDeprecatedExtensions()) {
        File toFile = new File(pluginBasedir, extension.getName());
        FileUtils.copyFile(extension, toFile);
        metadata.addDeployedFile(toFile);
      }

      return metadata;

    } catch (IOException e) {
      throw new SonarException("Fail to install plugin: " + metadata, e);
    }
  }

  public DefaultPluginMetadata extractMetadata(File file, boolean isCore) {
    try {
      PluginManifest manifest = new PluginManifest(file);
      DefaultPluginMetadata metadata = DefaultPluginMetadata.create(file);
      metadata.setKey(manifest.getKey());
      metadata.setName(manifest.getName());
      metadata.setDescription(manifest.getDescription());
      metadata.setLicense(manifest.getLicense());
      metadata.setOrganization(manifest.getOrganization());
      metadata.setOrganizationUrl(manifest.getOrganizationUrl());
      metadata.setMainClass(manifest.getMainClass());
      metadata.setVersion(manifest.getVersion());
      metadata.setHomepage(manifest.getHomepage());
      metadata.setPathsToInternalDeps(manifest.getDependencies());
      metadata.setUseChildFirstClassLoader(manifest.isUseChildFirstClassLoader());
      metadata.setBasePlugin(manifest.getBasePlugin());
      metadata.setCore(isCore);
      if (metadata.isOldManifest()) {
        completeDeprecatedMetadata(metadata);
      }
      return metadata;

    } catch (IOException e) {
      throw new IllegalStateException("Fail to extract plugin metadata from file: " + file, e);
    }
  }

  private void completeDeprecatedMetadata(DefaultPluginMetadata metadata) throws IOException {
    String mainClass = metadata.getMainClass();
    File pluginFile = metadata.getFile();
    try {
      // copy file in a temp directory because Windows+Oracle JVM Classloader lock the JAR file
      File tempFile = File.createTempFile(pluginFile.getName(), null);
      FileUtils.copyFile(pluginFile, tempFile);
      
      URLClassLoader pluginClassLoader = URLClassLoader.newInstance(new URL[]{tempFile.toURI().toURL()}, getClass().getClassLoader());
      Plugin pluginInstance = (Plugin) pluginClassLoader.loadClass(mainClass).newInstance();
      metadata.setKey(PluginKeyUtils.sanitize(pluginInstance.getKey()));
      metadata.setDescription(pluginInstance.getDescription());
      metadata.setName(pluginInstance.getName());

    } catch (Exception e) {
      throw new RuntimeException("The metadata main class can not be created. Plugin file=" + pluginFile.getName() + ", class=" + mainClass, e);
    }
  }
}
