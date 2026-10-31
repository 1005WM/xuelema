const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {JSDOM} = require('jsdom');
const assert = require('node:assert/strict');
const scripts = resolve(__dirname, '../../app/src/main/assets/scripts');
const parser = readFileSync(resolve(scripts, 'extract_homework.js'), 'utf8');
const bootstrap = readFileSync(resolve(scripts, 'frame_reader.js'), 'utf8').replace('/*__HOMEWORK_EXTRACTOR__*/', parser.trim());
const top = new JSDOM('<iframe src="https://mooc1-api.chaoxing.com/work/stu-work"></iframe><iframe src="https://untrusted.test/"></iframe>', {url:'https://i.chaoxing.com/base',runScripts:'outside-only'});
const child = new JSDOM(readFileSync(resolve(__dirname, '../../app/src/test/resources/fixtures/homework_stu_work.html'), 'utf8'), {url:'https://mooc1-api.chaoxing.com/work/stu-work',runScripts:'outside-only'});
const messages = [];
for (const dom of [top, child]) {
 dom.window.HomeworkDomBridge = {postMessage: value => messages.push(JSON.parse(value))};
 dom.window.eval(bootstrap);
}
Object.defineProperty(child.window, 'parent', {value:top.window});
const frames = top.window.document.querySelectorAll('iframe');
Object.defineProperty(frames[0], 'contentWindow', {value:{postMessage: (request, target) => {
 assert.equal(target, 'https://mooc1-api.chaoxing.com');
 child.window.dispatchEvent(new child.window.MessageEvent('message', {data:request,origin:'https://i.chaoxing.com',source:top.window}));
}}});
let externalCalls = 0;
Object.defineProperty(frames[1], 'contentWindow', {value:{postMessage: () => externalCalls++}});
const command = {type:'HOMEWORK_READ',nonce:'test-operation',round:1,course:null};
assert.equal(messages.length, 0, 'no DOM data is collected before a request');
top.window.__homeworkReadFrames(command);
assert.equal(messages.length, 2);
assert.equal(messages[0].page.items.length, 0, 'wrapper is not mistaken for the homework document');
assert.equal(messages[1].page.items.filter(x=>x.status==='NOT_SUBMITTED').length, 2);
assert.equal(messages[1].round, 1); assert.equal(externalCalls, 0);
messages.length = 0;
child.window.dispatchEvent(new child.window.MessageEvent('message', {data:command,origin:'https://evil.test',source:top.window}));
assert.equal(messages.length, 0, 'external origins cannot trigger collection');
frames[0].style.display = 'none'; top.window.__homeworkReadFrames({...command,round:2});
assert.equal(messages.length, 1, 'hidden child frames are not read');
assert.equal(messages[0].round, 2);
top.window.close(); child.window.close();
console.log('Official frame requests, wrapper/child separation, no passive collection, external-origin and hidden-frame guards passed.');
