// Node 18+ (veya Bun). conventional-changelog-cli kullanılacak.
// Bu script yalnızca oldTag → newTag aralığını TEK BLOK olarak üretir.

import {execSync} from 'node:child_process';
import {mkdtempSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

// UI ZIP'leri Pano modülü altında
const UI_DIR = 'Pano/src/main/resources/UIFiles';

// UI bileşeni → repo eşlemesi
const OWNER = 'PanoMC';
const REPOS = {
    'panel-ui': 'panel-ui',
    'setup-ui': 'setup-ui',
    'vanilla-theme': 'vanilla-theme',
};

// Bun varsa bunx, yoksa npx
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

/** "panel-ui-v1.0.0-dev.34.zip" veya "setup-ui-1.2.3.zip" → {comp, version (v ile)} */
function parseZip(name) {
    const m = name.match(/^([a-z0-9-]+)-((?:v)?\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)\.zip$/i);
    if (!m) return null;
    return {comp: m[1], version: m[2].startsWith('v') ? m[2] : `v${m[2]}`};
}

/** Çalışan ağacından güncel UI sürümlerini oku. */
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

/** Bu repo'daki önceki Pano tag'i. */
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

/** prevTag ağacındaki UI ZIP sürümlerini checkout yapmadan oku. */
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

/** Sürüm başlıklarını (## 1.2.3 (YYYY-MM-DD), anchor'lı vs.) temizle. */
function stripVersionHeadings(md) {
    const lines = md.split('\n');
    const out = [];
    for (let raw of lines) {
        let s = raw.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1'); // [1.2.3](url) → 1.2.3
        s = s.replace(/^<a name="[^"]+"><\/a>\s*/, '');      // eski anchor kalıpları
        const t = s.replace(/^#{1,6}\s*/, '').trim();       // heading hash'lerini kaldırıp test et
        const isVersionHeading = /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\s*\(\d{4}-\d{2}-\d{2}\))?$/.test(t);
        if (isVersionHeading) continue;
        out.push(raw);
    }
    // Kenarlardaki boş satırları toparla
    return out.join('\n').replace(/^\s+|\s+$/g, '').trim();
}

/**
 * Bir repo için oldTag → newTag aralığında TEK BLOK changelog üret.
 * - Shallow+blobless clone
 * - conventional-changelog-cli preset=conventionalcommits, release-count=1
 * - Başlık strip + boşluk düzeni
 */
function changelogForRange(repo, oldTag, newTag) {
    const tmp = mkdtempSync(join(tmpdir(), `cc-${repo}-`));
    try {
        const url = `https://github.com/${OWNER}/${repo}.git`;
        // blobless clone (dosya içeriksiz, sadece commit/refs)
        execSync(`git -c protocol.version=2 clone --filter=blob:none --no-checkout --quiet ${url} "${tmp}"`, {stdio: 'inherit'});
        execSync(`git -C "${tmp}" fetch --quiet --tags --force --prune`, {stdio: 'inherit'});

        // SADECE oldTag → newTag VE TEK BLOK: -r 1
        const cmd = `${NPX_CMD} conventional-changelog-cli -p conventionalcommits -r 1 --from "${oldTag}" --to "${newTag}"`;
        const notes = execSync(cmd, {cwd: tmp, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit']});

        // Sürüm başlığını kaldır, 3+ boş satırı 2'ye indir
        const cleaned = stripVersionHeadings(notes).replace(/\n{3,}/g, '\n\n').trim();

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
            '' // bölüm sonunda boş satır
        ].join('\n'));
    }

    if (!sections.length) {
        writeFileSync('UI_CHANGELOG.md', '');
        console.log('No UI changes found.');
        return;
    }

    // Başa 2 boş satır, bölümler arası 2 boş satır
    const output = `\n\n${sections.join('\n\n')}\n`;
    writeFileSync('UI_CHANGELOG.md', output);
    console.log('UI_CHANGELOG.md written.');
})();
