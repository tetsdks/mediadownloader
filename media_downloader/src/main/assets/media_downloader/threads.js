/* media_downloader page script: threads.
 * Injected by ScriptLibrary with evaluateJavascript, after a prelude that sets window.mksLowEnd and
 * window.mksScanScale (and, for the generic script, window.mksSingle / window.mksParserSite).
 * Talks to the app through the MediaDownloaderBridge JavaScript interface. */
(function(){
try{

/* Prevent multiple executions */
if(window.threadsButtonsInitialized){
    return;
}

/* The guard used to sit right here, before anything was wired. onLoadResource injects on every
   subresource, so the first injection can land while the document is still being built: the
   first appendChild threw, the guard was already set, and every later healthy injection then
   returned early - the script never installed at all. That is what made the buttons come and go
   between runs. Bail out while the document is not ready and set the guard only at the end. */
if(!document.body || !(document.head || document.documentElement)){
    return;
}

function redBtn(obj){
    obj.style['background']="url('data:image/png;base64,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')";
    obj.style['background-size']="100%";
}

var bSuc = false;
var processedVideos = new WeakSet();
var processedImages = new WeakSet();

/* Add global styles */
var styleSheet = document.createElement('style');
styleSheet.textContent = `
    .threads-dl-btn {
        display: block !important;
        width: 55px !important;
        height: 55px !important;
        position: absolute !important;
        top: 10px !important;
        right: 10px !important;
        z-index: 999999 !important;
        cursor: pointer !important;
        pointer-events: all !important;
        opacity: 0.95 !important;
        border-radius: 8px !important;
        background-size: cover !important;
    }
    
    .threads-dl-btn:active {
        opacity: 1 !important;
        transform: scale(0.95) !important;
    }
    
    /* Feed buttons live on <body> so no ancestor stacking context can bury them under
       the post's own click overlay, which is what swallowed the tap before. */
    .threads-dl-btn.floating-btn {
        position: fixed !important;
        z-index: 2147483647 !important;
        top: auto !important;
        right: auto !important;
    }

    /* Hide feed buttons when dialog is open */
    body:has([role="dialog"]) .threads-dl-btn:not(.dialog-btn) {
        display: none !important;
    }
    
    .threads-dl-btn.dialog-btn {
        position: fixed !important;
        top: 80px !important;
        right: 20px !important;
        z-index: 2147483647 !important;
    }
`;
(document.head || document.documentElement).appendChild(styleSheet);

/* One post is linked twice inside its own card: from the media (".../post/ID/media") and from
   the timestamp (".../post/ID"). Compared as raw hrefs those look like two different posts, so
   every walk below stopped one level too early - which is why the caption could never be
   reached. Compare posts by this key instead, and keep returning the href itself. */
/* Threads wraps its media in a long chain of layout divs - a walk of twelve levels never
   reached the card at all, which is why both the post link and the caption came back empty.
   The boundary rule below is what keeps the extra depth safe: a walk stops as soon as a level
   holds more than one post, so it can never climb out into the feed. */
var POST_WALK_DEPTH = 30;

function postKey(href){
    var h = (href || '').split('?')[0].split('#')[0];
    return h.replace(/\/media\/?$/, '').replace(/\/$/, '');
}

/* Find the permalink of the post this media belongs to. Walking up and taking the first
   'a[href*="/post/"]' is not safe: querySelector searches the whole subtree, so once the walk
   passes the post boundary it returns the first post link in the entire feed - the button would
   then download a different post's media. Stop as soon as a level holds exactly one post link,
   and give up rather than guess once a level holds more than one. */
function findPostUrl(el){
    var node = el;
    for(var i = 0; i < POST_WALK_DEPTH && node; i++){
        if(node.querySelectorAll){
            var links = node.querySelectorAll('a[href*="/post/"]');
            var seen = {};
            var unique = [];
            for(var j = 0; j < links.length; j++){
                var h = links[j].href;
                var key = postKey(h);
                if(key && !seen[key]){ seen[key] = 1; unique.push(h); }
            }
            if(unique.length === 1) return unique[0];
            if(unique.length > 1) return null;   /* past the post boundary - ambiguous */
        }
        node = node.parentElement;
    }
    return null;
}

/* Only safe on a single post page, where the address bar already identifies the post */
function fallbackPostUrl(){
    return location.href.indexOf('/post/') > -1 ? location.href : null;
}

/* The post's own text, which becomes the download's file name. A Threads permalink ends in an
   opaque id, so unlike the parser-less sites there is no readable slug to fall back on and the
   card is the only place a name can come from. The card's first line is the author handle and
   the age ("23h") sits on its own line, neither of which belongs in a file name. */
function captionOf(text){
    var raw = (text || '').split('\n');
    var parts = [];
    for(var i = 0; i < raw.length && parts.length < 40; i++){
        var line = raw[i].trim();
        if(!line) continue;
        if(/^[0-9]+[smhdw]$/.test(line)) continue;     /* the post's age */
        if(line === 'Translate') continue;
        /* Like, reply, repost and share counts. On a post with no caption these were the only
           text the walk found, so the download was named "4.5K 200 384 1.9K". */
        if(/^[0-9][0-9.,]*[KMB]?$/.test(line)) continue;
        parts.push(line);
    }
    if(!parts.length) return '';
    /* Threads splits a caption across many nodes - on an Urdu post innerText came back as a
       column of single characters, so taking one line returned one letter. Join what is left
       instead, and drop the leading author handle only when it actually looks like one, so a
       caption that happens to start on the first line is never thrown away. */
    if(parts.length > 1 && /^[A-Za-z0-9._]{2,30}$/.test(parts[0])) parts.shift();
    return parts.join(' ').replace(/\s+/g, ' ').trim();
}

function postTitle(el){
    try{
        /* Not findPostContainer: media is usually wrapped in its own permalink <a>, which holds
           exactly one post link and so satisfies that rule while carrying no text at all - that
           is why the title came back empty. Climb until a level actually has the caption, and
           stop at the same post boundary so a feed level can never be read. */
        var node = el;
        var fallback = '';
        for(var i = 0; i < POST_WALK_DEPTH && node; i++){
            if(node.querySelectorAll){
                var links = node.querySelectorAll('a[href*="/post/"]');
                var seen = {};
                var count = 0;
                for(var j = 0; j < links.length; j++){
                    var key = postKey(links[j].href);
                    if(key && !seen[key]){ seen[key] = 1; count++; }
                }
                if(count > 1) break;
            }
            var caption = captionOf(node.innerText);
            if(caption.length >= 8){
                /* A bare handle here is the attribution label drawn over reposted media, not
                   the post's own text - hold on to it in case no real caption turns up, but
                   keep climbing for one. */
                if(/^[A-Za-z0-9._]{2,30}$/.test(caption)){
                    if(!fallback) fallback = caption;
                } else {
                    return caption.substring(0, 80);
                }
            }
            node = node.parentElement;
        }
        return fallback.substring(0, 80);
    }catch(err){ return ''; }
}

/* A video's poster is the same frame the feed was showing, so the sheet does not open blank */
function postThumb(mediaElement, isVideo){
    try{
        return (isVideo ? mediaElement.getAttribute('poster') : mediaElement.getAttribute('src')) || '';
    }catch(err){ return ''; }
}

function reportMedia(mediaElement, isVideo){
    var url = isVideo
        ? (mediaElement.currentSrc || mediaElement.src || '')
        : (mediaElement.getAttribute('src') || '');
    var post = findPostUrl(mediaElement) || fallbackPostUrl();
    MediaDownloaderBridge.threadsMediaFound(url, post, postTitle(mediaElement), postThumb(mediaElement, isVideo));
}

/* Create button element */
function createButton(mediaElement, isVideo, isDialog){
    var btn = document.createElement('div');
    btn.className = 'threads-dl-btn' + (isDialog ? ' dialog-btn' : '');
    redBtn(btn);
    
    btn.onclick = function(e){
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        
        reportMedia(mediaElement, isVideo);
        return false;
    };
    
    btn.ontouchstart = function(e){
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        
        reportMedia(mediaElement, isVideo);
        return false;
    };
    
    return btn;
}

/* Nearest ancestor holding exactly one post - same boundary rule as findPostUrl, so this can
   never climb to the feed root and report every post's media as belonging to one post. */
function findPostContainer(el){
    var node = el;
    for(var i = 0; i < POST_WALK_DEPTH && node; i++){
        if(node.querySelectorAll){
            var links = node.querySelectorAll('a[href*="/post/"]');
            var seen = {};
            var count = 0;
            for(var j = 0; j < links.length; j++){
                var key = postKey(links[j].href);
                if(key && !seen[key]){ seen[key] = 1; count++; }
            }
            if(count === 1) return node;
            if(count > 1) return null;
        }
        node = node.parentElement;
    }
    return null;
}

function inDialog(el){
    return !!(el.closest && el.closest('[role="dialog"]'));
}

/* Only the media viewer should suppress the feed buttons. Threads also shows a login/app
   promo as role="dialog" to logged out users, and treating that as the viewer left the whole
   feed without buttons. */
function mediaDialogOpen(){
    var dialogs = document.querySelectorAll('[role="dialog"]');
    for(var i = 0; i < dialogs.length; i++){
        if(dialogs[i].querySelector('video')) return true;
        var imgs = dialogs[i].querySelectorAll('img');
        for(var j = 0; j < imgs.length; j++){
            var ir = imgs[j].getBoundingClientRect();
            if(ir.width > 250 && ir.height > 250) return true;
        }
    }
    return false;
}

/* True when a layer covering essentially the whole viewport is painted over the page and our
   media is not inside it - a full screen viewer or a modal. Deliberately not a per element hit
   test: Threads puts a transparent tap-to-open overlay on post media, so hit testing the media
   itself reported every feed button as covered and hid them all. */
function fullscreenOverlayActive(el){
    var top = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
    var node = top;
    for(var i = 0; i < 10 && node && node !== document.body; i++){
        if(node.classList && node.classList.contains('threads-dl-btn')) return false;
        var r = node.getBoundingClientRect();
        if(r.width >= window.innerWidth * 0.9 && r.height >= window.innerHeight * 0.75){
            return !node.contains(el);
        }
        node = node.parentElement;
    }
    return false;
}

/* Feed buttons are attached to <body> and tracked here so they can follow their media as the
   page scrolls. Appending them next to the media instead left them inside the post's own
   stacking context, where the post overlay captured the tap and opened the video full screen. */
var floatingButtons = [];

function positionFloating(f){
    var r = f.el.getBoundingClientRect();
    /* How much of the media is actually on screen. A carousel slide sits in a horizontal
       scroller and runs past the right edge, and a card scrolling into view is clipped at the
       bottom. Placing the button from the media's own edges then put it outside the viewport -
       the slide looked like it had no button at all - or left it sitting on top of the site's
       bottom bar. Both the hide test and the position work off the visible part instead. */
    var visibleW = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
    var visibleH = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
    var hidden = mediaDialogOpen()
        || r.width < 100 || r.height < 100
        || visibleW < 90 || visibleH < 90
        || fullscreenOverlayActive(f.el);
    /* The base .threads-dl-btn rule sets display/top/right with !important, which a plain
       inline style cannot override - these have to be set as important too or the button
       stays at its default position and never moves. */
    if(hidden){
        f.btn.style.setProperty('display', 'none', 'important');
        return;
    }
    f.btn.style.setProperty('display', 'block', 'important');
    f.btn.style.setProperty('right', 'auto', 'important');
    f.btn.style.setProperty('left', Math.max(0, Math.min(r.right, window.innerWidth) - 65) + 'px', 'important');
    /* Kept clear of Threads' own bottom bar: a card just scrolling into view put the button
       straight on top of it, where it swallowed taps meant for the site's navigation. */
    var top = Math.max(60, Math.max(r.top, 0) + 10);
    f.btn.style.setProperty('top', Math.min(top, window.innerHeight - 130) + 'px', 'important');
}

function attachFloatingButton(mediaElement, isVideo){
    var btn = createButton(mediaElement, isVideo, false);
    btn.className = 'threads-dl-btn floating-btn';
    document.body.appendChild(btn);
    var f = {btn: btn, el: mediaElement};
    floatingButtons.push(f);
    positionFloating(f);
}

function updateFloating(){
    for(var i = floatingButtons.length - 1; i >= 0; i--){
        var f = floatingButtons[i];
        if(!document.contains(f.el)){
            if(f.btn.parentNode) f.btn.parentNode.removeChild(f.btn);
            floatingButtons.splice(i, 1);
            continue;
        }
        positionFloating(f);
    }
}

/* Process videos in feed. Not scoped to <article>: the feed does not always use it. */
function processVideos(){
    var videos = document.querySelectorAll('video');

    videos.forEach(function(video){
        if(processedVideos.has(video)) return;
        if(inDialog(video)) return;

        var rect = video.getBoundingClientRect();
        if(rect.width < 100 || rect.height < 100) return;

        attachFloatingButton(video, true);
        processedVideos.add(video);
        bSuc = true;
    });
}

/* True for the still frame that sits behind a video, which processVideos already covers -
   downloading it would hand over a picture instead of the clip. Deliberately scoped to the
   image's own slide: asking whether the whole post contains a video cost a mixed carousel all
   of its photos, since one video slide then hid every picture beside it. The width test is
   what marks the slide boundary - an ancestor wider than the image has left it. */
function isVideoPoster(img){
    var width = img.getBoundingClientRect().width;
    var node = img.parentElement;
    for(var i = 0; i < 6 && node; i++){
        if(node.getBoundingClientRect().width > width + 40) return false;
        if(node.querySelector && node.querySelector('video')) return true;
        node = node.parentElement;
    }
    return false;
}

/* Process images in feed (only posts that carry no video of their own) */
function processImages(){
    var images = document.querySelectorAll('img');

    images.forEach(function(img){
        if(processedImages.has(img)) return;
        if(inDialog(img)) return;

        /* These are css pixels, and this viewport is only about 360 of them wide, so a flat 250
           excluded every carousel slide - each slide is roughly half the width of a single
           image post. Scale the floor to the viewport so slides pass while avatars and page
           chrome still do not. */
        var rect = img.getBoundingClientRect();
        var minWidth = Math.max(80, Math.min(250, window.innerWidth * 0.35));
        if(rect.width < minWidth || rect.height < 150) return;

        var src = img.getAttribute('src');
        if(!src || !src.startsWith('http')) return;

        /* An image downloads straight from its own src, so it does not need the post link and
           must not be skipped when the post cannot be identified - that was dropping the
           buttons on carousel slides, whose slides sit in their own scroll container. Size is
           what keeps avatars and page chrome out. */
        if(isVideoPoster(img)) return;

        attachFloatingButton(img, false);
        processedImages.add(img);
        bSuc = true;
    });
}

/* Process dialog (fullscreen view) */
function processDialog(){
    var dialog = document.querySelector('[role="dialog"]');
    
    if(!dialog){
        /* Remove dialog buttons when closed */
        document.querySelectorAll('.dialog-btn').forEach(function(btn){
            btn.remove();
        });
        return;
    }
    
    /* Check if button already exists */
    if(document.querySelector('.dialog-btn')) return;
    
    /* Check for video */
    var video = dialog.querySelector('video');
    if(video){
        var btn = createButton(video, true, true);
        document.body.appendChild(btn);
        bSuc = true;
        return;
    }
    
    /* Find largest image */
    var images = dialog.querySelectorAll('img');
    var largestImg = null;
    var maxArea = 0;
    
    images.forEach(function(img){
        var rect = img.getBoundingClientRect();
        var area = rect.width * rect.height;
        if(area > maxArea && rect.width > 300){
            maxArea = area;
            largestImg = img;
        }
    });
    
    if(largestImg){
        var src = largestImg.getAttribute('src');
        if(src && src.startsWith('http')){
            var btn = createButton(largestImg, false, true);
            document.body.appendChild(btn);
            bSuc = true;
        }
    }
}

/* Main loop */
function updateButtons(){
    processVideos();
    processImages();
    processDialog();
    updateFloating();
}

/* Run immediately */
updateButtons();

/* Run periodically for dynamic content */
setInterval(function(){ if(!document.hidden){ updateButtons(); } }, 300 * (window.mksScanScale || 1));

/* Observe DOM changes */
var observer = new MutationObserver(function(mutations){
    var hasDialog = document.querySelector('[role="dialog"]') !== null;
    if(hasDialog){
        processDialog();
    }
});

observer.observe(document.body, {
    childList: true,
    subtree: true
});

if(bSuc){
    MediaDownloaderBridge.walkSuc();
}

/* Only now: an injection that threw on the way here must not stop the next one from trying */
window.threadsButtonsInitialized = true;
console.log('[mks] threads script ready');
}catch(e){ console.log('[mks] threads script failed: ' + e); }
})();
