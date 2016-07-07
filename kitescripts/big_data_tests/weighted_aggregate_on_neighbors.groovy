// Tests the "Weighted aggregate on neighbors" operation

/// REQUIRE_SCRIPT random_attributes.groovy

project = lynx.loadProject('random_attributes')

project.weightedAggregateOnNeighbors(
  'prefix': '',
  'weight': 'rnd_std_uniform',
  'direction': 'all edges' ,
  'aggregate-rnd_std_normal': 'weighted_average'
)

project.computeUncomputed()
