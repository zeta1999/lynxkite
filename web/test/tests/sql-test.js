'use strict';

/* global element, by */
var lib = require('../test-lib.js');
var left = lib.left;
var right = lib.right;

module.exports = function(fw) {
  fw.statePreservingTest(
    'test-example project with example graph',
    'SQL default query works',
    function() {
      left.toggleSqlBox();
      left.runSql();
    
      left.expectSqlResult(
        ['age', 'gender', 'id', 'income', 'location', 'name'],
        [
          ['20.3', 'Male', '0', '1000.0', '[40.71448,-74.00598]', 'Adam'],
          ['18.2', 'Female', '1', 'null', '[47.5269674,19.0323968]', 'Eve'],
          ['50.3', 'Male', '2', '2000.0', '[1.352083,103.819836]', 'Bob'],
          ['2.0', 'Male', '3', 'null', '[-33.8674869,151.2069902]', 'Isolated Joe'],
        ]);
      // Reset state.
      left.toggleSqlBox();
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'SQL works for edge attributes',
    function() {
      left.toggleSqlBox();

      left.runSql('select edge_comment, src_name from triplets order by edge_comment');

      left.expectSqlResult(
        ['edge_comment', 'src_name'],
        [
          [ 'Adam loves Eve', 'Adam' ],
          [ 'Bob envies Adam', 'Bob' ],
          [ 'Bob loves Eve', 'Bob' ],
          [ 'Eve loves Adam', 'Eve' ],
        ]);

      // Reset state.
      left.toggleSqlBox();
    });

  fw.transitionTest(
    'empty test-example project',
    'SQL runs nice on belongs to reached from project and segmentation',
    function() {
      left.runOperation('New vertex set', { size: '100' });
      left.runOperation('Add random vertex attribute', { seed: '1' });
      left.runOperation('Copy graph into a segmentation');
      left.openSegmentation('self_as_segmentation');
      left.toggleSqlBox();
      left.runSql(
        'select sum(base_random / segment_random) as sum from `self_as_segmentation|belongsTo`');
      right.toggleSqlBox();
      right.runSql('select sum(base_random - segment_random) as error from belongsTo');
    },
    function() {
      left.expectSqlResult(['sum'], [['100.0']]);
      right.expectSqlResult(['error'], [['0.0']]);
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'Save SQL result as CSV works',
    function() {
      left.toggleSqlBox();

      left.setSql('select name, age, income from vertices order by name');

      left.startSqlSaving();

      // Choose csv format.
      left.side.element(by.css('#exportFormat option[value="csv"]')).click();

      // And go.
      lib.startDownloadWatch();
      left.executeSqlSaving();
      var downloadedFileName = lib.waitForNewDownload(/\.csv$/);
      lib.expectFileContents(
        downloadedFileName,
        'Adam,20.3,1000.0\nBob,50.3,2000.0\nEve,18.2,\nIsolated Joe,2.0,\n');

      // Reset state.
      left.toggleSqlBox();
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'Save SQL result as CSV works',
    function() {
      left.toggleSqlBox();

      left.setSql('select name, age, income from vertices order by name');

      left.startSqlSaving();

      // Choose csv format.
      left.side.element(by.css('#exportFormat option[value="csv"]')).click();

      // And go.
      lib.startDownloadWatch();
      left.executeSqlSaving();
      var downloadedFileName = lib.waitForNewDownload(/\.csv$/);
      lib.expectFileContents(
        downloadedFileName,
        'Adam,20.3,1000.0\nBob,50.3,2000.0\nEve,18.2,\nIsolated Joe,2.0,\n');

      // Reset state.
      left.toggleSqlBox();
    });

  fw.transitionTest(
    'empty test-example project',
    'table export and reimport',
    function() {
      left.runOperation('New vertex set', { size: '100' });
      left.runOperation('Add random vertex attribute', { name: 'random1', seed: '1' });
      left.runOperation('Add random vertex attribute', { name: 'random2', seed: '2' });
      left.runOperation('Add rank attribute', { keyattr: 'random1', rankattr: 'rank1' });
      left.runOperation('Add rank attribute', { keyattr: 'random2', rankattr: 'rank2' });

      left.toggleSqlBox();
      left.setSql(
        'select cast(rank1 as string), cast(rank2 as string) from vertices');
      left.startSqlSaving();
      left.side.element(by.css('#exportFormat option[value="table"]')).click();
      left.side.element(by.css('#exportKiteTable')).sendKeys('Random Edges');
      left.executeSqlSaving();

      left.runOperation('Vertex attribute to double', { attr: 'ordinal' });
      left.runOperation('Vertex attribute to string', { attr: 'ordinal' });
      left.runOperation(
        'Import edges for existing vertices from table',
        {
          table: 'Random Edges',
          attr: 'ordinal',
          src: 'rank1',
          dst: 'rank2',
        });

      left.toggleSqlBox();

      left.runSql('select sum(rank1) as r1sum, sum(rank2) as r2sum from edges');
      left.expectSqlResult(['r1sum', 'r2sum'], [['4950.0', '4950.0']]);

      left.runSql(
        'select min(edge_rank1 = src_ordinal) as srcgood, min(edge_rank2 = dst_ordinal) as dstgood from triplets');
      left.expectSqlResult(['srcgood', 'dstgood'], [['true', 'true']]);
    },
    function() {
    });

};
