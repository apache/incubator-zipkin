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

import {
  Box,
  createStyles,
  makeStyles,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
  Typography,
} from '@material-ui/core';
import React from 'react';

const useStyles = makeStyles(() =>
  createStyles({
    cell: {
      // overflow-wrap does not work well for elements with "display: table-cell",
      // so use word-break as a workaround.
      wordBreak: 'break-all',
    },
  }),
);

interface SpanTagTableProps {
  tags: { key: string; value: string }[];
}

const SpanTagTable = React.memo<SpanTagTableProps>(({ tags }) => {
  const classes = useStyles();

  return (
    <Box borderColor="grey.300" border="1px solid" borderRadius={3}>
      <TableContainer>
        <Table size="small">
          <TableBody>
            {tags.map((tag) => (
              <TableRow key={`${tag.key}---${tag.value}`}>
                <TableCell className={classes.cell}>
                  <Typography variant="caption" color="textSecondary">
                    {tag.key}
                  </Typography>
                  <Typography variant="body2">{tag.value}</Typography>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
});

export default SpanTagTable;
