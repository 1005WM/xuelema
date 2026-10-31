const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const engine = require('./load_question_engine.cjs');
function open(html, setup) {
  const dom = new JSDOM(html, {url:'https://mooc1.chaoxing.com/work/do',runScripts:'outside-only'});
  setup?.(dom.window); dom.window.eval(engine);
  return {w:dom.window,page:dom.window.__haQuestionEngine.extract()};
}
async function fill(r, answer) {
  return JSON.parse(JSON.stringify(await r.w.__haQuestionEngine.fill({page:r.page,currentFingerprint:r.page.fingerprint,
    answers:[{questionId:r.page.questions[0].id,answer}]})));
}
const choice = `<div class="TiMu" data-question-id="q"><div class="Zy_TItle">【单选题】选择偶数</div><label><input type="radio" name="q" value="A">A. 3</label><label><input type="radio" name="q" value="B">B. 4</label></div>`;
const text = `<form><div class="TiMu" data-question-id="s"><div class="Zy_TItle">【简答题】解释概念</div><textarea name="answerS" style="display:none"></textarea><div contenteditable="true" role="textbox"></div></div></form>`;
let cases=0;
(async()=>{
  for (const answer of ['b','Ｂ','答案：B','**B**','B. 4','4','["B"]']) {
    const r=open(choice), result=await fill(r,answer);
    assert.equal(result.filled.length,1,`${answer}: ${JSON.stringify(result)}`);
    assert.equal(result.feedback.q,'已回填');
    assert.equal(r.w.document.querySelector('input[value="B"]').checked,true); cases++;
  }
  for (const answer of ['B. 3','答案不确定，可能是B','["A","B"]']) {
    const r=open(choice), result=await fill(r,answer);
    assert.equal(result.filled.length,0); assert.equal(result.skipped,1);
    assert.ok(result.failed.some(reason=>/答案|选项/.test(reason)),JSON.stringify(result)); cases++;
    assert.match(result.feedback.q,/未回填：.+/);
  }
  const native=open(choice.replace('value="A"','value="101"').replace('value="B"','value="202"').replace('A. 3','3').replace('B. 4','4'));
  assert.equal((await fill(native,'202')).filled.length,1); cases++;
  for (const answer of ['a，b','["A","B"]','答案：AB']) {
    const r=open(choice.replace('单选题','多选题').replaceAll('type="radio"','type="checkbox"'));
    assert.equal((await fill(r,answer)).filled.length,1);
    assert.equal(r.w.document.querySelectorAll('input:checked').length,2); cases++;
  }
  const judge=open(choice.replace('单选题','判断题').replace('A. 3','A. 正确').replace('B. 4','B. 错误'));
  assert.equal((await fill(judge,'答案：错误')).filled.length,1); cases++;
  const duplicate=open(choice.replace('A. 3','A. 相同').replace('B. 4','B. 相同'));
  assert.equal((await fill(duplicate,'相同')).skipped,1); cases++;
  const existing=open(choice,w=>{w.document.querySelector('input[value="A"]').checked=true});
  assert.equal((await fill(existing,'答案：B')).skipped,1);
  assert.equal(existing.w.document.querySelector('input[value="A"]').checked,true); cases++;
  const synced=open(text,w=>w.document.querySelector('[contenteditable]').addEventListener('input',e=>{
    w.document.querySelector('textarea').value=e.target.innerHTML;
  }));
  assert.equal(synced.page.questions[0].canFill,true);
  assert.equal((await fill(synced,'答案\n<script>文本</script>')).filled.length,1);
  assert.equal(synced.w.document.querySelector('[contenteditable] script'),null); cases++;
  const unsynced=open(text);
  assert.equal((await fill(unsynced,'答案')).unverified.length,1); cases++;
  const occupied=open(text,w=>{w.document.querySelector('textarea').value='手工答案'});
  assert.equal((await fill(occupied,'AI答案')).filled.length,0);
  assert.equal(occupied.w.document.querySelector('textarea').value,'手工答案'); cases++;
  const visible=open(text.replace('style="display:none"',''));
  assert.equal((await fill(visible,'不能猜测两个作答区')).filled.length,0); cases++;
  const replaced=open(text), saved=replaced.w.document.querySelector('textarea'); saved.replaceWith(saved.cloneNode());
  await assert.rejects(()=>fill(replaced,'旧关联')); cases++;
  const iframe=open(text.replace('<div contenteditable="true" role="textbox"></div>','<iframe></iframe>'),w=>{
    const body=w.document.querySelector('iframe').contentDocument.body; body.contentEditable='true';
    body.addEventListener('input',()=>{w.document.querySelector('textarea').value=body.innerHTML});
  });
  assert.equal((await fill(iframe,'内嵌答案框')).filled.length,1); cases++;
  console.log(`Answer-fill compatibility: ${cases} regression cases passed.`);
})().catch(error=>{console.error(error);process.exitCode=1});
