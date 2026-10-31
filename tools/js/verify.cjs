const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const scripts = path.resolve(__dirname, '../../app/src/main/assets/scripts');
const fixtures = path.resolve(__dirname, '../../app/src/test/resources/fixtures');
const fixture = name => fs.readFileSync(path.join(fixtures,name),'utf8');
function run(html, script, setup, url = 'https://mooc1.chaoxing.com/work/list') {
  const dom = new JSDOM(html, {url,runScripts:'outside-only'});
  Object.defineProperty(dom.window.document, 'readyState', {value:'complete',configurable:true});
  if (setup) setup(dom.window);
  const value = JSON.parse(dom.window.eval(fs.readFileSync(path.join(scripts, script), 'utf8')));
  return {dom, value};
}
const parsed = run(fixture('homework_supported.html'), 'extract_homework.js', w=>w.__homeworkContext={id:'1:2',name:'数学'}).value;
assert.equal(parsed.items.length,1);assert.equal(parsed.items[0].dueAt,'2026-10-28 10:30');assert.equal(parsed.items[0].status,'NOT_SUBMITTED');assert.equal(parsed.items[0].courseName,'数学');
assert.equal(run(fixture('homework_unknown.html'),'extract_homework.js').value.recognized,false);
assert.equal(run(fixture('homework_empty.html'),'extract_homework.js').value.empty,true);
assert.equal(run('<form><input type="password"><button>登录</button></form>','extract_homework.js').value.loginRequired,true);
assert.equal(run('<a href="https://chaoxing.com.evil.test/work/do?workId=1">查看</a>','extract_homework.js').value.items.length,0);
// Question recognition/prefilling is verified against the production upstream engine in verify_ai_questions.cjs.
console.log('DOM extraction, date capture, origin filtering, list checks passed; AI engine checks are in the dedicated suite.');
const session = (html, url='https://i.chaoxing.com/base') => run(html,'detect_session.js',undefined,url).value;
assert.equal(session('<div class="user-name">同学</div><iframe src="https://mooc1.chaoxing.com/visit/courses"></iframe>').hasPersonalContent,true);
assert.equal(session('<div>个人空间</div>').hasPageContent,true);
assert.equal(session('<div>个人空间</div>').verified,false); // native HttpOnly cookie evidence is evaluated separately
assert.equal(session('<input name="uid" value="123"><a href="/logout">退出登录</a>').uid,'123');
assert.equal(session('<input name="uid" value="123"><a href="/logout">退出登录</a>').verified,true);
assert.equal(session('<input type="password"><a href="/logout">退出登录</a>').verified,false);
assert.equal(session('<div style="display:none"><input type="password"></div><a href="/logout">退出登录</a>').verified,true);
assert.equal(session('<div style="opacity:0"><input type="password"></div><a href="/logout">退出登录</a>').verified,true);
const hiddenLoginFrame = run('<iframe style="display:none"></iframe><a href="/logout">退出登录</a>','detect_session.js', w => {
  w.document.querySelector('iframe').contentDocument.body.innerHTML = '<input type="password">';
},'https://i.chaoxing.com/base').value;
assert.equal(hiddenLoginFrame.verified,true);
assert.equal(session('<div>登录已过期，请先登录</div>').loginForm,true);
assert.equal(session('<div></div>').hasPageContent,false);
assert.equal(session('<a href="/logout">退出登录</a>','https://passport2.chaoxing.com/login').verified,false);
assert.equal(session('<a href="/logout">退出登录</a>','https://i.chaoxing.com.evil.test/base').ready,false);
console.log('Session detection: missing logout, native-cookie handoff, login/expired/blank pages and spoofed origins passed.');
const legacy = run('<div id="courseList"><li class="course"><h3>数学</h3><a onclick="window.open(\'http://mooc1.chaoxing.com/visit/interaction?courseId=11&amp;clazzId=22\')">数学</a></li></div><iframe src="http://mooc1.chaoxing.com/visit/courses/list"></iframe>', 'extract_homework.js').value;
assert.equal(legacy.courses[0].url,'https://mooc1.chaoxing.com/visit/interaction?courseId=11&clazzId=22');
assert.equal(legacy.frames[0],'https://mooc1.chaoxing.com/visit/courses/list');
const current = run('<input name="courseId" value="11"><input name="classId" value="22"><div class="courseName">数学</div><ul id="workList"><li data-work-id="77"><span class="workName" title="第一章">省略标题</span><span>未做 截止时间：2026-10-28 10:30</span></li></ul>', 'extract_homework.js').value;
assert.equal(current.items[0].id,'11:22:77'); assert.equal(current.items[0].title,'第一章'); assert.equal(current.items[0].status,'NOT_SUBMITTED');
assert.equal(current.items[0].detailUrl,'https://mooc1.chaoxing.com/work/list');
const missing = run('<ul id="workList"><li data-work-id="77">作业 未交</li></ul>','extract_homework.js').value;
assert.equal(missing.items.length,0); assert.equal(missing.diagnostics.missingCourseId,1);
assert.equal(missing.diagnostics.workCandidates,1);
const namedUrl = run('<li class="work-item"><h3>练习</h3><a href="http://mooc1.chaoxing.com/work/dowork?workId=77&courseId=11&clazzId=22">查看</a><span>已提交 得分：92</span></li>','extract_homework.js').value;
assert.equal(namedUrl.items[0].id,'11:22:77'); assert.equal(namedUrl.items[0].status,'GRADED'); assert.equal(namedUrl.items[0].score,92);
assert.equal(namedUrl.courses.length,0); // work links are never queued as course entrances
const emptyWork = run('<div id="workList">暂无作业</div>','extract_homework.js').value;
assert.equal(emptyWork.workListComplete,true); assert.equal(emptyWork.courseListComplete,false);
assert.equal(run('<div id="workList"></div>','extract_homework.js').value.recognized,false); // await AJAX, not a success-empty snapshot
const pages = run('<div id="workList"><a rel="next" class="disabled" aria-disabled="true" href="?page=2">下一页</a></div>','extract_homework.js').value;
assert.equal(pages.next.length,0);
console.log('Homework sync: legacy HTTP entrances, current-page IDs, row attributes, missing-ID diagnostics, empty/AJAX distinction and disabled pagination passed.');
const inheritedClass = run('<li class="work-item"><a href="/work/dowork?workId=77&courseId=11">练习</a><span class="status" title="未提交"></span></li>', 'extract_homework.js',w=>w.__homeworkContext={id:'11:22',name:'数学'},'https://mooc1.chaoxing.com/work/list?courseId=11').value;
assert.equal(inheritedClass.items[0].id,'11:22:77'); assert.equal(inheritedClass.items[0].status,'NOT_SUBMITTED');
console.log('Course/class identity remains stable across iframe URLs and title-only status labels.');
const unsupportedNav = run('<div id="workList" data-homework-complete="true"><button onclick="showCompleted()">已完成（3）</button></div><li class="course" onclick="openCourse(123)">数学</li>', 'extract_homework.js').value;
assert.equal(unsupportedNav.diagnostics.unsupportedNavigation,1);
assert.equal(unsupportedNav.diagnostics.unresolvedCourses,1);
console.log('Unsupported JavaScript tabs and unresolved course cards block full-account completeness.');
