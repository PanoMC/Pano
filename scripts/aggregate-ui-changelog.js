// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Produces a single changelog block per component strictly for oldTag → newTag.
// No per-version headings, only grouped conventional commits from the exact compare range.

import {execSync} from 'node:child_process';
import {existsSync, readFileSync, writeFileSync} from 'node:fs';

// UI releases config file (single source of truth for pinned versions)
const UI_RELEASES_FILE = 'ui-releases.yml';

// Your org & repo mapping
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

/** Parse ui-releases.yml content → { component: version } */
function parseUiReleasesYml(content) {
    const map = {};
    for (const line of content.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) continue;
        const idx = trimmed.indexOf(':');
        if (idx === -1) continue;
        const key = trimmed.slice(0, idx).trim();
        const val = trimmed.slice(idx + 1).trim();
        if (REPOS[key]) map[key] = val.startsWith('v') ? val : `v${val}`;
    }
    return map;
}

/** Read current UI versions from ui-releases.yml in working tree. */
function listCurrentVersions() {
    if (!existsSync(UI_RELEASES_FILE)) {
        console.error(`${UI_RELEASES_FILE} not found.`);
        return {};
    }
    return parseUiReleasesYml(readFileSync(UI_RELEASES_FILE, 'utf8'));
}

/** Get previous release tag in this (Pano) repo. */
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

/** Read previous UI versions from prevTag. Tries ui-releases.yml first, falls back to GitHub Release body. */
function listPreviousVersionsFromTag(prevTag) {
    if (!prevTag) return {};

    // Try ui-releases.yml first (new format)
    try {
        const content = execSync(`git show ${prevTag}:${UI_RELEASES_FILE}`, {encoding: 'utf8'});
        return parseUiReleasesYml(content);
    } catch {
        // ui-releases.yml doesn't exist in this tag, fall back
    }

    // Fallback: fetch GitHub Release body and parse UI changelog entries
    // Format in release body: "### panel-ui: v1.0.0-dev.503 → v1.0.0-dev.509"
    // The right-hand version (after →) is what was bundled in that release
    try {
        const res = execSync(
            `curl -sf -H "Authorization: Bearer ${token}" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/PanoMC/pano/releases/tags/${prevTag}"`,
            {encoding: 'utf8'}
        );
        const release = JSON.parse(res);
        const body = release.body || '';
        const map = {};
        // Match: ### component-name: vOLD → vNEW
        const pattern = /^###\s+([a-z0-9-]+):\s+v\S+\s+→\s+(v\S+)/gm;
        let match;
        while ((match = pattern.exec(body)) !== null) {
            const comp = match[1];
            const toVersion = match[2];
            if (REPOS[comp]) map[comp] = toVersion;
        }
        if (Object.keys(map).length > 0) {
            console.log(`Resolved previous versions from GitHub Release body: ${JSON.stringify(map)}`);
            return map;
        }
    } catch (e) {
        console.warn(`Could not fetch GitHub Release for ${prevTag}: ${e.message}`);
    }

    return {};
}

/** Small GitHub API helper (compare range). */
async function ghJson(path) {
    const res = await fetch(`https://api.github.com${path}`, {
        headers: {
            Accept: 'application/vnd.github+json',
            Authorization: `Bearer ${token}`,
            'X-GitHub-Api-Version': '2022-11-28',
        },
    });
    if (!res.ok) {
        const txt = await res.text();
        throw new Error(`GitHub API ${res.status} for ${path}: ${txt}`);
    }
    return await res.json();
}

/** Parse conventional commit header → {type, scope, subject} or null. */
function parseConventional(line) {
    const m = line.match(/^(feat|fix|perf|refactor|docs|chore|build|ci)(?:\(([^\)]+)\))?:\s*(.+)$/i);
    if (!m) return null;
    return {type: m[1].toLowerCase(), scope: m[2] || null, subject: m[3].trim()};
}

/** Format a bullet line: "- subject (shortsha link)". */
function bulletLine({scope, subject, sha, repo}) {
    const short = sha.slice(0, 7);
    const link = `https://github.com/${OWNER}/${repo}/commit/${sha}`;
    const prefix = scope ? `${scope}: ` : '';
    return `- ${prefix}${subject} ([${short}](${link}))`;
}

/** Map types to section titles, and render groups in a stable order. */
const GROUPS = [
    {type: 'feat', title: 'Features'},
    {type: 'fix', title: 'Bug Fixes'},
    {type: 'perf', title: 'Performance'},
    {type: 'refactor', title: 'Refactoring'},
    {type: 'docs', title: 'Docs'},
    {type: 'build', title: 'Build'},
    {type: 'ci', title: 'CI'},
    {type: 'chore', title: 'Chore'},
];

/**
 * Collect ONE clean markdown block for exact range oldTag…newTag.
 * Only commits in that range (no per-release headings).
 */
async function notesForRange(repo, fromTag, toTag) {
    const cmp = await ghJson(`/repos/${OWNER}/${repo}/compare/${encodeURIComponent(fromTag)}...${encodeURIComponent(toTag)}`);

    // Group conventional commits
    /** @type {Record<string, string[]>} */
    const buckets = {};
    for (const c of cmp.commits || []) {
        const msg = (c.commit && c.commit.message || '').split('\n')[0].trim();
        const parsed = parseConventional(msg);
        if (!parsed) continue; // skip non-conventional / merge commits
        const key = parsed.type;
        (buckets[key] ||= []).push(bulletLine({scope: parsed.scope, subject: parsed.subject, sha: c.sha, repo}));
    }

    // Render grouped markdown
    const out = [];
    for (const g of GROUPS) {
        const list = buckets[g.type];
        if (!list || list.length === 0) continue;
        out.push(`#### ${g.title}`, '', list.join('\n'), ''); // blank line after each group
    }
    return out.join('\n').trim();
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
        try {
            const block = await notesForRange(repo, oldV, nowV); // STRICT old → new only
            if (!block) continue;

            sections.push(
                [
                    `### ${comp}: ${oldV} → ${nowV}`,
                    '',
                    block,
                    '', // trailing blank line inside section
                ].join('\n')
            );
        } catch (e) {
            console.error(`Failed to build notes for ${comp} (${oldV} → ${nowV}):`, e.message);
        }
    }

    if (!sections.length) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Two blank lines before and between sections so it never sticks to the main changelog
    const output = `\n\n${sections.join('\n\n')}\n`;
    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
