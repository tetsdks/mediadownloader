/* media_downloader page script: tiktok.
 * Injected by ScriptLibrary with evaluateJavascript, after a prelude that sets window.mksLowEnd and
 * window.mksScanScale (and, for the generic script, window.mksSingle / window.mksParserSite).
 * Talks to the app through the MediaDownloaderBridge JavaScript interface. */
try{
if(window.mksTtInit){ }else{
function mksTtLine(t){return (t||'')
.replace(/[\uDB80-\uDBFF][\uDC00-\uDFFF]/g,'')
.replace(/[\uE000-\uF8FF\u200E\u200F\u061C]/g,'').trim();}
function mksTtCard(){
var vids=document.getElementsByTagName('video');var best=null,bestA=0;
for(var i=0;i<vids.length;i++){var r=vids[i].getBoundingClientRect();
var vh=Math.max(0,Math.min(r.bottom,window.innerHeight)-Math.max(r.top,0));
var vw=Math.max(0,Math.min(r.right,window.innerWidth)-Math.max(r.left,0));
if(vh*vw>bestA){bestA=vh*vw;best=vids[i];}}
return best;}
function mksTtText(el){
var node=el;
for(var d=0;d<14&&node;d++){
var raw=(node.innerText||'').split('\n');var parts=[];
for(var k=0;k<raw.length&&parts.length<5;k++){
var line=mksTtLine(raw[k]);if(!line)continue;
/* the rail carries the counts and the actions, none of which name the video */
if(/^[0-9][0-9.,]*[KMB]?$/.test(line))continue;
if(line==='Follow'||line==='Log in'||line==='Sign up'||line==='Open app')continue;
parts.push(line);}
var text=parts.join(' ').replace(/\s+/g,' ').trim();
if(text.length>=10)return text.substring(0,120);
node=node.parentElement;}
return '';}
function mksTtPoster(el){
var p=el.getAttribute('poster');if(p&&p.indexOf('http')===0)return p;
var node=el;
for(var d=0;d<8&&node;d++){
if(node.querySelectorAll){
/* No size test: tiktok keeps its cover behind the player, so the element measures zero.
   Avatars are the only other picture on a card, and they say so in the url. */
var imgs=node.querySelectorAll('img');
for(var k=0;k<imgs.length;k++){var src=imgs[k].getAttribute('src');
if(src&&src.indexOf('http')===0&&src.indexOf('avatar')<0&&src.indexOf('100x100')<0)return src;}
/* and it is often a css background rather than an img at all */
var all=node.querySelectorAll('*');
for(var j=0;j<all.length&&j<80;j++){
var bg=window.getComputedStyle(all[j]).backgroundImage;
if(bg&&bg.indexOf('url("http')===0)return bg.substring(5,bg.length-2);}}
node=node.parentElement;}
return '';}
var mksTtLast='';var mksTtSince=0;
function mksTtScan(){try{
var v=mksTtCard();if(!v)return;
var t=mksTtText(v);var th=mksTtPoster(v);
var key=t+'|'+th;
if(!t&&!th)return;
/* Re-offered every so often even when nothing changed: the sniffer usually sees the video
   before the card has rendered, so the model it made is waiting to be named. */
mksTtSince++;
if(key===mksTtLast&&mksTtSince<5)return;
mksTtLast=key;mksTtSince=0;
console.log('[mks] tt card title='+t.substring(0,60)+' poster='+(th?'yes':'no'));
MediaDownloaderBridge.feedCardChanged(t,th);
}catch(e2){console.log('[mks] tt scan failed: '+e2);}}
mksTtScan();
setInterval(function(){ if(!document.hidden){ mksTtScan(); } }, 700 * (window.mksScanScale || 1));
window.mksTtInit=1;
console.log('[mks] tiktok script ready');
}
}catch(e){console.log('[mks] tiktok script failed: '+e);}
