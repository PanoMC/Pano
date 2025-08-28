// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Requires GITHUB_TOKEN with read access to the UI repos.

import {execSync} from 'node:child_process';
import {readdirSync, writeFileSync} from 'node:fs';

// Search UI ZIPs inside the Pano module
const UI_DIR = 'Pano/src/main/resources/UIFiles';

// Organization / repository mapping
const OWNER = 'PanoMC';
const REPOS = {
    'panel-ui': 'panel-ui',
    'setup-ui': 'setup-ui',
    'vanilla-theme': 'vanilla-theme',
};

const token = process.env.GITHUB_TOKEN;
if (!token) {
    console.error('GITHUB_TOKEN is required.');
    process.exit(1);
}

/**
 * Parse ZIP file name: "<component>-v1.0.0-dev.34.zip" (the "v" is optional)
 * Returns: { comp: string, version: string with leading "v" }
 */
function parseZip(name) {
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[a-z0-9.]+)?)\.zip$/i);
    if (!m) return null;
    return {comp: m[1], version: m[2].startsWith('v') ? m[2] : `v${m[2]}`};
}

/**
 * Read current UI ZIP versions from working tree.
 */
function listCurrentVersions() {
    const entries = readdirSync(UI_DIR, {withFileTypes: true})
        .filter((d) => d.isFile() && d.name.endsWith('.zip'))
        .map((d) => d.name);

    const map = {};
    for (const f of entries) {
        const p = parseZip(f);
        if (p && REPOS[p.comp]) map[p.comp] = p.version;
    }
    return map;
}

/**
 * Get the previous tag (semantic-release last tag).
 */
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

/**
 * Read UI ZIP versions from the previous tag’s tree without checking out.
 */
function listPreviousVersionsFromTag(prevTag) {
    if (!prevTag) return {};
    let listing = '';
    try {
        // List files at UI_DIR for the previous tag
        listing = execSync(`git ls-tree -r --name-only ${prevTag} ${UI_DIR}`, {encoding: 'utf8'});
    } catch {
        return {};
    }

    const files = listing.split('\n').filter(Boolean).map((p) => p.split('/').pop());
    const map = {};
    for (const f of files) {
        const p = parseZip(f);
        if (p && REPOS[p.comp]) map[p.comp] = p.version;
    }
    return map;
}

/**
 * Minimal GitHub API helper.
 */
async function ghJson(path, params = {}) {
    const url = `https://api.github.com${path}`;
    const res = await fetch(url, {
        headers: {
            Accept: 'application/vnd.github+json',
            Authorization: `Bearer ${token}`,
            'X-GitHub-Api-Version': '2022-11-28',
        },
        ...params,
    });
    if (!res.ok) {
        const txt = await res.text();
        throw new Error(`GitHub API ${res.status} for ${path}: ${txt}`);
    }
    return await res.json();
}

/**
 * Collect notes between fromTag…toTag for a given repo:
 * 1) Compare API conventional first-line commit headers
 * 2) Append target tag’s GitHub Release body if present
 */
async function collectNotesForRange(repo, fromTag, toTag) {
    const lines = [];

    // 1) Conventional commit headers from compare API
    try {
        const cmp = await ghJson(
            `/repos/${OWNER}/${repo}/compare/${encodeURIComponent(fromTag)}...${encodeURIComponent(toTag)}`
        );
        const commits = (cmp.commits || []).map((c) => c.commit?.message).filter(Boolean);
        for (const msg of commits) {
            const first = msg.split('\n')[0].trim();
            if (/^(feat|fix|perf|refactor|docs|chore|build|ci)(\(.+\))?:/i.test(first)) {
                lines.push(`- ${first}`);
            }
        }
    } catch {
        // Ignore; not all tags/compare ranges may exist
    }

    // 2) Pull the release body for the target tag if it exists
    try {
        const releases = await ghJson(`/repos/${OWNER}/${repo}/releases?per_page=100`);
        for (const r of releases) {
            if (r.tag_name === toTag && r.body && r.body.trim()) {
                // Add a blank line before release body to visually separate
                if (lines.length) lines.push('');
                lines.push(r.body.trim());
            }
        }
    } catch {
        // Ignore
    }

    return lines.length ? lines.join('\n') : null;
}

/**
 * Main
 */
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
        const notes = await collectNotesForRange(repo, oldV, nowV);
        if (!notes) continue;

        // Section with generous spacing around, no global heading
        sections.push(
            [
                `### ${comp}: ${oldV} → ${nowV}`,
                '', // blank line after section title
                notes,
                '', // trailing blank line to create paragraph spacing
            ].join('\n')
        );
    }

    if (sections.length === 0) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Start with two blank lines so it won't stick to the main release notes above
    const output = `\n\n${sections.join('\n')}\n`;
    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
