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
package org.sonar.server.measure.live;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.utils.System2;
import org.sonar.core.config.CorePropertyDefinitions;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.measure.LiveMeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.newcodeperiod.NewCodePeriodType;
import org.sonar.db.project.ProjectDto;
import org.sonar.server.es.ProjectIndexer;
import org.sonar.server.es.TestProjectIndexers;
import org.sonar.server.measure.Rating;
import org.sonar.server.qualitygate.EvaluatedQualityGate;
import org.sonar.server.qualitygate.QualityGate;
import org.sonar.server.qualitygate.changeevent.QGChangeEvent;
import org.sonar.server.setting.ProjectConfigurationLoader;
import org.sonar.server.setting.TestProjectConfigurationLoader;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.api.resources.Qualifiers.ORDERED_BOTTOM_UP;

@RunWith(DataProviderRunner.class)
public class LiveMeasureComputerImplTest {

  @Rule
  public DbTester db = DbTester.create();

  private final TestProjectIndexers projectIndexer = new TestProjectIndexers();
  private MetricDto intMetric;
  private MetricDto ratingMetric;
  private MetricDto alertStatusMetric;
  private ComponentDto project;
  private ProjectDto projectDto;
  private ComponentDto dir;
  private ComponentDto file1;
  private ComponentDto file2;
  private ComponentDto prBranch;
  private ComponentDto prBranchFile;
  private ComponentDto branch;
  private ComponentDto branchFile;
  private final LiveQualityGateComputer qGateComputer = mock(LiveQualityGateComputer.class);
  private final QualityGate qualityGate = mock(QualityGate.class);
  private final EvaluatedQualityGate newQualityGate = mock(EvaluatedQualityGate.class);

  @Before
  public void setUp() {
    intMetric = db.measures().insertMetric(m -> m.setValueType(Metric.ValueType.INT.name()));
    ratingMetric = db.measures().insertMetric(m -> m.setValueType(Metric.ValueType.RATING.name()));
    alertStatusMetric = db.measures().insertMetric(m -> m.setKey(CoreMetrics.ALERT_STATUS_KEY));
    project = db.components().insertPublicProject();
    projectDto = db.components().getProjectDto(project);
    dir = db.components().insertComponent(ComponentTesting.newDirectory(project, "src/main/java"));
    file1 = db.components().insertComponent(ComponentTesting.newFileDto(project, dir));
    file2 = db.components().insertComponent(ComponentTesting.newFileDto(project, dir));

    prBranch = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST));
    prBranchFile = db.components().insertComponent(ComponentTesting.newFileDto(prBranch));

    branch = db.components().insertProjectBranch(project);
    branchFile = db.components().insertComponent(ComponentTesting.newFileDto(branch));
  }

  @Test
  public void compute_and_insert_measures_if_they_do_not_exist_yet() {
    markProjectAsAnalyzed(project);

    List<QGChangeEvent> result = run(asList(file1, file2), newQualifierBasedIntFormula(), newRatingConstantFormula(Rating.C));

    // 2 measures per component have been created
    // Numeric value depends on qualifier (see newQualifierBasedIntFormula())
    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(8);
    assertThatIntMeasureHasValue(file1, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasValue(file1, Rating.C);
    assertThatIntMeasureHasValue(file2, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasValue(file2, Rating.C);
    assertThatIntMeasureHasValue(dir, ORDERED_BOTTOM_UP.indexOf(Qualifiers.DIRECTORY));
    assertThatRatingMeasureHasValue(dir, Rating.C);
    assertThatIntMeasureHasValue(project, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    assertThatRatingMeasureHasValue(project, Rating.C);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void compute_and_update_measures_if_they_already_exist() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setValue(42.0));
    db.measures().insertLiveMeasure(dir, intMetric, m -> m.setValue(42.0));
    db.measures().insertLiveMeasure(file1, intMetric, m -> m.setValue(42.0));
    db.measures().insertLiveMeasure(file2, intMetric, m -> m.setValue(42.0));

    // generates values 1, 2, 3
    List<QGChangeEvent> result = run(file1, newQualifierBasedIntFormula());

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(4);
    assertThatProjectChanged(result, project);

    // Numeric value depends on qualifier (see newQualifierBasedIntFormula())
    assertThatIntMeasureHasValue(file1, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatIntMeasureHasValue(dir, ORDERED_BOTTOM_UP.indexOf(Qualifiers.DIRECTORY));
    assertThatIntMeasureHasValue(project, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    // untouched
    assertThatIntMeasureHasValue(file2, 42.0);
  }

  @Test
  public void variation_is_refreshed_when_int_value_is_changed() {
    markProjectAsAnalyzed(project);
    // value is:
    // 42 on last analysis
    // 42-12=30 on beginning of leak period
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setValue(42.0).setVariation(12.0));

    // new value is 44, so variation on leak period is 44-30=14
    List<QGChangeEvent> result = run(file1, newIntConstantFormula(44.0));

    LiveMeasureDto measure = assertThatIntMeasureHasValue(project, 44.0);
    assertThat(measure.getVariation()).isEqualTo(14.0);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void variation_is_refreshed_when_rating_value_is_changed() {
    markProjectAsAnalyzed(project);
    // value is:
    // B on last analysis
    // D on beginning of leak period --> variation is -2
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setValue((double) Rating.B.getIndex()).setData("B").setVariation(-2.0));

    // new value is C, so variation on leak period is D to C = -1
    List<QGChangeEvent> result = run(file1, newRatingConstantFormula(Rating.C));

    LiveMeasureDto measure = assertThatRatingMeasureHasValue(project, Rating.C);
    assertThat(measure.getVariation()).isEqualTo(-1.0);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void variation_does_not_change_if_rating_value_does_not_change() {
    markProjectAsAnalyzed(project);
    // value is:
    // B on last analysis
    // D on beginning of leak period --> variation is -2
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setValue((double) Rating.B.getIndex()).setData("B").setVariation(-2.0));

    // new value is still B, so variation on leak period is still -2
    List<QGChangeEvent> result = run(file1, newRatingConstantFormula(Rating.B));

    LiveMeasureDto measure = assertThatRatingMeasureHasValue(project, Rating.B);
    assertThat(measure.getVariation()).isEqualTo(-2.0);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void refresh_leak_measures() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setVariation((double) Rating.E.getIndex()));
    db.measures().insertLiveMeasure(dir, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(dir, ratingMetric, m -> m.setVariation((double) Rating.D.getIndex()));
    db.measures().insertLiveMeasure(file1, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(file1, ratingMetric, m -> m.setVariation((double) Rating.C.getIndex()));

    // generates values 1, 2, 3 on leak measures
    List<QGChangeEvent> result = run(file1, newQualifierBasedIntLeakFormula(), newRatingLeakFormula(Rating.B));

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(6);

    // Numeric value depends on qualifier (see newQualifierBasedIntLeakFormula())
    assertThatIntMeasureHasLeakValue(file1, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasLeakValue(file1, Rating.B);
    assertThatIntMeasureHasLeakValue(dir, ORDERED_BOTTOM_UP.indexOf(Qualifiers.DIRECTORY));
    assertThatRatingMeasureHasLeakValue(dir, Rating.B);
    assertThatIntMeasureHasLeakValue(project, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    assertThatRatingMeasureHasLeakValue(project, Rating.B);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void refresh_after_first_analysis() {
    markProjectAsAnalyzed(project, null);
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setVariation(null).setValue(42.0));
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setValue((double) Rating.E.getIndex()).setData(Rating.E.name()));
    db.measures().insertLiveMeasure(dir, intMetric, m -> m.setVariation(null).setValue(42.0));
    db.measures().insertLiveMeasure(dir, ratingMetric, m -> m.setValue((double) Rating.D.getIndex()).setData(Rating.D.name()));
    db.measures().insertLiveMeasure(file1, intMetric, m -> m.setVariation(null).setValue(42.0));
    db.measures().insertLiveMeasure(file1, ratingMetric, m -> m.setValue((double) Rating.C.getIndex()).setData(Rating.C.name()));

    List<QGChangeEvent> result = run(file1, newQualifierBasedIntLeakFormula(), newIntConstantFormula(1337));

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(6);

    assertThatIntMeasureHasValue(file1, 1337);
    assertThatRatingMeasureHasValue(file1, Rating.C);
    assertThatIntMeasureHasValue(dir, 1337);
    assertThatRatingMeasureHasValue(dir, Rating.D);
    assertThatIntMeasureHasValue(project, 1337);
    assertThatRatingMeasureHasValue(project, Rating.E);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void calculate_new_metrics_if_it_is_pr_or_branch() {
    markProjectAsAnalyzed(prBranch, null);
    db.measures().insertLiveMeasure(prBranch, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(prBranchFile, intMetric, m -> m.setVariation(42.0).setValue(null));

    // generates values 1, 2, 3 on leak measures
    List<QGChangeEvent> result = run(prBranchFile, newQualifierBasedIntLeakFormula(), newRatingLeakFormula(Rating.B));

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(4);

    // Numeric value depends on qualifier (see newQualifierBasedIntLeakFormula())
    assertThatIntMeasureHasLeakValue(prBranchFile, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasLeakValue(prBranchFile, Rating.B);
    assertThatIntMeasureHasLeakValue(prBranch, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    assertThatRatingMeasureHasLeakValue(prBranch, Rating.B);
    assertThatProjectChanged(result, prBranch);
  }

  @Test
  public void calculate_new_metrics_if_it_is_branch_using_new_code_reference() {
    markProjectAsAnalyzed(branch, null, NewCodePeriodType.REFERENCE_BRANCH);
    db.measures().insertLiveMeasure(branch, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(branchFile, intMetric, m -> m.setVariation(42.0).setValue(null));

    // generates values 1, 2, 3 on leak measures
    List<QGChangeEvent> result = run(branchFile, newQualifierBasedIntLeakFormula(), newRatingLeakFormula(Rating.B));

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(4);

    // Numeric value depends on qualifier (see newQualifierBasedIntLeakFormula())
    assertThatIntMeasureHasLeakValue(branchFile, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasLeakValue(branchFile, Rating.B);
    assertThatIntMeasureHasLeakValue(branch, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    assertThatRatingMeasureHasLeakValue(branch, Rating.B);
    assertThatProjectChanged(result, branch);
  }

  @Test
  public void do_nothing_if_project_has_not_been_analyzed() {
    // project has no snapshots
    List<QGChangeEvent> result = run(file1, newIncrementalFormula());
    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isZero();
    assertThatProjectNotChanged(result, project);
  }

  @Test
  public void do_nothing_if_input_components_are_empty() {
    List<QGChangeEvent> result = run(emptyList(), newIncrementalFormula());

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isZero();
    assertThatProjectNotChanged(result, project);
  }

  @Test
  public void refresh_multiple_projects_at_the_same_time() {
    markProjectAsAnalyzed(project);
    ComponentDto project2 = db.components().insertPublicProject();
    ComponentDto fileInProject2 = db.components().insertComponent(ComponentTesting.newFileDto(project2));
    markProjectAsAnalyzed(project2);

    List<QGChangeEvent> result = run(asList(file1, fileInProject2), newQualifierBasedIntFormula());

    // generated values depend on position of qualifier in Qualifiers.ORDERED_BOTTOM_UP (see formula)
    assertThatIntMeasureHasValue(file1, 0);
    assertThatIntMeasureHasValue(dir, 2);
    assertThatIntMeasureHasValue(project, 4);
    assertThatIntMeasureHasValue(fileInProject2, 0);
    assertThatIntMeasureHasValue(project2, 4);

    // no other measures generated
    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(5);
    assertThatProjectChanged(result, project, project2);
  }

  @Test
  public void refresh_multiple_branches_at_the_same_time() {
    // FIXME
  }

  @Test
  public void event_contains_no_previousStatus_if_measure_does_not_exist() {
    markProjectAsAnalyzed(project);

    List<QGChangeEvent> result = run(file1);

    assertThat(result)
      .extracting(QGChangeEvent::getPreviousStatus)
      .containsExactly(Optional.empty());
  }

  @Test
  public void event_contains_no_previousStatus_if_measure_exists_and_has_no_value() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, alertStatusMetric, m -> m.setData((String) null));

    List<QGChangeEvent> result = run(file1);

    assertThat(result)
      .extracting(QGChangeEvent::getPreviousStatus)
      .containsExactly(Optional.empty());
  }

  @Test
  public void event_contains_no_previousStatus_if_measure_exists_and_is_empty() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, alertStatusMetric, m -> m.setData(""));

    List<QGChangeEvent> result = run(file1);

    assertThat(result)
      .extracting(QGChangeEvent::getPreviousStatus)
      .containsExactly(Optional.empty());
  }

  @Test
  public void event_contains_no_previousStatus_if_measure_exists_and_is_not_a_level() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, alertStatusMetric, m -> m.setData("fooBar"));

    List<QGChangeEvent> result = run(file1);

    assertThat(result)
      .extracting(QGChangeEvent::getPreviousStatus)
      .containsExactly(Optional.empty());
  }

  @Test
  @UseDataProvider("metricLevels")
  public void event_contains_previousStatus_if_measure_exists(Metric.Level level) {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, alertStatusMetric, m -> m.setData(level.name()));
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setVariation(42.0).setValue(null));

    List<QGChangeEvent> result = run(file1, newQualifierBasedIntLeakFormula());

    assertThat(result)
      .extracting(QGChangeEvent::getPreviousStatus)
      .containsExactly(Optional.of(level));
  }

  @DataProvider
  public static Object[][] metricLevels() {
    return Arrays.stream(Metric.Level.values())
      .map(l -> new Object[] {l})
      .toArray(Object[][]::new);
  }

  @Test
  public void event_contains_newQualityGate_computed_by_LiveQualityGateComputer() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, alertStatusMetric, m -> m.setData(Metric.Level.ERROR.name()));
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setVariation(42.0).setValue(null));
    BranchDto branch = db.getDbClient().branchDao().selectByBranchKey(db.getSession(), project.projectUuid(), "master")
      .orElseThrow(() -> new IllegalStateException("Can't find master branch"));

    List<QGChangeEvent> result = run(file1, newQualifierBasedIntLeakFormula());

    assertThat(result)
      .extracting(QGChangeEvent::getQualityGateSupplier)
      .extracting(Supplier::get)
      .containsExactly(Optional.of(newQualityGate));
    verify(qGateComputer).loadQualityGate(any(DbSession.class), argThat(p -> p.getUuid().equals(projectDto.getUuid())), eq(branch));
    verify(qGateComputer).getMetricsRelatedTo(qualityGate);
    verify(qGateComputer).refreshGateStatus(eq(project), same(qualityGate), any(MeasureMatrix.class), any(Configuration.class));
  }

  @Test
  public void exception_describes_context_when_a_formula_fails() {
    markProjectAsAnalyzed(project);
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();

    assertThatThrownBy(() -> {
      run(project, new IssueMetricFormula(metric, false, (context, issueCounter) -> {
        throw new NullPointerException("BOOM");
      }));
    })
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Fail to compute " + metric.getKey() + " on " + project.getDbKey());
  }

  private List<QGChangeEvent> run(ComponentDto component, IssueMetricFormula... formulas) {
    return run(singleton(component), formulas);
  }

  private List<QGChangeEvent> run(Collection<ComponentDto> components, IssueMetricFormula... formulas) {
    IssueMetricFormulaFactory formulaFactory = new TestIssueMetricFormulaFactory(asList(formulas));

    when(qGateComputer.loadQualityGate(any(DbSession.class), any(ProjectDto.class), any(BranchDto.class)))
      .thenReturn(qualityGate);
    when(qGateComputer.getMetricsRelatedTo(qualityGate)).thenReturn(singleton(CoreMetrics.ALERT_STATUS_KEY));
    when(qGateComputer.refreshGateStatus(eq(project), same(qualityGate), any(MeasureMatrix.class), any(Configuration.class)))
      .thenReturn(newQualityGate);
    MapSettings settings = new MapSettings(new PropertyDefinitions(System2.INSTANCE, CorePropertyDefinitions.all()));
    ProjectConfigurationLoader configurationLoader = new TestProjectConfigurationLoader(settings.asConfig());

    LiveMeasureComputerImpl underTest = new LiveMeasureComputerImpl(db.getDbClient(), formulaFactory, qGateComputer, configurationLoader, projectIndexer);

    return underTest.refresh(db.getSession(), components);
  }

  private void markProjectAsAnalyzed(ComponentDto p) {
    markProjectAsAnalyzed(p, 1_490_000_000L);
  }

  private void markProjectAsAnalyzed(ComponentDto p, @Nullable Long periodDate) {
    assertThat(p.qualifier()).isEqualTo(Qualifiers.PROJECT);
    markProjectAsAnalyzed(p, periodDate, null);
  }

  private void markProjectAsAnalyzed(ComponentDto p, @Nullable Long periodDate, @Nullable NewCodePeriodType type) {
    assertThat(p.qualifier()).isEqualTo(Qualifiers.PROJECT);
    db.components().insertSnapshot(p, s -> s.setPeriodDate(periodDate).setPeriodMode(type != null ? type.name() : null));
  }

  private LiveMeasureDto assertThatIntMeasureHasValue(ComponentDto component, double expectedValue) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), intMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricUuid()).isEqualTo(intMetric.getUuid());
    assertThat(measure.getValue()).isEqualTo(expectedValue);
    return measure;
  }

  private LiveMeasureDto assertThatRatingMeasureHasValue(ComponentDto component, Rating expectedRating) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), ratingMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricUuid()).isEqualTo(ratingMetric.getUuid());
    assertThat(measure.getValue()).isEqualTo(expectedRating.getIndex());
    assertThat(measure.getDataAsString()).isEqualTo(expectedRating.name());
    return measure;
  }

  private void assertThatIntMeasureHasLeakValue(ComponentDto component, double expectedValue) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), intMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricUuid()).isEqualTo(intMetric.getUuid());
    assertThat(measure.getValue()).isNull();
    assertThat(measure.getVariation()).isEqualTo(expectedValue);
  }

  private void assertThatRatingMeasureHasLeakValue(ComponentDto component, Rating expectedValue) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), ratingMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricUuid()).isEqualTo(ratingMetric.getUuid());
    assertThat(measure.getVariation()).isEqualTo(expectedValue.getIndex());
  }

  private IssueMetricFormula newIncrementalFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    AtomicInteger counter = new AtomicInteger();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> ctx.setValue(counter.incrementAndGet()));
  }

  private IssueMetricFormula newIntConstantFormula(double constant) {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> ctx.setValue(constant));
  }

  private IssueMetricFormula newRatingConstantFormula(Rating constant) {
    Metric metric = new Metric.Builder(ratingMetric.getKey(), ratingMetric.getShortName(), Metric.ValueType.valueOf(ratingMetric.getValueType())).create();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> ctx.setValue(constant));
  }

  private IssueMetricFormula newRatingLeakFormula(Rating rating) {
    Metric metric = new Metric.Builder(ratingMetric.getKey(), ratingMetric.getShortName(), Metric.ValueType.valueOf(ratingMetric.getValueType())).create();
    return new IssueMetricFormula(metric, true, (ctx, issues) -> ctx.setLeakValue(rating));
  }

  private IssueMetricFormula newQualifierBasedIntFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> ctx.setValue(ORDERED_BOTTOM_UP.indexOf(ctx.getComponent().qualifier())));
  }

  private IssueMetricFormula newQualifierBasedIntLeakFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    return new IssueMetricFormula(metric, true, (ctx, issues) -> ctx.setLeakValue(ORDERED_BOTTOM_UP.indexOf(ctx.getComponent().qualifier())));
  }

  private void assertThatProjectChanged(List<QGChangeEvent> events, ComponentDto... projects) {
    for (ComponentDto p : projects) {
      assertThat(projectIndexer.hasBeenCalled(p.uuid(), ProjectIndexer.Cause.MEASURE_CHANGE)).isTrue();
    }

    assertThat(events).extracting(e -> e.getBranch().getUuid())
      .containsExactlyInAnyOrder(Arrays.stream(projects).map(ComponentDto::uuid).toArray(String[]::new));
  }

  private void assertThatProjectNotChanged(List<QGChangeEvent> events, ComponentDto project) {
    assertThat(projectIndexer.hasBeenCalled(project.uuid(), ProjectIndexer.Cause.MEASURE_CHANGE)).isFalse();
    assertThat(events).isEmpty();
  }
}
