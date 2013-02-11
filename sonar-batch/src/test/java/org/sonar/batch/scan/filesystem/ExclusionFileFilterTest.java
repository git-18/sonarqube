/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2008-2012 SonarSource
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
package org.sonar.batch.scan.filesystem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.File;
import org.sonar.api.resources.JavaFile;
import org.sonar.api.scan.filesystem.FileFilter;
import org.sonar.api.scan.filesystem.ModuleFileSystem;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ExclusionFileFilterTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void source_inclusions() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_INCLUSIONS_PROPERTY, "**/*Dao.java");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    FileFilterContext context = new FileFilterContext(mock(ModuleFileSystem.class), FileFilter.FileType.SOURCE);
    context.setFileRelativePath("com/mycompany/Foo.java");
    assertThat(filter.accept(temp.newFile(), context)).isFalse();

    context.setFileRelativePath("com/mycompany/FooDao.java");
    assertThat(filter.accept(temp.newFile(), context)).isTrue();

    // source inclusions do not apply to tests
    context = new FileFilterContext(mock(ModuleFileSystem.class), FileFilter.FileType.TEST);
    context.setFileRelativePath("com/mycompany/Foo.java");
    assertThat(filter.accept(temp.newFile(), context)).isTrue();
  }

  @Test
  public void source_exclusions() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_EXCLUSIONS_PROPERTY, "**/*Dao.java");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    FileFilterContext context = new FileFilterContext(mock(ModuleFileSystem.class), FileFilter.FileType.SOURCE);
    context.setFileRelativePath("com/mycompany/FooDao.java");
    assertThat(filter.accept(temp.newFile(), context)).isFalse();

    context.setFileRelativePath("com/mycompany/Foo.java");
    assertThat(filter.accept(temp.newFile(), context)).isTrue();

    // source exclusions do not apply to tests
    context = new FileFilterContext(mock(ModuleFileSystem.class), FileFilter.FileType.TEST);
    context.setFileRelativePath("com/mycompany/FooDao.java");
    assertThat(filter.accept(temp.newFile(), context)).isTrue();
  }

  @Test
  public void resource_inclusions() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_INCLUSIONS_PROPERTY, "**/*Dao.c");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    assertThat(filter.isIgnored(new File("org/sonar", "FooDao.c"))).isFalse();
    assertThat(filter.isIgnored(new File("org/sonar", "Foo.c"))).isTrue();
  }

  @Test
  public void resource_exclusions() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_EXCLUSIONS_PROPERTY, "**/*Dao.c");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    assertThat(filter.isIgnored(new File("org/sonar", "FooDao.c"))).isTrue();
    assertThat(filter.isIgnored(new File("org/sonar", "Foo.c"))).isFalse();
  }

  /**
   * JavaFile will be deprecated
   */
  @Test
  public void java_resource_inclusions() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_INCLUSIONS_PROPERTY, "**/*Dao.java");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    assertThat(filter.isIgnored(new JavaFile("org.sonar", "FooDao"))).isFalse();
    assertThat(filter.isIgnored(new JavaFile("org.sonar", "Foo"))).isTrue();
  }

  /**
   * JavaFile will be deprecated
   */
  @Test
  public void java_resource_exclusions() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_EXCLUSIONS_PROPERTY, "**/*Dao.java");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    assertThat(filter.isIgnored(new JavaFile("org.sonar", "FooDao"))).isTrue();
    assertThat(filter.isIgnored(new JavaFile("org.sonar", "Foo"))).isFalse();
  }

  @Test
  public void test_settings() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_INCLUSIONS_PROPERTY, "source/inclusions");
    settings.setProperty(CoreProperties.PROJECT_EXCLUSIONS_PROPERTY, "source/exclusions");
    settings.setProperty(CoreProperties.PROJECT_TEST_INCLUSIONS_PROPERTY, "test/inclusions");
    settings.setProperty(CoreProperties.PROJECT_TEST_EXCLUSIONS_PROPERTY, "test/exclusions");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    assertThat(filter.sourceInclusions()[0].toString()).isEqualTo("source/inclusions");
    assertThat(filter.testInclusions()[0].toString()).isEqualTo("test/inclusions");
    assertThat(filter.sourceExclusions()[0].toString()).isEqualTo("source/exclusions");
    assertThat(filter.testExclusions()[0].toString()).isEqualTo("test/exclusions");
  }

  @Test
  public void should_trim_pattern() throws IOException {
    Settings settings = new Settings();
    settings.setProperty(CoreProperties.PROJECT_EXCLUSIONS_PROPERTY, "   **/*Dao.java   ");
    ExclusionFileFilter filter = new ExclusionFileFilter(settings);

    assertThat(filter.sourceExclusions()[0].toString()).isEqualTo("**/*Dao.java");
  }


}
