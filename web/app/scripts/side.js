// The Side object represents one project in the project view.
'use strict';

angular.module('biggraph')
  .factory('side', function (util, $rootScope, getCenter) {
    function defaultSideState() {
      return {
        projectName: undefined,
        filters: {
          edge: {},
          vertex: {},
        },
        axisOptions: {
          edge: {},
          vertex: {},
        },
        graphMode: undefined,
        bucketCount: '4',
        preciseBucketSizes: false,
        relativeEdgeDensity: false,
        sampleRadius: '1',
        display: 'svg',
        animate: {
          enabled: false,
          labelAttraction: '0',
          style: 'neutral',
        },
        attributeTitles: {},
        centers: undefined,
        lastCentersRequest: undefined,
        lastCentersResponse: undefined,
        customVisualizationFilters: false,
      };
    }

    function Side(sides, direction) {
      // The list of all sides.
      this.sides = sides;
      // Left or right?
      this.direction = direction;
      // The state of controls. E.g. bucket count.
      this.state = defaultSideState();
      // Everything needed for a view (state included).
      // Use this for rendering graph view instead of using state directly.
      this.viewData = {};
      // The /ajax/project Ajax response.
      this.project = undefined;
      this.workflowEditor = { enabled: false };
      this.sectionVisibility = {};
      for (var i = 0; i < this.sections.length; ++i) {
        this.sectionVisibility[this.sections[i]] = true;
      }
    }

    Side.prototype.sections = ['scalar', 'vertex-attribute', 'edge-attribute', 'segmentation'];
    Side.prototype.sectionHumanName = {
      'scalar': 'Graph attributes',
      'vertex-attribute': 'Vertex attributes',
      'edge-attribute': 'Edge attributes',
      'segmentation': 'Segmentations',
    };
    Side.prototype.sectionHelp = {
      'scalar': 'graph-attributes',
      'vertex-attribute': 'attributes',
      'edge-attribute': 'attributes',
      'segmentation': 'segmentations',
    };
    Side.prototype.sectionElements = function(section) {
      if (section === 'scalar') {
        return this.project.scalars.filter(function(s) {
          return s.title[0] !== '!' && s.title !== 'vertex_count' && s.title !== 'edge_count';
        });
      } else if (section === 'vertex-attribute') {
        return this.project.vertexAttributes;
      } else if (section === 'edge-attribute') {
        return this.project.edgeAttributes;
      } else if (section === 'segmentation') {
        return this.project.segmentations;
      }
      console.error('Unexpected section:', section);
    };
    Side.prototype.showSection = function(section, show) {
      var key = 'section-visibility: ' + section;
      if (show === undefined) {
        var saved = localStorage.getItem(key);
        return saved === null ? true : saved === 'true';
      } else {
        localStorage.setItem(key, show);
      }
    };

    // Returns the number of Bootstrap columns to use for this side.
    Side.prototype.columnWidth = function() {
      var selectorWidth = 3;
      if (this.state.projectName === undefined) {
        return selectorWidth;
      }
      var remaining = 12; // Bootstrap columns.
      var selectors = 0;
      for (var i = 0; i < this.sides.length; ++i) {
        if (this.sides[i].state.projectName === undefined) {
          selectors += 1;
        }
      }
      return (remaining - selectors * selectorWidth) / (this.sides.length - selectors);
    };

    // Creates a JSON formatted version of the current UI state of this side. The output is
    // abstracted a bit away from the exact status so that it can be reapplied in slighly different
    // circumctances as well. E.g. project name is omitted, as the same visualization makes
    // sense in forks of the original project and center list is replaced with the request used
    // to get this center list (as long as the current center list came from a getCenters request).
    Side.prototype.getBackendJson = function() {
      var backendState = angular.copy(this.state);
      delete backendState.projectName;
      if (this.state.centers === this.state.lastCentersResponse) {
        delete backendState.centers;
      } else {
        delete backendState.lastCentersRequest;
      }
      delete backendState.lastCentersResponse;
      return JSON.stringify(backendState, null, 2);
    };

    Side.prototype.updateFromBackendJson = function(backendJson) {
      var backendState = JSON.parse(backendJson);
      backendState.projectName = this.state.projectName;
      this.state = backendState;
      if (this.state.centers === undefined) {
        this.state.centers = [];
        this.sendCenterRequest(this.state.lastCentersRequest);
      }
    };

    Side.prototype.saveStateToBackend = function(scalarName, opFinishedCallback) {
      this.applyOp(
        'Save-UI-status-as-graph-attribute',
        {
          scalarName: scalarName,
          uiStatusJson: this.getBackendJson(),
        }).finally(opFinishedCallback);
    };


    Side.prototype.updateViewData = function() {
      var vd = this.viewData || {};
      var noCenters = (this.state.centers === undefined) || (this.state.centers.length === 0);
      if (!this.loaded() || !this.state.graphMode ||
          (this.state.graphMode === 'sampled' && noCenters)) {
        this.viewData = undefined;
        return;
      }

      vd.vertexSet = { id: this.project.vertexSet };
      if (this.project.edgeBundle) {
        vd.edgeBundle = { id: this.project.edgeBundle };
      } else {
        vd.edgeBundle = undefined;
      }
      vd.graphMode = this.state.graphMode;
      vd.display = this.state.display;

      vd.bucketCount = this.state.bucketCount;
      vd.preciseBucketSizes = this.state.preciseBucketSizes;
      vd.relativeEdgeDensity = this.state.relativeEdgeDensity;

      // "state" uses attribute names, while "viewData" uses attribute UUIDs.
      vd.xAttribute = this.resolveVertexAttribute(this.state.attributeTitles.x);
      vd.yAttribute = this.resolveVertexAttribute(this.state.attributeTitles.y);
      vd.xAxisOptions = this.axisOptions('vertex', this.state.attributeTitles.x);
      vd.yAxisOptions = this.axisOptions('vertex', this.state.attributeTitles.y);

      vd.vertexAttrs = {};
      vd.vertexAttrs.size = this.resolveVertexAttribute(this.state.attributeTitles.size);
      vd.vertexAttrs.color = this.resolveVertexAttribute(this.state.attributeTitles.color);
      vd.vertexAttrs.opacity = this.resolveVertexAttribute(this.state.attributeTitles.opacity);
      vd.vertexAttrs.label = this.resolveVertexAttribute(this.state.attributeTitles.label);
      vd.vertexAttrs.labelSize =
        this.resolveVertexAttribute(this.state.attributeTitles['label size']);
      vd.vertexAttrs.labelColor =
        this.resolveVertexAttribute(this.state.attributeTitles['label color']);
      vd.vertexAttrs.slider = this.resolveVertexAttribute(this.state.attributeTitles.slider);
      vd.vertexAttrs.icon = this.resolveVertexAttribute(this.state.attributeTitles.icon);
      vd.vertexAttrs.image = this.resolveVertexAttribute(this.state.attributeTitles.image);
      vd.vertexAttrs.position = this.resolveVertexAttribute(this.state.attributeTitles.position);
      vd.vertexAttrs.geo = this.resolveVertexAttribute(this.state.attributeTitles.geo);

      vd.edgeAttrs = {};
      var aggregated = function(attr, aggregator) {
        if (attr) {
          var aggrAttr = angular.copy(attr);
          aggrAttr.aggregator = aggregator;
          return aggrAttr;
        }
        return undefined;
      };
      vd.edgeAttrs.width = aggregated(
        this.resolveEdgeAttribute(this.state.attributeTitles.width),
        'sum');

      if (vd.graphMode !== 'bucketed') {
        vd.edgeAttrs.edgeLabel = aggregated(
          this.resolveEdgeAttribute(this.state.attributeTitles['edge label']),
          'set');
        var edgeColorAttr = this.resolveEdgeAttribute(this.state.attributeTitles['edge color']);
        if (edgeColorAttr !== undefined) {
          vd.edgeAttrs.edgeColor =
            (edgeColorAttr.typeName === 'Double') ?
            aggregated(edgeColorAttr, 'sum') : aggregated(edgeColorAttr, 'set');
        }
      }

      vd.edgeWidth = this.resolveEdgeAttribute(this.state.attributeTitles.width);

      vd.filters = {
        edge: this.nonEmptyEdgeFilters(),
        vertex: this.nonEmptyVertexFilters(),
      };

      vd.centers = this.state.centers || [];
      var that = this;
      vd.hasCenter = function(id) { return that.state.centers.indexOf(id) !== -1; };
      vd.setCenter = function(id) { that.state.centers = [id]; };
      vd.addCenter = function(id) { that.state.centers = that.state.centers.concat([id]); };
      vd.removeCenter = function(id) {
        that.state.centers =
          that.state.centers.filter(function(element) { return element !== id; });
      };
      vd.sampleRadius = this.state.sampleRadius;
      vd.animate = this.state.animate;
      vd.sliderPos = this.state.sliderPos;

      vd.hasParent = function() {
        return that.getParentSide() !== undefined;
      };
      vd.parentFilters = function() {
        return that.getParentSide().state.filters.vertex;
      };
      vd.parentFilterName = function() {
        return that.getParentSide().getSegmentationEntry(that).equivalentAttribute.title;
      };
      vd.filterValue = function(id) {
        return 'exists(' + id + ')';
      };
      vd.filterParentToSegment = function(segmentId) {
        vd.parentFilters()[vd.parentFilterName()] = vd.filterValue(segmentId);
      };
      vd.isParentFilteredToSegment = function(segmentId) {
        return vd.parentFilters()[vd.parentFilterName()] === vd.filterValue(segmentId);
      };
      vd.deleteParentsSegmentFilter = function() {
        delete vd.parentFilters()[vd.parentFilterName()];
      };

      vd.hasSegmentation = function() {
        return that.getSegmentationSide() !== undefined;
      };
      vd.segmentationFilters = function() {
        return that.getSegmentationSide().state.filters.vertex;
      };
      vd.filterSegmentationToParent = function(parentId) {
        vd.segmentationFilters()['#members'] = vd.filterValue(parentId);
      };
      vd.isSegmentationFilteredToParent = function(parentId) {
        return vd.segmentationFilters()['#members'] === vd.filterValue(parentId);
      };
      vd.deleteSegmentationsParentFilter = function() {
        delete vd.segmentationFilters()['#members'];
      };

      vd.setVertexFilter = function(title, value) {
        that.state.filters.vertex[title] = value;
      };

      this.viewData = vd;
    };

    Side.prototype.axisOptions = function(type, attr) {
      var defaultAxisOptions = {
        logarithmic: false,
      };
      return this.state.axisOptions[type][attr] || defaultAxisOptions;
    };

    Side.prototype.onGraphModeChange = function() {
      if (this.state.graphMode === 'bucketed') {
        if (this.state.display !== 'svg') {
          this.state.display = 'svg';
        }
      }
    };

    Side.prototype.resolveCenterRequestParams = function(params) {
      var resolvedParams = angular.copy(params);
      resolvedParams.filters = this.resolveVertexFilters(params.filters);
      resolvedParams.vertexSetId = this.project.vertexSet;
      return resolvedParams;
    };
    Side.prototype.sendCenterRequest = function(params) {
      var that = this;
      var resolvedParams = this.resolveCenterRequestParams(params);
      this.centerRequest = getCenter(resolvedParams);
      this.centerRequest.then(
        function(centers) {
          that.state.centers = centers;
          that.state.lastCentersRequest = params;
          that.state.lastCentersResponse = centers;
        });
    };

    Side.prototype.shortName = function() {
      if (this.state.projectName === undefined) {
        return undefined;
      }
      var path = util.projectPath(this.state.projectName);
      var last = path[path.length - 1];
      var directories = last.split('/');
      return directories[directories.length - 1];
    };
    Side.prototype.parentProjects = function() {
      var path = util.projectPath(this.state.projectName);
      path.pop();  // Discard project name.
      return path;
    };
    Side.prototype.isSegmentation = function() {
      return this.parentProjects().length !== 0;
    };
    Side.prototype.parentProject = function() {
      return this.parentProjects().join('|');
    };

    // Side.reload makes an unconditional, uncached Ajax request.
    // This is called when the project name changes, or when the project
    // itself is expected to change. (Such as after an operation.)
    Side.prototype.reload = function() {
      if (this.state.projectName) {
        var newProject = this.load();  // The old project is used to look for segmentations.
        for (var i = 0; i < this.sides.length; ++i) {
          var side = this.sides[i];
          if (side === this) { continue; }
          // If this project is open on the other side, update that instance too.
          if (side.state.projectName === this.state.projectName) {
            side.project = newProject;
          }
          // If a segmentation or parent is open, reload it as well.
          if (side.isSegmentationOf(this) || this.isSegmentationOf(side)) {
            side.project = side.load();
          }
        }
        this.project = newProject;
      } else {
        this.state = defaultSideState();
        this.project = undefined;
      }
      $rootScope.$broadcast('project reloaded');
    };

    // Reloads all sides. Avoids reloading a side more than once.
    Side.prototype.reloadAllProjects = function() {
      for (var i = 0; i < this.sides.length; ++i) {
        var side = this.sides[i];
        if (side.state.projectName) {
          side.project = side.load();
        } else {
          side.project = undefined;
        }
      }
      $rootScope.$broadcast('project reloaded');
    };

    Side.prototype.load = function() {
      return util.nocache('/ajax/project', { name: this.state.projectName });
    };

    Side.prototype.loaded = function() {
      return this.project && this.project.$resolved && !this.project.$error;
    };

    Side.prototype.reportLoadingError = function() {
      return util.reportRequestError(
          this.project, 'Could not load project: ' + this.state.projectName);
    };

    Side.prototype.toggleAttributeTitle = function(setting, value) {
      if (this.state.attributeTitles[setting] === value) {
        // Clicking the same attribute setting again turns it off.
        this.state.attributeTitles[setting] = undefined;
        // Apply dependencies.
        if (setting === 'label') {
          this.state.attributeTitles['label size'] = undefined;
          this.state.attributeTitles['label color'] = undefined;
        }
      } else {
        this.state.attributeTitles[setting] = value;
        // Apply mutual exclusions and do initialization.
        if (setting === 'slider') {
          this.state.attributeTitles.color = undefined;
          this.state.sliderPos = 50;
        } else if (setting === 'color') {
          this.state.attributeTitles.image = undefined;
          this.state.attributeTitles.slider = undefined;
        } else if (setting === 'icon') {
          this.state.attributeTitles.image = undefined;
        } else if (setting === 'image') {
          this.state.attributeTitles.color = undefined;
          this.state.attributeTitles.icon = undefined;
        } else if (setting === 'position') {
          this.state.attributeTitles.geo = undefined;
        } else if (setting === 'geo') {
          this.state.attributeTitles.position = undefined;
        }
      }
    };

    Side.prototype.filterApplied = function(settings, value) {
      var that = this;
      var applied = settings.filter(
        function(setting) {
          return that.state.attributeTitles[setting] === value;
        });
      return applied;
    };

    Side.prototype.closeInternal = function() {
      this.state.projectName = undefined;
    };

    Side.prototype.close = function() {
      if (this.direction === 'right' &&
          this.parentProjects().length >= 2 &&
          this.parentProject() === this.sides[0].state.projectName) {
        // If this project was:
        // 1. open on the right hand side
        // 2. was a segmentation of a segmentation
        // 3. it's parent was open on the left hand side
        // Then move its parent to the right hand side, and open its
        // grandparent on the left hand side.
        this.state.projectName = this.sides[0].parentProject();
        this.swapWithSide(this.sides[0]);
      } else {
        this.closeInternal();
      }
    };

    Side.prototype.startSavingAs = function() {
      this.showSaveAs = true;
      this.saveAsName = this.state.projectName;
    };

    Side.prototype.maybeSaveAs = function() {
      // We only need to do an actual action if the user has changed the name.
      if (this.saveAsName !== this.state.projectName) {
        this.saveAs(this.saveAsName);
      }
      this.showSaveAs = false;
    };

    Side.prototype.saveAs = function(newName) {
      var that = this;
      util.post('/ajax/forkEntry',
        {
          from: this.state.projectName,
          to: newName,
        }).then(function() {
          that.state.projectName = newName;
        });
    };

    Side.prototype.undo = function() {
      var that = this;
      util.post('/ajax/undoProject',
        {
          project: this.state.projectName,
        }).then(function() {
          that.reload();
        });
    };
    Side.prototype.redo = function() {
      var that = this;
      util.post('/ajax/redoProject',
        {
          project: this.state.projectName,
        }).then(function() {
          that.reload();
        });
    };

    // Returns a promise.
    Side.prototype.applyOp = function(op, params) {
      var that = this;
      return util.post('/ajax/projectOp',
        {
          project: this.state.projectName,
          op: { id: op, parameters: params },
        }).then(function() {
          that.reload();
        });
    };

    Side.prototype.saveNotes = function() {
      var that = this;
      this.savingNotes = true;
      this.applyOp('Change-project-notes', { notes: this.project.notes })
        .then(function() {
          that.unsavedNotes = false;
          that.savingNotes = false;
        });
    };

    Side.prototype.rename = function(kind, oldName, newName) {
      if (oldName === newName) { return; }
      this.applyOp('Rename-' + kind, { from: oldName, to: newName });
    };

    Side.prototype.duplicate = function(kind, name) {
      this.applyOp('Copy-' + kind, { from: name, to: 'copy_of_' + name });
    };

    Side.prototype.discard = function(kind, name) {
      // if the other side is the segmentation to be discarded, close it
      if (kind === 'segmentation') {
        for (var i = 0; i < this.sides.length; ++i) {
          var side = this.sides[i];
          if (side === this) { continue; }
          for (var j = 0; j < this.project.segmentations.length; ++j) {
            var seg = this.project.segmentations[j];
            if (seg.name === name) {
              if (side.project && seg.fullName === side.project.name) {
                side.close();
              } else {
                break;
              }
            }
          }
        }
      }
      this.applyOp('Discard-' + kind, { name: name });
    };

    Side.prototype.isInternalVertexFilter = function(name) {
      return this.resolveVertexAttribute(name).isInternal;
    };
    // Returns unresolved filters (i.e. keyed by the attribute name).
    Side.prototype.nonEmptyVertexFilterNames = function() {
      return this.nonEmptyFilterNames(this.state.filters.vertex);
    };
    Side.prototype.nonEmptyEdgeFilterNames = function() {
      return this.nonEmptyFilterNames(this.state.filters.edge);
    };
    Side.prototype.nonEmptyFilterNames = function(filters) {
      var res = [];
      for (var attr in filters) {
        if (filters[attr] !== '') {
          res.push({
            attributeName: attr,
            valueSpec: filters[attr] });
        }
      }
      return res;
    };

    Side.prototype.resolveVertexFilters = function(filters) {
      var that = this;
      return filters.map(function(f) {
        return {
          attributeId: that.resolveVertexAttribute(f.attributeName).id,
          valueSpec: f.valueSpec,
        };
      });
    };
    Side.prototype.nonEmptyVertexFilters = function() {
      return this.resolveVertexFilters(this.nonEmptyVertexFilterNames());
    };
    Side.prototype.resolveEdgeFilters = function(filters) {
      var that = this;
      return filters.map(function(f) {
        return {
          attributeId: that.resolveEdgeAttribute(f.attributeName).id,
          valueSpec: f.valueSpec,
        };
      });
    };
    Side.prototype.nonEmptyEdgeFilters = function() {
      return this.resolveEdgeFilters(this.nonEmptyEdgeFilterNames());
    };

    Side.prototype.hasFilters = function() {
      return (this.nonEmptyEdgeFilterNames().length !== 0 ||
              this.nonEmptyVertexFilterNames().length !== 0);
    };
    Side.prototype.applyFiltersEnabled = function() {
      var that = this;
      return this.nonEmptyVertexFilterNames().every(
        function(filter) { return !that.isInternalVertexFilter(filter.attributeName); });
    };

    Side.prototype.applyFilters = function() {
      var that = this;
      util.post('/ajax/filterProject',
        {
          project: this.state.projectName,
          edgeFilters: this.nonEmptyEdgeFilterNames(),
          vertexFilters: this.nonEmptyVertexFilterNames(),
        }).then(function() {
          that.clearFilters();
          that.reload();
        });
    };
    Side.prototype.clearFilters = function() {
      this.state.filters = { edge: {}, vertex: {} };
    };
    Side.prototype.filterSummary = function() {
      var that = this;
      var NBSP = '\u00a0';
      var res = [];
      function addNonEmpty(value, key) {
        if (value) {
          res.push(' ' + key + NBSP + value);
        }
      }
      function addProblematic(value, key) {
        if (that.isInternalVertexFilter(key)) {
          var note = ' Cannot' + NBSP + 'apply' + NBSP + key + NBSP + 'here';
          res.push(note);
        }
      }
      angular.forEach(this.state.filters.vertex, addProblematic);
      angular.forEach(this.state.filters.vertex, addNonEmpty);
      angular.forEach(this.state.filters.edge, addNonEmpty);
      return res.join(', ');
    };
    Side.prototype.filterableVertexAttributes = function() {
      return this.project.vertexAttributes.concat(
        this.project.segmentations.map(function(segmentation) {
          return segmentation.equivalentAttribute;
        }));
    };
    Side.prototype.resolveVertexAttribute = function(title) {
      var filterableAttributes = this.filterableVertexAttributes();
      for (var attrIdx = 0; attrIdx < filterableAttributes.length; attrIdx++) {
        var attr = filterableAttributes[attrIdx];
        if (attr.title === title) {
          return attr;
        }
      }
      return undefined;
    };

    Side.prototype.resolveEdgeAttribute = function(title) {
      for (var attrIdx = 0; attrIdx < this.project.edgeAttributes.length; attrIdx++) {
        var attr = this.project.edgeAttributes[attrIdx];
        if (attr.title === title) {
          return attr;
        }
      }
      return undefined;
    };

    Side.prototype.swapWithSide = function(otherSide) {
      var tmp;
      tmp = this.project; this.project = otherSide.project; otherSide.project = tmp;
      tmp = this.state; this.state = otherSide.state; otherSide.state = tmp;
      tmp = this.viewData; this.viewData = otherSide.viewData; otherSide.viewData = tmp;
    };

    Side.prototype.openSegmentation = function(seg) {
      // Move this side to the left if it's not on the left.
      if (this.direction !== 'left') {
        // Swap sides 0 and this.
        this.sides[0].closeInternal();
        this.sides[0].swapWithSide(this);
      }
      // Segmentations always open on the right.
      this.sides[1].state.projectName = seg.fullName;
      this.sides[1].state.graphMode = undefined;
    };

    // Abandon outstanding scalar requests.
    Side.prototype.abandonScalars = function() {
      if (this.scalars !== undefined) {
        var scalars = Object.keys(this.scalars);
        for (var i = 0; i < scalars.length; ++i) {
          this.scalars[scalars[i]].$abandon();
        }
      }
    };

    Side.prototype.loadScalars = function() {
      if (!this.loaded()) { return; }
      this.abandonScalars();
      this.scalars = {};
      var scalars = this.project.scalars;
      for (var i = 0; i < scalars.length; ++i) {
        var scalar = scalars[i];
        this.scalars[scalar.title] = util.lazyFetchScalarValue(
          scalar,
          true);
      }
    };

    Side.prototype.isSegmentationOf = function(parent) {
      return parent.getBelongsTo(this) !== undefined;
    };
    Side.prototype.getBelongsTo = function(segmentation) {
      var entry = this.getSegmentationEntry(segmentation);
      if (entry) {
        return entry.belongsTo;
      }
      return undefined;
    };
    Side.prototype.getSegmentationEntry = function(segmentation) {
      if (!this.loaded()) { return undefined; }
      if (!segmentation.project || !segmentation.project.$resolved) { return undefined; }
      for (var i = 0; i < this.project.segmentations.length; ++i) {
        var seg = this.project.segmentations[i];
        if (segmentation.project.name === seg.fullName) {
          return seg;
        }
      }
      return undefined;
    };
    Side.prototype.getParentSide = function() {
      for (var i = 0; i < this.sides.length; ++i) {
        var side = this.sides[i];
        if (side === this) { continue; }
        if (side.getSegmentationEntry(this)) {
          return side;
        }
      }
      return undefined;
    };
    Side.prototype.getSegmentationSide = function() {
      for (var i = 0; i < this.sides.length; ++i) {
        var side = this.sides[i];
        if (side === this) { continue; }
        if (side.isSegmentationOf(this)) {
          return side;
        }
      }
      return undefined;
    };

    // Called when Side.project is loaded.
    Side.prototype.onProjectLoaded = function() {
      this.cleanState();
      this.loadScalars();
      this.updateViewData();
      if (!this.project.vertexSet) {
        this.state.graphMode = undefined;
      }
    };

    // Removes entries from state which depend on nonexistent attributes
    Side.prototype.cleanState = function() {
      if (!this.loaded()) { return; }
      var vTitles = this.filterableVertexAttributes().map(function(a) { return a.title; });
      var eTitles = this.project.edgeAttributes.map(function(a) { return a.title; });
      for (var attr in this.state.filters.edge) {
        if (eTitles.indexOf(attr) === -1) {
          delete this.state.filters.edge[attr];
        }
      }
      for (attr in this.state.filters.vertex) {
        if (vTitles.indexOf(attr) === -1) {
          delete this.state.filters.vertex[attr];
        }
      }
      for (attr in this.state.axisOptions.vertex) {
        if (vTitles.indexOf(attr) === -1) {
          delete this.state.axisOptions.vertex[attr];
        }
      }
      for (attr in this.state.axisOptions.edge) {
        if (eTitles.indexOf(attr) === -1) {
          delete this.state.axisOptions.edge[attr];
        }
      }
      var allTitles = eTitles.concat(vTitles);
      for (attr in this.state.attributeTitles) {
        if (allTitles.indexOf(this.state.attributeTitles[attr]) === -1) {
          delete this.state.attributeTitles[attr];
        }
      }
    };

    return { Side: Side, defaultSideState: defaultSideState };
  });
