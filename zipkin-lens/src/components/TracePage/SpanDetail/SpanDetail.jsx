/*
 * Copyright 2015-2020 The OpenZipkin Authors
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
import { t, Trans } from '@lingui/macro';
import { useLingui } from '@lingui/react';
import PropTypes from 'prop-types';
import React, { useCallback } from 'react';
import { makeStyles } from '@material-ui/styles';
import Box from '@material-ui/core/Box';
import { Button } from '@material-ui/core';
import Typography from '@material-ui/core/Typography';

import SpanTags from './SpanTags';
import SpanAnnotations from './SpanAnnotations';
import { detailedSpanPropTypes } from '../../../prop-types';

const propTypes = {
  span: detailedSpanPropTypes.isRequired,
  reroot: PropTypes.func.isRequired,
};

const useStyles = makeStyles((theme) => ({
  root: {
    width: '100%',
    borderLeft: `1px solid ${theme.palette.grey[300]}`,
    backgroundColor: theme.palette.grey[100],
    height: '100%',
    overflow: 'auto',
  },
  serviceName: {
    textTransform: 'uppercase',
    overflowWrap: 'break-word',
  },
  spanName: {
    color: theme.palette.text.secondary,
    overflowWrap: 'break-word',
  },
  annotationsAndTagsBox: {
    paddingTop: theme.spacing(1),
    paddingLeft: theme.spacing(2),
    paddingRight: theme.spacing(2),
    paddingBottom: theme.spacing(1.5),
  },
  annotationsAndTagsTitle: {
    fontWeight: 'bold',
    marginBottom: theme.spacing(0.5),
  },
  spanIds: {
    display: 'flex',
    justifyContent: 'flex-end',
    paddingLeft: theme.spacing(2),
    paddingRight: theme.spacing(2),
    paddingTop: theme.spacing(0.1),
    paddingBottom: theme.spacing(0.1),
    fontSize: '0.8rem',
    borderBottom: `1px solid ${theme.palette.grey[300]}`,
  },
  spanIdEntry: {
    display: 'flex',
  },
  spanIdLabel: {
    color: theme.palette.grey[700],
    marginRight: theme.spacing(0.25),
  },
  spanIdValue: {
    color: theme.palette.grey[800],
    marginRight: theme.spacing(1),
  },
}));

const SpanDetail = React.memo(({ span, reroot }) => {
  const classes = useStyles();
  const { i18n } = useLingui();

  const handleRerootButtonClick = useCallback(() => {
    reroot(span.spanId);
  }, [span.spanId, reroot]);

  const spanIds = (
    <Box className={classes.spanIds}>
      {[
        { label: i18n._(t`Span ID`), value: span.spanId },
        { label: i18n._(t`Parent ID`), value: span.parentId },
      ].map((entry) => (
        <Box className={classes.spanIdEntry}>
          <Box className={classes.spanIdLabel}>{`${entry.label}:`}</Box>
          <Box className={classes.spanIdValue}>
            {entry.value || <Trans>None</Trans>}
          </Box>
        </Box>
      ))}
    </Box>
  );

  return (
    <Box className={classes.root}>
      <Box pt={2} pl={2} pr={2} pb={1.5}>
        <Typography variant="h5" className={classes.serviceName}>
          {span.serviceName}
        </Typography>
        <Box display="flex" justifyContent="space-between">
          <Typography variant="h6" className={classes.spanName}>
            {span.spanName}
          </Typography>
          <Button
            onClick={handleRerootButtonClick}
            variant="outlined"
            size="small"
          >
            Reroot
          </Button>
        </Box>
      </Box>
      {spanIds}
      <Box className={classes.annotationsAndTagsBox}>
        <Typography variant="h6" className={classes.annotationsAndTagsTitle}>
          <Trans>Annotations</Trans>
        </Typography>
        <SpanAnnotations span={span} />
      </Box>
      <Box className={classes.annotationsAndTagsBox}>
        <Typography variant="h6" className={classes.annotationsAndTagsTitle}>
          <Trans>Tags</Trans>
        </Typography>
        <SpanTags tags={span.tags} />
      </Box>
    </Box>
  );
});

SpanDetail.propTypes = propTypes;

export default SpanDetail;
