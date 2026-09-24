/* media_downloader page script: twitter.
 * Injected by ScriptLibrary with evaluateJavascript, after a prelude that sets window.mksLowEnd and
 * window.mksScanScale (and, for the generic script, window.mksSingle / window.mksParserSite).
 * Talks to the app through the MediaDownloaderBridge JavaScript interface. */
    var animStyle = document.createElement('style');
    animStyle.innerHTML = `
        /* Smooth pulsing effect */
        @keyframes pulse {
            0%   { transform: scale(1); opacity: 0.9; }
            50%  { transform: scale(1.15); opacity: 1; }
            100% { transform: scale(1); opacity: 0.9; }
        }

        .downbtn, .vbtn, .redbtn {
            animation: pulse 1.5s ease-in-out infinite;
            transition: transform 0.2s ease;
            border-radius: 50%;
        }

        .downbtn:hover, .vbtn:hover, .redbtn:hover {
            transform: scale(1.25);
        }
    `;
    /* This is the first statement of the facebook, instagram and pexels scripts, and
       document.head is null while the document is still being built - onLoadResource injects
       long before that. It threw here and took the whole script with it, silently. Falling back
       to documentElement is enough, and a later injection retries anyway. */
    var mksStyleRoot = document.head || document.documentElement;
    if(mksStyleRoot && !window.mksLowEnd){ mksStyleRoot.appendChild(animStyle); }
function setStyle(obj,css){for(var attr in css){obj.style[attr]=css[attr];}}function styledata(topnum){return{'display':'block','background':"url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAHYAAAB2CAMAAAAqeZcjAAAArlBMVEUAAAD/OTn/ODj/ODj/VVX/ODj/ODj/ODj/OTn/OTn/ODj/ODj/PDz/Rkb/Ozv/Pj7/ODj/OTn/ODj/ODj/OTn/OTn/OTn/OTn/OTn/OTn/OTn/OTn/ODj/ODj/Ojr/PT3/PT3/Q0P/VVX/ODj/ODj/////0ND/WFj/iIj/8fH/+/v/c3P/QED/3Nz/9vb/Ozv/t7f/SEj/ycn/fn7/ZGT/1tb/wcH/bGz/jo7/m5vNeQRzAAAAJHRSTlMA/MeeBPPf0biloogoCx4h+e3s49ypinhvY1lRTUQ5LhkTBtUAYTEFAAADEUlEQVRo3t3bWVfaUBSG4Z3EDEAS5llE8dNSp6rV1v7/P9a1vHCTFQpn3Cv0ufXiXUBikpN9SNcmX06jLEyTQa83SNIwi6bLfEM+deetUYA9glFr3iUfikWU4KAkWhSOo6t2DAVxe+WuWXaGUDbslE6ieRRASxDl1tH1OIC2YLy2il62YKh1aRw9m8UwFs/OzKoXKaykFyYfdQJrE+0PXIZwINQ8mc77cKJ/rlPtwJmO+s/ahkNtxR+4yOBUpnSBuArhWHilUL2Gc9dHu0UID8LiyNGUwYvs8HHVhidtrfNV5Pw9h0f//H9V9uFRv9xfPQvhVbj/sJrAs8neqzq8u6hXz1J4l9a/5hkEzGr3iDEExJdU1YKIVrW6hobX25p7KFpXsmNo+H5Ts4Wi8W41D6SyQU4sglQWEVfLAJDKBmXleieSrV4Bh2Des8OvFQIw/1msqncyIlm+vyliMIFsXHxmF2ASWSyqJ61Elk/dBEwkm3yu6IHJZNElojmYUHZevdL6z/JVdwQmlB0RbQIwoWywoRxMKoucllDx8m3HUz37Z/fvDzhmSVOoeOPUUc84akoRdLv2VUSUwW33+QeOyyiEot9PzqoIKYXL7i+lKlJKoNu1ryKhATS6d26qGFAPGu7vnFTR46x191W5ih5/ybbd1wcoG/Ahpdy1ryKhFJp+3llXkVIIZt691aoipAxMuWtZRaZ1u8pduyqi2oXPoPuuW8WUljCxtapiSTmMbG2qyDVv4erd9xdoCzZEI5j5+Kw+GlQxslkI+zCtomXyMMJd/So/jHRhbGtWRZcfNAUl/FgtKeJFBEkLXjIRFBe8QCSozcthklbqi3/PtxrecMhQY6nz8UbDPQ7p7CzsymWDkr5EctmosmgvlQ1y2jGWyo5p11rqSF434fWT2Mu2ZrxaFHqR2pTXxhIvyZs0EuB9AKJZ4x5+h1uaN8rjcXCpiWNavobSmjqC52PgsMnjlY6HSZs/OutuUPg0xqKdDIGf0si75YD/6W1nENy8UdfV3Kpy2htzdLch/QebrtxuMfsLfN0B74AQVOUAAAAASUVORK5CYII=')",'width':'40px','height':'40px','z-index':'99','position':'absolute','background-size':'100%','bottom':topnum+'px','right':'20px'}}var posts=document.querySelectorAll('article');var bSuc=false;for(var i=0;i<posts.length;i++){(function(i){var node=posts[i].querySelector('video');if(node==null){var imgs=posts[i].getElementsByTagName('img');for(var j=imgs.length-1;j>=0;j--){if(imgs[j].clientWidth>window.innerWidth/3){node=imgs[j];break;}}}if(node!=null&&!posts[i].querySelector('i.downbtn')){var pUrl='';var aList=posts[i].querySelectorAll('a');for(var j=0;j<aList.length;j++){var temp=aList[j].href;if(temp==null)continue;/* The post's own url and nothing else. The old test allowed anything with /status/ in it and fewer than eight slashes, and took the last one - on a logged in timeline every post also carries a link to .../status/<id>/analytics, which fits both and comes later, so that is what was handed over. The scraper answered null for it and the sheet closed itself: a button that looked like it did nothing. A status url ends at its id. */var mksClean=temp.split('?')[0].split('#')[0].replace(/\/+$/,'');if(/\/status\/[0-9]+$/.test(mksClean)){pUrl=mksClean;}}if(pUrl.length>0){var redbtn=document.createElement('i');redbtn.className='downbtn';setStyle(redbtn,styledata(posts[i].getBoundingClientRect().bottom-node.getBoundingClientRect().bottom+20));posts[i].appendChild(redbtn);bSuc=true;redbtn.onclick=function(){MediaDownloaderBridge.drinkTwitter(pUrl);return false;}}}if(!posts[i].querySelector('i.downbtn')){var imgs=posts[i].getElementsByTagName('img');for(var j=0;j<imgs.length;j++){(function(j){if(imgs[j].clientWidth>window.innerWidth/3){var imgurl=imgs[j].getAttribute('src');if(imgurl!=null&&imgurl.startsWith('http')){var redbtn=document.createElement('i');redbtn.className='downbtn';setStyle(redbtn,styledata(10));imgs[j].parentNode.appendChild(redbtn);bSuc=true;redbtn.onclick=function(e){MediaDownloaderBridge.runITwitter(imgurl);return false;}}}})(j);}}})(i)}if(!bSuc){var videos=document.getElementsByTagName('video');for(var i=0;i<videos.length;i++){(function(i){if(!videos[i].parentNode.querySelector('i.downbtn')){var v=videos[i].closest('[data-testid="videoComponent"]');var co=v.querySelector('div[data-testid*="immersive-video-controls"]');var url=co.getAttribute('data-testid').replace('immersive-video-controls-','https://twitter.com/tmp/status/');var redbtn=document.createElement('i');redbtn.className='downbtn';setStyle(redbtn,styledata(videos[i].clientHeight/2));videos[i].parentNode.appendChild(redbtn);bSuc=true;redbtn.onclick=function(){MediaDownloaderBridge.drinkTwitter(url);return false;}}})(i);}}if(bSuc){MediaDownloaderBridge.drinkSuc();};
