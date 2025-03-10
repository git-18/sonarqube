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
import { Link } from 'react-router';
import withAppStateContext from '../../app/components/app-state/withAppStateContext';
import DetachIcon from '../../components/icons/DetachIcon';
import { AppState } from '../../types/appstate';

interface OwnProps {
  appState: AppState;
  customProps?: {
    [k: string]: any;
  };
}

type Props = OwnProps & React.AnchorHTMLAttributes<HTMLAnchorElement>;

const SONARQUBE_LINK = '/#sonarqube#/';
const SONARQUBE_ADMIN_LINK = '/#sonarqube-admin#/';

export class DocLink extends React.PureComponent<Props> {
  handleClickOnAnchor = (event: React.MouseEvent<HTMLAnchorElement>) => {
    const { customProps, href = '#' } = this.props;
    if (customProps && customProps.onAnchorClick) {
      customProps.onAnchorClick(href, event);
    }
  };

  render() {
    const { appState, children, href, customProps, ...other } = this.props;
    if (href && href.startsWith('#')) {
      return (
        <a href="#" onClick={this.handleClickOnAnchor}>
          {children}
        </a>
      );
    }

    if (href && href.startsWith('/')) {
      if (href.startsWith(SONARQUBE_LINK)) {
        return <SonarQubeLink url={href}>{children}</SonarQubeLink>;
      } else if (href.startsWith(SONARQUBE_ADMIN_LINK)) {
        return (
          <SonarQubeAdminLink canAdmin={appState.canAdmin} url={href}>
            {children}
          </SonarQubeAdminLink>
        );
      }
      const url = '/documentation' + href;
      return (
        <Link to={url} {...other}>
          {children}
        </Link>
      );
    }

    return (
      <>
        <a href={href} rel="noopener noreferrer" target="_blank" {...other}>
          {children}
        </a>
        <DetachIcon
          className="text-muted little-spacer-left little-spacer-right text-baseline"
          size={12}
        />
      </>
    );
  }
}

export default withAppStateContext(DocLink);

interface SonarQubeLinkProps {
  children: React.ReactNode;
  url: string;
}

function SonarQubeLink({ children, url }: SonarQubeLinkProps) {
  const to = `/${url.substr(SONARQUBE_LINK.length)}`;
  return (
    <Link target="_blank" to={to}>
      {children}
    </Link>
  );
}

interface SonarQubeAdminLinkProps {
  canAdmin?: boolean;
  children: React.ReactNode;
  url: string;
}

function SonarQubeAdminLink({ canAdmin, children, url }: SonarQubeAdminLinkProps) {
  if (!canAdmin) {
    return <>{children}</>;
  }
  const to = `/${url.substr(SONARQUBE_ADMIN_LINK.length)}`;
  return (
    <Link target="_blank" to={to}>
      {children}
    </Link>
  );
}
