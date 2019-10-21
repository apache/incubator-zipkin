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
import PropTypes from 'prop-types';
import React from 'react';
import { withStyles } from '@material-ui/styles';
import Box from '@material-ui/core/Box';
import Typography from '@material-ui/core/Typography';

import SpanTags from './SpanTags';
import SpanAnnotations from './SpanAnnotations';
import { detailedSpanPropTypes } from '../../../prop-types';

const propTypes = {
  span: detailedSpanPropTypes.isRequired,
  minHeight: PropTypes.number.isRequired,
  classes: PropTypes.shape({}).isRequired,
};

const style = theme => ({
  root: {
    width: '100%',
    borderLeft: `1px solid ${theme.palette.grey[300]}`,
    backgroundColor: theme.palette.grey[100],
  },
  names: {
    borderBottom: `1px solid ${theme.palette.grey[300]}`,
  },
  serviceName: {
    textTransform: 'uppercase',
  },
  spanName: {
    color: theme.palette.text.hint,
  },
});

const SpanDetail = React.memo(({ span, minHeight, classes }) => (
  <Box minHeight={minHeight} className={classes.root}>
    <Box pt={2} pl={2} pb={1.5} pr={2} className={classes.names}>
      <Typography variant="h5" className={classes.serviceName}>
        {span.serviceName}
      </Typography>
      <Typography variant="h6" className={classes.spanName}>
        {span.spanName}
      </Typography>
    </Box>
    <Box pt={1} pl={2} pr={2} pb={1.5}>
      <Box fontWeight="bold" fontSize="1.4rem">
        Annotations
      </Box>
      <SpanAnnotations span={span} />
    </Box>
    <Box pt={1} pl={2} pr={2} pb={1.5}>
      <Box fontWeight="bold" fontSize="1.4rem" mb={0.5}>
        Tags
      </Box>
      <SpanTags tags={span.tags} />
    </Box>
  </Box>
));

SpanDetail.propTypes = propTypes;

export default withStyles(style)(SpanDetail);
