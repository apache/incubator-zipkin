/*
 * Copyright 2015-2021 The OpenZipkin Authors
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
import {
  Box,
  Collapse,
  Divider,
  IconButton,
  Typography,
} from '@material-ui/core';
import ArrowDropDownIcon from '@material-ui/icons/ArrowDropDown';
import ArrowDropUpIcon from '@material-ui/icons/ArrowDropUp';
import ArrowRightIcon from '@material-ui/icons/ArrowRight';
import LabelImportantIcon from '@material-ui/icons/LabelImportant';
import React from 'react';
import { useToggle } from 'react-use';

import SpanAnnotationTable from './SpanAnnotationTable';
import SpanTagTable from './SpanTagTable';
import { selectServiceColor } from '../../../constants/color';
import { AdjustedSpan } from '../../../models/AdjustedTrace';

interface SpanDetailProps {
  span: AdjustedSpan;
  minHeight: number;
}

const SpanDetail = React.memo<SpanDetailProps>(({ span, minHeight }) => {
  const { i18n } = useLingui();

  const [openAnnotations, toggleOpenAnnotations] = useToggle(true);
  const [openTags, toggleOpenTags] = useToggle(true);

  return (
    <Box bgcolor="background.paper" boxShadow={3} minHeight={minHeight}>
      <Box
        pt={2}
        pl={2}
        pr={2}
        pb={1.5}
        borderLeft="8px solid"
        borderColor={selectServiceColor(span.serviceName)}
      >
        <Typography variant="h6">{span.serviceName}</Typography>
        <Box display="flex" alignItems="center">
          <ArrowRightIcon fontSize="small" />
          <Typography variant="body2" color="textSecondary">
            {span.spanName}
          </Typography>
        </Box>
      </Box>
      <Divider />
      <Box display="flex" justifyContent="flex-end" mr={1} bgcolor="grey.50">
        {[
          { label: i18n._(t`Span ID`), value: span.spanId },
          { label: i18n._(t`Parent ID`), value: span.parentId },
        ].map((entry) => (
          <Box display="flex">
            <Box color="text.secondary" mr={0.5}>{`${entry.label}: `}</Box>
            <Box mr={1}>{entry.value || <Trans>None</Trans>}</Box>
          </Box>
        ))}
      </Box>
      <Divider />
      <Box
        pt={1}
        pr={2}
        pl={2}
        display="flex"
        alignItems="center"
        justifyContent="space-between"
      >
        <Box display="flex" alignItems="center">
          <LabelImportantIcon fontSize="small" />
          <Box ml={0.5} mr={0.5}>
            <Typography variant="body1">
              <Trans>Annotations</Trans>
            </Typography>
          </Box>
        </Box>
        <IconButton size="small" onClick={toggleOpenAnnotations}>
          {openAnnotations ? <ArrowDropUpIcon /> : <ArrowDropDownIcon />}
        </IconButton>
      </Box>
      <Collapse in={openAnnotations}>
        <Box pl={2} pr={2} pt={1} pb={1} height={240}>
          <SpanAnnotationTable annotations={span.annotations} />
        </Box>
      </Collapse>
      <Box
        pt={1}
        pr={2}
        pl={2}
        display="flex"
        alignItems="center"
        justifyContent="space-between"
      >
        <Box display="flex" alignItems="center">
          <LabelImportantIcon fontSize="small" />
          <Box ml={0.5}>
            <Typography variant="body1">
              <Trans>Tags</Trans>
            </Typography>
          </Box>
        </Box>
        <IconButton size="small" onClick={toggleOpenTags}>
          {openTags ? <ArrowDropUpIcon /> : <ArrowDropDownIcon />}
        </IconButton>
      </Box>
      <Collapse in={openTags}>
        <Box pl={2} pr={2} pt={1} pb={1}>
          <SpanTagTable tags={span.tags} />
        </Box>
      </Collapse>
    </Box>
  );
});

export default SpanDetail;
