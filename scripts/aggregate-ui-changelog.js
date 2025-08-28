// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Requires GITHUB_TOKEN (just for rate limits if you later add API),
// but this script works offline by cloning the UI repos and running conventional-changelog.
// It pulls notes for an exact range: oldTag → newTag, exactly like semantic does.

import {execSync} from 'node:child_process';
import {mkdtempSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

// Look for bundled UI ZIPs inside the Pano module
const UI_DIR = 'Pano/src/main/resources/UIFiles';

// Map UI components to GitHub repos
const OWNER = 'PanoMC';
const REPOS = {
    'panel-ui': 'panel-ui',
    'setup-ui': 'setup-ui',
    'vanilla-theme': 'vanilla-theme',
};

// Prefer bunx if available, fallback to npx
function detectRunner() {
    try {
        execSync('bunx --version', {stdio: 'ignore'});
        return 'bunx';
    } catch {
    }
    try {
        execSync('npx -v', {stdio: 'ignore'});
        return 'npx -y';
    } catch {
    }
    throw new Error('Neither bunx nor npx is available in PATH.');
}

const NPX_CMD = detectRunner();

/** Parse ZIP name like: "panel-ui-v1.0.0-dev.34.zip" or "setup-ui-1.2.3.zip". */
function parseZip(name) {
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)\.zip$/i);
    if (!m) return null;
    return {comp: m[1], version: m[2].startsWith('v') ? m[2] : `v${m[2]}`};
}

/** Read current UI ZIP versions from working tree. */
function listCurrentVersions() {
    const files = readdirSync(UI_DIR, {withFileTypes: true})
        .filter((d) => d.isFile() && d.name.endsWith('.zip'))
        .map((d) => d.name);
    const map = {};
    for (const f of files) {
        const p = parseZip(f);
        if (p && REPOS[p.comp]) map[p.comp] = p.version;
    }
    return map;
}

/** Get previous tag (the last release tag in the Pano repo). */
function getPrevTag() {
    try {
        return execSync('git describe --tags --abbrev=0 HEAD^', {encoding: 'utf8'}).trim();
    } catch {
        try {
            return execSync('git describe --tags --abbrev=0', {encoding: 'utf8'}).trim();
        } catch {
            return null;
        }
    }
}

/** Read previous UI ZIP versions from tree at prevTag without checking out. */
function listPreviousVersionsFromTag(prevTag) {
    if (!prevTag) return {};
    let out = '';
    try {
        out = execSync(`git ls-tree -r --name-only ${prevTag} ${UI_DIR}`, {encoding: 'utf8'});
    } catch {
        return {};
    }
    const files = out.split('\n').filter(Boolean).map((p) => p.split('/').pop());
    const map = {};
    for (const f of files) {
        const p = parseZip(f);
        if (p && REPOS[p.comp]) map[p.comp] = p.version;
    }
    return map;
}

/** Strip version headings like "## 1.0.0 (YYYY-MM-DD)" or "1.0.0-dev.183 (YYYY-MM-DD)". */
function stripVersionHeadings(md) {
    const lines = md.split('\n');
    const out = [];
    for (let raw of lines) {
        // unwrap links in headings like "## [1.0.0](...) (YYYY-MM-DD)"
        let s = raw.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
        // drop markdown heading hashes to test
        const t = s.replace(/^#{1,6}\s*/, '').trim();
        const isVersionHeading = /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\s*\(\d{4}-\d{2}-\d{2}\))?$/.test(t);
        if (isVersionHeading) continue;
        out.push(raw);
    }
    // trim surrounding blank lines
    return out.join('\n').replace(/^\s+|\s+$/g, '').trim();
}

/**
 * Generate conventional-changelog for a repo between oldTag → newTag.
 * We shallow+blobless clone to minimize bandwidth.
 */
function changelogForRange(repo, oldTag, newTag) {
    const tmp = mkdtempSync(join(tmpdir(), `cc-${repo}-`));
    try {
        const url = `https://github.com/${OWNER}/${repo}.git`;
        // blobless clone (full commit graph, no file contents)
        execSync(`git -c protocol.version=2 clone --filter=blob:none --no-checkout --quiet ${url} "${tmp}"`, {stdio: 'inherit'});
        // Ensure tags are present (blobless)
        execSync(`git -C "${tmp}" fetch --quiet --tags --force --prune`, {stdio: 'inherit'});

        // Generate notes for exact range
        const cmd = `${NPX_CMD} conventional-changelog-cli -p conventionalcommits -r 0 --from "${oldTag}" --to "${newTag}"`;
        const notes = execSync(cmd, {cwd: tmp, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit']});

        // Clean: drop the release heading; keep sections like "### Features"
        const cleaned = stripVersionHeadings(notes);

        // Also collapse 3+ consecutive blank lines to exactly 2
        return cleaned.replace(/\n{3,}/g, '\n\n').trim();
    } finally {
        // Clean up
        try {
            rmSync(tmp, {recursive: true, force: true});
        } catch {
        }
    }
}

/** Main */
(async () => {
    const current = listCurrentVersions();
    const prevTag = getPrevTag();
    const previous = listPreviousVersionsFromTag(prevTag);

    const sections = [];
    for (const comp of Object.keys(REPOS)) {
        const nowV = current[comp];
        const oldV = previous[comp];
        if (!nowV || !oldV || nowV === oldV) continue;

        const repo = REPOS[comp];
        const block = changelogForRange(repo, oldV, nowV);
        if (!block) continue;

        // Section heading + spacing; block already has proper markdown (Features, Bug Fixes, …)
        sections.push([
            `### ${comp}: ${oldV} → ${nowV}`,
            '',
            block,
            '' // trailing blank line inside section
        ].join('\n'));
    }

    if (!sections.length) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Two blank lines before everything, and two blank lines between sections.
    const output = `\n\n${sections.join('\n\n')}\n`;
    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
