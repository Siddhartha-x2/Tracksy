import { copyFile, mkdir, readdir, readFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime=path.join(root,'work/runtime');
const output=path.join(root,'outputs');
const stamp=new Date().toISOString().replaceAll(':','-').replaceAll('.','-');
const staging=path.join(root,'work/packages',stamp);
const temporary=path.join(root,'work/package-temp',stamp);
const project=path.join(staging,'Tracksy');
const archive=path.join(output,'Tracksy_Upgrade_Source.zip');
const sourceDirectories=['frontend','backend','scripts','tests','docs'];
const rootFiles=['README.md','package.json','package-lock.json','Dockerfile','compose.yaml','.env.example','.gitignore','.gitattributes','.dockerignore','START_TRACKSY.cmd'];
const required=['Tracksy/frontend/src/App.tsx','Tracksy/frontend/package-lock.json','Tracksy/backend/pom.xml','Tracksy/backend/src/main/resources/db/migration/V6__assessment_cutoffs.sql','Tracksy/scripts/local.mjs','Tracksy/docs/verification.md'];
function inside(file,parent) { return path.resolve(file).startsWith(path.resolve(parent)+path.sep); }
if(!inside(staging,path.join(root,'work/packages'))||!inside(archive,output))throw new Error('Package paths must remain in this workspace.');
const sourceFiles=[];
async function collect(relative) {
  const folder=path.join(root,relative);
  for(const entry of await readdir(folder,{withFileTypes:true})) {
    const name=path.posix.join(relative,entry.name);
    if(entry.isSymbolicLink())throw new Error('Source archives cannot contain symbolic links: '+name);
    if(entry.isDirectory()){
      if(['node_modules','dist','target','.git'].includes(entry.name)||name==='backend/src/main/resources/static')continue;
      await collect(name);
    }else if(entry.isFile()&&!entry.name.endsWith('.tsbuildinfo')&&!entry.name.endsWith('.log'))sourceFiles.push(name);
  }
}
for(const directory of sourceDirectories)await collect(directory);
sourceFiles.push(...rootFiles);
await mkdir(project,{recursive:true});await mkdir(output,{recursive:true});
await mkdir(temporary,{recursive:true});
for(const relative of sourceFiles) {
  const source=path.join(root,relative),destination=path.join(project,relative);
  if(!inside(source,root)||!inside(destination,project))throw new Error('A source path escaped the package directory.');
  await mkdir(path.dirname(destination),{recursive:true});await copyFile(source,destination);
}
const jdk=(await readdir(runtime,{withFileTypes:true})).find(entry=>entry.isDirectory()&&entry.name.startsWith('jdk-'));
if(!jdk)throw new Error('Java 21 tools are missing. Run npm run setup.');
const jar=path.join(runtime,jdk.name,'bin/jar.exe');
function run(args) {
  const result=spawnSync(jar,[`-J-Djava.io.tmpdir=${temporary}`,...args],{cwd:root,encoding:'utf8',windowsHide:true,timeout:30000});
  if(result.error||result.status!==0)throw new Error(result.error?.message||result.stderr||'Archive utility failed.');
  return result.stdout;
}
// The JDK archive tool writes a standard ZIP. Source hierarchy is retained.
run(['--create','--no-manifest','--file',archive,'-C',staging,'.']);
const entries=run(['--list','--file',archive]).split(/\r?\n/).filter(Boolean).map(name=>name.replaceAll('\\','/'));
for(const name of required)if(!entries.includes(name))throw new Error('Archive is missing '+name);
if(entries.some(name=>/(^|\/)(node_modules|work|server|target|dist)\/|\.sqlite$|(^|\/)\.env$/.test(name)))throw new Error('Archive contains private or generated files.');
for(const [source,destination]of [['docs/implementation.md','Tracksy_Implementation.md'],['docs/verification.md','Tracksy_Verification.md'],['work/full-build.log','Tracksy_Build.log']])await copyFile(path.join(root,source),path.join(output,destination));
const size=(await stat(archive)).size;
const checksum=createHash('sha256').update(await readFile(archive)).digest('hex');
console.log(JSON.stringify({archive,sourceFiles:sourceFiles.length,bytes:size,sha256:checksum,structureVerified:true},null,2));
