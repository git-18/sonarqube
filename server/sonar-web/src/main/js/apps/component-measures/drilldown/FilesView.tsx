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
import { throttle } from 'lodash';
import * as React from 'react';
import { Button } from '../../../components/controls/buttons';
import ListFooter from '../../../components/controls/ListFooter';
import { Alert } from '../../../components/ui/Alert';
import { isInput, isShortcut } from '../../../helpers/keyboardEventHelpers';
import { KeyboardCodes } from '../../../helpers/keycodes';
import { translate, translateWithParameters } from '../../../helpers/l10n';
import { formatMeasure, isDiffMetric, isPeriodBestValue } from '../../../helpers/measures';
import { scrollToElement } from '../../../helpers/scrolling';
import { BranchLike } from '../../../types/branch-like';
import { MeasurePageView } from '../../../types/measures';
import {
  ComponentMeasure,
  ComponentMeasureEnhanced,
  Dict,
  Metric,
  Paging
} from '../../../types/types';
import ComponentsList from './ComponentsList';

interface Props {
  branchLike?: BranchLike;
  components: ComponentMeasureEnhanced[];
  defaultShowBestMeasures: boolean;
  fetchMore: () => void;
  handleSelect: (component: ComponentMeasureEnhanced) => void;
  handleOpen: (component: ComponentMeasureEnhanced) => void;
  loadingMore: boolean;
  metric: Metric;
  metrics: Dict<Metric>;
  paging?: Paging;
  rootComponent: ComponentMeasure;
  selectedComponent?: ComponentMeasureEnhanced;
  selectedIdx?: number;
  view: MeasurePageView;
}

interface State {
  showBestMeasures: boolean;
}

export default class FilesView extends React.PureComponent<Props, State> {
  listContainer?: HTMLElement | null;

  constructor(props: Props) {
    super(props);
    this.state = { showBestMeasures: props.defaultShowBestMeasures };
    this.selectNext = throttle(this.selectNext, 100);
    this.selectPrevious = throttle(this.selectPrevious, 100);
  }

  componentDidMount() {
    document.addEventListener('keydown', this.handleKeyDown);
    if (this.props.selectedComponent !== undefined) {
      this.scrollToElement();
    }
  }

  componentDidUpdate(prevProps: Props) {
    if (
      this.props.selectedComponent &&
      prevProps.selectedComponent !== this.props.selectedComponent
    ) {
      this.scrollToElement();
    }
    if (prevProps.metric.key !== this.props.metric.key || prevProps.view !== this.props.view) {
      this.setState({ showBestMeasures: this.props.defaultShowBestMeasures });
    }
  }

  componentWillUnmount() {
    document.removeEventListener('keydown', this.handleKeyDown);
  }

  handleKeyDown = (event: KeyboardEvent) => {
    if (isInput(event) || isShortcut(event)) {
      return true;
    }
    if (event.code === KeyboardCodes.UpArrow) {
      event.preventDefault();
      this.selectPrevious();
    } else if (event.code === KeyboardCodes.DownArrow) {
      event.preventDefault();
      this.selectNext();
    } else if (event.code === KeyboardCodes.RightArrow) {
      event.preventDefault();
      this.openSelected();
    }
  };

  getVisibleComponents = () => {
    const { components } = this.props;
    if (this.state.showBestMeasures) {
      return components;
    }
    const filtered = components.filter(component => !this.hasBestValue(component));
    if (filtered.length === 0) {
      return components;
    }
    return filtered;
  };

  handleShowBestMeasures = () => {
    this.setState({ showBestMeasures: true });
  };

  hasBestValue = (component: ComponentMeasureEnhanced) => {
    const { metric } = this.props;
    const focusedMeasure = component.measures.find(measure => measure.metric.key === metric.key);
    if (focusedMeasure && isDiffMetric(metric.key)) {
      return isPeriodBestValue(focusedMeasure);
    }
    return Boolean(focusedMeasure && focusedMeasure.bestValue);
  };

  openSelected = () => {
    if (this.props.selectedComponent !== undefined) {
      this.props.handleOpen(this.props.selectedComponent);
    }
  };

  selectPrevious = () => {
    const { selectedIdx } = this.props;
    const visibleComponents = this.getVisibleComponents();
    if (selectedIdx !== undefined && selectedIdx > 0) {
      this.props.handleSelect(visibleComponents[selectedIdx - 1]);
    } else {
      this.props.handleSelect(visibleComponents[visibleComponents.length - 1]);
    }
  };

  selectNext = () => {
    const { selectedIdx } = this.props;
    const visibleComponents = this.getVisibleComponents();
    if (selectedIdx !== undefined && selectedIdx < visibleComponents.length - 1) {
      this.props.handleSelect(visibleComponents[selectedIdx + 1]);
    } else {
      this.props.handleSelect(visibleComponents[0]);
    }
  };

  scrollToElement = () => {
    if (this.listContainer) {
      const elem = this.listContainer.getElementsByClassName('selected')[0];
      if (elem) {
        scrollToElement(elem, { topOffset: 215, bottomOffset: 100 });
      }
    }
  };

  render() {
    const { components } = this.props;
    const filteredComponents = this.getVisibleComponents();
    const hidingBestMeasures = filteredComponents.length < components.length;
    return (
      <div ref={elem => (this.listContainer = elem)}>
        <ComponentsList
          branchLike={this.props.branchLike}
          components={filteredComponents}
          metric={this.props.metric}
          metrics={this.props.metrics}
          rootComponent={this.props.rootComponent}
          selectedComponent={this.props.selectedComponent}
          view={this.props.view}
        />
        {hidingBestMeasures && this.props.paging && (
          <Alert className="spacer-top" variant="info">
            <div className="display-flex-center">
              {translateWithParameters(
                'component_measures.hidden_best_score_metrics',
                formatMeasure(this.props.paging.total - filteredComponents.length, 'INT'),
                formatMeasure(this.props.metric.bestValue, this.props.metric.type)
              )}
              <Button className="button-small spacer-left" onClick={this.handleShowBestMeasures}>
                {translate('show_them')}
              </Button>
            </div>
          </Alert>
        )}
        {!hidingBestMeasures && this.props.paging && this.props.components.length > 0 && (
          <ListFooter
            count={this.props.components.length}
            loadMore={this.props.fetchMore}
            loading={this.props.loadingMore}
            total={this.props.paging.total}
          />
        )}
      </div>
    );
  }
}
