# Can be set from the command line. E.g.:
#   make ecosystem-release VERSION=2.0.0
export VERSION=snapshot
export BDT=normal  # Big data test test set size.

find = git ls-files --others --exclude-standard --cached
pip = .build/pip3-packages-installed

.SUFFIXES: # Disable built-in rules.
.PHONY: all
all: backend

.build/gulp-done: $(shell $(find) web/app) web/gulpfile.js web/package.json
	cd web && yarn && gulp && cd - && touch $@
.build/documentation-verified: $(shell $(find) app) .build/gulp-done
	./tools/check_documentation.sh && touch $@
$(pip): python_requirements.txt
	pip3 install --user -r python_requirements.txt && touch $@
.build/backend-done: $(shell $(find) app project tools lib conf) build.sbt .build/gulp-done
	sbt stage && touch $@
.build/backend-test-passed: $(shell $(find) app test project conf) build.sbt
	./.test_backend.sh && touch $@
.build/frontend-test-passed: \
		$(shell $(find) web/test) build.sbt .build/backend-done \
		.build/documentation-verified .build/gulp-done
	./.test_frontend.sh && touch $@
.build/chronomaster-test-passed: $(shell $(find) chronomaster) $(pip)
	chronomaster/test.sh && touch $@
.build/remote_api-python-test-passed: $(shell $(find) remote_api/python) .build/backend-done $(pip)
	tools/with_lk.sh remote_api/python/test.sh && touch $@
	ecosystem/native/tools/build-monitoring.sh && ecosystem/native/bundle.sh && touch $@
.build/documentation-done-${VERSION}: $(shell $(find) ecosystem/documentation remote_api/python)
	ecosystem/documentation/build.sh native && touch $@
.build/ecosystem-done: \
		$(shell $(find) ecosystem/native remote_api chronomaster ecosystem/release/lynx/luigi_tasks) \
		.build/backend-done .build/documentation-done-${VERSION}
	ecosystem/native/tools/build-monitoring.sh && ecosystem/native/bundle.sh && touch $@

# Short aliases for command-line use.
.PHONY: backend
backend: .build/backend-done
.PHONY: frontend
frontend: .build/gulp-done
.PHONY: ecosystem
ecosystem: .build/ecosystem-done
.PHONY: backend-test
backend-test: .build/backend-test-passed
.PHONY: frontend-test
frontend-test: .build/frontend-test-passed
.PHONY: chronomaster-test
chronomaster-test: .build/chronomaster-test-passed
.PHONY: remote_api-test
remote_api-test: .build/remote_api-python-test-passed
.PHONY: ecosystem-test
ecosystem-test: chronomaster-test remote_api-test
.PHONY: test
test: backend-test frontend-test ecosystem-test
.PHONY: big-data-test
big-data-test: .build/ecosystem-done
	./test_ecosystem.py \
		--lynx_release_dir ecosystem/native/dist \
		--bigdata \
		--bigdata_test_set ${BDT}
