// node >=18 (veya Bun); GITHUB_TOKEN gerekir
// ZIP adı kalıbı: <comp>-v1.0.0-dev.34.zip (v opsiyonel)
// src/main/resources/UIFiles altında aranır
import {execSync} from 'node:child_process';
import {readdirSync, writeFileSync} from 'node:fs';

const UI_DIR = 'Pano/src/main/resources/UIFiles';
const OWNER = 'PanoMC'; // org/user
const REPOS = {
    'panel-ui': 'panel-ui',
    'setup-ui': 'setup-ui',
    'vanilla-theme': 'vanilla-theme',
};

const token = process.env.GITHUB_TOKEN;
if (!token) {
    console.error('GITHUB_TOKEN yok.');
    process.exit(1);
}

function parseZip(name) {
    // panel-ui-v1.0.0-dev.34.zip  |  setup-ui-1.2.3.zip
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[a-z0-9.]+)?)\.zip$/i);
    if (!m) return null;
    return {comp: m[1], version: m[2].startsWith('v') ? m[2] : `v${m[2]}`};
}

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
        throw new Error(`GitHub API ${res.status}: ${txt}`);
    }
    return await res.json();
}

async function collectNotesForRange(repo, fromTag, toTag) {
    const lines = [];
    // 1) Tag compare ile aradaki conventional commit başlıkları
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
        /* ignore */
    }
    // 2) toTag için varsa GitHub Release body’si
    try {
        const releases = await ghJson(`/repos/${OWNER}/${repo}/releases?per_page=100`);
        for (const r of releases) {
            if (r.tag_name === toTag && r.body && r.body.trim()) {
                lines.push(r.body.trim());
            }
        }
    } catch {
        /* ignore */
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

        sections.push(`### ${comp} ${oldV} → ${nowV}\n${notes}`);
    }

    if (sections.length === 0) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }
    writeFileSync('UI_CHANGELOG.md', `## Bundled UI Updates\n\n${sections.join('\n\n')}\n`);
    console.log('UI_CHANGELOG.md written.');
})();
