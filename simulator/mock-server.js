'use strict';

/**
 * 在沙盒里起一个「假抖音」，专门复现真实链路里最容易翻车的几处：
 *  - 短链 302 的 Location 是相对路径
 *  - 分享页第一次只给 ttwid Cookie、不给数据，必须带 Cookie 再请求一次
 *  - _ROUTER_DATA 的标题里带 } 和 {，非贪婪正则会被截断
 *  - playwm -> play / 1080p 端点再 302 到最终 CDN 直链
 */
const http = require('node:http');

const AWEME_ID = '7412345678901234567';
const VID = 'v0300fg10000c8test00000';

function createMockServer() {
  const state = { shareHits: 0, requests: [] };
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const p = url.pathname;
    state.requests.push({ path: p, cookie: req.headers.cookie || '' });

    // 1) 短链：故意给相对 Location
    if (p === '/AbC123' || p === '/AbC123/') {
      res.writeHead(302, { Location: `/share/video/${AWEME_ID}/?region=CN` });
      res.end('');
      return;
    }

    // 1b) 风控短链：分享页永远不给数据
    if (p === '/Blocked' || p === '/Blocked/') {
      res.writeHead(302, { Location: '/share/video/0000000000000000000/' });
      res.end('');
      return;
    }

    // 2) 分享页：首次只发 Cookie；带 Cookie 才吐 _ROUTER_DATA
    if (p.startsWith(`/share/video/${AWEME_ID}`)) {
      state.shareHits += 1;
      if (!String(req.headers.cookie || '').includes('ttwid')) {
        res.writeHead(200, {
          'Content-Type': 'text/html; charset=utf-8',
          'Set-Cookie': 'ttwid=1|mock-ttwid|xyz; Path=/; HttpOnly',
        });
        res.end('<html><body>loading</body></html>');
        return;
      }
      const base = `http://127.0.0.1:${server.address().port}`;
      const router = {
        loaderData: {
          [`video_${AWEME_ID}/page`]: {
            videoInfoRes: {
              item_list: [
                {
                  desc: '测试 } 与 { 花括号，还有 [中括号]',
                  video: {
                    play_addr: {
                      uri: VID,
                      url_list: [
                        `${base}/aweme/v1/playwm/?video_id=${VID}&ratio=720p&line=0`,
                      ],
                    },
                  },
                },
              ],
            },
          },
        },
        extra: 'ok',
      };
      const html = `<html><body><script>window._ROUTER_DATA = ${JSON.stringify(router)};</script></body></html>`;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
      return;
    }

    // 3) 播放端点：1080p 与 play(去水印) 都 302 到最终直链
    if (p === '/aweme/v1/play/' || p === '/aweme/v1/playwm/') {
      const base = `http://127.0.0.1:${server.address().port}`;
      res.writeHead(302, { Location: `${base}/cdn/real.mp4?sign=deadbeef` });
      res.end('');
      return;
    }

    // 4) 最终直链
    if (p === '/cdn/real.mp4') {
      res.writeHead(200, { 'Content-Type': 'video/mp4' });
      res.end('FAKE-VIDEO-BYTES');
      return;
    }

    // 5) 风控场景：分享页始终不给数据
    if (p === '/share/video/0000000000000000000') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<html><body>blocked</body></html>');
      return;
    }

    res.writeHead(404);
    res.end('not found');
  });

  return {
    state,
    listen: () => new Promise((resolve) => {
      server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`));
    }),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

module.exports = { createMockServer, AWEME_ID, VID };
