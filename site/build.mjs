import {readFileSync,writeFileSync,mkdirSync,readdirSync,rmSync} from "node:fs";
import {dirname,join,relative} from "node:path";
import {fileURLToPath} from "node:url";
import {qrMatrix,qrSvg} from "./qr.mjs";
import {renderSite} from "./render.mjs";
const root=dirname(fileURLToPath(import.meta.url)),output=join(root,"public");
const catalog=JSON.parse(readFileSync(join(root,"edition-v1.json"),"utf8"));
if(catalog.base_url!=="https://sasan-yavari.github.io/ContractForge-BookEdition/")throw Error("Unexpected base URL");
if(catalog.chapters.map(c=>c.number).join(",")!==Array.from({length:17},(_,i)=>i+1).join(","))throw Error("Expected 17 chapters in order");
const examples={};
for(const c of catalog.chapters)for(const r of c.resources)if(r.example_path){
  if(!/^examples\/(3|14)\/[a-zA-Z0-9_.-]+$/.test(r.example_path))throw Error("Unexpected example path");
  examples[r.example_path]=readFileSync(join(root,r.example_path),"utf8");
}
const files=renderSite(catalog,examples,qrMatrix,qrSvg);
files["assets/style.css"]=readFileSync(join(root,"style.css"),"utf8");
let localLinks=0;
for(const [path,text]of Object.entries(files)){
  if(path.split("/").includes(".."))throw Error("Unsafe generated path");
  if(/https:\/\/github\.com\/Sasan-Yavari\/(AI-Books|ContractForge)(?:\/|")/.test(text))throw Error("Private repository link");
  if(!path.endsWith(".html"))continue;
  for(const m of text.matchAll(/(?:href|src)="([^"]+)"/g)){
    const href=m[1].replaceAll("&amp;","&");if(href.startsWith("https://"))continue;
    if(/^[a-z]+:/i.test(href))throw Error("Unexpected URL scheme");
    const [target,fragment]=href.split("#"),parts=path.split("/").slice(0,-1);
    for(const part of target.split("/")){if(part==="..")parts.pop();else if(part&&part!==".")parts.push(part);}
    let dest=target?parts.join("/"):path;if(target.endsWith("/"))dest+=(dest?"/":"")+"index.html";
    if(!Object.hasOwn(files,dest))throw Error("Missing local target: "+path+" -> "+href);
    if(fragment&&!files[dest].includes('id="'+fragment+'"'))throw Error("Missing fragment: "+href);
    localLinks++;
  }
}
const list=dir=>readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?list(join(dir,e.name)):[relative(output,join(dir,e.name)).replaceAll("\\","/")]);
if(process.argv.includes("--check")){
  for(const [path,text]of Object.entries(files))if(readFileSync(join(output,path),"utf8")!==text)throw Error("Generated file differs: "+path);
  if(list(output).sort().join("\n")!==Object.keys(files).sort().join("\n"))throw Error("Unexpected generated file set");
}else{
  rmSync(output,{recursive:true,force:true});
  for(const [path,text]of Object.entries(files)){const dest=join(output,path);mkdirSync(dirname(dest),{recursive:true});writeFileSync(dest,text);}
}
console.log(JSON.stringify({chapters:17,files:Object.keys(files).length,localLinks,qrCodes:18}));
