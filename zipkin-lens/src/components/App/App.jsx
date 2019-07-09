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
import { Provider } from 'react-redux';
import { BrowserRouter, Route } from 'react-router-dom';
import { ThemeProvider } from '@material-ui/styles';
import { MuiPickersUtilsProvider } from '@material-ui/pickers';
import MomentUtils from '@date-io/moment';

import Layout from './Layout';
import DiscoverPage from '../DiscoverPage';
import TracePageContainer from '../../containers/TracePage/TracePageContainer';
import TraceViewerContainer from '../../containers/TraceViewer/TraceViewerContainer';
import configureStore from '../../store/configure-store';
import { theme } from '../../colors';
import { useMount } from '../../hooks';

const applicationTitle = 'Zipkin';

const App = () => {
  useMount(() => {
    document.title = applicationTitle;
  });

  return (
    <MuiPickersUtilsProvider utils={MomentUtils}>
      <ThemeProvider theme={theme}>
        <Provider store={configureStore()}>
          <BrowserRouter>
            <Layout>
              <Route
                exact
                path={['/zipkin', '/zipkin/dependency']}
                component={DiscoverPage}
              />
              <Route
                exact
                path="/zipkin/traces/:traceId"
                component={TracePageContainer}
              />
              <Route
                exact
                path="/zipkin/traceViewer"
                component={TraceViewerContainer}
              />
            </Layout>
          </BrowserRouter>
        </Provider>
      </ThemeProvider>
    </MuiPickersUtilsProvider>
  );
};

export default App;
