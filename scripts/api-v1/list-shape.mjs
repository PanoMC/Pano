// List-shape rule of the route walk (pure functions, unit-tested in list-shape.test.mjs).
//
// Decision 80 (PANO-OPEN-FRONTEND.md section 3, owner, 2026-10-09): a list always comes under `items`; `page` is present only on
// lists that are actually paged. An unpaged list answering { items: [...] } is correct. "Paged" means the endpoint declares a
// paging parameter (page, pageSize, size, limit, offset, skip, cursor, ...Page); a paged endpoint without `page` still fails,
// and so does a list under any other key.

const BAD_LIST_KEYS = ['totalPage', 'totalPages', 'totalCount', 'meta'];
const LIST_COMPANIONS = new Set(['page', 'meta', 'total', 'totalPage', 'totalPages', 'totalCount', 'totalItems', 'count', 'pages', 'pageCount', 'hasMore', 'nextCursor', 'cursor', 'size']);
const PAGING_PARAM = /^(page|pageSize|pageNumber|perPage|per_page|size|limit|offset|skip|cursor)$|Page$/;

/** @param {Iterable<string>} names query parameter names of an operation @returns {boolean} */
export function declaresPaging(names) {
  for (const n of names) if (PAGING_PARAM.test(n)) return true;
  return false;
}

/**
 * A response is a list when it is a bare array, carries an `items` array, or has exactly one array value and nothing but
 * paging companions beside it. Objects that merely contain arrays (config, site info, permissions) are not lists.
 */
export function isList(json) {
  if (Array.isArray(json)) return true;
  if (json === null || typeof json !== 'object') return false;
  if (Array.isArray(json.items)) return true;
  const arrays = Object.keys(json).filter((k) => Array.isArray(json[k]));
  if (arrays.length !== 1) return false;
  return Object.keys(json).every((k) => k === arrays[0] || LIST_COMPANIONS.has(k));
}

/**
 * @param {any} json
 * @param {boolean} [paged] the endpoint declares a paging parameter (or is not known: unknown counts as paged). Only an
 *   endpoint known to be unpaged may answer { items } without `page`.
 * @returns {string[]}
 */
export function pageProblems(json, paged = true) {
  if (!isList(json)) {
    // not a list by shape, but the legacy paging keys are never allowed beside an array
    if (json !== null && typeof json === 'object' && !Array.isArray(json) && Object.values(json).some(Array.isArray)) {
      const bad = BAD_LIST_KEYS.filter((k) => k in json);
      if (bad.length) return [`carries ${bad.map((k) => `\`${k}\``).join(', ')} beside a list`];
    }
    return [];
  }
  const out = [];
  if (Array.isArray(json)) return ['body is a bare array, expected { items, page }'];
  if (!Array.isArray(json.items)) out.push(`list under [${Object.keys(json).filter((k) => Array.isArray(json[k])).join(', ')}] but no \`items\` array`);
  const p = json.page;
  if (!paged && p === undefined) {
    // decision 80: an unpaged list under `items` needs no `page`
  } else if (p === null || typeof p !== 'object' || Array.isArray(p)) out.push('no `page` object');
  else if ('nextCursor' in p) {
    // cursor lists (console search, server activity, alerts, notifications; doc 04 section 4): { size, nextCursor }
    if (typeof p.size !== 'number') out.push('page.size is not a number');
    if (p.nextCursor !== null && typeof p.nextCursor !== 'string') out.push('page.nextCursor is neither a string nor null');
  } else
    for (const k of ['number', 'size', 'totalItems', 'totalPages'])
      if (typeof p[k] !== 'number') out.push(`page.${k} is not a number`);
  for (const k of BAD_LIST_KEYS) if (k in json) out.push(`carries \`${k}\``);
  if ('data' in json && Array.isArray(json.data)) out.push('carries `data` beside the list');
  return out;
}
