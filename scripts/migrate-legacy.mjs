import { DatabaseSync } from 'node:sqlite';
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const source=path.join(root,'server/data/tracksy.sqlite');
if(!existsSync(source)) throw new Error('No legacy SQLite database was found.');
const out=path.join(root,'work/legacy-import');
await mkdir(out,{recursive:true});
const db=new DatabaseSync(source,{readOnly:true});
const tables=['users','projects','project_members','tasks','evidence','evidence_versions','evidence_reviews'];
const data=Object.fromEntries(tables.map(table=>[table,db.prepare(`SELECT * FROM ${table}`).all()]));
db.close();
const value=v=>v==null?'NULL':typeof v==='number'||typeof v==='boolean'?String(v):`'${String(v).replaceAll("'","''")}'`;
const timestamp=v=>v?(String(v).includes('T')?String(v):String(v).replace(' ','T')+'Z'):null;
const insert=(table,row)=>`INSERT INTO ${table} (${Object.keys(row).join(',')}) VALUES (${Object.values(row).map(value).join(',')});`;
const sql=["BEGIN; SET standard_conforming_strings=on;", "DO $$ BEGIN IF EXISTS(SELECT 1 FROM users) THEN RAISE EXCEPTION 'Legacy import requires an empty, migrated target database. Existing PostgreSQL data was left untouched.'; END IF; END $$;"];
for(const u of data.users)sql.push(insert('users',{...u,enabled:true,created_at:timestamp(u.created_at)}));
for(const p of data.projects){
 sql.push(insert('projects',{...p,status:'Active',supervision_status:'Approved',created_at:timestamp(p.created_at)}));
 sql.push(insert('score_rubrics',{project_id:p.id,version:1,tasks:40,evidence:20,consistency:15,peer:10,repository:0,documentation:10,milestones:5,created_by:p.faculty_owner_id}));
}
for(const m of data.project_members)sql.push(insert('project_members',{...m,project_role:data.users.find(u=>u.id===m.user_id)?.role==='faculty'?'faculty':'member',joined_at:timestamp(m.joined_at)}));
for(const t of data.tasks){
 const e=data.evidence.find(e=>e.task_id===t.id && e.student_id===t.assigned_to);
 const status=e?.status==='Approved'?'Done':e?.status==='Under review'?'Under review':t.status==='Done'||t.status==='Under review'?'In progress':t.status;
 sql.push(insert('tasks',{...t,status,complexity:1,created_at:timestamp(t.created_at)}));
 sql.push(insert('task_history',{task_id:t.id,actor_id:t.created_by,action:'Legacy task imported',details:status!==t.status?`Status normalized from ${t.status} to ${status} using evidence.`:'Imported with original assignment and points.'}));
}
for(const e of data.evidence)sql.push(insert('evidence',{...e,created_at:timestamp(e.created_at),updated_at:timestamp(e.updated_at)}));
for(const v of data.evidence_versions)sql.push(insert('evidence_versions',{...v,submitted_at:timestamp(v.submitted_at)}));
for(const r of data.evidence_reviews)sql.push(insert('evidence_reviews',{...r,quality:null,reviewed_at:timestamp(r.reviewed_at)}));
for(const p of data.projects)sql.push(insert('audit_events',{project_id:p.id,actor_id:p.faculty_owner_id,action:'Legacy project imported',entity_type:'Project',entity_id:p.id,details:'Original SQLite records preserved. Historical reviews have no numerical quality grade; none was invented.'}));
for(const table of ['users','evidence_versions','evidence_reviews'])sql.push(`SELECT setval(pg_get_serial_sequence('${table}','id'),COALESCE((SELECT MAX(id) FROM ${table}),1),(SELECT COUNT(*)>0 FROM ${table}));`);
sql.push('COMMIT;');
const sqlPath=path.join(out,'migration.sql');
await writeFile(sqlPath,sql.join('\n'));
await writeFile(path.join(out,'manifest.json'),JSON.stringify({source,tables:Object.fromEntries(tables.map(t=>[t,data[t].length])),createdAt:new Date().toISOString(),notes:['Original password hashes preserved; successful login upgrades them to BCrypt.','Historical review quality remains ungraded.','Target must be empty; import rolls back entirely on error.']},null,2));
console.log('Legacy migration prepared:',Object.fromEntries(tables.map(t=>[t,data[t].length])));
if(process.argv.includes('--apply')){
 const settings=JSON.parse((await readFile(path.join(root,'work/runtime/local-settings.json'),'utf8')).replace(/^\uFEFF/,''));
 const psql=path.join(root,'work/runtime/postgresql/pgsql/bin/psql.exe');
 const result=spawnSync(psql,['-X','-w','-h','127.0.0.1','-p',String(settings.port),'-U',settings.username,'-d','tracksy','-v','ON_ERROR_STOP=1','-f',sqlPath],{env:{...process.env,PGPASSWORD:settings.password,PGCONNECT_TIMEOUT:'5'},encoding:'utf8',timeout:30000});
 if(result.status!==0)throw new Error(result.stderr||'Legacy migration failed. PostgreSQL transaction rolled back.');
 const uploadTarget=path.join(root,'work/uploads'); await mkdir(uploadTarget,{recursive:true});
 for(const v of data.evidence_versions){if(v.stored_filename){const f=path.basename(v.stored_filename); const old=path.join(root,'server/uploads',f); if(existsSync(old))await copyFile(old,path.join(uploadTarget,f));}}
 console.log('Original records imported successfully. Historical review quality remains ungraded.');
}
