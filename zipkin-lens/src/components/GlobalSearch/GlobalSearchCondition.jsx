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
import React, { useState, useRef } from 'react';
import { connect } from 'react-redux';
import { makeStyles } from '@material-ui/styles';
import Box from '@material-ui/core/Box';
import Button from '@material-ui/core/Button';
import Paper from '@material-ui/core/Paper';

import GlobalSearchConditionKey from './GlobalSearchConditionKey';
import GlobalSearchConditionValue from './GlobalSearchConditionValue';
import { globalSearchConditionsPropTypes } from '../../prop-types';
import * as globalSearchActionCreators from '../../actions/global-search-action';

const useStyles = makeStyles(theme => ({
  root: {
    display: 'flex',
    alignItems: 'center',
    height: '2.4rem',
    // I want to set `overflow:hidden` here, but I cannot do that because ReactSelect
    // opens menu as a child component.
    // ReactSelect supports menu component using Portal too, but I don't want to use
    // it because there is a bug of display when `control` component is moved for layout.
  },
  deleteButton: {
    minWidth: '2.4rem',
    width: '2.4rem',
    height: '100%',
    fontSize: '1.2rem',
    boxShadow: 'none',
    borderTopLeftRadius: 0,
    borderTopRightRadius: '0.2rem',
    borderBottomLeftRadius: 0,
    borderBottomRightRadius: '0.2rem',
    color: theme.palette.primary.contrastText,
    backgroundColor: theme.palette.primary.light,
    '&:hover': {
      backgroundColor: theme.palette.primary.main,
    },
  },
}));

const propTypes = {
  conditionIndex: PropTypes.number.isRequired,
  deleteCondition: PropTypes.func.isRequired,
  addCondition: PropTypes.func.isRequired,
  conditions: globalSearchConditionsPropTypes.isRequired,
};

const GlobalSearchCondition = ({
  conditions,
  conditionIndex,
  deleteCondition,
  addCondition,
}) => {
  const classes = useStyles();

  const [isKeyFocused, setIsKeyFocused] = useState(false);
  const [isValueFocused, setIsValueFocused] = useState(false);

  // These ref are necessary, because in callback function raw props value will be old value.
  // Please see: https://reactjs.org/docs/hooks-faq.html#why-am-i-seeing-stale-props-or-state-inside-my-function
  const conditionsRef = useRef(conditions);
  conditionsRef.current = conditions;

  const isKeyFocusedRef = useRef(isKeyFocused);
  const isValueFocusedRef = useRef(isValueFocused);
  isKeyFocusedRef.current = isKeyFocused;
  isValueFocusedRef.current = isValueFocused;

  const deleteWhenValueIsEmpty = () => {
    setTimeout(() => {
      if (
        !isKeyFocusedRef.current
        && !isValueFocusedRef.current
        && !conditionsRef.current[conditionIndex].value
      ) {
        deleteCondition(conditionIndex);
      }
    }, 0);
  };

  const handleKeyFocus = () => setIsKeyFocused(true);
  const handleKeyBlur = () => {
    setIsKeyFocused(false);
    deleteWhenValueIsEmpty();
  };
  const handleValueFocus = () => setIsValueFocused(true);
  const handleValueBlur = () => {
    setIsValueFocused(false);
    deleteWhenValueIsEmpty();
  };

  const handleDeleteButtonClick = () => deleteCondition(conditionIndex);

  const valueRef = useRef(null);
  const focusValue = () => {
    // Delay is needed to avoid calling focus
    // until the value element is mounted.
    // If don't delay, focus cannot be executed.
    setTimeout(() => valueRef.current.focus(), 0);
  };

  return (
    <Paper className={classes.root}>
      <GlobalSearchConditionKey
        conditionIndex={conditionIndex}
        isFocused={isKeyFocused}
        onFocus={handleKeyFocus}
        onBlur={handleKeyBlur}
        focusValue={focusValue}
      />
      <GlobalSearchConditionValue
        conditionIndex={conditionIndex}
        isFocused={isValueFocused}
        onFocus={handleValueFocus}
        onBlur={handleValueBlur}
        valueRef={valueRef}
        addCondition={addCondition}
      />
      <Button
        variant="contained"
        onClick={handleDeleteButtonClick}
        className={classes.deleteButton}
      >
        <Box component="span" className="fas fa-times" />
      </Button>
    </Paper>
  );
};

GlobalSearchCondition.propTypes = propTypes;

const mapStateToProps = state => ({
  conditions: state.globalSearch.conditions,
});

const mapDispatchToProps = (dispatch) => {
  const { deleteCondition } = globalSearchActionCreators;
  return {
    deleteCondition: index => dispatch(deleteCondition(index)),
  };
};

export default connect(
  mapStateToProps,
  mapDispatchToProps,
)(GlobalSearchCondition);