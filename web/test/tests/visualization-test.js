'use strict';

var fs = require('fs');
var lib = require('../test-lib.js');

module.exports = function(fw) {
  // A matcher for lists of objects that ignores fields not present in the reference.
  // Example use:
  //   expect([{ a: 1, b: 1234 }, { a: 2, b: 2345 }]).toConcur([{ a: 1 }, { a: 2 }]);
  // Constraints in strings are also accepted for numerical values. E.g. '<5'.
  // Objects are recursively checked.
  function addConcurMatcher() {
    jasmine.addMatchers({
      toConcur: function(util, customEqualityTesters) {
        return { compare: function(actual, expected) {
          function match(actual, expected) {
            if (expected === null) {
              return actual === null;
            } else if (typeof expected === 'object') {
              var keys = Object.keys(expected);
              for (var i = 0; i < keys.length; ++i) {
                var av = actual[keys[i]];
                var ev = expected[keys[i]];
                if (!match(av, ev)) {
                  return false;
                }
              }
              return true;
            } else if (typeof expected === 'string' && expected[0] === '<') {
              return actual < parseFloat(expected.slice(1));
            } else if (typeof expected === 'string' && expected[0] === '>') {
              return actual > parseFloat(expected.slice(1));
            } else {
              return util.equals(actual, expected, customEqualityTesters);
            }
          }

          if (actual.length !== expected.length) {
            return { pass: false };
          }
          for (var i = 0; i < actual.length; ++i) {
            if (!match(actual[i], expected[i])) {
              return { pass: false };
            }
          }
          return { pass: true };
        }};
      }});
  }

  function positions(graph) {
    var pos = [];
    for (var i = 0; i < graph.vertices.length; ++i) {
      pos.push(graph.vertices[i].pos);
    }
    return pos;
  }

  // Moves all positions horizontally so that the x coordinate of the leftmost
  // position becomes zero. (Inputs and outputs string lists.)
  function normalize(positions) {
    var i, minx;
    for (i = 1; i < positions.length; ++i) {
      var p = positions[i];
      minx = (minx === undefined || p.x < minx) ? p.x : minx;
    }
    for (i = 0; i < positions.length; ++i) {
      positions[i].x -= minx;
    }
  }

  fw.statePreservingTest(
    'test-example project with example graph',
    'sampled mode attribute visualizations',
    function() {
      addConcurMatcher();
      lib.left.hoverAway();
      lib.left.toggleSampledVisualization();

      var expectedEdges = [
        { src : 0, dst: 1 },
        { src : 1, dst: 0 },
        { src : 2, dst: 0 },
        { src : 2, dst: 1 },
      ];
      var savedPositions;
      var GRAY = 'rgb(107, 107, 107)',
          BLUE = 'rgb(53, 53, 161)',
          RED = 'rgb(161, 53, 53)';

      // No attributes visualized.
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.edges).toConcur([
          { color: '', label: '', width: '>2' },
          { color: '', label: '', width: '>2' },
          { color: '', label: '', width: '>2' },
          { color: '', label: '', width: '>2' },
          ]);
        expect(graph.vertices).toConcur([
          { color: GRAY, icon: 'circle', label: '' },
          { color: GRAY, icon: 'circle', label: '' },
          { color: GRAY, icon: 'circle', label: '' },
          ]);
        savedPositions = positions(graph);
      });

      lib.left.visualizeAttribute('name', 'label');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { label: 'Adam' },
          { label: 'Eve' },
          { label: 'Bob' },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('gender', 'icon');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { icon: 'male' },
          { icon: 'female' },
          { icon: 'male' },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('income', 'color');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { color: BLUE },
          { color: GRAY },
          { color: RED },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('age', 'size');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { size: '<15' },
          { size: '<15' },
          { size: '>15' },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('age', 'opacity');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { opacity: '<0.5' },
          { opacity: '<0.5' },
          { opacity: '1' },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('age', 'label-size');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { labelSize: '<15' },
          { labelSize: '<15' },
          { labelSize: '>15' },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('age', 'label-color');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { labelColor: 'rgb(66, 53, 161)' },
          { labelColor: BLUE },
          { labelColor: RED },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      // There is no URL attribute in the example graph. Since we only check the "href"
      // attribute anyway, any string is good enough for the test.
      lib.left.visualizeAttribute('name', 'image');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { image: 'Adam' },
          { image: 'Eve' },
          { image: 'Bob' },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      // Try removing some visualizations.
      lib.left.doNotVisualizeAttribute('age', 'opacity');
      lib.left.doNotVisualizeAttribute('age', 'label-size');
      lib.left.doNotVisualizeAttribute('age', 'label-color');
      lib.left.doNotVisualizeAttribute('name', 'image');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { opacity: '1', labelSize: '15', labelColor: '', image: null },
          { opacity: '1', labelSize: '15', labelColor: '', image: null },
          { opacity: '1', labelSize: '15', labelColor: '', image: null },
          ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      // Edge attributes.
      lib.left.visualizeAttribute('weight', 'width');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.edges).toConcur([
          { width: '<6' },
          { width: '<6' },
          { width: '>6' },
          { width: '>6' },
        ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('weight', 'edge-color');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.edges).toConcur([
          { color: BLUE },
          { color: 'rgb(125, 53, 161)' },
          { color: 'rgb(161, 53, 125)' },
          { color: RED },
        ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      lib.left.visualizeAttribute('comment', 'edge-label');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.edges).toConcur([
          { label: 'Adam loves Eve' },
          { label: 'Eve loves Adam' },
          { label: 'Bob envies Adam' },
          { label: 'Bob loves Eve' },
        ]);
        expect(positions(graph)).toEqual(savedPositions);
      });

      // Location attributes.
      lib.left.visualizeAttribute('location', 'position');
      // Toggle off and on to shake off the unpredictable offset from the non-positioned layout.
      lib.left.toggleSampledVisualization();
      lib.left.toggleSampledVisualization();
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { pos: { x: '>560', y: '>200' } },
          { pos: { x: '>560', y: '<200' } },
          { pos: { x: '<560', y: '<100' } },
          ]);
      });

      lib.left.visualizeAttribute('location', 'geo');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur(expectedEdges);
        expect(graph.vertices).toConcur([
          { pos: { x: '<500', y: '<100' } },
          { pos: { x: '>500', y: '<100' } },
          { pos: { x: '>600', y: '>100' } },
          ]);
      });

      lib.navigateToProject('test-example'); // Restore state.
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'visualize as slider',
    function() {
      addConcurMatcher();
      lib.left.hoverAway();
      lib.left.toggleSampledVisualization();
      lib.left.visualizeAttribute('name', 'label');
      lib.left.visualizeAttribute('age', 'slider');
      var RED = 'rgb(161, 53, 53)',
          YELLOW = 'rgb(184, 184, 46)',
          GREEN = 'rgb(53, 161, 53)';
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: GREEN },
          { label: 'Eve', color: GREEN },
          { label: 'Bob', color: RED },
          ]);
      });
      var slider = lib.left.attributeSlider('age');
      /* global protractor */
      var K = protractor.Key;

      slider.sendKeys(K.HOME);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: RED },
          { label: 'Eve', color: RED },
          { label: 'Bob', color: RED },
          ]);
      });

      slider.sendKeys(K.RIGHT);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: RED },
          { label: 'Eve', color: YELLOW },
          { label: 'Bob', color: RED },
          ]);
      });

      slider.sendKeys(K.RIGHT);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: RED },
          { label: 'Eve', color: GREEN },
          { label: 'Bob', color: RED },
          ]);
      });

      slider.sendKeys(K.END);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: GREEN },
          { label: 'Eve', color: GREEN },
          { label: 'Bob', color: GREEN },
          ]);
      });

      slider.sendKeys(K.LEFT);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: GREEN },
          { label: 'Eve', color: GREEN },
          { label: 'Bob', color: YELLOW },
          ]);
      });

      slider.sendKeys(K.LEFT);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam', color: GREEN },
          { label: 'Eve', color: GREEN },
          { label: 'Bob', color: RED },
          ]);
      });
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'bucketed mode attribute visualizations',
    function() {
      addConcurMatcher();
      lib.left.toggleBucketedVisualization();
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur([{ src: 0, dst: 0 }]);
        expect(graph.vertices).toConcur([{ label: '4' }]);
      });

      lib.left.visualizeAttribute('gender', 'x');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur([
          { src: 0, dst: 1, width: '<6' },
          { src: 1, dst: 0, width: '>6' },
          { src: 1, dst: 1, width: '<6' },
          ]);
        expect(graph.vertices).toConcur([
          { label: '1' },
          { label: '3' },
          ]);
      });

      lib.left.visualizeAttribute('age', 'y');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.edges).toConcur([
          { src: 0, dst: 2, width: '>2' },
          { src: 2, dst: 0, width: '>2' },
          { src: 3, dst: 0, width: '>2' },
          { src: 3, dst: 2, width: '>2' },
          ]);
        expect(graph.vertices).toConcur([
          { label: '1' },
          { label: '1' },
          { label: '1' },
          { label: '1' },
          ]);
      });

      lib.navigateToProject('test-example'); // Restore state.
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'visualization for two open projects',
    function() {
      addConcurMatcher();
      lib.left.toggleSampledVisualization();
      lib.left.visualizeAttribute('name', 'label');
      var leftPositions;
      lib.visualization.graphData().then(function(graph) {
        leftPositions = positions(graph);
        normalize(leftPositions);
        expect(graph.vertices.length).toBe(3);
      });

      lib.right.openSecondProject('test-example');
      lib.right.toggleBucketedVisualization();
      lib.right.visualizeAttribute('gender', 'y');
      lib.visualization.graphData().then(function(graph) {
        // Make sure the original vertices did not move.
        function matchPos(a, b) {
          return a.x.toFixed(3) === b.x.toFixed(3) && a.y.toFixed(3) === b.y.toFixed(3);
        }
        var pos = positions(graph);
        normalize(pos);
        for (var i = 0; i < leftPositions.length; ++i) {
          var found = false;
          for (var j = 0; j < pos.length; ++j) {
            if (matchPos(pos[j], leftPositions[i])) {
              found = true;
              break;
            }
          }
          expect(found).toBe(true);
        }
        expect(graph.edges).toConcur([
          { src: 0, dst: 1, width: '<6' },
          { src: 0, dst: 3, width: '<6' },
          { src: 1, dst: 0, width: '<6' },
          { src: 1, dst: 4, width: '<6' },
          { src: 2, dst: 0, width: '<6' },
          { src: 2, dst: 1, width: '<6' },
          { src: 2, dst: 3, width: '<6' },
          { src: 2, dst: 4, width: '<6' },
          { src: 3, dst: 0, width: '<6' },
          { src: 3, dst: 4, width: '<6' },
          { src: 4, dst: 0, width: '<6' },
          { src: 4, dst: 1, width: '>6' },
          { src: 4, dst: 3, width: '>6' },
          { src: 4, dst: 4, width: '<6' },
          ]);
        expect(graph.vertices).toConcur([
          { label: 'Adam' },
          { label: 'Eve' },
          { label: 'Bob' },
          { label: '1' },
          { label: '3' },
          ]);
      });

      // Check TSV of this complex visualization.
      var expectedTSV = fs.readFileSync(__dirname + '/visualization-tsv-data.txt', 'utf8');
      expect(lib.visualization.asTSV()).toEqual(expectedTSV);

      lib.navigateToProject('test-example'); // Restore state.
    });

  fw.statePreservingTest(
    'test-example project with example graph',
    'visualization context menu',
    function() {
      addConcurMatcher();
      lib.left.toggleSampledVisualization();
      lib.left.visualizeAttribute('name', 'label');
      lib.visualization.elementByLabel('Eve').click();
      lib.visualization.clickMenu('add-to-centers');
      lib.left.setSampleRadius(0);
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Adam' },
          { label: 'Eve' },
        ]);
        expect(graph.edges).toConcur([
          { src: 0, dst: 1 },
          { src: 1, dst: 0 },
        ]);
      });

      lib.visualization.elementByLabel('Adam').click();
      lib.visualization.clickMenu('remove-from-centers');
      lib.visualization.graphData().then(function(graph) {
        expect(graph.vertices).toConcur([
          { label: 'Eve' },
        ]);
        expect(graph.edges).toEqual([]);
      });

      lib.navigateToProject('test-example'); // Restore state.
    });
};
