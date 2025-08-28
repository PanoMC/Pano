// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Requires GITHUB_TOKEN with read access to the UI repos.

import {execSync} from 'node:child_process';
import {readdirSync, writeFileSync} from 'node:fs';

// Look for UI ZIPs inside the Pano module
const UI_DIR = 'Pano/src/main/resources/UIFiles';

// Mapping of UI components to their repos
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

/** Parse ZIP name like: "panel-ui-v1.0.0-dev.34.zip" */
function parseZip(name) {
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[a-z0-9.]+)?)\.zip$/i);
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

/** Get previous release tag in this repo. */
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

/** Read previous UI ZIP versions from tree at prevTag. */
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

/** Minimal GitHub API helper. */
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

/** Normalize text for deduping: lower-case, strip links/hashes/punct/extra spaces. */
function normalize(s) {
    return s
        // collapse markdown links: [text](url) -> text
        .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
        // drop any (...) that contains a 7+ hex hash
        .replace(/\((?=[^)]*[0-9a-fA-F]{7,})[^)]*\)/g, '')
        // remove leading bullets if any
        .replace(/^[-*]\s+/, '')
        // trim punctuation at ends
        .replace(/^[\s.:;,-]+|[\s.:;,-]+$/g, '')
        // collapse whitespace
        .replace(/\s+/g, ' ')
        .trim()
        .toLowerCase();
}

/** Extract subject from a conventional header ("type(scope)?: subject"). */
function extractConventionalSubject(line) {
    const m = line.match(/^(feat|fix|perf|refactor|docs|chore|build|ci)(\([^)]+\))?:\s*(.+)$/i);
    return m ? m[3].trim() : line.trim();
}

/** Remove version heading lines like "1.0.0-dev.183 (2025-08-28)" (with or without leading ### or links). */
function stripVersionHeadings(body) {
    const lines = body.split('\n');
    const cleaned = [];
    for (let raw of lines) {
        let line = raw.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').trim(); // unwrap links
        line = line.replace(/^#{1,6}\s*/, ''); // drop leading markdown hashes
        const isVersionHeading = /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\s*\(\d{4}-\d{2}-\d{2}\))?$/.test(line);
        if (isVersionHeading) continue;
        cleaned.push(raw); // keep original spacing
    }
    // trim leading/trailing blank lines
    return cleaned.join('\n').replace(/^\s+|\s+$/g, '').trim();
}

/**
 * Collect notes between fromTag…toTag for a given repo:
 * - Gather conventional commit headers via compare API
 * - Pull target tag’s release body (if present) and strip version headings
 * - Dedupe: if body already mentions a commit subject, drop that commit line
 */
async function collectNotesForRange(repo, fromTag, toTag) {
    const commitLines = [];
    const commitSubjects = [];

    // 1) Commit headers from compare API
    try {
        const cmp = await ghJson(
            `/repos/${OWNER}/${repo}/compare/${encodeURIComponent(fromTag)}...${encodeURIComponent(toTag)}`
        );
        const commits = (cmp.commits || []).map((c) => c.commit?.message).filter(Boolean);
        for (const msg of commits) {
            const first = msg.split('\n')[0].trim();
            if (/^(feat|fix|perf|refactor|docs|chore|build|ci)(\(.+\))?:/i.test(first)) {
                commitLines.push(`- ${first}`);
                commitSubjects.push(normalize(extractConventionalSubject(first)));
            }
        }
    } catch {
        // ignore
    }

    // 2) Target release body if available
    let releaseBody = '';
    try {
        const releases = await ghJson(`/repos/${OWNER}/${repo}/releases?per_page=100`);
        const r = releases.find((x) => x.tag_name === toTag && x.body && x.body.trim());
        if (r) releaseBody = stripVersionHeadings(r.body.trim());
    } catch {
        // ignore
    }

    // 3) Dedupe commit lines if body already contains the same subjects (even without bullets)
    if (releaseBody) {
        const bodyNorm = normalize(releaseBody);
        const filtered = commitLines.filter((line) => {
            const subj = normalize(extractConventionalSubject(line.replace(/^-+\s*/, '')));
            return !bodyNorm.includes(subj);
        });

        const merged = [];
        if (filtered.length) merged.push(...filtered);
        if (releaseBody) {
            if (merged.length) merged.push(''); // blank line before body
            merged.push(releaseBody);
        }
        return merged.length ? merged.join('\n') : null;
    }

    // If no body, return commits
    return commitLines.length ? commitLines.join('\n') : null;
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
        const notes = await collectNotesForRange(repo, oldV, nowV);
        if (!notes) continue;

        // Section with heading, blank line, notes, and generous spacing between sections
        sections.push(
            [
                `### ${comp}: ${oldV} → ${nowV}`,
                '',
                notes,
                '',
            ].join('\n')
        );
    }

    if (sections.length === 0) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Two blank lines before and between sections so it never sticks to the main changelog
    const output = `\n\n${sections.join('\n\n')}\n`;
    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
