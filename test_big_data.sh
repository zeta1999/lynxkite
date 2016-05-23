#!/bin/bash

# This is the script that Jenkins calls when the user wants to run 
# a "big data" test on a Pull Request. It fires up an EMR cluster,
# runs groovy scripts in it and creates a file with the results.
# In case of Jenkins, it also dumps the test results as a commit
# into the PR.
#
# Usage:
#   test_big_data.sh test_or_list test_data_set [# of emr instances]
#
# Default arguments:
#   test_big_data.sh default.list fake_westeros_v3_25m_799m 3
#
# Examples:
#   test_big_data.sh visualization.groovy               # Single test.
#   test_big_data.sh all.list                           # All tests (hopefully).
#   test_big_data.sh all.list fake_westeros_v3_25m_799m # Big graphs.
#   test_big_data.sh all.list fake_westeros_v3_100k_2m  # Small graphs.
#
#   test_big_data.sh default.list fake_westeros_v3_100k_2m
#   CLUSTER_NAME=$USER-xyz test_big_data.sh default.list fake_westeros_v3_100k_2m
#
# Location of tests: kitescripts/big_data_tests
# Location of data sets: s3://lynxkite-test-data/
# Current data sets:
#   fake_westeros_v3_100k_2m    # 100k vertices, 2m edges (small)
#   fake_westeros_v3_5m_145m    # 5m vertices, 145m edges (default)
#   fake_westeros_v3_10m_303m   # 10m vertices, 303m edges (large)
#   fake_westeros_v3_25m_799m   # 25m vertices 799m edges (xlarge)
#   twitter
#   twitter_sampled_10p
# These were generated by:
#   kitescripts/gen_test_data/generate_fake_westeros.groovy


set -xueo pipefail
trap "echo $0 has failed" ERR

TEST_SELECTOR="${1:-load_edges_from_test_set.groovy}"
DATA_SET="${2:-fake_westeros_v3_100k_2m}"
NUM_EMR_INSTANCES=${3:-3}

RESULTS_DIR="$(dirname $0)/kitescripts/big_data_tests/results/emr${NUM_EMR_INSTANCES}_${DATA_SET}"
TMP_RESULTS_DIR="${RESULTS_DIR}.new"
rm -Rf ${TMP_RESULTS_DIR}
cp -a ${RESULTS_DIR} ${TMP_RESULTS_DIR}

# Run test.
NUM_INSTANCES=${NUM_EMR_INSTANCES} \
EMR_RESULTS_DIR=${TMP_RESULTS_DIR} \
  $(dirname $0)/tools/emr_based_test.sh backend \
    --remote_test_dir=/home/hadoop/biggraphstage/kitescripts/big_data_tests \
    --local_test_dir=$(dirname $0)/kitescripts/big_data_tests \
    --test_selector="${TEST_SELECTOR}" \
    --lynxkite_arg="testDataSet:${DATA_SET}"

finalize_results() {
  mkdir -p ${RESULTS_DIR}
  cp ${TMP_RESULTS_DIR}/* ${RESULTS_DIR}
  rm -Rf ${TMP_RESULTS_DIR}
}

if [[ "$USER" == 'jenkins' ]]; then
  # Commit and push changed output on PR branch.
  git config user.name 'lynx-jenkins'
  git config user.email 'pizza-support@lynxanalytics.com'
  git config push.default simple
  export GIT_SSH_COMMAND='ssh -i ~/.ssh/lynx-jenkins'
  git fetch
  git checkout "$GIT_BRANCH"
  git reset --hard "origin/$GIT_BRANCH"  # Discard potential local changes from failed runs.
  finalize_results
  git add ${RESULTS_DIR}
  git commit -am "Update Big Data Test results."
  git push
else
  finalize_results
fi
