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
import * as React from 'react';
import PageActions from '../../components/ui/PageActions';
import { getComponentMeasureUniqueKey } from '../../helpers/component';
import { isInput, isShortcut } from '../../helpers/keyboardEventHelpers';
import { KeyboardCodes } from '../../helpers/keycodes';
import { ComponentMeasure } from '../../types/types';
import { getWrappedDisplayName } from './utils';

export interface WithKeyboardNavigationProps {
  components?: ComponentMeasure[];
  cycle?: boolean;
  isFile?: boolean;
  onEndOfList?: () => void;
  onGoToParent?: () => void;
  onHighlight?: (item: ComponentMeasure) => void;
  onSelect?: (item: ComponentMeasure) => void;
  selected?: ComponentMeasure;
}

export default function withKeyboardNavigation<P>(
  WrappedComponent: React.ComponentClass<P & Partial<WithKeyboardNavigationProps>>
) {
  return class Wrapper extends React.Component<P & WithKeyboardNavigationProps> {
    static displayName = getWrappedDisplayName(WrappedComponent, 'withKeyboardNavigation');

    componentDidMount() {
      document.addEventListener('keydown', this.handleKeyDown);
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
        return this.skipIfFile(this.handleHighlightPrevious);
      } else if (event.code === KeyboardCodes.DownArrow) {
        event.preventDefault();
        return this.skipIfFile(this.handleHighlightNext);
      } else if (event.code === KeyboardCodes.RightArrow || event.code === KeyboardCodes.Enter) {
        event.preventDefault();
        return this.skipIfFile(this.handleSelectCurrent);
      } else if (event.code === KeyboardCodes.LeftArrow) {
        event.preventDefault();
        this.handleSelectParent();
        return false; // always hijack left / Why did you put this @wouter?
      }
      return false;
    };

    getCurrentIndex = () => {
      const { selected, components = [] } = this.props;
      return selected
        ? components.findIndex(
            component =>
              getComponentMeasureUniqueKey(component) === getComponentMeasureUniqueKey(selected)
          )
        : -1;
    };

    skipIfFile = (handler: () => void) => {
      if (this.props.isFile) {
        return true;
      }
      handler();
      return false;
    };

    handleHighlightNext = () => {
      if (this.props.onHighlight === undefined) {
        return;
      }

      const { components = [], cycle } = this.props;
      const index = this.getCurrentIndex();
      const first = cycle ? 0 : index;

      this.props.onHighlight(
        index < components.length - 1 ? components[index + 1] : components[first]
      );

      if (index + 1 === components.length - 1 && this.props.onEndOfList) {
        this.props.onEndOfList();
      }
    };

    handleHighlightPrevious = () => {
      if (this.props.onHighlight === undefined) {
        return;
      }
      const { components = [], cycle } = this.props;
      const index = this.getCurrentIndex();
      const last = cycle ? components.length - 1 : index;

      this.props.onHighlight(index > 0 ? components[index - 1] : components[last]);
    };

    handleSelectCurrent = () => {
      if (this.props.onSelect === undefined) {
        return;
      }

      const { selected } = this.props;
      if (selected !== undefined) {
        this.props.onSelect(selected as ComponentMeasure);
      }
    };

    handleSelectNext = () => {
      if (this.props.onSelect === undefined) {
        return;
      }

      const { components = [] } = this.props;
      const index = this.getCurrentIndex();

      if (index !== -1 && index < components.length - 1) {
        this.props.onSelect(components[index + 1]);
      }
    };

    handleSelectParent = () => {
      if (this.props.onGoToParent !== undefined) {
        this.props.onGoToParent();
      }
    };

    handleSelectPrevious = () => {
      if (this.props.onSelect === undefined) {
        return;
      }

      const { components = [] } = this.props;
      const index = this.getCurrentIndex();

      if (components.length && index > 0) {
        this.props.onSelect(components[index - 1]);
      }
    };

    render() {
      return (
        <>
          <PageActions showShortcuts={!this.props.isFile} />

          <WrappedComponent {...this.props} />
        </>
      );
    }
  };
}
