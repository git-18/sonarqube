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
import { ReactWrapper, ShallowWrapper } from 'enzyme';
import { setImmediate } from 'timers';
import { KeyboardCodes, KeyboardKeys } from './keycodes';

export function mockEvent(overrides = {}) {
  return {
    target: { blur() {} },
    currentTarget: { blur() {} },
    preventDefault() {},
    stopPropagation() {},
    ...overrides
  } as any;
}

export function click(element: ShallowWrapper | ReactWrapper, event = {}): void {
  // `type()` returns a component constructor for a composite element and string for DOM nodes
  if (typeof element.type() === 'function') {
    element.prop<Function>('onClick')();
    // TODO find out if `root` is a public api
    // https://github.com/airbnb/enzyme/blob/master/packages/enzyme/src/ReactWrapper.js#L109
    (element as any).root().update();
  } else {
    element.simulate('click', mockEvent(event));
  }
}

export function clickOutside(event = {}): void {
  const dispatchedEvent = new MouseEvent('click', event);
  window.dispatchEvent(dispatchedEvent);
}

export function submit(element: ShallowWrapper | ReactWrapper): void {
  element.simulate('submit', {
    preventDefault() {}
  });
}

export function change(
  element: ShallowWrapper | ReactWrapper,
  value: string | object,
  event = {}
): void {
  // `type()` returns a component constructor for a composite element and string for DOM nodes
  if (typeof element.type() === 'function') {
    element.prop<Function>('onChange')(value);
    // TODO find out if `root` is a public api
    // https://github.com/airbnb/enzyme/blob/master/packages/enzyme/src/ReactWrapper.js#L109
    (element as any).root().update();
  } else {
    element.simulate('change', {
      target: { value },
      currentTarget: { value },
      ...event
    });
  }
}

export const KEYCODE_MAP: { [code in KeyboardCodes]?: string } = {
  [KeyboardCodes.Enter]: 'enter',
  [KeyboardCodes.LeftArrow]: 'left',
  [KeyboardCodes.UpArrow]: 'up',
  [KeyboardCodes.RightArrow]: 'right',
  [KeyboardCodes.DownArrow]: 'down'
};

export function keydown(args: {
  code?: KeyboardCodes;
  key?: KeyboardKeys;
  metaKey?: boolean;
  ctrlKey?: boolean;
}): void {
  const event = new KeyboardEvent('keydown', args as KeyboardEventInit);
  document.dispatchEvent(event);
}

export function elementKeydown(element: ShallowWrapper, key: KeyboardKeys): void {
  const event = {
    currentTarget: { element },
    nativeEvent: {
      key,
      stopImmediatePropagation: () => {
        /* noop */
      }
    },
    preventDefault() {
      /*noop*/
    }
  };

  if (typeof element.type() === 'string') {
    // `type()` is string for native dom elements
    element.simulate('keydown', event);
  } else {
    element.prop<Function>('onKeyDown')(event);
  }
}

export function resizeWindowTo(width?: number, height?: number) {
  // `document.documentElement.clientHeight/clientWidth` are getters by default,
  // so we need to redefine them. Pass `configurable: true` to allow to redefine
  // the properties multiple times.
  if (width) {
    Object.defineProperty(document.documentElement, 'clientWidth', {
      configurable: true,
      value: width
    });
  }
  if (height) {
    Object.defineProperty(document.documentElement, 'clientHeight', {
      configurable: true,
      value: height
    });
  }

  const resizeEvent = new Event('resize');
  window.dispatchEvent(resizeEvent);
}

export function scrollTo({ left = 0, top = 0 }) {
  Object.defineProperty(window, 'pageYOffset', { value: top });
  Object.defineProperty(window, 'pageXOffset', { value: left });
  const resizeEvent = new Event('scroll');
  window.dispatchEvent(resizeEvent);
}

export function setNodeRect({ width = 50, height = 50, left = 0, top = 0 }) {
  const { findDOMNode } = require('react-dom');
  const element = document.createElement('div');
  Object.defineProperty(element, 'getBoundingClientRect', {
    value: () => ({ width, height, left, top })
  });
  findDOMNode.mockReturnValue(element);
}

export function doAsync(fn?: Function): Promise<void> {
  return new Promise(resolve => {
    setImmediate(() => {
      if (fn) {
        fn();
      }
      resolve();
    });
  });
}

export async function waitAndUpdate(wrapper: ShallowWrapper<any, any> | ReactWrapper<any, any>) {
  await new Promise(setImmediate);
  wrapper.update();
}
