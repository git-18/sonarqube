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
import { debounce, keyBy, uniqBy } from 'lodash';
import * as React from 'react';
import { FormattedMessage } from 'react-intl';
import { withRouter, WithRouterProps } from 'react-router';
import { getSuggestions } from '../../../api/components';
import { DropdownOverlay } from '../../../components/controls/Dropdown';
import OutsideClickHandler from '../../../components/controls/OutsideClickHandler';
import SearchBox from '../../../components/controls/SearchBox';
import ClockIcon from '../../../components/icons/ClockIcon';
import { lazyLoadComponent } from '../../../components/lazyLoadComponent';
import DeferredSpinner from '../../../components/ui/DeferredSpinner';
import { isInput, isShortcut } from '../../../helpers/keyboardEventHelpers';
import { KeyboardKeys } from '../../../helpers/keycodes';
import { translate, translateWithParameters } from '../../../helpers/l10n';
import { scrollToElement } from '../../../helpers/scrolling';
import { getComponentOverviewUrl } from '../../../helpers/urls';
import { ComponentQualifier } from '../../../types/component';
import { Dict } from '../../../types/types';
import RecentHistory from '../RecentHistory';
import './Search.css';
import { ComponentResult, More, Results, sortQualifiers } from './utils';

const SearchResults = lazyLoadComponent(() => import('./SearchResults'));
const SearchResult = lazyLoadComponent(() => import('./SearchResult'));

interface State {
  loading: boolean;
  loadingMore?: string;
  more: More;
  open: boolean;
  projects: Dict<{ name: string }>;
  query: string;
  results: Results;
  selected?: string;
  shortQuery: boolean;
}

export class Search extends React.PureComponent<WithRouterProps, State> {
  input?: HTMLInputElement | null;
  node?: HTMLElement | null;
  nodes: Dict<HTMLElement>;
  mounted = false;

  constructor(props: WithRouterProps) {
    super(props);
    this.nodes = {};
    this.search = debounce(this.search, 250);
    this.state = {
      loading: false,
      more: {},
      open: false,
      projects: {},
      query: '',
      results: {},
      shortQuery: false
    };
  }

  componentDidMount() {
    this.mounted = true;
    document.addEventListener('keydown', this.handleSKeyDown);
  }

  componentDidUpdate(_prevProps: WithRouterProps, prevState: State) {
    if (prevState.selected !== this.state.selected) {
      this.scrollToSelected();
    }
  }

  componentWillUnmount() {
    this.mounted = false;
    document.removeEventListener('keydown', this.handleSKeyDown);
  }

  focusInput = () => {
    if (this.input) {
      this.input.focus();
    }
  };

  handleClickOutside = () => {
    this.closeSearch(false);
  };

  handleFocus = () => {
    if (!this.state.open) {
      // simulate click to close any other dropdowns
      const body = document.documentElement;
      if (body) {
        body.click();
      }
    }
    this.openSearch();
  };

  openSearch = () => {
    if (!this.state.open && !this.state.query) {
      this.search('');
    }
    this.setState({ open: true });
  };

  closeSearch = (clear = true) => {
    if (this.input) {
      this.input.blur();
    }
    if (clear) {
      this.setState({
        more: {},
        open: false,
        projects: {},
        query: '',
        results: {},
        selected: undefined,
        shortQuery: false
      });
    } else {
      this.setState({ open: false });
    }
  };

  getPlainComponentsList = (results: Results, more: More) =>
    sortQualifiers(Object.keys(results)).reduce((components, qualifier) => {
      const next = [...components, ...results[qualifier].map(component => component.key)];
      if (more[qualifier]) {
        next.push('qualifier###' + qualifier);
      }
      return next;
    }, []);

  stopLoading = () => {
    if (this.mounted) {
      this.setState({ loading: false });
    }
  };

  search = (query: string) => {
    if (query.length === 0 || query.length >= 2) {
      this.setState({ loading: true });
      const recentlyBrowsed = RecentHistory.get().map(component => component.key);
      getSuggestions(query, recentlyBrowsed).then(response => {
        // compare `this.state.query` and `query` to handle two request done almost at the same time
        // in this case only the request that matches the current query should be taken
        if (this.mounted && this.state.query === query) {
          const results: Results = {};
          const more: More = {};
          this.nodes = {};
          response.results.forEach(group => {
            results[group.q] = group.items.map(item => ({ ...item, qualifier: group.q }));
            more[group.q] = group.more;
          });
          const list = this.getPlainComponentsList(results, more);
          this.setState(state => ({
            loading: false,
            more,
            projects: { ...state.projects, ...keyBy(response.projects, 'key') },
            results,
            selected: list.length > 0 ? list[0] : undefined,
            shortQuery: query.length > 2 && response.warning === 'short_input'
          }));
        }
      }, this.stopLoading);
    } else {
      this.setState({ loading: false });
    }
  };

  searchMore = (qualifier: string) => {
    const { query } = this.state;
    if (query.length === 1) {
      return;
    }

    this.setState({ loading: true, loadingMore: qualifier });
    const recentlyBrowsed = RecentHistory.get().map(component => component.key);
    getSuggestions(query, recentlyBrowsed, qualifier).then(response => {
      if (this.mounted) {
        const group = response.results.find(group => group.q === qualifier);
        const moreResults = (group ? group.items : []).map(item => ({ ...item, qualifier }));
        this.setState(state => ({
          loading: false,
          loadingMore: undefined,
          more: { ...state.more, [qualifier]: 0 },
          projects: { ...state.projects, ...keyBy(response.projects, 'key') },
          results: {
            ...state.results,
            [qualifier]: uniqBy([...state.results[qualifier], ...moreResults], 'key')
          },
          selected: moreResults.length > 0 ? moreResults[0].key : state.selected
        }));
        this.focusInput();
      }
    }, this.stopLoading);
  };

  handleQueryChange = (query: string) => {
    this.setState({ query, shortQuery: query.length === 1 });
    this.search(query);
  };

  selectPrevious = () => {
    this.setState(({ more, results, selected }) => {
      if (selected) {
        const list = this.getPlainComponentsList(results, more);
        const index = list.indexOf(selected);
        return index > 0 ? { selected: list[index - 1] } : null;
      }
      return null;
    });
  };

  selectNext = () => {
    this.setState(({ more, results, selected }) => {
      if (selected) {
        const list = this.getPlainComponentsList(results, more);
        const index = list.indexOf(selected);
        return index >= 0 && index < list.length - 1 ? { selected: list[index + 1] } : null;
      }
      return null;
    });
  };

  openSelected = () => {
    const { results, selected } = this.state;

    if (!selected) {
      return;
    }

    if (selected.startsWith('qualifier###')) {
      this.searchMore(selected.substr(12));
    } else {
      let qualifier = ComponentQualifier.Project;

      if ((results[ComponentQualifier.Portfolio] ?? []).find(r => r.key === selected)) {
        qualifier = ComponentQualifier.Portfolio;
      } else if ((results[ComponentQualifier.SubPortfolio] ?? []).find(r => r.key === selected)) {
        qualifier = ComponentQualifier.SubPortfolio;
      }

      this.props.router.push(getComponentOverviewUrl(selected, qualifier));

      this.closeSearch();
    }
  };

  scrollToSelected = () => {
    if (this.state.selected) {
      const node = this.nodes[this.state.selected];
      if (node && this.node) {
        scrollToElement(node, { topOffset: 30, bottomOffset: 30, parent: this.node });
      }
    }
  };

  handleSKeyDown = (event: KeyboardEvent) => {
    if (isInput(event) || isShortcut(event)) {
      return true;
    }
    if (event.key === KeyboardKeys.KeyS) {
      event.preventDefault();
      this.focusInput();
      this.openSearch();
    }
  };

  handleKeyDown = (event: React.KeyboardEvent) => {
    switch (event.nativeEvent.key) {
      case KeyboardKeys.Enter:
        event.preventDefault();
        event.nativeEvent.stopImmediatePropagation();
        this.openSelected();
        break;
      case KeyboardKeys.UpArrow:
        event.preventDefault();
        event.nativeEvent.stopImmediatePropagation();
        this.selectPrevious();
        break;
      case KeyboardKeys.Escape:
        event.preventDefault();
        event.nativeEvent.stopImmediatePropagation();
        this.closeSearch();
        break;
      case KeyboardKeys.DownArrow:
        event.preventDefault();
        event.nativeEvent.stopImmediatePropagation();
        this.selectNext();
        break;
    }
  };

  handleSelect = (selected: string) => {
    this.setState({ selected });
  };

  innerRef = (component: string, node: HTMLElement | null) => {
    if (node) {
      this.nodes[component] = node;
    }
  };

  searchInputRef = (node: HTMLInputElement | null) => {
    this.input = node;
  };

  renderResult = (component: ComponentResult) => (
    <SearchResult
      component={component}
      innerRef={this.innerRef}
      key={component.key}
      onClose={this.closeSearch}
      onSelect={this.handleSelect}
      projects={this.state.projects}
      selected={this.state.selected === component.key}
    />
  );

  renderNoResults = () => (
    <div className="navbar-search-no-results">
      {translateWithParameters('no_results_for_x', this.state.query)}
    </div>
  );

  render() {
    const search = (
      <li className="navbar-search dropdown">
        <DeferredSpinner className="navbar-search-icon" loading={this.state.loading} />

        <SearchBox
          autoFocus={this.state.open}
          innerRef={this.searchInputRef}
          minLength={2}
          onChange={this.handleQueryChange}
          onFocus={this.handleFocus}
          onKeyDown={this.handleKeyDown}
          placeholder={translate('search.placeholder')}
          value={this.state.query}
        />

        {this.state.shortQuery && (
          <span className="navbar-search-input-hint">
            {translateWithParameters('select2.tooShort', 2)}
          </span>
        )}

        {this.state.open && Object.keys(this.state.results).length > 0 && (
          <DropdownOverlay noPadding={true}>
            <div className="global-navbar-search-dropdown" ref={node => (this.node = node)}>
              <SearchResults
                allowMore={this.state.query.length !== 1}
                loadingMore={this.state.loadingMore}
                more={this.state.more}
                onMoreClick={this.searchMore}
                onSelect={this.handleSelect}
                renderNoResults={this.renderNoResults}
                renderResult={this.renderResult}
                results={this.state.results}
                selected={this.state.selected}
              />
              <div className="dropdown-bottom-hint">
                <div className="pull-right">
                  <ClockIcon className="little-spacer-right" size={12} />
                  {translate('recently_browsed')}
                </div>
                <FormattedMessage
                  defaultMessage={translate('search.shortcut_hint')}
                  id="search.shortcut_hint"
                  values={{
                    shortcut: <span className="shortcut-button shortcut-button-small">s</span>
                  }}
                />
              </div>
            </div>
          </DropdownOverlay>
        )}
      </li>
    );

    return this.state.open ? (
      <OutsideClickHandler onClickOutside={this.handleClickOutside}>{search}</OutsideClickHandler>
    ) : (
      search
    );
  }
}

export default withRouter<{}>(Search);
