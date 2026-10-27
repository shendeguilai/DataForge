# Browser regression flows

The suite starts a local application with a temporary H2 database and temporary runtime folder, then controls a separate headless browser. It uses test-only fixtures for the OJ and AtCoder integrations, and must never point at a real OJ account or production server. No existing local application data is used.

Prerequisites: Java 17, Maven, a C++17 compiler, Node 20 or later, and Playwright 1.62.1. Install the Playwright browser locally when necessary.

```bash
mvn --batch-mode test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/dataforge-e2e-classpath.txt
npm install --prefix /tmp/dataforge-browser --no-save --ignore-scripts playwright@1.62.1
/tmp/dataforge-browser/node_modules/.bin/playwright install chromium
export DATAFORGE_E2E_CLASSPATH="$(< /tmp/dataforge-e2e-classpath.txt)"
export NODE_PATH=/tmp/dataforge-browser/node_modules
node tests/e2e/platform.cjs
```

Set `DATAFORGE_BROWSER_EXECUTABLE` to use a compatible already-installed Chromium-based browser, or `DATAFORGE_E2E_PORT` to select a free local port. The suite prints its temporary output directory for diagnosis and does not delete it. The simulated exam publishes a directory-error result; it does not pretend to test Docker grading, which is covered separately by the sandbox tests on a Linux Docker host.
