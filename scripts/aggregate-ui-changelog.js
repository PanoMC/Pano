// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Produces a single changelog block per component strictly for oldTag → newTag.
// No per-version headings, only grouped conventional commits from the exact compare range.

import {execSync} from 'node:child_process';
import {readdirSync, writeFileSync} from 'node:fs';

// Where the bundled UI ZIPs live inside your repo
const UI_DIR = 'Pano/src/main/resources/UIFiles';

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

/** Parse "panel-ui-v1.0.0-dev.34.zip" or "setup-ui-1.2.3.zip" → { comp, version: 'v1.2.3' } */
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

/** Read previous UI ZIP versions from the tree at prevTag (no checkout). */
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
