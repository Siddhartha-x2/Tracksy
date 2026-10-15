import { spawn } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { existsSync, createReadStream, createWriteStream } from 'node:fs';
import { readFile, writeFile, mkdir, readdir, cp, rm } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime=path.join(root,'work/runtime');
const command=process.argv[2]||'start';
if(Number(process.versions.node.split('.')[0])<24) throw new Error('Tracksy requires Node.js 24 or newer.');

function execute(file,args=[],options={}) {
  return new Promise((resolve,reject)=>{
    const child=spawn(file,args,{cwd:root,env:options.env||process.env,windowsHide:true,stdio:options.capture||options.input!==undefined?['pipe','pipe','pipe']:'inherit',timeout:options.timeout||0});
    let stdout='',stderr='';
    if(child.stdout)child.stdout.on('data',chunk=>stdout+=chunk);
    if(child.stderr)child.stderr.on('data',chunk=>stderr+=chunk);
    if(child.stdin)child.stdin.end(options.input||'');
    child.on('error',reject);
    child.on('close',code=>{
      if(code!==0&&!options.allowFailure)reject(new Error(`${path.basename(file)} failed (${code ?? 'interrupted'}). ${stderr.trim()}`));
      else resolve({code,stdout,stderr});
    });
  });
}
function npm(args) {
  const cli=process.env.npm_execpath||path.join(path.dirname(process.execPath),'node_modules/npm/bin/npm-cli.js');
  if(!existsSync(cli))throw new Error('npm was not found. Install the official Node.js distribution.');
  return execute(process.execPath,[cli,...args]);
}
async function directory(prefix) {
  if(!existsSync(runtime))return null;
  const entries=await readdir(runtime,{withFileTypes:true});
  const found=entries.find(entry=>entry.isDirectory()&&entry.name.startsWith(prefix));
  return found?path.join(runtime,found.name):null;
}
async function java() {
  const jdk=await directory('jdk-');
  if(!jdk)throw new Error('Java 21 tools are missing. Run npm run setup.');
  return {jdk,file:path.join(jdk,'bin/java.exe')};
}
async function settings() {return JSON.parse((await readFile(path.join(runtime,'local-settings.json'),'utf8')).replace(/^\uFEFF/,''));}
async function download(url,file,algorithm,checksum) {
  const response=await fetch(url,{signal:AbortSignal.timeout(300000)});
  if(!response.ok)throw new Error(`Tool download failed (${response.status}): ${new URL(url).hostname}`);
  await pipeline(Readable.fromWeb(response.body),createWriteStream(file));
  if(algorithm){
    const hash=createHash(algorithm);for await(const chunk of createReadStream(file))hash.update(chunk);
    if(hash.digest('hex')!==checksum.trim().toLowerCase())throw new Error('Downloaded tool checksum does not match the vendor.');
  }
}
async function extract(file,destination) {
  const literal=value=>`'${value.replaceAll("'","''")}'`;
  await execute('powershell.exe',['-NoProfile','-NonInteractive','-Command',`Expand-Archive -LiteralPath ${literal(file)} -DestinationPath ${literal(destination)} -Force`]);
}
async function setup() {
  if(process.platform!=='win32')throw new Error('Use the Docker instructions on Linux/macOS. The portable tool download is for Windows.');
  await mkdir(runtime,{recursive:true});
  if(!await directory('jdk-')){
    console.log('Downloading official Eclipse Temurin Java 21...');
    const response=await fetch('https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse');
    if(!response.ok)throw new Error('Adoptium tool manifest is unavailable.');
    const assets=await response.json();const pkg=assets[0]?.binary?.package;
    if(!pkg?.link||!pkg.checksum)throw new Error('Java tool manifest did not contain the Windows JDK.');
    const archive=path.join(runtime,'jdk21.zip');await download(pkg.link,archive,'sha256',pkg.checksum);await extract(archive,runtime);
  }
  if(!await directory('apache-maven-')){
    console.log('Downloading official Apache Maven...');
    const url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip';
    const response=await fetch(url+'.sha512');if(!response.ok)throw new Error('Maven checksum is unavailable.');
    const archive=path.join(runtime,'maven.zip');await download(url,archive,'sha512',await response.text());await extract(archive,runtime);
  }
  if(!existsSync(path.join(runtime,'postgresql/pgsql/bin/pg_ctl.exe'))){
    console.log('Downloading official PostgreSQL 17 Windows binaries...');
    const archive=path.join(runtime,'postgresql.zip');await download('https://sbp.enterprisedb.com/getfile.jsp?fileid=1260616',archive);
    await extract(archive,path.join(runtime,'postgresql'));
  }
  console.log('Workspace tools are ready. Run npm run dev.');
}
async function database() {
  const bin=path.join(runtime,'postgresql/pgsql/bin'),data=path.join(runtime,'pg-data'),config=path.join(runtime,'local-settings.json');
  if(!existsSync(path.join(bin,'initdb.exe')))throw new Error('PostgreSQL tools are missing. Run npm run setup.');
  if(!existsSync(config))await writeFile(config,JSON.stringify({port:54329,username:'tracksy',password:randomBytes(32).toString('base64'),adminPassword:randomBytes(32).toString('base64')},null,2));
  const s=await settings();
  if(!existsSync(path.join(data,'PG_VERSION'))){
    const passwordFile=path.join(runtime,'bootstrap-password.txt');await writeFile(passwordFile,s.adminPassword);
    try{
      await execute(path.join(bin,'initdb.exe'),['-D',data,'-U','postgres','-A','scram-sha-256','-E','UTF8','--locale=C',`--pwfile=${passwordFile}`]);
      await writeFile(path.join(data,'tracksy.conf'),`listen_addresses='127.0.0.1'\nport=${s.port}\nmax_connections=100\n`);
      const base=await readFile(path.join(data,'postgresql.conf'),'utf8');await writeFile(path.join(data,'postgresql.conf'),base+"\ninclude='tracksy.conf'\n");
    }finally{await rm(passwordFile,{force:true});}
  }
  const env={...process.env,PGPASSWORD:s.adminPassword,PGCONNECT_TIMEOUT:'5'};
  const query=async(sql)=>execute(path.join(bin,'psql.exe'),['-X','-w','-h','127.0.0.1','-p',String(s.port),'-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-tA'],{capture:true,input:sql+';\n',env,timeout:15000});
  // A successful connection is more reliable than a process-ID check on Windows.
  // Never start a second database after an access-denied, authentication or timeout error.
  try { await query('SELECT 1'); }
  catch(error) {
    if(!/Connection refused|10061/i.test(error.message))throw new Error('Cannot reach the local PostgreSQL service. '+error.message);
    const status=await execute(path.join(bin,'pg_ctl.exe'),['-D',data,'status'],{capture:true,allowFailure:true,timeout:10000});
    if(status.code===0)throw new Error('PostgreSQL reports that it is running but is not accepting connections. Read work/runtime/postgresql.log; a second copy was not started.');
    await execute(path.join(bin,'pg_ctl.exe'),['-D',data,'-l',path.join(runtime,'postgresql.log'),'-w','start'],{timeout:60000});
    await query('SELECT 1');
  }
  if(!(await query("SELECT 1 FROM pg_roles WHERE rolname='tracksy'")).stdout.trim())await query(`CREATE ROLE tracksy LOGIN PASSWORD '${s.password.replaceAll("'","''")}'`);
  for(const name of ['tracksy','tracksy_test'])if(!(await query(`SELECT 1 FROM pg_database WHERE datname='${name}'`)).stdout.trim())await query(`CREATE DATABASE ${name} OWNER tracksy`);
  console.log(`PostgreSQL ready on 127.0.0.1:${s.port}.`);
  return s;
}
async function build() {
  const {jdk,file}=await java(),maven=await directory('apache-maven-');
  if(!maven)throw new Error('Maven is missing. Run npm run setup.');
  if(!existsSync(path.join(root,'frontend/node_modules/typescript')))await npm(['--prefix','frontend','ci','--no-fund','--no-audit']);
  await npm(['--prefix','frontend','run','build']);
  const target=path.join(root,'backend/src/main/resources/static');
  if(!target.startsWith(root+path.sep))throw new Error('Static output path is outside the project.');
  await rm(target,{recursive:true,force:true});await cp(path.join(root,'frontend/dist'),target,{recursive:true});
  // This is the same official Java launcher used by Maven's startup script.
  // Invoke it directly so Windows script policies and cmd quoting are unnecessary.
  await execute(file,['--enable-native-access=ALL-UNNAMED','-classpath',path.join(maven,'boot/*'),`-Dclassworlds.conf=${path.join(maven,'bin/m2.conf')}`,`-Dmaven.home=${maven}`,`-Dlibrary.jansi.path=${path.join(maven,'lib/jansi-native')}`,`-Dmaven.multiModuleProjectDirectory=${path.join(root,'backend')}`,'org.codehaus.plexus.classworlds.launcher.Launcher','-f',path.join(root,'backend/pom.xml'),`-Dmaven.repo.local=${path.join(runtime,'maven-cache')}`,'-B','package'],{env:{...process.env,JAVA_HOME:jdk}});
}
async function localEnvironment(databaseName,uploadName) {
  const s=await settings();let integration={};
  const allowed=new Set(['GITHUB_CLIENT_ID','GITHUB_CLIENT_SECRET','GITHUB_REDIRECT_URI','COOKIE_SECURE','APP_ORIGINS','SMTP_HOST','SMTP_PORT','SMTP_USER','SMTP_PASSWORD','SMTP_FROM','SMTP_SSL','PUBLIC_APP_URL']);
  if(existsSync(path.join(root,'.env')))for(const line of (await readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/)){
    const match=line.match(/^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$/);
    if(match&&allowed.has(match[1])){
      let value=match[2].trim();if(value.length>=2&&((value.startsWith('"')&&value.endsWith('"'))||(value.startsWith("'")&&value.endsWith("'"))))value=value.slice(1,-1);
      integration[match[1]]=value;
    }
  }
  return {...process.env,...integration,DB_URL:`jdbc:postgresql://127.0.0.1:${s.port}/${databaseName}`,DB_USER:s.username,DB_PASSWORD:s.password,TRACKSY_UPLOAD_DIR:path.join(root,'work',uploadName),SPRING_PROFILES_ACTIVE:'demo',TRACKSY_BIND_ADDRESS:'127.0.0.1'};
}
async function start(rebuild) {
  const base='http://127.0.0.1:4000';
  try {
    const response=await fetch(base+'/api/health',{signal:AbortSignal.timeout(2000)});
    const health=await response.json().catch(()=>null);
    if(response.ok&&health?.status==='ok'&&health?.app==='Tracksy'){
      console.log(`Tracksy is already running: ${base}/`);return;
    }
    throw new Error('Port 4000 already has a server that is not ready. Check its terminal before starting another copy.');
  }catch(error){
    if(error.cause?.code==='EACCES')throw new Error('Windows denied access to localhost. Run START_TRACKSY.cmd from File Explorer and keep its window open.');
    if(error.message.startsWith('Port 4000'))throw error;
    if(error.cause?.code!=='ECONNREFUSED')throw new Error('The localhost connection could not be checked ('+(error.cause?.code||error.name)+'). A second server was not started. Run START_TRACKSY.cmd from File Explorer.');
  }
  if(rebuild)await build();
  const jar=path.join(root,'backend/target/tracksy-1.0.0.jar');if(!existsSync(jar))throw new Error('Build is missing. Run npm run dev first.');
  await database();const {file}=await java();
  const logPath=path.join(runtime,'application.log');
  const log=createWriteStream(logPath,{flags:'w'});
  console.log('Starting Tracksy. Keep this terminal open.');
  console.log('Startup log: '+logPath);
  const child=spawn(file,['-jar',jar,'--server.port=4000'],{cwd:root,env:await localEnvironment('tracksy','uploads'),windowsHide:true,stdio:['inherit','pipe','pipe']});
  child.stdout.on('data',chunk=>{process.stdout.write(chunk);log.write(chunk);});
  child.stderr.on('data',chunk=>{process.stderr.write(chunk);log.write(chunk);});
  let exited=false,exitCode,spawnError;
  const finished=new Promise(resolve=>{
    child.once('error',error=>{spawnError=error;exited=true;resolve();});
    child.once('close',code=>{exitCode=code;exited=true;resolve();});
  });
  let announced=false,lastProblem='Server did not respond.';
  try{
    const deadline=Date.now()+60000;
    while(Date.now()<deadline&&!exited){
      try{
        const response=await fetch(base+'/api/health',{signal:AbortSignal.timeout(2000)});
        const health=await response.json().catch(()=>null);
        if(response.ok&&health?.status==='ok'&&health?.app==='Tracksy'){
          announced=true;console.log(`\nTracksy is ready: ${base}/\nKeep this window open while you use the website.\n`);break;
        }
        lastProblem=`Readiness returned HTTP ${response.status}.`;
      }catch(error){
        lastProblem=error.cause?.code||error.message;
        if(error.cause?.code==='EACCES')throw new Error('Windows denied the localhost readiness connection. Run START_TRACKSY.cmd directly from File Explorer.');
      }
      await new Promise(resolve=>setTimeout(resolve,500));
    }
    if(spawnError)throw spawnError;
    if(!announced){
      if(!exited)child.kill();
      throw new Error(`Tracksy did not become ready. ${exited?'Java exited with code '+exitCode+'.':lastProblem} Read ${logPath}`);
    }
    await finished;
    if(spawnError)throw spawnError;
    if(exitCode!==0&&exitCode!==130)throw new Error(`Tracksy stopped (Java exit ${exitCode}). Keep its terminal open; startup details are in ${logPath}`);
  }finally{
    if(!announced&&!exited){child.kill();await finished;}
    log.end();
  }
}
async function integrationTest() {
  await build();await database();const {file}=await java();const env=await localEnvironment('tracksy_test','test-uploads');
  const out=createWriteStream(path.join(runtime,'test-server.log')),err=createWriteStream(path.join(runtime,'test-server-errors.log'));
  const child=spawn(file,['-jar',path.join(root,'backend/target/tracksy-1.0.0.jar'),'--server.port=4010'],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  child.stdout.pipe(out);child.stderr.pipe(err);
  let startupLog='',started=false;
  child.stdout.on('data',chunk=>{startupLog=(startupLog+chunk).slice(-32768);started ||= startupLog.includes('Started TracksyApplication');});
  let spawnError;child.on('error',error=>spawnError=error);
  const cleanup=()=>{if(child.exitCode===null)child.kill();};process.once('SIGINT',cleanup);process.once('SIGTERM',cleanup);
  try{
    const base='http://127.0.0.1:4010';let ready=false;
    for(let i=0;i<60;i++){
      if(spawnError)throw spawnError;if(child.exitCode!==null)throw new Error('Test backend exited. Read work/runtime/test-server.log.');
      try{if(started){const r=await fetch(base+'/api/health',{signal:AbortSignal.timeout(1000)});if(r.ok&&(await r.json()).status==='ok'){ready=true;break;}}}catch{}
      await new Promise(resolve=>setTimeout(resolve,500));
    }
    if(!ready)throw new Error('Test backend did not become ready. Read work/runtime/test-server.log.');
    const s=await settings();
    await execute(path.join(runtime,'postgresql/pgsql/bin/psql.exe'),['-X','-w','-h','127.0.0.1','-p',String(s.port),'-U',s.username,'-d','tracksy_test','-v','ON_ERROR_STOP=1','-f',path.join(root,'tests/schema-integrity.sql')],{env:{...process.env,PGPASSWORD:s.password,PGCONNECT_TIMEOUT:'5'}});
    await execute(process.execPath,['--test','tests/workflow.test.mjs'],{env:{...process.env,TEST_BASE_URL:base}});
  }finally{cleanup();process.removeListener('SIGINT',cleanup);process.removeListener('SIGTERM',cleanup);}
}
async function backup() {
  const s=await settings();const destination=path.join(root,'work/backups',new Date().toISOString().replaceAll(':','-'));
  await mkdir(destination,{recursive:true});
  await execute(path.join(runtime,'postgresql/pgsql/bin/pg_dump.exe'),['-w','-h','127.0.0.1','-p',String(s.port),'-U',s.username,'-d','tracksy','-Fc','-f',path.join(destination,'database.dump')],{env:{...process.env,PGPASSWORD:s.password,PGCONNECT_TIMEOUT:'5'}});
  const uploads=path.join(root,'work/uploads');if(existsSync(uploads))await cp(uploads,path.join(destination,'uploads'),{recursive:true});
  await writeFile(path.join(destination,'manifest.json'),JSON.stringify({database:'tracksy',createdAt:new Date().toISOString(),applicationVersion:'1.0.0'},null,2));
  console.log('Backup saved to '+destination);
}
try{
  if(command==='setup')await setup();else if(command==='build')await build();else if(command==='database')await database();
  else if(command==='dev')await start(true);else if(command==='start')await start(false);else if(command==='test')await integrationTest();else if(command==='backup')await backup();
  else throw new Error('Unknown local command: '+command);
}catch(error){
  console.error('\n'+error.message);
  try{
    await mkdir(runtime,{recursive:true});
    const errorLog=path.join(runtime,'launcher-error.log');
    await writeFile(errorLog,new Date().toISOString()+'\n'+error.message+'\n');
    console.error('Launcher error saved to '+errorLog);
  }catch{}
  process.exitCode=1;
}
