'use strict';

/**
 * DouyinParser.kt 的等价 JS 镜像，用来在沙盒里「跑一遍这个软件」。
 * 算法逐段对应 Kotlin 实现；域名通过 toMock() 映射到本地假抖音。
 */
const URL_RE = /https?:\/\/[^\s"'<>()（）[\]{}，。；、]+/i;
const ID_RE = /(?:\/share\/(?:video|slides|note)\/|\/video\/|\/note\/|modal_id=|aweme_id=|item_ids=)(\d{8,25})/;

function firstUrl(text) {
  const m = String(text || '').match(URL_RE);
  return m ? m[0].replace(/[.,;!?，。；！？、]+$/, '') : null;
}

function idOf(...xs) {
  for (const s of xs) {
    if (!s) continue;
    const m = String(s).match(ID_RE);
    if (m) return m[1];
  }
  return null;
}

function cookieOf(res) {
  const jar = new Map();
  let raw = [];
  try {
    if (typeof res.headers.getSetCookie === 'function') raw = res.headers.getSetCookie() || [];
    else {
      const v = res.headers.get('set-cookie');
      if (v) raw = [v];
    }
  } catch (_) { return ''; }
  for (const line of raw) {
    const pair = String(line).split(';')[0];
    const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
  }
  return [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
}

/** 花括号配对截取，字符串内的括号不参与计数 */
function routerData(html) {
  const at = String(html || '').indexOf('window._ROUTER_DATA');
  if (at < 0) return null;
  const start = html.indexOf('{', at);
  if (start < 0) return null;
  let depth = 0, inStr = false, quote = '', esc = false;
  for (let i = start; i < html.length; i += 1) {
    const c = html[i];
    if (inStr) {
      if (esc) { esc = false; continue; }
      if (c === '\\') { esc = true; continue; }
      if (c === quote) inStr = false;
      continue;
    }
    if (c === '"' || c === "'") { inStr = true; quote = c; continue; }
    if (c === '{') depth += 1;
    else if (c === '}') {
      depth -= 1;
      if (depth === 0) {
        try { return JSON.parse(html.slice(start, i + 1)); } catch (_) { return null; }
      }
    }
  }
  return null;
}

function deepFindItem(node, depth = 0) {
  if (!node || typeof node !== 'object' || depth > 7) return null;
  if (Array.isArray(node.item_list) && node.item_list.length) return node.item_list[0];
  for (const k of Object.keys(node)) {
    const hit = deepFindItem(node[k], depth + 1);
    if (hit) return hit;
  }
  return null;
}

function itemFrom(router, id) {
  const ld = (router && router.loaderData) || {};
  const keys = Object.keys(ld);
  const key = keys.find((k) => k.includes(`video_${id}/page`)) ||
    keys.find((k) => k.includes(`note_${id}/page`)) ||
    keys.find((k) => k.startsWith('video_')) ||
    keys.find((k) => k.startsWith('note_')) || null;
  if (key) {
    const hit = deepFindItem(ld[key]);
    if (hit) return hit;
  }
  return deepFindItem(ld) || deepFindItem(router);
}

function videoUrls(item, base) {
  const play = (item && item.video && item.video.play_addr) || {};
  const list = Array.isArray(play.url_list) ? play.url_list : [];
  let vid = play.uri || null;
  if (!vid && list[0]) {
    try { vid = new URL(list[0], base).searchParams.get('video_id'); } catch (_) { vid = null; }
  }
  const out = [];
  if (vid) out.push(`${base}/aweme/v1/play/?video_id=${encodeURIComponent(vid)}&ratio=1080p&line=0`);
  for (const u of list) out.push(String(u).replace(/playwm/g, 'play'));
  return [...new Set(out)];
}

function imageUrls(item) {
  const images = (item && Array.isArray(item.images)) ? item.images : [];
  const out = [];
  for (const im of images) {
    const list = (im && Array.isArray(im.url_list)) ? im.url_list : [];
    if (list.length) out.push(list[list.length - 1]);
  }
  return out;
}

function createMirror(base) {
  const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile Safari/604.1';
  const H = { 'User-Agent': UA, Accept: 'text/html,application/json;q=0.9,*/*;q=0.8' };
  const toMock = (u) => {
    try {
      const x = new URL(u);
      if (/v\.douyin\.com|iesdouyin\.com|snssdk\.com|douyin\.com/i.test(x.host)) {
        return base + x.pathname + x.search;
      }
    } catch (_) { /* 已经是本地地址 */ }
    return u;
  };

  const get = (u, opts = {}) => globalThis.fetch(toMock(u), {
    method: 'GET', redirect: 'manual', headers: { ...H, ...(opts.headers || {}) },
  });

  async function expandShortLink(input) {
    let current = toMock(input);
    const seen = new Set();
    let jar = '';
    for (let i = 0; i < 6; i += 1) {
      if (seen.has(current)) break;
      seen.add(current);
      const r = await get(current);
      const c = cookieOf(r);
      if (c) jar = c;
      const loc = r.headers.get('location');
      if (!loc) return { id: idOf(current), jar };
      const next = new URL(loc, current).toString();   // 相对 Location 必须 resolve
      const id = idOf(next) || idOf(loc);
      if (id) return { id, jar };
      current = next;
    }
    return { id: idOf(current), jar };
  }

  async function sharePage(id, jar) {
    const url = `https://www.iesdouyin.com/share/video/${id}/`;
    const first = await get(url, { headers: { Referer: 'https://www.douyin.com/' } });
    const html = await first.text();
    if (html.includes('_ROUTER_DATA')) return html;
    const cookie = jar || cookieOf(first);
    const second = await get(url, { headers: { Referer: 'https://www.douyin.com/', Cookie: cookie } });
    return second.text();
  }

  async function resolveFinal(url) {
    const r = await get(url);
    const loc = r.headers.get('location');
    return (loc && /^https?:/i.test(loc)) ? loc : url;
  }

  async function parse(raw, log = () => {}) {
    const input = firstUrl(raw);
    if (!input) throw new Error('没找到链接');
    if (!/douyin\.com|iesdouyin\.com|snssdk\.com/i.test(input)) throw new Error('不是抖音链接');
    log(`提取链接 ${input}`);

    const { id, jar } = await expandShortLink(input);
    if (!id) throw new Error('链接里没识别出作品 ID');
    log(`短链 302(相对 Location) -> aweme_id=${id}`);

    const html = await sharePage(id, jar);
    log(`分享页第 2 次请求(带 ttwid) 拿到 _ROUTER_DATA，长度 ${html.length}`);
    const item = itemFrom(routerData(html), id);
    if (!item) throw new Error('分享页没返回作品数据');
    log(`作品标题: ${item.desc}`);

    const title = item.desc || '';
    const images = imageUrls(item);
    const candidates = videoUrls(item, base);
    if (!candidates.length) {
      if (images.length) return { type: 'images', urls: images, title, id };
      throw new Error('没取到视频地址');
    }
    log(`构造播放地址 ${candidates[0]}`);
    const final = await resolveFinal(candidates[0]);
    log(`跟随 302 -> 最终直链 ${final}`);
    return { type: 'video', urls: [final], title, id };
  }

  return { parse, firstUrl, idOf, routerData, videoUrls };
}

module.exports = { createMirror, firstUrl, idOf, routerData, videoUrls, imageUrls };
