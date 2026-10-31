const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {JSDOM} = require('jsdom');
const assert = require('node:assert/strict');
const script = readFileSync(resolve(__dirname, '../../app/src/main/assets/scripts/extract_homework.js'), 'utf8');
function parse(html) {
 const dom = new JSDOM(html, {url:'https://mooc1-api.chaoxing.com/work/stu-work',runScripts:'outside-only'});
 const page = JSON.parse(dom.window.eval(script)); dom.window.close(); return page;
}
const html = readFileSync(resolve(__dirname, '../../app/src/test/resources/fixtures/homework_stu_work.html'), 'utf8');
const page = parse(html);
assert.equal(page.items.length, 3, 'taskrefId rows must be read; exams must be excluded');
assert.equal(page.items.filter(x => x.status === 'NOT_SUBMITTED').length, 2);
assert.equal(page.items.filter(x => x.status === 'SUBMITTED').length, 1);
assert.equal(page.items.find(x => x.id === '11:22:77')?.courseName, '高等数学');
assert.equal(page.items.find(x => x.id === '33:44:88')?.title, '单词练习');
assert.equal(page.items.find(x => x.id === '33:44:88')?.dueAt, null, 'relative dates must not be invented');
assert.equal(page.navigation.some(x => /workHandle/.test(x)), false, 'detail handlers are not list navigation');
assert.equal(page.diagnostics.referenceLinks, 3);
assert.equal(page.diagnostics.pendingLabels, 2);
for (const label of ['未提交','未完成','尚未提交','未作答','待作答','未开始']) {
 const row = `<li data="https://mooc1-api.chaoxing.com/workHandle/handle?courseId=11&clazzId=22&taskrefId=77"><div role="option"><p>练习</p><span class="status">${label}</span><span>数学</span></div></li>`;
 assert.equal(parse(row).items[0].status, 'NOT_SUBMITTED', label);
}
const otherStatus = '<li class="work-item"><h3>未完成作业汇总</h3><a href="https://mooc1.chaoxing.com/work/do?courseId=11&classId=22&workId=77">查看</a><span class="status">已截止</span><span>未交</span></li>';
assert.equal(parse(otherStatus).items[0].status, 'NOT_SUBMITTED', 'deadline status cannot mask explicit submission status');
assert.equal(parse(otherStatus.replace('<span>未交</span>','')).items[0].status, 'UNKNOWN', 'title text is not submission evidence');
console.log('Official aggregate work/taskrefId structure passed.');
