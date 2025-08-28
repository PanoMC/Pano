// scripts/aggregate-ui-changelog.js
// Node 18+ (or Bun). Requires GITHUB_TOKEN.

import {execSync} from 'node:child_process';
import {readdirSync, writeFileSync} from 'node:fs';

const UI_DIR = 'Pano/src/main/resources/UIFiles';
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

function parseZip(name) {
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[a-z0-9.]+)?)\.zip$/i);
    if (!m) return null;
    return {comp: m[1], version: m[2].startsWith('v') ? m[2] : `v${m[2]}`};
}

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

function listPreviousVersionsFromTag(prevTag) {
    if (!prevTag) return {};
    let listing = '';
    try {
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

// Normalize UI repo release body into clean bullet lines
function normalizeReleaseBody(body) {
    const out = [];
    let inCode = false;
    for (const raw of body.split('\n')) {
        const line = raw.replace(/\s+$/, ''); // rtrim
        const t = line.trim();

        // toggle code blocks
        if (t.startsWith('```')) {
            inCode = !inCode;
            continue;
        }
        if (inCode) continue;

        // skip empty
        if (!t) continue;

        // drop version/date headings & section headers
        if (
            /^#{1,6}\s*(v?\d+\.\d+\.\d+(?:-[^\s)]+)?)(\s*\(\d{4}-\d{2}-\d{2}\))?/i.test(t) ||
            /^(v?\d+\.\d+\.\d+(?:-[^\s)]+)?)(\s*\(\d{4}-\d{2}-\d{2}\))?$/.test(t) ||
            /^#{1,6}\s*(features|bug fixes|fixes|performance improvements|reverts|chores|chore|ci|build)\s*$/i.test(t) ||
            /^(features|bug fixes|fixes|performance improvements|reverts|chores|chore|ci|build)\s*$/i.test(t) ||
            /^-{3,}$/.test(t)
        ) continue;

        // normalize list items / commit-like lines
        let item = t.replace(/^[-*]\s+/, '');      // strip leading list marker
        item = item.replace(/^\d+\.\s+/, '');      // strip ordered list
        item = item.replace(/^\s+/, '');

        // if it looks like a conventional header or a plain message, bullet it
        if (!/^[-*]\s+/.test(t)) {
            if (/^(feat|fix|perf|refactor|docs|chore|build|ci)(\(.+\))?:/i.test(item)) {
                out.push(`- ${item}`);
            } else {
                out.push(`- ${item}`);
            }
        } else {
            out.push(`- ${item}`);
        }
    }

    // dedupe (case-insensitive)
    const seen = new Set();
    const uniq = [];
    for (const l of out) {
        const k = l.toLowerCase();
        if (!seen.has(k)) {
            seen.add(k);
            uniq.push(l);
        }
    }
    return uniq;
}

async function collectNotesForRange(repo, fromTag, toTag) {
    const set = new Set(); // for dedupe
    const lines = [];

    // 1) Conventional commit headers via compare API
    try {
        const cmp = await ghJson(
            `/repos/${OWNER}/${repo}/compare/${encodeURIComponent(fromTag)}...${encodeURIComponent(toTag)}`
        );
        const commits = (cmp.commits || []).map((c) => c.commit?.message).filter(Boolean);
        for (const msg of commits) {
            const first = msg.split('\n')[0].trim();
            if (/^(feat|fix|perf|refactor|docs|chore|build|ci)(\(.+\))?:/i.test(first)) {
                const line = `- ${first}`;
                const key = line.toLowerCase();
                if (!set.has(key)) {
                    set.add(key);
                    lines.push(line);
                }
            }
        }
    } catch {
        // ignore
    }

    // 2) Append normalized release body (toTag), if any
    try {
        const releases = await ghJson(`/repos/${OWNER}/${repo}/releases?per_page=100`);
        for (const r of releases) {
            if (r.tag_name === toTag && r.body && r.body.trim()) {
                const norm = normalizeReleaseBody(r.body);
                for (const l of norm) {
                    const key = l.toLowerCase();
                    if (!set.has(key)) {
                        set.add(key);
                        lines.push(l);
                    }
                }
            }
        }
    } catch {
        // ignore
    }

    return lines.length ? lines.join('\n') : null;
}

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

        sections.push(
            [
                `### ${comp}: ${oldV} → ${nowV}`,
                '',
                notes
            ].join('\n')
        );
    }

    if (sections.length === 0) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Visible spacing in GitHub release body
    const SPACER = '\n<br/>\n<br/>\n';
    const output = `${SPACER}${sections.join(SPACER)}\n`;

    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
