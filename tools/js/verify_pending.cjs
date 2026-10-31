// Structural regression fixtures, not captures from a logged-in account.
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const script = readFileSync(resolve(__dirname, '../../app/src/main/assets/scripts/extract_homework.js'), 'utf8');
function parse(html, url = 'https://mooc1.chaoxing.com/mooc2/work/list?courseId=11&classId=22') {
  const dom = new JSDOM(html, {url, runScripts:'outside-only'});
  const page = JSON.parse(dom.window.eval(script));
  dom.window.close();
  return page;
}
const mixed = parse(readFileSync(resolve(__dirname, '../../app/src/test/resources/fixtures/homework_data_links.html'), 'utf8'));
assert.equal(mixed.items.length, 2, 'pending data links and completed href links must both be read');
assert.equal(mixed.items.find(x => x.id === '11:22:77')?.status, 'NOT_SUBMITTED');
assert.equal(mixed.items.find(x => x.id === '11:22:77')?.title, '导数练习');
assert.equal(new URL(mixed.items.find(x => x.id === '11:22:77').detailUrl).searchParams.get('enc'), 'fixture+signature');
assert.equal(mixed.items.find(x => x.id === '11:22:78')?.status, 'GRADED');
console.log('Mixed pending data links and completed href links passed.');
const rawLink = 'https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?courseId=11&amp;classId=22&amp;workId=77&amp;enc=a%2Bb%26c';
const row = (attrs, status) => `<li ${attrs}><p class="overHidden2 fl">练习</p>${status}</li>`;
for (const attr of ['data', 'dataurl', 'data-url', 'data-href']) {
  const page = parse(row(`${attr}="${rawLink}" onclick="goTask(this);"`, '<p class="status fl">未完成</p>'));
  assert.equal(page.items.length, 1, attr);
  assert.equal(page.items[0].status, 'NOT_SUBMITTED', attr);
  assert.equal(new URL(page.items[0].detailUrl).searchParams.get('enc'), 'a+b&c', 'never double-decode ticket parameters');
  assert.equal(page.diagnostics.attributeLinks, 1);
}
const fallbackStatus = parse(row(`data="${rawLink}" onclick="goTask(this);"`, '<span class="status"></span><span>未提交</span>'));
assert.equal(fallbackStatus.items[0].status, 'NOT_SUBMITTED', 'blank status elements must not suppress row text');
assert.equal(parse(row(`data="${rawLink}"`, '<p class="status fl">待批阅</p>')).items[0].status, 'SUBMITTED');
assert.equal(parse(row(`data="${rawLink}&amp;answerId=0"`, '<p class="status fl">已截止</p>')).items[0].status, 'UNKNOWN', 'answerId and deadline alone do not prove submission status');
assert.equal(parse(row(`data="${rawLink}" style="display:none"`, '<p class="status fl">未完成</p>')).items.length, 0, 'hidden tabs must not pollute the current list');
assert.equal(parse(row('data="https%3A%2F%2Fchaoxing.com.evil.test%2Fwork%2Ftask%3FworkId%3D7" onclick="goTask(this);"', '<p class="status">未完成</p>')).items.length, 0);
assert.equal(parse(row('data="https%3A%2F%2Fmooc1.chaoxing.com%ZZ" onclick="goTask(this);"', '<p class="status">未完成</p>')).items.length, 0);
assert.equal(parse(row('data=\'{"workId":77}\' onclick="goTask(this);"', '<p class="status">未完成</p>')).diagnostics.missingWorkId, 1);
const nav = parse('<a href="javascript:;" dataurl="https://mooc1.chaoxing.com/mooc2/work/list?courseId=11&amp;classId=22">作业</a>');
assert.equal(nav.navigation.length, 1); assert.equal(nav.diagnostics.unsupportedNavigation, 0);
const empty = parse('<div id="workList">暂无作业</div>');
assert.equal(empty.empty, true); assert.equal(empty.workListComplete, true); assert.equal(empty.diagnostics.workCandidates, 0);
console.log('Attribute variants, encoding, pending status fallback, tab visibility, unknown statuses, origin checks, malformed data, navigation and empty-page regressions passed.');
