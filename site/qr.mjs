// Fixed QR Model 2 version 4-M, byte mode, mask 0.
// Two 32-byte data blocks, each with 18 Reed-Solomon parity bytes.
export function qrMatrix(text) {
  if (!/^[\x20-\x7e]{1,62}$/.test(text)) throw new Error("Expected 1-62 printable ASCII characters");
  const bits=[], push=(n,k)=>{for(let i=k-1;i>=0;i--)bits.push((n>>>i)&1);};
  push(4,4); push(text.length,8);
  for(let i=0;i<text.length;i++)push(text.charCodeAt(i),8);
  push(0,Math.min(4,512-bits.length));
  while(bits.length%8)bits.push(0);
  const data=[];
  for(let i=0;i<bits.length;i+=8)data.push(bits.slice(i,i+8).reduce((a,b)=>(a<<1)|b,0));
  for(let p=0;data.length<64;p++)data.push(p%2?0x11:0xec);
  const mul=(a,b)=>{let v=0;for(let i=7;i>=0;i--){v=(v<<1)^((v>>>7)*0x11d);v^=((b>>>i)&1)*a;}return v;};
  const divisor=Array(18).fill(0);divisor[17]=1;
  for(let i=0,root=1;i<18;i++,root=mul(root,2))for(let j=0;j<18;j++)divisor[j]=mul(divisor[j],root)^(j+1<18?divisor[j+1]:0);
  const blocks=[data.slice(0,32),data.slice(32)];
  const parity=blocks.map(block=>{const r=Array(18).fill(0);for(const byte of block){const f=byte^r.shift();r.push(0);for(let i=0;i<18;i++)r[i]^=mul(divisor[i],f);}return r;});
  const words=[];for(let i=0;i<32;i++)for(let j=0;j<2;j++)words.push(blocks[j][i]);for(let i=0;i<18;i++)for(let j=0;j<2;j++)words.push(parity[j][i]);
  const size=33,m=Array.from({length:size},()=>Array(size).fill(false)),reserved=Array.from({length:size},()=>Array(size).fill(false));
  const set=(x,y,v)=>{if(x>=0&&y>=0&&x<size&&y<size){m[y][x]=Boolean(v);reserved[y][x]=true;}};
  for(let i=0;i<size;i++){set(6,i,i%2===0);set(i,6,i%2===0);}
  for(const [cx,cy] of [[3,3],[29,3],[3,29]])for(let dy=-4;dy<=4;dy++)for(let dx=-4;dx<=4;dx++){const d=Math.max(Math.abs(dx),Math.abs(dy));set(cx+dx,cy+dy,d!==2&&d!==4);}
  for(let dy=-2;dy<=2;dy++)for(let dx=-2;dx<=2;dx++)set(26+dx,26+dy,Math.max(Math.abs(dx),Math.abs(dy))!==1);
  const bit=i=>(0x5412>>>i)&1;
  for(let i=0;i<=5;i++)set(8,i,bit(i));
  set(8,7,bit(6));set(8,8,bit(7));set(7,8,bit(8));
  for(let i=9;i<15;i++)set(14-i,8,bit(i));
  for(let i=0;i<8;i++)set(size-1-i,8,bit(i));
  for(let i=8;i<15;i++)set(8,size-15+i,bit(i));
  set(8,size-8,true);
  let index=0;
  for(let right=size-1;right>=1;right-=2){if(right===6)right=5;for(let v=0;v<size;v++)for(let j=0;j<2;j++){
    const x=right-j,y=((right+1)&2)===0?size-1-v:v;if(reserved[y][x])continue;
    const b=index<800?((words[index>>>3]>>>(7-(index&7)))&1):0;
    m[y][x]=Boolean(b^((x+y)%2===0?1:0));index++;
  }}
  if(index!==807)throw new Error("Unexpected QR module count");
  return m.map(row=>row.map(Number).join(""));
}
export function qrSvg(payload, rows) {
  const esc=s=>s.replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll('"',"&quot;");
  const path=rows.flatMap((row,y)=>[...row].flatMap((b,x)=>b==="1"?[`M${x+4} ${y+4}h1v1h-1z`]:[])).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 41 41" width="164" height="164" role="img" aria-label="QR code for ${esc(payload)}" shape-rendering="crispEdges"><title>${esc(payload)}</title><rect width="41" height="41" fill="white"/><path d="${path}" fill="black"/></svg>\n`;
}
