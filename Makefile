.PHONY: build
build:
	./gradlew build

.PHONY: test
test:
	./gradlew test

.PHONY: verify
verify:
	# Plugin Verifier compatibility check.  Catches binary-incompat issues
	# (removed/changed IntelliJ Platform APIs) against the IDE matrix the
	# IntelliJ Platform Gradle Plugin's `pluginVerification.ides {}` block
	# resolves -- currently the `recommended()` set for since-build 261.
	./gradlew verifyPlugin

.PHONY: run-ide
run-ide: x11-grant
	# Boots a sandboxed PhpStorm with this plugin loaded.  Used for the
	# manual smoke tests: open a .xphp file, confirm diagnostics / hover /
	# go-to-definition / completion.  Sandbox is fully isolated from any
	# PhpStorm install on the host (separate config/system dirs, separate
	# JVM process); safe to run alongside your daily IDE.
	./gradlew runIde

# Allow the jdk container's GUI clients to reach the host's X server so
# the sandbox PhpStorm window from `run-ide` actually displays.
# `xhost +local:docker` is idempotent (re-adding an existing rule is a
# no-op), so we can call it before every `run-ide` without leaking ACL
# entries.  Pre-req only for `run-ide`; headless targets don't need it.
# Guarded with `command -v` so contributors on macOS / Windows / WSL
# (where xhost may be absent or unnecessary) don't hit a confusing
# error before `runIde` even gets a chance.  Linux+X11 is the
# documented build host; this just degrades gracefully off it.
.PHONY: x11-grant
x11-grant:
	@command -v xhost >/dev/null 2>&1 && xhost +local:docker || \
	  echo "(skipping xhost: not on X11 or xhost not installed)"

.PHONY: dist
dist:
	# Produce the installable plugin zip at build/distributions/.  Use this
	# when you want to install into your *existing* PhpStorm (not the
	# sandbox `run-ide` boots): once the zip exists, in your running IDE go
	# to Settings -> Plugins -> gear icon -> "Install Plugin from Disk..."
	# and pick the file.  Requires PhpStorm 2026.1 or newer (since-build=261);
	# older builds reject the install with an "incompatible build number"
	# error.  Restart of PhpStorm required after install.
	./gradlew buildPlugin
	@echo
	@echo "Installable plugin distribution:"
	@ls -la build/distributions/ 2>/dev/null || \
	  echo "  (build/distributions/ not created; check gradle output above)"

.PHONY: clean
clean:
	gradlew clean
