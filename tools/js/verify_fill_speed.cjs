const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const engine = require('./load_question_engine.cjs');
const html = [1,2,3].map(id => `<div class="TiMu" data-question-id="${id}"><div class="Zy_TItle">【简答题】说明${id}</div><textarea></textarea></div>`).join('');
function scenario(content = html) {
  const dom = new JSDOM(content, {url:'https://mooc1.chaoxing.com/exam-ans/exam/test',runScripts:'outside-only'});
  dom.window.eval(engine);
  const api = dom.window.__haQuestionEngine, page = api.extract(), writes = [];
  dom.window.document.querySelectorAll('textarea,[contenteditable]').forEach((node,index) => node.addEventListener('input', () => writes.push({index,time:Date.now()})));
  const fill = interval => api.fill({page,currentFingerprint:page.fingerprint,fillIntervalMillis:interval,
    answers:page.questions.map(q => ({questionId:q.id,answer:'参考答案'}))});
  return {dom,api,page,writes,fill};
}
(async () => {
  const paced = scenario();
  const result = await paced.fill(500);
  assert.equal(result.filled.length,3);
  assert.equal(paced.writes.length,3);
  for (let i=1;i<paced.writes.length;i++) assert.ok(paced.writes[i].time-paced.writes[i-1].time>=500,'writes must respect the configured gap');
  const slow = scenario(), operation = slow.fill(1000);
  await new Promise(resolve => setTimeout(resolve,150));
  assert.equal(slow.writes.length,1);
  slow.api.cancel();
  const stopped = await operation;
  assert.equal(slow.writes.length,1);
  assert.equal(stopped.filled.length,1);
  const changed = scenario(), pending = changed.fill(500);
  await new Promise(resolve => setTimeout(resolve,150));
  changed.dom.window.document.querySelectorAll('textarea')[1].value='人工填写';
  await pending;
  assert.equal(changed.writes.length,1);
  assert.equal(changed.dom.window.document.querySelectorAll('textarea')[1].value,'人工填写');
  const clamped = scenario();
  await clamped.fill(0);
  assert.ok(clamped.writes[1].time-clamped.writes[0].time>=500);
  const mixed = scenario(html.replace('<textarea></textarea>', '<div contenteditable="true" role="textbox"></div>'));
  assert.equal((await mixed.fill(500)).filled.length,3);
  for (let i=1;i<mixed.writes.length;i++) assert.ok(mixed.writes[i].time-mixed.writes[i-1].time>=500,'native and contenteditable writers must share one pacer');
  const navigated = scenario(), navigating = navigated.fill(500);
  await new Promise(resolve=>setTimeout(resolve,150));
  navigated.dom.reconfigure({url:'https://mooc1.chaoxing.com/exam-ans/exam/other'});
  await navigating;
  assert.equal(navigated.writes.length,1);
  console.log('Fill pacing: configurable gaps, cancellation, manual edits, minimum interval, mixed editors and navigation guards passed.');
})().catch(error=>{console.error(error);process.exitCode=1;});
