// A scalar that represents a change in another scalar.
'use strict';

angular.module('biggraph').directive('delta', function() {
  return {
    restrict: 'E',
    scope: { ref: '=' },
    templateUrl: 'scripts/project/delta.html',
  };
});
