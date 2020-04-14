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
import React from 'react';
import { faSearch } from '@fortawesome/free-solid-svg-icons';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import Box from '@material-ui/core/Box';
import Button from '@material-ui/core/Button';
import Typography from '@material-ui/core/Typography';
import { KeyboardDateTimePicker } from '@material-ui/pickers';
import { makeStyles } from '@material-ui/styles';

import TraceJsonUploader from '../Common/TraceJsonUploader';
import TraceIdSearchInput from '../Common/TraceIdSearchInput';

const useStyles = makeStyles((theme) => ({
  root: {
    boxShadow: theme.shadows[3],
    zIndex: 1, // for box-shadow
  },
  upperBox: {
    borderBottom: `1px solid ${theme.palette.grey[300]}`,
    paddingLeft: theme.spacing(3),
    paddingRight: theme.spacing(3),
    width: '100%',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  upperRightBox: {
    paddingRight: theme.spacing(3),
    display: 'flex',
    alignItems: 'center',
  },
  searchBox: {
    width: '100%',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    padding: theme.spacing(1),
  },
  dateTimePicker: {
    marginLeft: theme.spacing(1),
    marginRight: theme.spacing(1),
  },
  dateTimePickerInput: {
    fontSize: '1rem',
    height: '1.6rem',
    padding: '0.4rem 0.6rem',
    '&:disabled': {
      color: theme.palette.text.primary,
    },
  },
  findButton: {
    fontSize: '1.2rem',
    padding: theme.spacing(1),
    minWidth: 0,
  },
}));

const propTypes = {
  startTime: PropTypes.shape({}).isRequired,
  endTime: PropTypes.shape({}).isRequired,
  onStartTimeChange: PropTypes.func.isRequired,
  onEndTimeChange: PropTypes.func.isRequired,
  onFindButtonClick: PropTypes.func.isRequired,
};

const DependenciesPageHeader = React.memo(
  ({
    startTime,
    endTime,
    onStartTimeChange,
    onEndTimeChange,
    onFindButtonClick,
  }) => {
    const classes = useStyles();
    const { i18n } = useLingui();

    const dateTimePickerCommonProps = React.useMemo(
      () => ({
        inputVariant: 'outlined',
        className: classes.dateTimePicker,
        InputProps: {
          // See: https://github.com/openzipkin/zipkin/issues/3052
          // DateTimePicker's input is not very useful, so disable it and
          // let users use a calendar dialog instead.
          disabled: true,
          classes: { input: classes.dateTimePickerInput },
        },
      }),
      [classes.dateTimePicker, classes.dateTimePickerInput],
    );

    return (
      <Box className={classes.root}>
        <Box className={classes.upperBox}>
          <Typography variant="h5" className={classes.pageTitle}>
            <Trans>Dependencies</Trans>
          </Typography>
          <Box className={classes.upperRightBox}>
            <TraceJsonUploader />
            <TraceIdSearchInput />
          </Box>
        </Box>
        <Box className={classes.searchBox}>
          <KeyboardDateTimePicker
            label={i18n._(t`Start Time`)}
            value={startTime}
            onChange={onStartTimeChange}
            {...dateTimePickerCommonProps}
          />
          -
          <KeyboardDateTimePicker
            label={i18n._(t`End Time`)}
            value={endTime}
            onChange={onEndTimeChange}
            {...dateTimePickerCommonProps}
          />
          <Button
            color="primary"
            variant="contained"
            onClick={onFindButtonClick}
            className={classes.findButton}
          >
            <FontAwesomeIcon icon={faSearch} />
          </Button>
        </Box>
      </Box>
    );
  },
);

DependenciesPageHeader.propTypes = propTypes;

export default DependenciesPageHeader;
