# User guide: what is done and the plan for the larger version

The user guide is a small Jekyll site in [`guide/`](guide/) (home, command line, desktop app, IntelliJ plugin), published to GitHub Pages by [`.github/workflows/pages.yml`](../.github/workflows/pages.yml) when `docs/guide/**` changes on `master`. It is plain Markdown with step lists and no screenshots. This page plans the larger setting: screenshots that stay current, a build module, and links from the interfaces. Tasks T28 to T30 in [PLAN.md](PLAN.md) track it.

## Today

- Sources: `docs/guide/*.md`, theme `minima`, configured in `docs/guide/_config.yml`. No toolchain beyond the `actions/jekyll-build-pages` step.
- Publishing needs a one-time setting by a repository admin: *Settings > Pages > Build and deployment > Source: GitHub Actions*. The site is then at `https://dan323.github.io/http-client/`.
- The guides are tutorials ("how do I run my first request"). The reference stays in [features.md](features.md); the guides link to it instead of copying it.
- Pull requests that change only docs skip the build (see `ci.yml`), so a broken guide page is only seen when the Pages workflow runs on `master`. T28 adds a build check on pull requests.

## Plan

### T28: build the site on pull requests and check it

- Run `jekyll build` (the same action as the deploy, without the deploy job) when `docs/guide/**` changes in a pull request, and fail on a broken build.
- Check links: internal links and the GitHub links into `docs/` and `samples/`.
- Keep the check outside the required `test`/`plugin`/`quality` checks, or add a path filter so the docs-only skip in `ci.yml` is not defeated.

### T29: screenshots

Principle: a screenshot is either generated from the code at every build, or it is dated and has a checklist. Never an undocumented hand-made image.

| Interface | How | Why |
|---|---|---|
| Desktop app | Generated. A test in `desktop` renders the real composables (`App`, `ResponsePane`) headless to a PNG using the sample files and a fixed set of outcomes, as `AppUiTest` already does without capturing. A Gradle task `:user-guide:desktopScreenshots` copies the PNGs into the site. | The UI changes often (polish, env editor F17, history F13). Generated images cannot go stale. |
| Command line | Not an image. Real output captured by running `httpc` against the mock server (`samples/`) and pasted as code blocks by a Gradle task. | Text is searchable, copyable and exact. |
| IntelliJ plugin | Manual. A checklist file lists each shot (id, what to open, IDE version, theme, size); images live in `docs/guide/assets/plugin/` and each page states the IDE version they were taken with. | The plugin cannot run headless; this is the one manual part. |

Decisions for T29:

- Light theme only, fixed window size, one font, so regenerated images do not differ for no reason.
- PNG files are committed (GitHub Pages builds from the repository), so a regeneration shows up as a diff in the pull request. A CI step regenerates and fails if the committed files differ, but only for the desktop images.
- Keep each image under about 150 KB.

### T30: a `user-guide` Gradle subproject

Only worth adding together with T29. A build-only module like `quality-reports` (not shipped, excluded from coverage and Sonar):

- `:user-guide:desktopScreenshots` and `:user-guide:cliTranscripts` generate the assets above.
- `:user-guide:site` assembles `docs/guide` plus the generated assets into one folder, which both the pull-request check (T28) and the Pages deploy use, so what CI builds is what is published.
- Keep the Jekyll build for the final step. Moving to a different generator (MkDocs Material, Docusaurus) would add Python or Node to a repository that needs only the JDK today; reconsider only for search or versioned docs.

### Links from the interfaces (T31)

When the guide is stable:

- Desktop: a *Help* button (or menu entry) that opens the guide page for the desktop app in the system browser.
- Plugin: a *Help* link on the tool window and on the environment header, using the platform's browse action.
- CLI: one line in `httpc --help` and in the usage printed on a bad argument.
- Each link goes to the page of that interface, as a constant in one place per front end, so a changed address is a one-line change. Check that the link works before each release.

### Versions

The site documents the latest release. Each page names the version it was written for where behavior changed. If old versions need their own docs, add versioned folders then (MkDocs `mike` is the usual tool and would be the reason to move generators).

## Open decisions

- Name of the Pages address (project site on `github.io/http-client` as configured, or a custom domain).
- Whether the guide should also be shipped inside the installers for offline use.
- Which screenshots the first generated set contains (suggestion: file open with the request list, a response with a failed and a passed assertion, the environment picker open).
