/*
 * Copyright 2015-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
import React from 'react';
import { shallow } from 'enzyme';
import CssBaseline from '@material-ui/core/CssBaseline';

import Layout from './Layout';
import Sidebar from './Sidebar';

describe('<Layout />', () => {
  let wrapper;

  beforeEach(() => {
    wrapper = shallow(
      <Layout>
        <div className="child-1" />
        <div className="child-2" />
        <div className="child-3" />
      </Layout>,
    );
  });

  it('should render CssBaseline', () => {
    const items = wrapper.find(CssBaseline);
    expect(items.length).toBe(1);
  });

  it('should render Sidebar', () => {
    const items = wrapper.find(Sidebar);
    expect(items.length).toBe(1);
  });
});
