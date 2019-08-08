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
import { makeStyles } from '@material-ui/styles';
import Box from '@material-ui/core/Box';
import Drawer from '@material-ui/core/Drawer';
import List from '@material-ui/core/List';
import grey from '@material-ui/core/colors/grey';

import SidebarMenuItem from './SidebarMenuItem';
import { theme } from '../../colors';
import Logo from '../../img/zipkin-sm-logo.svg';

const useStyles = makeStyles({
  paper: {
    width: '3.2rem',
    backgroundColor: grey[900],
    display: 'flex',
    flexDirection: 'column',
    justifyContent: 'space-between',
  },
  logo: {
    marginTop: '0.8rem',
    marginBottom: '0.35rem',
    width: '2.2rem',
    height: '2.2rem',
    '& *': {
      fill: theme.palette.common.white,
    },
  },
});

const Sidebar = () => {
  const classes = useStyles();

  return (
    <Drawer
      open
      variant="permanent"
      classes={{ paper: classes.paper }}
    >
      <Box>
        <Box display="flex" justifyContent="center" width="100%">
          <Logo className={classes.logo} />
        </Box>
        <List data-testid="internal-links">
          <SidebarMenuItem title="Discover Page" links={['/zipkin', '/zipkin/dependency']} logo="fas fa-search" />
        </List>
      </Box>
      <List data-testid="external-links">
        <SidebarMenuItem isExternal title="Zipkin Home" links={['https://zipkin.io/']} logo="fas fa-home" />
        <SidebarMenuItem isExternal title="Repository" links={['https://github.com/openzipkin/zipkin']} logo="fab fa-github" />
        <SidebarMenuItem isExternal title="Twitter" links={['https://twitter.com/zipkinproject']} logo="fab fa-twitter" />
        <SidebarMenuItem isExternal title="Gitter" links={['https://gitter.im/openzipkin/zipkin/']} logo="fab fa-gitter" />
      </List>
    </Drawer>
  );
};

export default Sidebar;
