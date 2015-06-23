// Presents the parameters for saving an operation.
'use strict';

angular.module('biggraph').directive('workflowSaver', function(util) {
  return {
    restrict: 'E',
    scope: { code: '=', mode: '=', side: '=' },
    templateUrl: 'workflow-saver.html',
    link: function(scope) {
      scope.name = '';
      scope.description = '';
      scope.cancel = function() {
        scope.mode.enabled = false;
      };
      scope.save = function() {
        util.post(
          '/ajax/saveWorkflow',
          {
            workflowName: scope.name,
            stepsAsJSON: scope.code,
            description: scope.description,
          },
          function() {
            scope.mode.enabled = false;
            scope.side.reloadAllProjects();
          });
      };
    }
  };
});
