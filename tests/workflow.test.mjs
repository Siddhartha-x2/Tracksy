import { test } from 'node:test';
import assert from 'node:assert/strict';

const base=process.env.TEST_BASE_URL || 'http://127.0.0.1:4010';
const suffix=Date.now().toString(36);
class Client {
  cookies=new Map(); token=''; header='X-CSRF-TOKEN';
  async call(path,method='GET',body,headers={}) {
    const options={method,headers:{Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; '),...headers}};
    if(method!=='GET')options.headers[this.header]=this.token;
    if(body instanceof FormData)options.body=body;
    else if(body!==undefined){options.headers['Content-Type']='application/json';options.body=JSON.stringify(body);}
    const response=await fetch(base+'/api'+path,options);
    for(const cookie of response.headers.getSetCookie()){const [pair]=cookie.split(';');const i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1));}
    const text=await response.text(); let data; try{data=JSON.parse(text);}catch{data=text;}
    if(data?.csrfToken)this.token=data.csrfToken;
    return {status:response.status,data,headers:response.headers};
  }
  async csrf(){const r=await this.call('/auth/csrf');assert.equal(r.status,200);this.token=r.data.token;this.header=r.data.headerName;}
  async login(email,password){await this.csrf();const r=await this.call('/auth/login','POST',{email,password});assert.equal(r.status,200,JSON.stringify(r.data));this.user=r.data.user;return this.user;}
  async register(name,email){await this.csrf();const r=await this.call('/auth/register','POST',{name,email,password:'TracksyTest2026!'});assert.equal(r.status,200,JSON.stringify(r.data));this.user=r.data.user;return this.user;}
}
function expect(result,status){assert.equal(result.status,status,JSON.stringify(result.data));return result.data;}
function evidence(title,note='This submission documents the assigned deliverable, implementation choices and acceptance checks.'){const f=new FormData();f.set('title',title);f.set('note',note);return f;}

test('Tracksy end-to-end authorization, approval, privacy and integrity checks',async t=>{
 const guide=new Client(), a=new Client(), b=new Client(), outsider=new Client(), anonymous=new Client(), admin=new Client();
 const config=expect(await anonymous.call('/auth/config'),200);
 const accounts=config.demoAccounts;
 const email=role=>Array.isArray(accounts)?accounts.find(x=>x.role===role)?.email:undefined;
 await guide.login(email('faculty')||'faculty@tracksy.demo','TracksyDemo2026!');
 await a.register('=HYPERLINK("https://example.test")',`test-a-${suffix}@tracksy.test`);
 await b.register('Test Teammate',`test-b-${suffix}@tracksy.test`);
 await outsider.register('Test Outsider',`test-out-${suffix}@tracksy.test`);
 const project=expect(await guide.call('/projects','POST',{name:`Verified workflow ${suffix}`,description:'Isolated integration verification project',term:'2026 test',problemStatement:'Protect project work and ensure fair evidence-based assessment.',techStack:'React, TypeScript, Spring Boot, PostgreSQL'}),201).project;
 const pid=project.id;
 await t.test('CSRF and anonymous access are enforced',async()=>{
  expect(await anonymous.call(`/projects/${pid}/tasks`),401);
  expect(await anonymous.call('/auth/login','POST',{email:guide.user.email,password:'TracksyDemo2026!'},{'X-CSRF-TOKEN':''}),403);
 });
 await t.test('Invitations are identity-bound and cannot be reused',async()=>{
  const created=expect(await guide.call(`/projects/${pid}/invitations`,'POST',{email:a.user.email}),201);
  const invite=created.invitation;
  assert.ok(invite?.id);
  assert.ok([403,404].includes((await b.call(`/invitations/${invite.id}/respond`,'POST',{accept:true})).status));
  expect(await a.call(`/invitations/${invite.id}/respond`,'POST',{accept:true}),200);
  expect(await a.call(`/invitations/${invite.id}/respond`,'POST',{accept:true}),409);
  const second=expect(await guide.call(`/projects/${pid}/invitations`,'POST',{email:b.user.email}),201).invitation;
  expect(await b.call(`/invitations/${second.id}/respond`,'POST',{accept:true}),200);
 });
 await t.test('A join code creates a request without granting access or exposing the code to students',async()=>{
  const own=expect(await guide.call('/projects'),200).projects.find(p=>p.id===pid);
  assert.ok(own.joinCode);
  expect(await outsider.call('/projects/join','POST',{joinCode:own.joinCode}),200);
  expect(await outsider.call(`/projects/${pid}/tasks`),404);
  const studentProject=expect(await a.call('/projects'),200).projects.find(p=>p.id===pid);
  assert.equal(studentProject.joinCode,undefined);
 });
 let task, item;
 await t.test('Assignment and calendar validation are enforced on the server',async()=>{
  const values={name:'Implement secure workflow',details:'Build the project evidence transition.',assignedTo:a.user.id,priority:'High',complexity:2,acceptanceCriteria:'Only approved evidence can complete this task.'};
  expect(await guide.call(`/projects/${pid}/tasks`,'POST',{...values,dueDate:'2026-99-99'}),400);
  expect(await guide.call(`/projects/${pid}/tasks`,'POST',{...values,assignedTo:guide.user.id}),400);
  task=expect(await guide.call(`/projects/${pid}/tasks`,'POST',values),201).task;
  assert.ok(task.id);
  assert.equal(task.points,10);
 });
 await t.test('Task completion cannot bypass faculty evidence review',async()=>{
  expect(await guide.call(`/tasks/${task.id}`,'PATCH',{status:'Done'}),409);
  expect(await a.call(`/tasks/${task.id}`,'PATCH',{status:'In progress'}),200);
  assert.ok([403,404].includes((await b.call(`/projects/${pid}/tasks/${task.id}/evidence`,'POST',evidence('Not my task'))).status));
  const invalid=evidence('Fake PNG');invalid.set('file',new Blob(['This is not a PNG.'],{type:'image/png'}),'proof.png');
  expect(await a.call(`/projects/${pid}/tasks/${task.id}/evidence`,'POST',invalid),400);
  item=expect(await a.call(`/projects/${pid}/tasks/${task.id}/evidence`,'POST',evidence('Verified implementation')),201).evidence;
  assert.equal(item.version,1);
  assert.equal(expect(await a.call(`/projects/${pid}/tasks`),200).tasks.find(x=>x.id===task.id).status,'Under review');
  assert.ok([403,404].includes((await b.call(`/evidence/${item.id}/history`)).status));
 });
 await t.test('Review and resubmission require the exact version reviewed',async()=>{
  expect(await guide.call(`/evidence/${item.id}/review`,'POST',{decision:'Approved',feedback:'Verified.',quality:4,expectedVersion:2}),409);
  expect(await guide.call(`/evidence/${item.id}/review`,'POST',{decision:'Changes requested',feedback:'Please include the access-control explanation.',quality:3,expectedVersion:1}),200);
  const update=evidence('Improved verified implementation');update.set('expectedVersion','1');
  item=expect(await a.call(`/evidence/${item.id}/resubmit`,'POST',update),200).evidence;
  assert.equal(item.version,2);
  expect(await guide.call(`/evidence/${item.id}/review`,'POST',{decision:'Approved',feedback:'Stale screen.',quality:5,expectedVersion:1}),409);
  expect(await guide.call(`/evidence/${item.id}/review`,'POST',{decision:'Approved',feedback:'The revised implementation is verified.',quality:4,expectedVersion:2}),200);
  const history=expect(await a.call(`/evidence/${item.id}/history`),200).versions;
  assert.equal(history.length,2);assert.equal(history.find(v=>v.version===1).decision,'Changes requested');assert.equal(history.find(v=>v.version===2).decision,'Approved');
  assert.equal(expect(await a.call(`/projects/${pid}/tasks`),200).tasks.find(x=>x.id===task.id).status,'Done');
 });
 await t.test('Private evaluations and report exports stay within the student scope',async()=>{
  const report=expect(await a.call(`/projects/${pid}/report`),200);
  assert.equal(report.members.length,1);assert.equal(report.members[0].userId,a.user.id);assert.equal(report.members[0].completionScore,100);
  const teacher=expect(await guide.call(`/projects/${pid}/report`),200);assert.equal(teacher.members.filter(x=>x.role==='student').length,2);
  expect(await b.call(`/projects/${pid}/rubric`,'POST',{weights:{tasks:100,evidence:0,consistency:0,peer:0,repository:0,documentation:0,milestones:0}}),403);
  expect(await guide.call(`/projects/${pid}/rubric`,'POST',{weights:{tasks:100,evidence:50,consistency:0,peer:0,repository:0,documentation:0,milestones:0}}),400);
  const csv=expect(await a.call(`/projects/${pid}/report.csv`),200);assert.ok(csv.includes("'=HYPERLINK"));assert.ok(!csv.includes(b.user.name));
  const pdf=await fetch(base+`/api/projects/${pid}/report.pdf`,{headers:{Cookie:[...a.cookies].map(([k,v])=>`${k}=${v}`).join('; ')}});assert.equal(pdf.status,200);assert.equal(new TextDecoder().decode(new Uint8Array(await pdf.arrayBuffer()).slice(0,5)),'%PDF-');
 });
 await t.test('Weekly logs and confidential peer review use real project membership',async()=>{
  const today=new Date();const monday=new Date(Date.UTC(today.getUTCFullYear(),today.getUTCMonth(),today.getUTCDate()));monday.setUTCDate(monday.getUTCDate()-((monday.getUTCDay()+6)%7));
  expect(await a.call(`/projects/${pid}/logs`,'POST',{weekStart:monday.toISOString().slice(0,10),completedWork:'Built and revised the secure project workflow.',nextPlan:'Finish the next integration milestone and documentation.',blockers:'',hours:5,taskIds:[task.id]}),201);
  const peer={period:`${new Date().getFullYear()}-${String(new Date().getMonth()+1).padStart(2,'0')}`,revieweeId:b.user.id,collaboration:4,communication:4,reliability:4,feedback:'Private evaluator feedback for testing'};
  expect(await a.call(`/projects/${pid}/peer-reviews`,'POST',{...peer,revieweeId:a.user.id}),400);
  expect(await a.call(`/projects/${pid}/peer-reviews`,'POST',peer),201);
  expect(await a.call(`/projects/${pid}/peer-reviews`,'POST',peer),409);
  const received=expect(await b.call(`/projects/${pid}/peer-reviews`),200);assert.ok(!JSON.stringify(received).includes(peer.feedback));
 });
 await t.test('Unverified GitHub claims and external URLs cannot manipulate scoring',async()=>{
  expect(await guide.call(`/projects/${pid}/repository`,'PUT',{url:'http://127.0.0.1/internal',owner:'test',name:'repo'}),400);
  expect(await guide.call(`/projects/${pid}/rubric`,'POST',{weights:{tasks:35,evidence:20,consistency:15,peer:10,repository:10,documentation:5,milestones:5}}),400);
  expect(await a.call('/integrations/github/status'),200);
 });
 await t.test('Disabled accounts lose existing sessions and administrators cannot read arbitrary projects',async()=>{
  await admin.login(email('admin')||'admin@tracksy.demo','TracksyDemo2026!');
  expect(await admin.call(`/projects/${pid}/evidence`),404);
  expect(await admin.call(`/admin/users/${admin.user.id}`,'PATCH',{enabled:false}),409);
  expect(await admin.call(`/admin/users/${outsider.user.id}`,'PATCH',{enabled:false}),200);
  expect(await outsider.call('/auth/me'),401);
 });
});
