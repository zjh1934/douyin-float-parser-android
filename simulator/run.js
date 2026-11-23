'use strict';

/**
 * 沙盒里「模拟运行这个 APP」：
 *   1) 起一个假抖音（含相对 Location / ttwid 二次请求 / 花括号标题 / 302 直链）
 *   2) 按悬浮窗状态机跑一遍完整交互（点开 → 输入 → 提交 → 变色 → 展开 → 复制）
 *   3) 跑错误链路（非抖音链接、分享页风控）
 *   4) 把模拟出来的正确行为，逐条对照 Kotlin 源码里是否真的实现了
 * 第 4 步不通过就 exit 1，CI 会因此拦住打包。
 */
const fs = require('node:fs');
const path = require('node:path');
const { createMockServer } = require('./mock-server');
const { createMirror } = require('./mirror');

const ROOT = path.join(__dirname, '..');
const KT_PARSER = path.join(ROOT, 'app/src/main/java/com/example/douyinfloat/DouyinParser.kt');
const KT_SERVICE = path.join(ROOT, 'app/src/main/java/com/example/douyinfloat/FloatService.kt');

const COLORS = { IDLE: '#3B6EA8', LOADING: '#D08A2C', OK: '#2F9E63', ERROR: '#C8544B' };
const SIZES = { BUBBLE: '56x56', INPUT: '348x60', RESULT: '396x140' };

function makeUI() {
  const ui = {
    status: 'IDLE', mode: 'BUBBLE', color: COLORS.IDLE, size: SIZES.BUBBLE,
    urls: [], clipboard: '', icon: 'copy', keyboard: false,
    flags: new Set(['FLAG_NOT_FOCUSABLE']), lastInput: '',
  };
  const render = () => {
    ui.color = COLORS[ui.status];
    ui.size = SIZES[ui.mode];
  };
  return {
    ui,
    click() {
      if (ui.status === 'LOADING') return;
      if (ui.mode !== 'BUBBLE') {
        ui.mode = 'BUBBLE';
        ui.keyboard = false;
        ui.flags.add('FLAG_NOT_FOCUSABLE');
        render();
        return;
      }
      if (ui.status === 'OK' && ui.urls.length) {
        ui.mode = 'RESULT';
        ui.icon = 'copy';
      } else {
        ui.mode = 'INPUT';
        ui.flags.delete('FLAG_NOT_FOCUSABLE'); // 否则 EditText 拿不到焦点、弹不出键盘
        ui.keyboard = true;
      }
      render();
    },
    type(s) { ui.lastInput = s; },
    collapseAndLoad() { ui.mode = 'BUBBLE'; ui.keyboard = false; ui.flags.add('FLAG_NOT_FOCUSABLE'); ui.status = 'LOADING'; render(); },
    success(urls) { ui.urls = urls; ui.status = 'OK'; render(); },
    fail() { ui.status = 'ERROR'; render(); },
    copy() { ui.clipboard = ui.urls.join('\n'); ui.icon = 'check'; },
  };
}

const results = [];
function check(name, ok, hint) {
  results.push({ name, ok, hint });
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}${ok ? '' : `  -> ${hint}`}`);
}

async function main() {
  const mock = createMockServer();
  const base = await mock.listen();
  const mirror = createMirror(base);
  const netLog = [];
  const log = (s) => { netLog.push(s); console.log('  · ' + s); };

  console.log('\n========== 1. 模拟悬浮窗交互 ==========');
  const sim = makeUI();
  console.log(`  初始: 球=${sim.ui.color} 状态=${sim.ui.status} 视图=${sim.ui.mode} 窗口=${sim.ui.size}`);
  sim.click();
  console.log(`  点一下球: 展开输入框 视图=${sim.ui.mode} 窗口=${sim.ui.size} 键盘=${sim.ui.keyboard} 焦点标记=${[...sim.ui.flags].join(',') || '无'}`);
  sim.type('8.87 复制打开抖音 https://v.douyin.com/AbC123/ 看看');
  sim.collapseAndLoad();
  console.log(`  提交: 收成球 状态=${sim.ui.status} 球=${sim.ui.color}`);

  console.log('\n========== 2. 解析链路（假抖音） ==========');
  let parsed;
  try {
    parsed = await mirror.parse(sim.ui.lastInput, log);
  } catch (e) {
    console.log('  ! 解析抛错: ' + e.message);
  }
  if (parsed) {
    sim.success(parsed.urls);
    console.log(`  结果: 状态=${sim.ui.status} 球=${sim.ui.color} 拿到 ${parsed.urls.length} 条地址`);
  } else {
    sim.fail();
  }

  console.log('\n========== 3. 再点开 + 一键复制 ==========');
  sim.click();
  console.log(`  再点开: 视图=${sim.ui.mode} 窗口=${sim.ui.size} 显示=${sim.ui.urls.join('\\n').slice(0, 60)}...`);
  sim.copy();
  console.log(`  点复制图标: 剪贴板=${sim.ui.clipboard} 图标=${sim.ui.icon}`);

  console.log('\n========== 4. 错误链路 ==========');
  let nonDouyinRejected = false;
  try { await mirror.parse('https://example.com/a'); } catch (e) { nonDouyinRejected = /不是抖音链接/.test(e.message); }
  console.log(`  非抖音链接被拒绝: ${nonDouyinRejected}`);
  let riskRejected = false;
  try { await mirror.parse('https://v.douyin.com/Blocked/'); } catch (e) { riskRejected = /没返回作品数据/.test(e.message); }
  console.log(`  分享页风控 -> 明确报错: ${riskRejected}`);
  const errSim = makeUI();
  errSim.collapseAndLoad();
  errSim.fail();
  errSim.click();
  console.log(`  解析失败: 球=${errSim.ui.color}，再点开回到 ${errSim.ui.mode}`);

  console.log('\n========== 5. 运行时断言 ==========');
  const expectedFinal = `${base}/cdn/real.mp4?sign=deadbeef`;
  check('短链相对 Location 能解析出 aweme_id', netLog.some((l) => l.includes('7412345678901234567')), '短链 Location 是相对路径，直接 new URL() 会抛异常');
  check('分享页需要带 ttwid 二次请求', netLog.some((l) => l.includes('ttwid')) && mock.state.shareHits >= 2, '只请求一次分享页会拿到空页面');
  check('标题里的 } { 不会截断 _ROUTER_DATA', !!(parsed && parsed.title.includes('{') && parsed.title.includes('}')), '非贪婪正则会被字符串里的 } 截断');
  check('拿到最终 CDN 直链', !!(parsed && parsed.urls[0] === expectedFinal), `期望 ${expectedFinal}`);
  check('成功后球变绿', sim.ui.color === COLORS.OK, '状态色映射');
  check('展开结果时窗口放大到 396x140', sim.ui.size === SIZES.RESULT, '透明空白不能拦截下层点击');
  check('复制图标把地址写进剪贴板', sim.ui.clipboard === expectedFinal, '剪贴板内容');
  check('失败后变红且能重新打开输入框', errSim.ui.color === COLORS.ERROR && errSim.ui.mode === 'INPUT', '错误态恢复');
  check('非抖音链接直接拒绝', nonDouyinRejected, '输入校验');
  check('分享页风控时明确失败而不是返回空地址', riskRejected, '空结果必须落到 ERROR 态');

  console.log('\n========== 6. Kotlin 源码一致性检查 ==========');
  const parser = fs.readFileSync(KT_PARSER, 'utf8');
  const service = fs.readFileSync(KT_SERVICE, 'utf8');
  check('相对 Location 用 URI.resolve 而不是 URL(loc)',
    /URI\(/.test(parser) && /\.resolve\(/.test(parser),
    'DouyinParser.kt: `URL(loc).toString()` 遇到相对 Location 直接抛 MalformedURLException');
  check('ttwid Cookie 二次请求分享页',
    /"Cookie"/.test(parser) && /second|Cookie/.test(parser) && (parser.match(/request\(/g) || []).length >= 3,
    'DouyinParser.kt: sharePage 只请求一次，风控时拿不到 _ROUTER_DATA');
  check('_ROUTER_DATA 用花括号配对提取',
    /depth/.test(parser) && /inString|inStr/.test(parser),
    'DouyinParser.kt: 非贪婪正则遇 desc 里的 } 会截断 JSON');
  check('网络请求在后台线程，结果回主线程刷新',
    /Thread\s*\{/.test(service) && /mainHandler\.post/.test(service),
    'FloatService.kt: submit() 在主线程直接 parse()，会触发 NetworkOnMainThreadException');
  check('展开输入框时移除 FLAG_NOT_FOCUSABLE 并弹键盘',
    /FLAG_NOT_FOCUSABLE\.inv\(\)/.test(service) && /showSoftInput\(/.test(service),
    'FloatService.kt: 悬浮窗带 FLAG_NOT_FOCUSABLE 时 EditText 无法聚焦、输入法弹不出来');
  check('解析异常被捕获并落到 ERROR 态',
    /Thread\s*\{[\s\S]{0,300}try\s*\{[\s\S]{0,300}catch\s*\(\s*\w+\s*:\s*Exception\s*\)/.test(service) &&
      /Status\.ERROR/.test(service),
    'FloatService.kt: 网络异常会直接把服务打崩');
  check('前台服务有通知渠道',
    /createNotificationChannel\(/.test(service) && /startForeground\(/.test(service),
    'FloatService.kt: Android 8+ 前台服务缺通知会被系统杀掉');
  check('窗口尺寸随视图切换',
    /resize\(/.test(service) && /updateViewLayout\(/.test(service),
    'FloatService.kt: 固定 WRAP_CONTENT 会让透明区域拦截点击');

  const failed = results.filter((r) => !r.ok);
  console.log(`\n========== 汇总: ${results.length - failed.length}/${results.length} 通过 ==========`);
  if (failed.length) {
    console.log('以下实现与模拟运行不一致，必须先改代码再出包：');
    failed.forEach((f) => console.log(`  - ${f.name}: ${f.hint}`));
    process.exitCode = 1;
  } else {
    console.log('全部一致，可以出包。');
  }
  await mock.close();
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
