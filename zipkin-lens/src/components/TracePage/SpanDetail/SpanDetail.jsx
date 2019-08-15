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
import React, { useState, useCallback, useEffect } from 'react';
import { makeStyles } from '@material-ui/styles';
import Box from '@material-ui/core/Box';
import Typography from '@material-ui/core/Typography';
import grey from '@material-ui/core/colors/grey';

import SpanAnnotation from './SpanAnnotation';
import SpanAnnotationGraph from './SpanAnnotationGraph';
import SpanTags from './SpanTags';
import { detailedSpanPropTypes } from '../../../prop-types';

const propTypes = {
  span: detailedSpanPropTypes.isRequired,
  minHeight: PropTypes.number.isRequired,
};

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: grey[100],
  },
  serviceName: {
    textTransform: 'uppercase',
  },
  spanName: {
    color: theme.palette.text.hint,
  },
}));

const SpanDetail = ({ span, minHeight }) => {
  const classes = useStyles();

  const [annotationValue, setAnnotationValue] = useState();

  const handleAnnotationClick = useCallback((value) => {
    setAnnotationValue(value);
  }, []);

  useEffect(() => {
    // Refresh selected annotation when the different span is selected.
    setAnnotationValue(null);
  }, [span.spanId]);

  const selectedAnnotation = span.annotations.find(a => a.value === annotationValue);

  return (
    <Box
      width="100%"
      minHeight={minHeight}
      borderLeft={1}
      borderColor="grey.300"
      className={classes.root}
    >
      <Box>
        <Box pt={2} pl={2} pr={2} pb={1.5} borderBottom={1} borderColor="grey.300">
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
          <SpanAnnotationGraph
            duration={span.duration}
            startTs={span.timestamp}
            serviceName={span.serviceName}
            annotations={span.annotations}
            onAnnotationClick={handleAnnotationClick}
            selectedAnnotationValue={annotationValue}
          />
          {
            selectedAnnotation ? (
              <Box mt={1}>
                <SpanAnnotation annotation={selectedAnnotation} />
              </Box>
            ) : null
          }
        </Box>
        <Box
          pt={1}
          pl={2}
          pr={2}
          pb={1.5}
        >
          <Box fontWeight="bold" fontSize="1.4rem" mb={0.5}>
            Tags
          </Box>
          <SpanTags tags={span.tags} />
        </Box>
      </Box>
    </Box>
  );
};

SpanDetail.propTypes = propTypes;

export default SpanDetail;
