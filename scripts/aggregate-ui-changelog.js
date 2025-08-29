// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Uses conventional-changelog-cli to get notes for exactly oldTag → newTag.

import {execSync} from 'node:child_process';
import {mkdtempSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

// Bundled UI ZIPs live here (inside the Pano module)
const UI_DIR = 'Pano/src/main/resources/UIFiles';

// UI component → repo mapping
const OWNER = 'PanoMC';
const REPOS = {
    'panel-ui': 'panel-ui',
    'setup-ui': 'setup-ui',
    'vanilla-theme': 'vanilla-theme',
};

// Prefer bunx if present, otherwise npx
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
    throw new Error('Neither bunx nor npx found in PATH.');
}
const NPX_CMD = detectRunner();

/** Parse "panel-ui-v1.0.0-dev.34.zip" or "setup-ui-1.2.3.zip" → { comp, version(with leading v) } */
function parseZip(name) {
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)\.zip$/i);
    if (!m) return null;
    return {comp: m[1], version: m[2].startsWith('v') ? m[2] : `v${m[2]}`};
}

/** Read current UI versions from working tree. */
function listCurrentVersions() {
    const files = readdirSync(UI_DIR, {withFileTypes: true})
        .filter(d => d.isFile() && d.name.endsWith('.zip'))
        .map(d => d.name);
    const map = {};
    for (const f of files) {
        const p = parseZip(f);
        if (p && REPOS[p.comp]) map[p.comp] = p.version;
    }
    return map;
}

/** Get the previous Pano tag. */
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

/** Read previous UI ZIP versions from prevTag’s tree (no checkout). */
function listPreviousVersionsFromTag(prevTag) {
    if (!prevTag) return {};
    let out = '';
    try {
        out = execSync(`git ls-tree -r --name-only ${prevTag} ${UI_DIR}`, {encoding: 'utf8'});
    } catch {
        return {};
    }
    const files = out.split('\n').filter(Boolean).map(p => p.split('/').pop());
    const map = {};
    for (const f of files) {
        const p = parseZip(f);
        if (p && REPOS[p.comp]) map[p.comp] = p.version;
    }
    return map;
}

/** Remove version headings *and* date-only lines like "(2025-08-28)" (with or without hashes). */
function stripVersionHeadings(md) {
    const lines = md.split('\n');
    const out = [];
    for (let raw of lines) {
        // unwrap links in headings: "## [1.2.3](...) (YYYY-MM-DD)"
        let s = raw.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
        // old anchor pattern: <a name="..."></a>
        s = s.replace(/^<a name="[^"]+"><\/a>\s*/, '');
        // for matching, drop markdown hashes
        const t = s.replace(/^#{1,6}\s*/, '').trim();

        const isVersionHeading =
            /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\s*\(\d{4}-\d{2}-\d{2}\))?$/.test(t);

        // handle date-only lines like "(2025-08-28)" or "2025-08-28", with optional hashes/spaces
        const isDateOnly =
            /^#{0,6}\s*\(?\d{4}-\d{2}-\d{2}\)?\s*$/.test(s);

        if (isVersionHeading || isDateOnly) continue; // drop
        out.push(raw);
    }
    // normalize outer/triple blanks
    return out.join('\n').replace(/^\s+|\s+$/g, '').replace(/\n{3,}/g, '\n\n').trim();
}

/**
 * Produce a single conventional-changelog block for oldTag → newTag.
 * - Shallow+blobless clone to speed up
 * - preset=conventionalcommits, release-count=1 (only that range)
 * - Strip version headings and date-only lines
 */
function changelogForRange(repo, oldTag, newTag) {
    const tmp = mkdtempSync(join(tmpdir(), `cc-${repo}-`));
    try {
        const url = `https://github.com/${OWNER}/${repo}.git`;
        execSync(`git -c protocol.version=2 clone --filter=blob:none --no-checkout --quiet ${url} "${tmp}"`, {stdio: 'inherit'});
        execSync(`git -C "${tmp}" fetch --quiet --tags --force --prune`, {stdio: 'inherit'});

        const cmd = `${NPX_CMD} conventional-changelog-cli -p conventionalcommits -r 1 --from "${oldTag}" --to "${newTag}"`;
        const raw = execSync(cmd, {cwd: tmp, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit']});

        const cleaned = stripVersionHeadings(raw);
        return cleaned;
    } finally {
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

        sections.push([
            `### ${comp}: ${oldV} → ${nowV}`,
            '',
            block,
            '' // trailing blank for readability
        ].join('\n'));
    }

    if (!sections.length) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Start with two blank lines; two blank lines between sections
    const output = `\n\n${sections.join('\n\n')}\n`;
    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
