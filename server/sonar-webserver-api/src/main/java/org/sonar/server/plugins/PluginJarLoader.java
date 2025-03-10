/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.plugins;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.commons.io.FileUtils;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.platform.PluginInfo;
import org.sonar.core.platform.SonarQubeVersion;
import org.sonar.server.platform.ServerFileSystem;
import org.sonar.updatecenter.common.Version;

import static java.lang.String.format;
import static org.apache.commons.io.FileUtils.moveFile;
import static org.sonar.core.util.FileUtils.deleteQuietly;
import static org.sonar.server.log.ServerProcessLogging.STARTUP_LOGGER_NAME;
import static org.sonar.server.plugins.PluginType.BUNDLED;
import static org.sonar.server.plugins.PluginType.EXTERNAL;

public class PluginJarLoader {
  private static final Logger LOG = Loggers.get(PluginJarLoader.class);

  // List of plugins that are silently removed if installed
  private static final Set<String> DEFAULT_BLACKLISTED_PLUGINS = Set.of("scmactivity", "issuesreport", "genericcoverage");
  // List of plugins that should prevent the server to finish its startup
  private static final Set<String> FORBIDDEN_INCOMPATIBLE_PLUGINS = Set.of(
    "sqale", "report", "views", "authgithub", "authgitlab", "authbitbucket", "authsaml", "ldap", "scmgit", "scmsvn");

  private static final String LOAD_ERROR_GENERIC_MESSAGE = "Startup failed: Plugins can't be loaded. See web logs for more information";

  private final ServerFileSystem fs;
  private final SonarQubeVersion sonarQubeVersion;
  private final Set<String> blacklistedPluginKeys;

  @Inject
  public PluginJarLoader(ServerFileSystem fs, SonarQubeVersion sonarQubeVersion) {
    this(fs, sonarQubeVersion, DEFAULT_BLACKLISTED_PLUGINS);
  }

  PluginJarLoader(ServerFileSystem fs, SonarQubeVersion sonarQubeVersion, Set<String> blacklistedPluginKeys) {
    this.fs = fs;
    this.sonarQubeVersion = sonarQubeVersion;
    this.blacklistedPluginKeys = blacklistedPluginKeys;
  }

  /**
   * Load the plugins that are located in lib/extensions and extensions/plugins. Blacklisted plugins are deleted.
   */
  public Collection<ServerPluginInfo> loadPlugins() {
    Map<String, ServerPluginInfo> bundledPluginsByKey = new LinkedHashMap<>();
    for (ServerPluginInfo bundled : getBundledPluginsMetadata()) {
      failIfContains(bundledPluginsByKey, bundled,
        plugin -> MessageException.of(format("Found two versions of the plugin %s [%s] in the directory %s. Please remove one of %s or %s.",
          bundled.getName(), bundled.getKey(), getRelativeDir(fs.getInstalledBundledPluginsDir()), bundled.getNonNullJarFile().getName(), plugin.getNonNullJarFile().getName())));
      bundledPluginsByKey.put(bundled.getKey(), bundled);
    }

    Map<String, ServerPluginInfo> externalPluginsByKey = new LinkedHashMap<>();
    for (ServerPluginInfo external : getExternalPluginsMetadata()) {
      failIfContains(bundledPluginsByKey, external,
        plugin -> MessageException.of(format("Found a plugin '%s' in the directory '%s' with the same key [%s] as a built-in feature '%s'. Please remove '%s'.",
          external.getName(), getRelativeDir(fs.getInstalledExternalPluginsDir()), external.getKey(), plugin.getName(),
          new File(getRelativeDir(fs.getInstalledExternalPluginsDir()), external.getNonNullJarFile().getName()))));
      failIfContains(externalPluginsByKey, external,
        plugin -> MessageException.of(format("Found two versions of the plugin '%s' [%s] in the directory '%s'. Please remove %s or %s.", external.getName(), external.getKey(),
          getRelativeDir(fs.getInstalledExternalPluginsDir()), external.getNonNullJarFile().getName(), plugin.getNonNullJarFile().getName())));
      externalPluginsByKey.put(external.getKey(), external);
    }

    for (PluginInfo downloaded : getDownloadedPluginsMetadata()) {
      failIfContains(bundledPluginsByKey, downloaded,
        plugin -> MessageException.of(format("Fail to update plugin: %s. Built-in feature with same key already exists: %s. Move or delete plugin from %s directory",
          plugin.getName(), plugin.getKey(), getRelativeDir(fs.getDownloadedPluginsDir()))));

      ServerPluginInfo installedPlugin;
      if (externalPluginsByKey.containsKey(downloaded.getKey())) {
        deleteQuietly(externalPluginsByKey.get(downloaded.getKey()).getNonNullJarFile());
        installedPlugin = moveDownloadedPluginToExtensions(downloaded);
        LOG.info("Plugin {} [{}] updated to version {}", installedPlugin.getName(), installedPlugin.getKey(), installedPlugin.getVersion());
      } else {
        installedPlugin = moveDownloadedPluginToExtensions(downloaded);
        LOG.info("Plugin {} [{}] installed", installedPlugin.getName(), installedPlugin.getKey());
      }

      externalPluginsByKey.put(downloaded.getKey(), installedPlugin);
    }

    Map<String, ServerPluginInfo> plugins = new HashMap<>(externalPluginsByKey.size() + bundledPluginsByKey.size());
    plugins.putAll(externalPluginsByKey);
    plugins.putAll(bundledPluginsByKey);

    unloadIncompatiblePlugins(plugins);

    return plugins.values();
  }

  /**
   * Removes the plugins that are not compatible with current environment.
   */
  private static void unloadIncompatiblePlugins(Map<String, ServerPluginInfo> pluginsByKey) {
    // loop as long as the previous loop ignored some plugins. That allows to support dependencies
    // on many levels, for example D extends C, which extends B, which requires A. If A is not installed,
    // then B, C and D must be ignored. That's not possible to achieve this algorithm with a single iteration over plugins.
    Set<String> removedKeys = new HashSet<>();
    do {
      removedKeys.clear();
      for (ServerPluginInfo plugin : pluginsByKey.values()) {
        if (!isCompatible(plugin, pluginsByKey)) {
          removedKeys.add(plugin.getKey());
        }
      }
      for (String removedKey : removedKeys) {
        pluginsByKey.remove(removedKey);
      }
    } while (!removedKeys.isEmpty());
  }

  @VisibleForTesting
  static boolean isCompatible(ServerPluginInfo plugin, Map<String, ServerPluginInfo> allPluginsByKeys) {
    if (!Strings.isNullOrEmpty(plugin.getBasePlugin()) && !allPluginsByKeys.containsKey(plugin.getBasePlugin())) {
      // it extends a plugin that is not installed
      LOG.warn("Plugin {} [{}] is ignored because its base plugin [{}] is not installed", plugin.getName(), plugin.getKey(), plugin.getBasePlugin());
      return false;
    }

    for (PluginInfo.RequiredPlugin requiredPlugin : plugin.getRequiredPlugins()) {
      PluginInfo installedRequirement = allPluginsByKeys.get(requiredPlugin.getKey());
      if (installedRequirement == null) {
        // it requires a plugin that is not installed
        LOG.warn("Plugin {} [{}] is ignored because the required plugin [{}] is not installed", plugin.getName(), plugin.getKey(), requiredPlugin.getKey());
        return false;
      }
      Version installedRequirementVersion = installedRequirement.getVersion();
      if (installedRequirementVersion != null && requiredPlugin.getMinimalVersion().compareToIgnoreQualifier(installedRequirementVersion) > 0) {
        // it requires a more recent version
        LOG.warn("Plugin {} [{}] is ignored because the version {} of required plugin [{}] is not installed", plugin.getName(), plugin.getKey(),
          requiredPlugin.getMinimalVersion(), requiredPlugin.getKey());
        return false;
      }
    }
    return true;
  }

  private static String getRelativeDir(File dir) {
    Path parent = dir.toPath().getParent().getParent();
    return parent.relativize(dir.toPath()).toString();
  }

  private static void failIfContains(Map<String, ? extends PluginInfo> map, PluginInfo value, Function<PluginInfo, RuntimeException> msg) {
    PluginInfo pluginInfo = map.get(value.getKey());
    if (pluginInfo != null) {
      RuntimeException exception = msg.apply(pluginInfo);
      logGenericPluginLoadErrorLog();
      throw exception;
    }
  }

  private static void logGenericPluginLoadErrorLog() {
    Logger logger = Loggers.get(STARTUP_LOGGER_NAME);
    logger.error(LOAD_ERROR_GENERIC_MESSAGE);
  }

  private List<ServerPluginInfo> getBundledPluginsMetadata() {
    return loadPluginsFromDir(fs.getInstalledBundledPluginsDir(), jar -> ServerPluginInfo.create(jar, BUNDLED));
  }

  private List<ServerPluginInfo> getExternalPluginsMetadata() {
    return loadPluginsFromDir(fs.getInstalledExternalPluginsDir(), jar -> ServerPluginInfo.create(jar, EXTERNAL));
  }

  private List<PluginInfo> getDownloadedPluginsMetadata() {
    return loadPluginsFromDir(fs.getDownloadedPluginsDir(), PluginInfo::create);
  }

  private ServerPluginInfo moveDownloadedPluginToExtensions(PluginInfo pluginInfo) {
    File destDir = fs.getInstalledExternalPluginsDir();
    File destFile = new File(destDir, pluginInfo.getNonNullJarFile().getName());
    if (destFile.exists()) {
      deleteQuietly(destFile);
    }

    movePlugin(pluginInfo.getNonNullJarFile(), destFile);
    return ServerPluginInfo.create(destFile, EXTERNAL);
  }

  private static void movePlugin(File sourcePluginFile, File destPluginFile) {
    try {
      moveFile(sourcePluginFile, destPluginFile);
    } catch (IOException e) {
      throw new IllegalStateException(format("Fail to move plugin: %s to %s", sourcePluginFile.getAbsolutePath(), destPluginFile.getAbsolutePath()), e);
    }
  }

  private <T extends PluginInfo> List<T> loadPluginsFromDir(File pluginsDir, Function<File, T> toPluginInfo) {
    List<T> list = listJarFiles(pluginsDir).stream()
      .map(toPluginInfo)
      .filter(this::checkPluginInfo)
      .collect(Collectors.toList());
    failIfContainsIncompatiblePlugins(list);
    return list;
  }

  private static void failIfContainsIncompatiblePlugins(List<? extends PluginInfo> plugins) {
    List<String> incompatiblePlugins = plugins.stream()
      .filter(p -> FORBIDDEN_INCOMPATIBLE_PLUGINS.contains(p.getKey()))
      .map(p -> "'" + p.getKey() + "'")
      .sorted()
      .collect(Collectors.toList());

    if (!incompatiblePlugins.isEmpty()) {
      logGenericPluginLoadErrorLog();
      throw MessageException.of(String.format("The following %s no longer compatible with this version of SonarQube: %s",
        incompatiblePlugins.size() > 1 ? "plugins are" : "plugin is", String.join(", ", incompatiblePlugins)));
    }
  }

  private boolean checkPluginInfo(PluginInfo info) {
    String pluginKey = info.getKey();
    if (blacklistedPluginKeys.contains(pluginKey)) {
      LOG.warn("Plugin {} [{}] is blacklisted and is being uninstalled", info.getName(), pluginKey);
      deleteQuietly(info.getNonNullJarFile());
      return false;
    }

    if (Strings.isNullOrEmpty(info.getMainClass()) && Strings.isNullOrEmpty(info.getBasePlugin())) {
      LOG.warn("Plugin {} [{}] is ignored because entry point class is not defined", info.getName(), info.getKey());
      return false;
    }

    if (!info.isCompatibleWith(sonarQubeVersion.get().toString())) {
      throw MessageException.of(format("Plugin %s [%s] requires at least SonarQube %s", info.getName(), info.getKey(), info.getMinimalSqVersion()));
    }
    return true;
  }

  private static Collection<File> listJarFiles(File dir) {
    if (dir.exists()) {
      return FileUtils.listFiles(dir, new String[] {"jar"}, false);
    }
    return Collections.emptyList();
  }

}
