/* media_downloader page script: generic.
 * Injected by ScriptLibrary with evaluateJavascript, after a prelude that sets window.mksLowEnd and
 * window.mksScanScale (and, for the generic script, window.mksSingle / window.mksParserSite).
 * Talks to the app through the MediaDownloaderBridge JavaScript interface. */
(function(){
try{
/* Set before the guard below, not after it. These sites are single page apps: opening a card
   from the feed keeps the same document, so the script installed on the feed goes on running on
   the video's own page. The app re-injects on every navigation and the guard turns that into a
   no-op, so this is the one line that gets to run again - which is what keeps the answer
   current. Frozen at first injection, a 9gag post opened from the feed still counted as a feed
   and drew a second button next to the app's own. */
if(window.mksGenInit){ return; }

/* Injection happens on every resource the page loads, so the first few arrive before the
   document exists. Leave quietly and let a later one install - throwing here logged a
   TypeError that was pure noise, and noise is what hides a real fault. */
if(!(document.head || document.documentElement)){ return; }

var css = document.createElement('style');
css.textContent = '.mks-dl-btn{position:fixed !important;width:55px !important;' +
    'height:55px !important;z-index:2147483647 !important;background-size:cover !important;' +
    'border-radius:8px !important;opacity:0.95 !important;display:none !important}';
/* head is not there yet on a very early injection, and reaching through null used to throw
   after the guard had already been set - which then blocked every later, healthy injection */
(document.head || document.documentElement).appendChild(css);

var tracked = [];
var mksLabelled = '';
var mksMediaSent = '';
var mksPicSent = '';


/* The page title on a feed names the feed, not this video, so take the card's own heading.
   Not the first heading found: players carry their own headings ("Premium Only Content" sits
   inside Rumble's), and querySelector returns whichever comes first in the subtree. Collect the
   headings from a few levels up and keep the longest, since a real video title is far longer
   than a badge. Only a few levels, or the walk reaches other cards. */
/* A name with the card's counters still stuck to the front of it.
   Bitchute writes its view count as the material ligature "visibility" run straight into the
   number, and the running time straight after that, with no space anywhere - so a heading read
   off the card came back as "visibility111011205910 911 RITUALS & ISRAELIS -- Dani". The name
   is whatever follows the running time. Only applied when the line actually starts that way, so
   a title that merely begins with a word and a digit is left alone. */
function mksCleanName(t){
    /* A player control is not a name, wherever it was picked up. The filter on the title
       candidates only covers the ones headingIn considers; sky's "Skip backward 10
       seconds" arrived through cardText instead, which reads whatever text is near the
       media - so the video was offered under the name of the button beside it. Every exit
       of findTitle comes through here, which is why the rule lives here. */
    var mksName = (t || '').replace(/\s+/g, ' ').trim();
    /* Markup is not a name. A page that lazy loads its pictures keeps the real <img> inside a
       <noscript>, and where the wrapper around it is not being rendered innerText falls back to
       textContent - which hands the tags over as though they were words. A fandom picture came
       back called "img srcset altSpiderMan Vol 4 1", the markup itself. No title a reader would
       write contains a tag, so text holding one is thrown away and the next candidate is used. */
    if(/<\s*[a-z!\/]/i.test(mksName) || /\b(srcset|data-src|class)\s*=/i.test(mksName)){ return ''; }
    if(/^(play|pause|mute|unmute|replay|fullscreen|full screen|settings|volume|captions|subtitles|seek)$/i.test(mksName)){ return ''; }
    /* A player's labelled buttons: xvideos names its gear "Player settings", and a paused player
       left that as the only text near the video. */
    if(/^(screenshot|take screenshot|(player|video) (settings|options)|(playback )?speed|quality|autoplay|picture[- ]in[- ]picture|cast|(enter|exit) full ?screen)$/i.test(mksName)){ return ''; }
    /* The site's own download button, and the other actions that sit beside it. Pexels labels
       its button title="Download", and that became the name: the video was saved as
       "Download.mp4". Whole labels only, so a video called "Download Festival 2024" keeps its
       name. */
    if(/^(download|free download|download free|edit|like|follow|collect|add to collection)$/i.test(mksName)){ return ''; }
    if(/^skip (backward|forward|ahead|back)/i.test(mksName)){ return ''; }
    if(isMediaFileName(mksName)){ return ''; }
    if(/^(loading|please wait|buffering)\b/i.test(mksName)){ return ''; }
    var line = (t || '').replace(/\s+/g, ' ').trim();
    if(!line) return '';
    if(!/^[a-z_]{4,}[0-9]/.test(line)) return line;
    var m = line.match(/[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?/);
    if(!m) return '';
    return line.slice(line.indexOf(m[0]) + m[0].length).trim();
}

function findTitle(v){
    /* The card this video belongs to. Without a boundary the walk climbs out of it: on the 9gag
       feed six levels reach the neighbouring post, and the longest text found there won - a
       video came back correctly but wearing the post above it for a name. */
    var box = null;
    try{ box = v.closest ? v.closest('article,[data-entry-id],[id^="jsid-post"]') : null; }catch(e){}
    var vh = 0;
    try{ vh = v.getBoundingClientRect().height; }catch(e){}
    var best = '';
    var node = v;
    /* Twelve, not six. Six was a stand-in for "do not climb out of the card", and there are two
       real boundaries below now - the card count and the height - which stop the walk where it
       should stop whatever the markup looks like. Six levels was not enough to get out of a
       shadow root and into the post: a reddit video sits inside shreddit-player, and the walk
       ran out before reaching the post that names it, so every reddit video download was called
       after the page ("Oddly Satisfying") instead of the post. */
    for(var i = 0; i < 12 && node; i++){
        if(box && node !== box && !box.contains(node)){ break; }
        /* No card marker on this site either - then stop as soon as the node has grown far
           taller than the media itself, which is what climbing into the feed looks like. */
        if(!box && i > 0 && vh > 0){
            var nh = 0;
            try{ nh = node.getBoundingClientRect().height; }catch(e){}
            if(nh > vh + 700){ break; }
        }
        /* And stop the moment the node covers a second card, however tall it is. Rumble's
           listing packs its cards close enough that the height rule alone still let the walk
           take in the one next door, and the heading found there is the one the download was
           named after - a video called "The Creators Are Afraid of Their Creation" that was
           nothing of the sort. Two card links under one node means it is the listing, not a
           card. */
        if(!box && i > 0 && mksCardsUnder(node) > 1){ break; }
        best = headingIn(node, best);
        if(best.length > 45){ break; }
        node = mksUp(node);
    }
    /* The video can sit deeper than six levels below its card - on 9gag it does, which left the
       heading unread and the name falling back to the card's text, tags and all. The card
       itself is in hand, so ask it directly. */
    if(box && best.length <= 25){ best = headingIn(box, best); }
    /* Anything this short is a label rather than a name - tumblr's cards offered the blog's
       handle, "maykitz", while the post's own words sat right beside it. The card's text wins
       whenever it reads like a sentence and the heading does not. */
    if(best.length < 25){
        var written = cardText(v);
        if(written.length >= 20){ return mksCleanName(written).slice(0, 120); }
    }
    if(best.length > 3){ return mksCleanName(best).slice(0, 120); }
    /* Nothing on the card reads as a heading. Tumblr is the case in point: a post is body text
       and tags, so every download from it was called "Unknown". The card's own text is the only
       thing that names it, read the way the facebook and instagram scripts read theirs - the
       counts, the buttons and the timestamps skipped, and the first few real lines kept. */
    var written = mksCleanName(cardText(v));
    if(written.length > 3){ return written; }
    /* The markup says nothing at all, so the screen is asked instead. Imdb's listings play a
       trailer inside its own island of divs - ten levels up there is no heading, no link and no
       text - while the title it belongs to is written right under the player ("Neagley"), and
       the download could only be named after the page. Only reached when nothing else named it,
       and only a heading beside this media: across it and within a couple of lines of it. */
    return mksCleanName(mksHeadingBeside(v));
}

function mksHeadingBeside(v){
    try{
        var r = v.getBoundingClientRect();
        if(r.width < 1 || r.height < 1){ return ''; }
        var hs = document.querySelectorAll('h1,h2,h3,h4');
        var best = '', bestGap = 1e9;
        for(var i = 0; i < hs.length && i < 200; i++){
            if(!usableTitleNode(hs[i])){ continue; }
            var hr = hs[i].getBoundingClientRect();
            if(hr.width < 1 || hr.height < 1){ continue; }
            /* Above or below it, across the media - or beside it on the same row, which is how a
               listing lays a small player out: imdb puts the title to the right of a 117x66
               trailer. Either way the heading has to line up with the media, not sit in another
               column or another row. */
            var over = Math.min(r.right, hr.right) - Math.max(r.left, hr.left);
            var overY = Math.min(r.bottom, hr.bottom) - Math.max(r.top, hr.top);
            var gap;
            if(over >= r.width * 0.4){
                gap = hr.top >= r.bottom ? hr.top - r.bottom : (r.top >= hr.bottom ? r.top - hr.bottom : 0);
            }else if(overY >= r.height * 0.5){
                gap = hr.left >= r.right ? hr.left - r.right : (r.left >= hr.right ? r.left - hr.right : 0);
            }else{
                continue;
            }
            if(gap > 160 || gap >= bestGap){ continue; }
            var text = titleTextOf(hs[i]);
            if(text.length < 3 || text.length > 120){ continue; }
            best = text;
            bestGap = gap;
        }
        return best;
    }catch(e){ return ''; }
}

/* How many different cards a node covers - one, or more than one. Used as a boundary: a node
   that reaches two cards is the listing around them, and nothing inside it names this video. */
function mksCardsUnder(node){
    if(!node.querySelectorAll){ return 0; }
    var as = node.querySelectorAll('a[href]');
    var seen = {};
    var n = 0;
    for(var i = 0; i < as.length; i++){
        if(!isCardHref(as[i].href)){ continue; }
        var key = as[i].href.split('?')[0].split('#')[0];
        if(seen[key]){ continue; }
        seen[key] = 1;
        n++;
        if(n > 1){ return n; }
    }
    return n;
}

function headingIn(node, best){
    if(!node.querySelectorAll){ return best; }
    /* A real heading first, and the first one, not the longest text on the card. Taking the
       longest picked whichever element happened to wrap the heading and the tag row together -
       a 9gag video came back as "Cybercab turned into Cybercop donald trump elon musk...".
       Only when a card has no heading at all do the weaker candidates get a turn. */
    var hs = node.querySelectorAll('h1,h2,h3,h4');
    for(var k = 0; k < hs.length; k++){
        if(!usableTitleNode(hs[k])) continue;
        var h = titleTextOf(hs[k]);
        if(h.length >= 8){ return h.length > best.length ? h : best; }
    }
    var els = node.querySelectorAll('[title],a[href]');
    for(var j = 0; j < els.length; j++){
        if(!usableTitleNode(els[j])) continue;
        var t = titleTextOf(els[j]);
        if(t.length > best.length && t.length < 200){ best = t; }
    }
    return best;
}

function usableTitleNode(el){
    /* A call to action names nothing. 9gag puts "Read post" over its cards, and it was winning
       on a card whose real heading is elsewhere. */
    /* Both of them: titleTextOf prefers the title attribute, so a filter that read only the
       text missed the label that was actually being used. */
    /* A heading nobody can see names nothing. Xvideos keeps its age gate's "Verify your age to
       get full access" in an h1 that is not rendered once the gate is passed, and being the only
       heading on the page it became the name of every video there. No box at all means the
       element or an ancestor is display:none. */
    try{ if(el.getClientRects().length === 0) return false; }catch(e){}
    var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    var ta = (el.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
    if(/^(Read post|Use app|Open app|See more|Show more)$/i.test(t)) return false;
    /* Markup, not words - see mksCleanName. Refused here as well so the search moves on to the
       next candidate rather than ending up with no name at all. */
    if(/<\s*[a-z!\/]/i.test(t) || /\b(srcset|data-src)\s*=/i.test(t)) return false;
    /* An instruction is not a name. Wikipedia's viewer hangs title="Go to corresponding file
       page" on the link beside the picture, and that was the longest text there, so it became
       the name the download was offered under. */
    if(/^(go to|click|tap)\b/i.test(t) || /^(go to|click|tap)\b/i.test(ta)) return false;
    /* Nor is a player control. Sky hangs "Skip backward 10 seconds" on an element beside
       its video, and that was the longest text there, so the sheet offered the video under
       the name of the button next to it. Whole labels, matched whole, so a video actually
       called "Play" is untouched - a control never has anything else in it. */
    var mksCtl = /^(screenshot|take screenshot|(player|video) (settings|options)|(playback )?speed|quality|autoplay|picture[- ]in[- ]picture|cast|(enter|exit) full ?screen|play|pause|mute|unmute|replay|next|previous|fullscreen|full screen|settings|volume|captions|subtitles|seek|share|save|more|download|free download|download free|edit|like|follow|collect|add to collection)$/i;
    if(mksCtl.test(t) || mksCtl.test(ta)) return false;
    if(/^skip (backward|forward|ahead|back)/i.test(t) ||
       /^skip (backward|forward|ahead|back)/i.test(ta)) return false;
    if(isDateLike(t) || isDateLike(el.getAttribute('title') || '')) return false;
    if(isMediaFileName(t) || isMediaFileName(ta)) return false;
    /* A player's own buttons are often plain spans: xnxx's screenshot control is
       span.screenshot-btn > span[title="Screenshot"], shown while the controls are up, and the
       download was saved as "Screenshot.mp4". A short label on something styled as a button or
       an icon is that control, not a name. */
    if((t.length + ta.length) < 40){
        var mksCls = ('' + (el.className || '')) + ' ' + ('' + ((el.parentElement && el.parentElement.className) || ''));
        if(/(^|[-_\s])(btn|button|icon)([-_\s]|$)/i.test(mksCls)) return false;
    }
    /* A loading notice is not a name: hqporner heads its player with an h3 reading "Loading may
       take some time ...". */
    if(/^(loading|please wait|buffering)\b/i.test(t)) return false;
    /* Skip anything inside the player: its headings are chrome ("Premium Only Content"), never
       the video's name, and a control is not a title either - Rumble's captions button carries
       title="Toggle closed captions menu". */
    if(el.querySelector && el.querySelector('video')) return false;
    if(el.closest && el.closest('button,[role="button"],[role="menu"]')) return false;
    return true;
}

/* A timestamp is not a name. Tumblr hangs the post's date on the link to it, and that link
   was winning: every picture downloaded from tumblr was called "September 5 2026 at 728 PM". */
/* The page's own name, with the site's name trimmed off it: browsers get "Chess 1 - Elon Musk
   0 - 9GAG", and only the first half of that names the post. Used where a picture's own card
   says nothing, which is how a post's page reads once the feed's markup is gone. */
function ogTitle(){
    try{
        var m = document.querySelector('meta[property="og:title"]');
        var v = m ? (m.getAttribute('content') || '') : '';
        return v.replace(/\s+/g, ' ').trim().slice(0, 120);
    }catch(e){ return ''; }
}

/* The cover a page states for the one video it holds. Worth asking for before anything is
   guessed at: a frame grabbed off the player is cross origin, so the canvas is tainted and what
   comes back is a black square - which is exactly what the sheet was showing for a rumble
   video's own page. */
function ogImage(){
    try{
        var m = document.querySelector('meta[property="og:image"]') ||
                document.querySelector('meta[name="twitter:image"]');
        var v = m ? (m.getAttribute('content') || '') : '';
        return /^https?:/.test(v) ? v : '';
    }catch(e){ return ''; }
}

function pageTitle(){
    try{
        var t = (document.title || '').replace(/\s+/g, ' ').trim();
        if(!t) return '';
        var parts = t.split(/\s+[-|·]\s+/);
        var best = '';
        for(var i = 0; i < parts.length; i++){
            if(parts[i].length > best.length){ best = parts[i]; }
        }
        return (best || t).slice(0, 120);
    }catch(e){ return ''; }
}

function isDateLike(t){
    if(!t) return false;
    var v = t.trim();
    if(/^(mon|tue|wed|thu|fri|sat|sun)/i.test(v) && v.length < 40) return true;
    if(/(january|february|march|april|may|june|july|august|september|october|november|december)/i.test(v)
        && /\d/.test(v) && v.length < 60) return true;
    /* Written short, the way pexels dates its files: "Feb 18, 2019". */
    if(/^(jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}\b/i.test(v) && v.length < 40) return true;
    if(/^\d{1,2}[:/.\-]\d{1,2}/.test(v)) return true;
    if(/\d{1,2}:\d{2}\s*(am|pm)?$/i.test(v)) return true;
    return false;
}

/* A file's name is not the video's. YouPorn's player keeps a debug panel whose span carries the
   segment being played in its title attribute, "seg-4-v1-a1.ts", and with the page heading out
   of the walk's reach that was the name the download was offered under. One word ending in a
   media extension is a file, never something a reader would call the video. */
function isMediaFileName(t){
    return /^[^\s\/]+\.(ts|m4s|m4v|mp4|m3u8|mpd|webm|mov|mkv|aac|m4a|mp3|jpe?g|png|gif|webp)$/i.test((t || '').trim());
}

function titleTextOf(el){
    /* innerText before textContent: textContent hands back what is inside <noscript> as
       though it were words on the page. Fandom lazy loads every picture that way, so a
       download from it was named "img srcset altSpiderMan Vol 4 1" - the markup itself.
       innerText reads what is rendered, which is the text a reader would call it by. */
    var t = el.getAttribute('title') || el.innerText || el.textContent || '';
    return t.replace(/\s+/g, ' ').trim();
}

function cardText(v){
    /* Tumblr renders every post in its own iframe, and inside it there is nothing but the
       media - measured on the device, innerText was empty at every level above the video. The
       text that names the post is in the page holding the frame, and frameElement reaches it
       as long as the two are same origin, which a post frame is. */
    var t = walkText(v.parentElement, playerLines(v));
    if(t) return t;
    try{
        if(window.frameElement){ return walkText(window.frameElement); }
    }catch(e){}
    return '';
}

/* The player's own text: its settings menu, quality labels, cast and seek hints. The box that
   holds the video and is no bigger than it is the player, and whatever is written inside it is
   chrome - the rule usableTitleNode applies to headings. Xvideos keeps "Quality", "Auto" and
   the speed menu rendered in there while the title overlay hides during playback, so the walk
   joined the menu labels into the name the download was offered under. */
/* The player around a video: the highest box above it that is no bigger than it, or null. */
function mksPlayerBox(v){
    try{
        var r = v.getBoundingClientRect();
        var box = v;
        for(var i = 0; i < 6; i++){
            var up = box.parentElement;
            if(!up) break;
            var ur = up.getBoundingClientRect();
            if(ur.width > r.width + 60 || ur.height > r.height + 60) break;
            box = up;
        }
        return box === v ? null : box;
    }catch(e){ return null; }
}

function playerLines(v){
    var lines = {};
    try{
        var box = mksPlayerBox(v);
        if(!box) return lines;
        var raw = (box.innerText || '').split(String.fromCharCode(10));
        for(var k = 0; k < raw.length && k < 200; k++){
            var line = raw[k].replace(/\s+/g, ' ').trim();
            if(line) lines[line] = true;
        }
    }catch(e){}
    return lines;
}

function walkText(from, skip){
    try{
        var node = from;
        var fh = 0;
        try{ fh = from.getBoundingClientRect().height; }catch(e){}
        for(var d = 0; d < 12 && node; d++){
            /* Same boundary as findTitle: text from a neighbouring card is not this one's name. */
            if(d > 0 && fh > 0 && node.getBoundingClientRect().height > fh + 700){ break; }
            var raw = (node.innerText || '').split(String.fromCharCode(10));
            var parts = [];
            for(var k = 0; k < raw.length && parts.length < 5; k++){
                var line = raw[k].replace(/\s+/g, ' ').trim();
                if(!line) continue;
                if(skip && skip[line]) continue;
                /* A card's counters, glued to the icons that label them. Bitchute writes the
                   view count as the material ligature "visibility" run straight into the number,
                   and the running time straight after it, all on one line - so the name came out
                   as "visibility111011205910 911 RITUALS...". The name is whatever follows the
                   running time; a line that has counters and nothing after them is not a name. */
                var mksIcon = line.match(/^[a-z_]{4,}[0-9]/);
                if(mksIcon){
                    var mksTime = line.match(/[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?/);
                    if(!mksTime) continue;
                    line = line.slice(line.indexOf(mksTime[0]) + mksTime[0].length).trim();
                    if(!line) continue;
                }
                if(line.length > 90) line = line.slice(0, 90);
                if(/^[0-9][0-9.,]*[KMB]?$/.test(line)) continue;
                if(/^[0-9]+[smhdwy]$/.test(line)) continue;
                if(/^(Follow|Share|Reblog|Like|Notes|More|Open app|Log in|Sign up)$/i.test(line)) continue;
                /* The rest of a media page's furniture: the buttons beside the player and the
                   file's specification. Pexels stands its author, "Donate", "License" and
                   "Dimensions 3840x2160" around the video, and joined together they became the
                   name a download was offered under. */
                if(/^(Donate|License|Licence|Download|Save|Subscribe|Join|Upgrade|Free|Pro|Sign in|Report|Embed|Copy link)$/i.test(line)) continue;
                if(/^(Dimensions|Resolution|Duration|File size|Format|Aspect ratio|Uploaded|Published)\b/i.test(line)) continue;
                /* The same specification and counters written with no label of their own: pexels
                   stands "3840x2160", the date and "Downloads"/"Views" around its player, and
                   joined together they became the name the download was offered under. */
                if(/^[0-9]{3,5}\s*[x×]\s*[0-9]{3,5}$/i.test(line)) continue;
                if(/^(Downloads|Views|Plays|Likes|Comments|Saves|Shares|Followers)$/i.test(line)) continue;
                /* The card's furniture, not its name: a section tag ("WTF"), the player's own
                   "Play", the "Cheered by" line. Anything this short names nothing anyway, and
                   the joined result still has to reach twelve characters to be used at all. */
                if(line.length <= 4) continue;
                if(/^(Cheered by|Sponsored|Promoted|Advertisement)/i.test(line)) continue;
                /* A player's loading notice: hqporner's frame sits under "Loading may take some
                   time ...", and that was the name. */
                if(/^(loading|please wait|buffering)\b/i.test(line)) continue;
                /* A row of labelled stats is not a name. Thisvid's player has none of its own, and
                   the first long line near it was "Rating: 0.0 Viewed: Added: 2 hours ago
                   Duration:". Two labels on one line is a stats row; a title has one at most. */
                if((line.match(/[A-Za-z]+:(\s|$)/g) || []).length >= 2) continue;
                /* The card's call to action, not its name: "Read post", "Use app". */
                if(/^(Read post|Use app|Open app|See more|Show more)$/i.test(line)) continue;
                if(isDateLike(line)) continue;
                /* One line that reads like a sentence is the post's name; the lines after it are
                   the tag row. Joining everything gave "Cybercab turned into Cybercop donald
                   trump elon musk". Short lines are still joined, since a card whose text is all
                   short fragments has no single line worth having. */
                if(line.length >= 20){ return line.slice(0, 120); }
                parts.push(line);
            }
            var text = parts.join(' ').replace(/\s+/g, ' ').trim();
            /* The same stats row, written one label and one value per line, is only a row once
               joined - so it is asked again here. */
            if(text.length >= 12 && (text.match(/[A-Za-z]+:(\s|$)/g) || []).length < 2){ return text.slice(0, 120); }
            node = mksUp(node);
        }
    }catch(e){}
    return '';
}

/* The permalink of this one video. Its slug carries the title in readable form
   ("/v7exqzu-sorry-i-annoyed-you-with-my-friendship-...") which is far more reliable than
   guessing which element on the card holds the heading. */
/* The last named segment of a link. Read past a trailing slash: pexels ends every permalink
   with one - /video/sunlight-seen-through-leaves-10395606/ - so the last piece after split('/')
   was empty, the card's own link was passed over as having no slug, and a video on its search
   page was named after the download button beside it. */
function mksLastSegment(h){
    var segs = (h || '').split('?')[0].split('#')[0].split('/').filter(function(x){ return !!x; });
    return segs.length ? segs[segs.length - 1] : '';
}

function findLink(v){
    var node = v;
    for(var i = 0; i < 6 && node; i++){
        /* The anchor the media sits inside is its own card's link. querySelectorAll below only
           looks at what is under a node, never at the node itself, so a <video> wrapped
           directly in its permalink - pexels builds every video card that way - was the one
           link this walk could not see. */
        if(i > 0 && node.tagName === 'A'){
            var own = node.href || '';
            if(typeof own === 'string' && own.indexOf(location.origin) === 0 &&
               mksLastSegment(own).split('-').length >= 3){ return own; }
        }
        /* Not past the card this media belongs to. Wikipedia's full screen viewer hides the
           article rather than removing it, so six levels up from the picture on screen is a
           <body> still holding a link to every one of the article's thirty seven pictures -
           and the first of those became the downloaded file's name. */
        if(i > 0 && mksCardsUnder(node) > 1){ break; }
        if(node.querySelectorAll){
            var as = node.querySelectorAll('a[href]');
            for(var k = 0; k < as.length; k++){
                var h = as[k].href || '';
                /* This site's own pages only. Vimeo puts a link to its Creative Commons help
                   page beside every player, and its slug reads like a title - so every vimeo
                   download was called "12427652203153 What do the different Creative Comm". */
                if(typeof h !== 'string' || h.indexOf(location.origin) !== 0) continue;
                var last = mksLastSegment(h);
                if(last && last.split('-').length >= 3){ return h; }
            }
        }
        node = mksUp(node);
    }
    return '';
}

/* href must be read as a string: on an SVG <a> the property is an SVGAnimatedString, and
   calling split() on it throws - which aborted the whole scan before the poster cards were
   ever reached, leaving a button on the one playing video only. */
/* A permalink whose last segment is an id rather than words. Six digits or more, so a year
   or a page number is not mistaken for one. */
function isIdHref(h){
    if(!h || typeof h !== 'string') return false;
    var last = h.split('?')[0].split('#')[0].replace(/\/+$/, '').split('/').pop();
    /* A couple of letters may label the id: imdb names a trailer's page /video/vi3877612057/, and
       without this the link beside the poster was refused and a film's page handed over nothing. */
    return /^[a-z]{0,3}[0-9]{6,}$/i.test(last || '');
}

function isSlugHref(h){
    if(!h || typeof h !== 'string') return false;
    var last = h.split('?')[0].split('#')[0].replace(/\/+$/, '').split('/').pop();
    return !!last && last.split('-').length >= 3;
}

/* The stream itself, when the page is honest about it. Restarting the video only works while
   the player has to fetch something; a plain progressive mp4 that has already played is served
   from cache, no request reaches the sniffer, and the sheet sits on "Parsing Media" for ever -
   which is exactly what 9gag does. An <video src> or a <source src> is the media, so hand it
   over and let the restart be the fallback it was meant to be. */
/* A placeholder a player parks in its element until the real stream is attached to it. ok.ru
   keeps https://st-ok.cdn-vk.ru/res/i/video/stub.mp4 there and builds the video through MSE
   afterwards, so the element's src is 652 bytes of nothing - which is exactly the size the
   sheet offered, and exactly what a download from it produced. Named files only, so a real
   video that happens to live under a folder called stub is not refused. */
var mksStubFile = /\/(stub|blank|empty|dummy|placeholder|silence)(-|_|\.)?[0-9]*\.(mp4|m4v|webm|mov|m4s)([?#]|$)/i;

function mksNotAStub(u){
    if(!u) return '';
    return mksStubFile.test('' + u) ? '' : u;
}

/* The best of the qualities a player lists as its own sources. Tnaflix writes 144 to 720 into
   <source size="..."> and plays 480, so the file handed over was never its best. Only sources
   that state a number are compared; a list without them says nothing about quality. */
function bestLabelledSource(el){
    var best = '', bestN = 0;
    var srcs = el.querySelectorAll ? el.querySelectorAll('source[src]') : [];
    for(var i = 0; i < srcs.length; i++){
        var s = srcs[i];
        var label = s.getAttribute('size') || s.getAttribute('label') || s.getAttribute('res') ||
            s.getAttribute('data-res') || s.getAttribute('title') || '';
        var m = ('' + label).match(/(\d{3,4})/);
        var n = m ? parseInt(m[1], 10) : 0;
        var u = s.src || s.getAttribute('src') || '';
        if(n > bestN && u.indexOf('http') === 0){ best = u; bestN = n; }
    }
    return best;
}

/* A player's own play control, pressed. A cover is not always what starts the player: KVS tube
   sites (porntrex) run Flowplayer, whose cover takes a click and does nothing, while its
   a.fp-play starts the stream - without it the sheet waited for a stream that never came.
   Only inside the player's own box, so the control of a neighbouring card is never pressed. */
function mksPressPlay(el){
    try{
        var h = el.getBoundingClientRect().height;
        var node = el;
        for(var i = 0; i < 5 && node; i++){
            if(node !== el && node.getBoundingClientRect().height > h + 100) return;
            var play = node.querySelector ? node.querySelector(
                '.fp-play, .jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid, .mgp_playbackBtn, ' +
                '[aria-label="Play" i], button[title="Play" i]') : null;
            if(play){ play.click(); return; }
            node = node.parentElement;
        }
    }catch(e){}
}

/* What the player itself says it is playing. A player streaming through MSE hands its element a
   blob: url, so the element knows nothing - and a page with one player per row (imdb's listings)
   could only be answered from whatever stream the page had heard first, which named and sized
   every row after the same video. JW Player, which those listings run, names the file in its own
   playlist item. */
function mksPlayerFile(el){
    try{
        if(!window.jwplayer){ return ''; }
        var node = el;
        for(var d = 0; d < 8 && node; d++){
            if(('' + (node.className || '')).indexOf('jwplayer') >= 0 || /player/i.test(node.id || '')){
                var player = window.jwplayer(node.id || node);
                var item = player && player.getPlaylistItem && player.getPlaylistItem();
                var file = item && (item.file || (item.sources && item.sources[0] && item.sources[0].file));
                if(file && ('' + file).indexOf('http') === 0){ return mksNotAStub('' + file); }
            }
            node = node.parentElement;
        }
    }catch(e){}
    return '';
}

function directSrc(el){
    try{
        if(el.tagName !== 'VIDEO'){ return ''; }
        var labelled = bestLabelledSource(el);
        if(labelled){ return mksNotAStub(labelled); }
        var fromPlayer = mksPlayerFile(el);
        if(fromPlayer){ return fromPlayer; }
        var s = el.currentSrc || el.getAttribute('src') || '';
        if(s.indexOf('http') !== 0){
            var srcs = el.querySelectorAll ? el.querySelectorAll('source[src]') : [];
            for(var i = 0; i < srcs.length; i++){
                var c = srcs[i].getAttribute('src') || '';
                if(c.indexOf('http') === 0){ return mksNotAStub(c); }
            }
            return '';
        }
        return mksNotAStub(s);
    }catch(e){ return ''; }
}

/* Last resort for a player that shows no cover anywhere: a frame of the video itself. Measured
   on snackvideo - no img and no background image at any level above the player, so there is
   nothing else to use. Whether this works is up to the cdn: a frame from a cross origin video
   taints the canvas and reading it back throws, which is caught here. Small on purpose, since
   it travels to the app as text. */
function frameOf(v){
    try{
        if(!v.videoWidth || !v.videoHeight) return '';
        var w = 320;
        var h = Math.round(v.videoHeight * (w / v.videoWidth));
        var c = document.createElement('canvas');
        c.width = w; c.height = h;
        c.getContext('2d').drawImage(v, 0, 0, w, h);
        var data = c.toDataURL('image/jpeg', 0.5);
        console.log('[mks] frame captured ' + data.length);
        return data;
    }catch(e){ console.log('[mks] frame not readable: ' + e); return ''; }
}

/* The picture a card is showing, at the largest size the page itself offers, or nothing if the
   card is showing a moving preview rather than a picture. srcset is the browser's own list of
   the sizes that exist, so the biggest of those is a real url and not a guess at one. */
/* A card showing a video that has not started looks exactly like a card showing a picture -
   both are one still image - except that the player leaves its marks on it: the running time in
   a corner, or a play control. Neither appears on a picture post. Without this the cover of a
   video would be offered as though it were the post. */
function looksLikeVideoCard(el){
    try{
        /* On this picture, not somewhere else on the page. The walk climbs five levels and then
           searches everything under each of them, so on an espn story the photo at the top was
           called a video card by a player further down that carried a play badge and a "1:12" -
           and a still photo treated as a video is a button that opens a video which is not
           there and downloads nothing. A mark counts only where it sits on the image itself. */
        var box = el.getBoundingClientRect();
        if(box.width < 2 || box.height < 2) return false;
        /* The link this picture sits in names a video. Reading the running time off the card
           is not enough on its own: bilibili draws its duration in a span the walk below does
           not always reach, so its cover was taken for a photograph and the button downloaded
           18.98 kB of thumbnail instead of the video. Where the card's own href says "video",
           the cover is standing in for one, whatever the badge looks like.

           Only the words that can mean nothing else. "live" and "movie" were left out on
           purpose: a news site writes /live/ for a text liveblog full of photographs, and
           treating those as video cards would stop every picture there being downloaded. */
        var lk = null;
        try{ lk = el.closest ? el.closest('a[href]') : null; }catch(eC){}
        if(lk && lk.href){
            var mksHref = ('' + lk.href).split('?')[0];
            if(/\/(video|videos|watch|shorts|reel|reels|embed)(\/|$)/i.test(mksHref)) return true;
        }
        var over = function(r){
            var w = Math.min(box.right, r.right) - Math.max(box.left, r.left);
            var h = Math.min(box.bottom, r.bottom) - Math.max(box.top, r.top);
            return w > 0 && h > 0;
        };
        var node = el;
        for(var d = 0; d < 5 && node; d++){
            if(node.querySelectorAll){
                var vids = node.querySelectorAll('video');
                for(var v = 0; v < vids.length; v++){
                    if(over(vids[v].getBoundingClientRect())) return true;
                }
                var marks = node.querySelectorAll('[class*="play" i],[aria-label*="play" i],[class*="duration" i]');
                for(var i = 0; i < marks.length; i++){
                    var r = marks[i].getBoundingClientRect();
                    if(r.width > 10 && r.height > 10 && over(r)) return true;
                }
            }
            /* A running time written beside the picture - "1:12" - but only while the node is
               still about this one picture. Once it has grown past the image it is the page. */
            /* Hours count too. The pattern stopped at minutes and seconds, so a card marked
               1:11:51 - an hour long talk on rumble - matched nothing, was taken for a
               photograph, and the button downloaded its thumbnail instead of the video. */
            /* And a card that is streaming says LIVE where it would otherwise print a running
               time. Same thing: the poster stands for a video, not for a picture. */
            var nr = node.getBoundingClientRect();
            if(nr.height < box.height * 2.5){
                var txt = (node.innerText || '');
                if(txt.length < 400 && /(^|\s)\d{1,2}:\d{2}(:\d{2})?(\s|$)/.test(txt)) return true;
                if(txt.length < 400 && /(^|\s)LIVE(\s|$)/.test(txt)) return true;
            }
            node = mksUp(node);
        }
    }catch(e){}
    return false;
}

/* A card's picture where the site paints it as a background rather than placing an <img>.
   Odysee builds every thumbnail that way, so querySelectorAll('img') found nothing on it and no
   button was ever drawn. Only elements that already declare one are asked - reading the computed
   style of every element on a feed is far too expensive to do four times a second. */
function mksBgUrl(el){
    try{
        if(!el || el.tagName === 'IMG') return '';
        var v = '';
        var inline = el.getAttribute && el.getAttribute('style');
        if(inline && inline.indexOf('background') >= 0){ v = inline; }
        else { v = (window.getComputedStyle(el).backgroundImage || ''); }
        /* Any url(...), not only one that spells out its scheme. A poster given as
           //host/path or as /path is just as real, and requiring "http" here meant a player
           whose cover was written that way looked to the script like no cover at all. */
        var m = v.match(/url\((?:'|")?([^'")]+)(?:'|")?\)/);
        if(!m) return '';
        var u = m[1].trim();
        if(u.indexOf('data:') === 0) return '';
        if(u.indexOf('//') === 0) return location.protocol + u;
        if(u.indexOf('/') === 0) return location.origin + u;
        if(u.indexOf('http') !== 0) return '';
        return u;
    }catch(e){ return ''; }
}

function mksBgElements(){
    var out = [];
    try{
        var sel = '[style*="background-image"],[class*="thumb" i],[class*="poster" i],[class*="cover" i]';
        var cand = mksDeepAll(sel);
        var lim = Math.min(cand.length, 300);
        for(var i = 0; i < lim; i++){
            if(!mksBgUrl(cand[i])) continue;
            /* A backdrop is not a picture. This is here for the sites that paint a card's own
               thumbnail as a background - odysee does - and those elements hold nothing: the
               poster is all they are. A page wrapper with a decorative background holds the
               page, and imgur's is exactly that, so a tap on its button downloaded
               desktop-assets/homebg.png, the wallpaper behind the feed. Anything with media of
               its own inside it is the room, not the picture on the wall. */
            try{
                if(cand[i].querySelector && cand[i].querySelector('img,video,iframe')) continue;
            }catch(eq){}
            out.push(cand[i]);
        }
    }catch(e){}
    return out;
}

function stillImageOf(img){
    try{
        /* currentSrc, not the attribute: tumblr sets none and serves everything through
           srcset, so reading the attribute found nothing at all. */
        var src = img.currentSrc || img.src || mksBgUrl(img) || '';
        if(src.indexOf('http') !== 0) return '';
        /* Picture formats. webp is included because that is what 9gag serves its still posts
           as - measured on the device, a picture post's own page carries _460swp.webp, exactly
           the shape a video's preview has, so the format says nothing about which it is. What
           does say is [looksLikeVideoCard]. */
        /* The whole url, decoded, not just the path. An image served through a resizer has no
           extension of its own - espn hands its photos over as
           a.espncdn.com/combiner/i?img=%2Fphoto%2F...jpg&w=920 - and testing the path alone
           said "not a picture", so the photo was taken for a video card: the button opened a
           video that was not there and nothing was downloaded. The name of the file being
           resized is in the query, which is why the query is read too. */
        var mksProbe = src;
        try{ mksProbe = decodeURIComponent(src); }catch(eDec){}
        /* An <img> that has loaded IS a picture, whatever its url looks like. Requiring a
           file extension refused vimeo outright: its posters come from i.vimeocdn.com with no
           extension at all, so every card on the staff picks feed was passed over and the page
           reported no media whatsoever. A decent natural size is asked for instead, which is
           what keeps logos and avatars - the reason the extension test was here - out. */
        var mksNamed = /\.(jpe?g|png|pnj|gif|webp|avif)([?&#/]|$)/i.test(mksProbe);
        if(!mksNamed){
            var mksNw = img.naturalWidth || 0;
            var mksNh = img.naturalHeight || 0;
            if(img.tagName !== 'IMG' || mksNw < 200 || mksNh < 120) return '';
        }
        var best = src;
        var bestW = 0;
        var set = img.getAttribute('srcset') || '';
        if(set){
            var parts = set.split(',');
            for(var i = 0; i < parts.length; i++){
                var bits = parts[i].trim().split(/\s+/);
                var u = bits[0];
                var w = parseInt((bits[1] || '').replace(/[^0-9]/g, ''), 10) || 0;
                if(u && u.indexOf('http') === 0 && w > bestW){ bestW = w; best = u; }
            }
        }
        return mksFullSize(best);
    }catch(e){ return ''; }
}

/* The full picture, where the page is showing a shrunken copy of it.
   Imgur lays a feed out with i.imgur.com/<id>_d.webp?maxwidth=520&shape=thumb - 44 kB of
   thumbnail - while the picture itself is i.imgur.com/<id>.webp, and downloading the first one
   is downloading the preview rather than the post. The query is what says "thumb" here, so it is
   dropped along with the _d that goes with it. Only for hosts that are known to work this way:
   a size in a url is not always a thumbnail, and guessing wrong turns a working download into a
   404. */
function mksFullSize(url){
    try{
        if(/(^|\.)imgur\.com\//.test(url)){
            return url.split('?')[0].replace(/_d(\.[a-z0-9]+)$/i, '$1');
        }
        /* Espn's resizer takes the size it should serve in the query; without it the photo
           comes back whole. */
        if(/[a-z0-9]*\.espncdn\.com\/combiner\//i.test(url)){
            return url.replace(/([?&])(w|h|scale|cquality|location)=[^&]*/gi, '')
                      .replace(/[?&]$/, '')
                      .replace(/\?&/, '?');
        }
        /* A resizer that carries the original url inside its own path: odysee serves
           thumbnails.odycdn.com/optimize/s:390:220/quality:85/plain/<the real url>. */
        var mksPlain = url.indexOf('/plain/http');
        if(mksPlain > 0){ return url.slice(mksPlain + 7); }
        /* Reddit is deliberately left alone. Its previews look like a resized copy of a file
           that also lives on i.redd.it, and rewriting them there answers 403: the whole query
           is signed, so the picture cannot be asked for by any other name. Measured on r/pics -
           the rewrite turned a download that worked into one that failed. */
    }catch(e){}
    return url;
}

function coverNear(v){
    try{
        var node = v.parentElement;
        var best = '';
        var bestArea = 0;
        var mksLazy = '';
        for(var d = 0; d < 4 && node; d++){
            var imgs = node.querySelectorAll ? node.querySelectorAll('img') : [];
            for(var i = 0; i < imgs.length; i++){
                /* currentSrc as well: a card that serves its cover through srcset sets no src
                   attribute at all, and reading only the attribute found nothing. */
                /* data-src as well: a cover that has never been on screen is often still
                   waiting behind a lazy loading attribute, and that is exactly the state a
                   poster is in on a feed card whose video started without it. */
                var src = imgs[i].currentSrc || imgs[i].getAttribute('src') ||
                    imgs[i].getAttribute('data-src') || '';
                if(src.indexOf('http') !== 0) continue;
                /* The picture's own size, not the size it is drawn at. A poster is hidden the
                   moment its video starts - and this runs on the press, by which time the
                   player is usually playing - so measuring the rect skipped the very image it
                   was looking for. Measured on the device: imgur and veoh both handed the sheet
                   an empty thumbnail that way, and the sheet showed a blank grey square under a
                   perfectly good title and size. naturalWidth is there whether or not the
                   element is on screen. */
                var r = imgs[i].getBoundingClientRect();
                var w = imgs[i].naturalWidth || r.width;
                var h = imgs[i].naturalHeight || r.height;
                /* Nothing measurable at all means the picture has not loaded - imgur leaves a
                   card's cover in that state once its video is playing. It is still the cover,
                   so it is kept as a last resort rather than thrown away. */
                if(w < 1 && h < 1){ if(!mksLazy) mksLazy = src; continue; }
                if(w < 80 || h < 80) continue;
                var area = w * h;
                if(area > bestArea){ bestArea = area; best = src; }
            }
            if(best) return best;
            /* Some players paint the cover as a background rather than placing an <img>. */
            var bg = mksBgUrl(node);
            if(bg && bg.indexOf('http') === 0) return bg;
            node = mksUp(node);
        }
        if(mksLazy) return mksLazy;
    }catch(e){}
    return '';
}

/* How long the media on the card runs, in seconds, or 0 where nothing can say.
   The player itself is the only thing on a page that knows, and it knows as soon as it has read
   its own header - so a press hands the time over and the app has it for every site, not just the
   few whose parser states it. A player with nothing loaded reports 0 and a live one infinity;
   both are sent as they are and refused on the other side, where the rule can be tested. */
function mksRunningTime(el){
    try{
        if(!el || el.tagName !== 'VIDEO') return 0;
        var d = el.duration;
        return (typeof d === 'number') ? d : 0;
    }catch(e){ return 0; }
}

/* card: the anchor a poster sits in, null when the element is a live <video> */
/* A poster the page can actually be asked for. A data: uri here is the placeholder a site
   paints while the real cover loads - nytimes sets every video's poster to a few hundred bytes
   of base64 blur - and it is not artwork: the app will not fetch one, so the sheet opened with
   a correct title and size over a grey square. Treated as no poster at all, which sends the
   search on to the cover sitting on the card. */
function mksUsablePoster(u){
    if(!u) return '';
    var v = '' + u;
    if(v.indexOf('data:') === 0 || v.indexOf('blob:') === 0 || v.indexOf('about:') === 0) return '';
    return v;
}

function makeBtn(el, card){
    var b = document.createElement('div');
    b.className = 'mks-dl-btn';
    /* Everything the button needs to be seen, set on the element itself rather than left to the
       injected stylesheet. A site may forbid inline styles outright - mastodon serves
       style-src 'self' with a nonce, and chromium then refuses the <style> block this script
       adds - and the class carried position:fixed, the circle and the colour, so the button was
       built, placed and clickable while being invisible to the reader. Properties set through
       the CSSOM like these are not what a style-src directive blocks. */
    b.style.setProperty('position', 'fixed', 'important');
    b.style.setProperty('z-index', '2147483647', 'important');
    b.style.setProperty('border-radius', '50%', 'important');
    b.style.setProperty('background-color', '#ef2b2b', 'important');
    b.style.setProperty('background-repeat', 'no-repeat', 'important');
    b.style.setProperty('background-position', 'center', 'important');
    b.style.setProperty('box-shadow', '0 2px 6px rgba(0,0,0,0.45)', 'important');
    b.style.setProperty('cursor', 'pointer', 'important');
    b.style.setProperty('pointer-events', 'auto', 'important');
    b.style.setProperty('display', 'block', 'important');
    /* The icon sits on top of that colour, so where a page also refuses data: images the
       button is still a red disc the reader can see and press. */
    b.style.setProperty('background-image', "url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAGwAAABsCAMAAAC4uKf/AAABVlBMVEUAAAD8NTX/Ojr3MzP0MTH/OTnuLCzdISHSHR30MDD0MDDwLy/PGBjvLy/sKirbHh7iHh7uHx/6NDTPGRn4NDTPGBjwLy/wLy/OGBjQGhrPGRnrLCznKCj3MzPPGRn1MTHOGRnxLy/QGhrvLi7PGRneIyPtLCzPGBjPGBjsLCzsLCzQGhrOGBjRGhrnKyvmJibpJibQHBzsKSnoKSnYHx/xLy/PGBjbISHOGBjPGBjtLCziJSXQGRnmJyfPGhrOGhrPGhrnKSnoKirsKSn/ODj////OGBj/iYn/qan+Nzf/lpb/a2v/jo75NDT9Njb/+/vcISHQGRnxLi7tLCz/Zmb1MTH//f3/9/f/eXn/XV3qKyvoKSnlJyfTGxv/f3//c3P/Q0P/8/PgJCTaICDYHx/WHR3/8PD/m5v/hob/TU3/PT3/kZH/aWn/YmL/xMT/sbH/oaH/jIwF1IN/AAAARHRSTlMA/gTy6gmmMxX95MzGxTceEQj6+ff01tCxi4V0Z/Tv7uzay7u2qqiek4WEbGlOKigiG1738OXf1tK+taV8W1BEOpSTXT+do+YAAAP6SURBVGjevdjXVxNBFMfxu7sxnXRIpXcBAXuvP0dHzZJiLCkgAvb6/78Ioi6GlJndnf285uF77jl375kTkhErLmR9Rialca6lMoYvu1CMkQJnSrOTJnowJ2dLZ8hFG34fxwDc599waablMMdQPLzsfL71EQ2CtJF1ciIQMSHBjATIrnNjHJL42DmyQ/drsEHz6yTt/DhsGn8gu4IROBCRWsyYAUeMGAk7q8Eh7SwJGoELRkhEchqumE7SUIkwXBJO0BBxA64x4kPmMuAiY+BsyTBcFdapv2m4bFp455V+AWehQJ+vO6ZBAS3W8/YaUMLodZUjUCRCp5yHMuepiz4OZcZ1+p8fCvm73jYaFNLO0UljEPPxUbfXEDBGJwQ4xDx92O0ZBPCAwNqLxGTXf91UHTPXuw6wyph1kM9o6mPa36O1DPUxLNOxsBexMP22wb2I8Q3rUqmNWTfL503M93sXuTcxfrSPJXgTQ4mIZuFRbJaIJuFRbJKITHgUM4li8CqGGBUBeBQr0gKG+vDkn++nYt+sHz9/wkALlMVQr989FLG1g8Gy5INYzXkLPjLgTm3rFYYxKAOh2q7zFjKUgpCvu45bSJEGF2ovtiFAIw5Bz3cdtsCJQ7j20lkLnDQI+/Kyd+sDxGiUgkzNSQspykDC0x61RxCVIQNwVPsJYQb5IGXnpe2jDx9lIWdn62TrMyRkyQ+LdO0JZPipCFmvtuy1UKQAJFm195ATIJmv2qrZaWnW61vK9pZUy3p/X4cN2y9+QNZ1IroFO15D2i0i0jV4QtPp0CV44hIduQdP3KcjwTI8UA5af1wpN0bHShjo02NRH9FfiY7pIQzy5qGo5+grpNMfN9THbtBfwZDqWChI/8yrjs2TZTOkNhbapBPm+YBtfCSq3zbyeTopeAEKXQjSf1bKUKaxQl2uQJkr1C3QgiKtAJ2Sr0GJWp5O02fKUKA8o1MP8YsmXGdejFNPa3scLuN7a9RHocJdblUK1NdihbvbWqT+9FyFu9nK6TRAMrpfhkvK+9EkDZSIdhpwRaMTTdAQyVy1DhfUq7kkDaUvsrcaHNLeskWdRBRYpwZHah1WIEFrE/aGs8aaWCNh8Sirtjhs4a0qi8ZJgp5nrFOHDfUOY3md5ARmGDtompBiNg8YmwmQvNUJxqrtMoSV21XGJlbJluBSmjG21ywLlZp7jLH0UpDs2syPskP7rQYGarT22aHR/CY5ESxMsSPVSrtW7jlRrV2psiNThSA5pd/Opdmx6kGl3azXa6FGI1Sr15vtykGVHUvnbuvkisTq3CgbYHRuNUEu0u/evDbaM3Tt5l2dFIjfWVmai169PJVOT12+Gp1bWrkTJwm/AItAhX6yV/qAAAAAAElFTkSuQmCC')", 'important');
    b.style.setProperty('background-size', '100%', 'important');
    var fire = function(e){
        e.preventDefault();
        e.stopPropagation();
        if(e.stopImmediatePropagation){ e.stopImmediatePropagation(); }
        try{
            /* On a page that holds one piece of media, that page is the media's own page -
               there is no card to point at, and a link picked off it is some other page of the
               site. Vimeo's player sits beside links to its own marketing pages, and one of
               those named a download "online video hosting". */
            /* On a page holding one video that video's page is this one - except where what was
               pressed is a poster with a card of its own. Imdb's film page is read as one
               media's page and its slate links to the trailer's own page, so handing over the
               film page offered nothing at all; the poster's card is the page that has it. */
            var mksPosterCard = el.tagName !== 'VIDEO' && el.tagName !== 'IFRAME' && card && card.href;
            var href = (window.mksSingle && !mksPosterCard)
                ? location.href
                : ((card && card.href) || findLink(el) || location.href);
            var poster = '';
            if(el.tagName === 'IMG'){ poster = mksUsablePoster(el.src); }
            else if(el.getAttribute){ poster = mksUsablePoster(el.getAttribute('poster')); }
            /* A card whose cover is painted as a background rather than placed as an
               <img>: odysee, bitchute and coub all build their feeds that way, and the
               element tracked there is a plain div. Neither branch above can read it, so
               the sheet opened with a correct title and size over a blank grey square. */
            if(!poster && el.tagName !== 'VIDEO'){ poster = mksUsablePoster(mksBgUrl(el)); }
            /* Snackvideo and moj set no poster on their players, so their downloads had no
               artwork at all. The cover is still on the card as an image behind the video -
               take the biggest one there. It cannot be read off the video itself: the frame is
               cross origin, so a canvas of it is tainted and refuses to be read. */
            if(!poster && el.tagName === 'VIDEO'){
                poster = (window.mksSingle ? ogImage() : '') || coverNear(el) || frameOf(el);
            }
            /* Still nothing, and this is the only video the page holds: the page's own cover is
               this video's. streamable and archive.org set no poster on their player and have
               no card around it, so their downloads came with a grey square. A feed has many
               videos and never takes this, so one card is never given the page's cover. */
            if(!poster && el.tagName === 'VIDEO' && mksDeepAll('video').length === 1){ poster = ogImage(); }
            /* A picture post. Its card holds a still and no video anywhere - the post's own
               page has none either - so the picture is the download, and it is right here. A
               video's card carries an animated preview instead (webp or gif on 9gag), which is
               what keeps a cover from being handed over in place of the video behind it. */
            /* A background picture counts as the download only where nothing links away from
               it. Odysee paints every card's poster as a background inside the card's own link,
               and taking that was taking the poster - 390x220 of thumbnail - in place of the
               video it stands for. With a link there, the card is opened instead, the same as
               any other poster. */
            /* On a site the app can parse, a card's poster is never the download - the card's
               own page is, and the parser answers it with the real media whatever that turns out
               to be. Pinterest is the case in point: a video pin shows a still with a duration
               badge the card test does not recognise, so the poster was taken and a 13 kB
               thumbnail was offered for a nine second video. Asking the parser costs nothing for
               a picture pin either, since it answers those with the picture. */
            var mksPic = ((el.tagName === 'IMG' || (mksBgUrl(el) && !card)) &&
                !looksLikeVideoCard(el) && !(window.mksParserSite && !window.mksSingle))
                ? stillImageOf(el) : '';
            /* A picture's own description, where the page keeps one. Pinterest names every pin
               in the image's alt text and nowhere else on the card, so without this its
               downloads were all called "Unknown". */
            /* And that page states its name in its own og tag, which beats anything guessed
               at from the dom around the player. */
            var mksName = window.mksSingle ? (ogTitle() || findTitle(el)) : findTitle(el);
            var mksAlt = el.tagName === 'IMG'
                ? (el.getAttribute('alt') || '').replace(/\s+/g, ' ').trim()
                : '';
            /* Alt text only where the card says nothing. It describes the picture rather than
               naming the post, and where a post has both the post's own words are the name a
               reader would give it - 9gag writes an accessibility description into alt
               ("This post features a humorous juxtaposition where...") while its heading says
               what the post is. */
            if(!mksName && mksAlt){ mksName = mksAlt.slice(0, 120); }
            /* Still nothing on the card - then the page itself, which on a page holding one
               video is that video's name. */
            if(!mksName){ mksName = pageTitle(); }
            /* A video's element src is only handed over on a feed. On a page holding one
               video the player has usually shown an advert first, and the element is still
               pointing at it - ted handed over 788 kB of pre-roll that way - and the real
               stream is adaptive anyway, which is what the sniffer is there for. A picture is
               different: it is the file itself, wherever it is. */
            /* Nothing can be read out of a frame, so nothing is claimed about it: the page it
               sits on is handed over and the app resolves the video from there. */
            /* A card on a feed the app can parse, with no link to read it by. Pinterest's grid
               is this shape: the tile carries no anchor, no <video> and no running time, so
               nothing here can tell a video pin from a picture pin - and the poster was handed
               over as the download, which is why a video came back as a 13 kB thumbnail. The
               tile itself knows where it goes, so it is pressed: pinterest opens the pin, and on
               that page the parser answers properly - the video for a video pin, every page for
               a story. Nothing is claimed here, because nothing here is known. */
            /* Only where the site's posts live at a known path, which is what makes a tile with no
               link a post worth opening. Imdb's listings play a trailer inline with no link
               anywhere around it - the press pressed the tile, nothing opened, and the app was
               never told - so there the press is answered from the page like any other. */
            if(el.tagName !== 'VIDEO' && el.tagName !== 'IFRAME' &&
               !card && !mksPic && !window.mksSingle && window.mksParserSite && window.mksPostPath){
                try{ el.click(); }catch(xP){}
                try{
                    var mksTileUp = el.parentElement;
                    for(var mksTd = 0; mksTd < 3 && mksTileUp; mksTd++){
                        if(mksTileUp.click){ mksTileUp.click(); }
                        mksTileUp = mksTileUp.parentElement;
                    }
                }catch(xP2){}
                return false;
            }
            var mksFrame = el.tagName === 'IFRAME';
            /* On a feed the app can parse, nothing on the tile is the download - the pin behind
               it is. The tile the grid branch above presses is the one with no link at all;
               pinterest's home feed wraps the very same tile in an anchor, so that branch was
               skipped and the element's own src was handed over instead - a 29 kB thumbnail
               offered for a video pin, which is what "it still downloads images" meant. Here the
               card's page is sent on its own and the parser answers it: the video for a video
               pin, every page for a story pin. */
            /* Only where there is a card to read. On a feed the parser knows, the card's page
               answers better than anything on the tile - but imdb's listings play a trailer per
               row with no link anywhere around them, each its own file, and withholding those
               left every press to be answered from whatever stream the page had already heard:
               the same title and the same size for every video on the page. */
            var mksParserFeed = window.mksParserSite && !window.mksSingle && !!card;
            var mksMedia = (mksFrame || mksParserFeed)
                ? '' : (mksPic || (window.mksSingle ? '' : directSrc(el)));
            if(mksFrame){ href = location.href; }
            MediaDownloaderBridge.genericMediaRequested(
                href, mksName, poster, mksMedia,
                el.tagName === 'VIDEO' || !!mksPic, !!mksPic, mksRunningTime(el));
            if(el.tagName === 'VIDEO'){
                /* Restart so the player asks for the stream again and the sniffer sees it. A player
                   streaming through MSE has its media buffered and asks for nothing when merely
                   restarted, so a page with several of them - imdb lists one per row - could not
                   tell which was pressed and answered every press with the same video. load()
                   makes that player fetch its own stream again; it is only ever called on the
                   element the reader pressed. */
                try{ el.pause(); el.currentTime = 0; }catch(x){}
                if(!directSrc(el)){ try{ el.load(); }catch(xL){} }
                var p = el.play();
                if(p && p.catch){ p.catch(function(){}); }
                /* A player that has not loaded anything yet cannot be restarted from its element:
                   youporn's has no source until its own play control is pressed, so the press
                   started nothing and the sheet waited for a stream that never came. */
                if(!el.currentSrc){ mksPressPlay(el); }
            }else if(card && !mksPic && !window.mksParserSite){
                /* A card that has not started has no <video> anywhere - not on the card and,
                   measured on 9gag, not on the post's own page either until it is played by
                   hand. There is nothing to read and nothing yet to catch, so the tap opens the
                   post and the app waits there rather than showing a spinner over a stream that
                   does not exist. The moment the video is played, the sheet opens by itself.

                   Not on a site the app can parse, though: there the card's own link is all the
                   app needs, and it answers with that video's name and qualities without the
                   page ever being opened. */
                /* Unless the card points at the page we are already on. Espn hangs the
                   story's own url on its player, so opening it went nowhere: the press was
                   taken, the card was clicked, and nothing whatever happened - which reads as a
                   button that does not work. There the player has to be started where it
                   stands, the same as a reader would, so it asks for its stream. */
                var mksHere = location.href.split('#')[0];
                var mksTo = (card.href || '').split('#')[0];
                if(mksTo && mksTo !== mksHere){
                    /* The app takes it from here: it reads the card's page for the media it
                       names and only opens the card when the page names nothing. Navigating
                       here first took the reader off the feed on every tap, download or not. */
                }else{
                    try{ el.click(); }catch(x1){}
                    try{
                        var mksHost = el.parentElement;
                        if(mksHost && mksHost.click){ mksHost.click(); }
                    }catch(x2){}
                }
            }else if(!card && !mksPic && !window.mksParserSite){
                /* No link on the card at all - the poster is all there is, so press it. */
                try{ el.click(); }catch(x3){}
                mksPressPlay(el);
            }
        }catch(err){ console.log('[mks] tap failed: ' + err); }
        return false;
    };
    /* The tap has to be taken before the page sees it. Assigning onclick and ontouchstart is
       not enough on a site that acts on the press rather than the click - dailymotion opens its
       player full screen on pointerdown, so the button appeared to do nothing at all while the
       video took over the screen. Every press event is caught here, in the capture phase, and
       stopped where it is; the work is done once, on the release. */
    var mksArmed = false;
    ['pointerdown', 'touchstart', 'mousedown'].forEach(function(name){
        b.addEventListener(name, function(e){
            mksArmed = true;
            e.preventDefault();
            e.stopPropagation();
            if(e.stopImmediatePropagation){ e.stopImmediatePropagation(); }
        }, true);
    });
    ['pointerup', 'touchend', 'mouseup', 'click'].forEach(function(name){
        b.addEventListener(name, function(e){
            e.preventDefault();
            e.stopPropagation();
            if(e.stopImmediatePropagation){ e.stopImmediatePropagation(); }
            if(!mksArmed && name !== 'click'){ return; }
            mksArmed = false;
            fire(e);
        }, true);
    });
    (document.body || document.documentElement).appendChild(b);
    tracked.push({ b: b, v: el, c: card || null });
}

/* Whether the media is really the thing painted where it says it is.
   A rectangle is not proof. Wikipedia collapses a section by leaving its contents laid out and
   clipping them with a container of no height, so every picture in a closed section still
   reports a size and a position on screen - measured on the device, 180x127 at the top of the
   viewport - and buttons were drawn across the list of section headings, over nothing at all.
   Asking the page what is actually drawn at that point settles it. The answer is allowed to be
   something else nearby: a player's own overlay sits over its video, and a shadow root reports
   its host, so the chain above the media and the neighbourhood just around it both count. */
function mksReallyThere(el, r){
    /* Is this media actually shown where its box says it is - asked of the layout, not of the
       hit tester. document.elementFromPoint and elementsFromPoint both answer "what would a
       finger touch here", and that is a different question: an element with pointer-events
       none is painted perfectly well and is in no stack at all, which is how the nytimes video
       listing builds its cards - so every one of its 65 buttons was hidden as "not there".
       What this needs to catch is media clipped away or switched off by something above it:
       wikipedia's collapsed sections keep their thumbnails at full size inside a container of
       no height, so the boxes look right and nothing is on screen. Both of those are visible
       in the computed style and the ancestors boxes, with no hit testing at all. */
    try{
        /* A player fades its own video out until it plays and shows a preview in the same box:
           vimeo sets opacity 0 on the layer holding the <video>. That is the player's business,
           not the video being gone, so a transparent layer inside the player's own box is let
           through. Anything transparent above the player still hides it. */
        var mksPlayer = el.tagName === 'VIDEO' ? mksPlayerBox(el) : null;
        var node = el;
        for(var i = 0; i < 14 && node && node.nodeType === 1; i++){
            var st = null;
            try{ st = window.getComputedStyle(node); }catch(eS){ st = null; }
            if(st){
                if(st.visibility === 'hidden' || st.visibility === 'collapse')return false;
                if(st.display === 'none')return false;
                var op = parseFloat(st.opacity);
                var mksInPlayer = mksPlayer && node !== mksPlayer && mksPlayer.contains(node);
                if(!isNaN(op) && op === 0 && !mksInPlayer)return false;
                if(node !== el){
                    var ov = '' + (st.overflow || '') + ' ' + (st.overflowX || '') + ' ' +
                        (st.overflowY || '');
                    if(ov.indexOf('hidden') >= 0 || ov.indexOf('clip') >= 0 ||
                       ov.indexOf('scroll') >= 0 || ov.indexOf('auto') >= 0){
                        var b = node.getBoundingClientRect();
                        var w = Math.min(r.right, b.right) - Math.max(r.left, b.left);
                        var h = Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top);
                        if(w <= 1 || h <= 1)return false;
                        /* Mostly clipped away is the same as gone: a quarter of it left is the
                           least that is worth offering to download. */
                        if(r.width * r.height > 0 &&
                           (w * h) < (r.width * r.height * 0.25))return false;
                    }
                }
            }
            node = mksUp(node);
        }
        /* Nothing in the layout says it is hidden. Ask the hit tester too, but only where its
           answer means anything: an element the page has taken out of hit testing - nytimes
           sets pointer-events none on every card image - is painted perfectly well and appears
           in no stack, so there the layout is the whole answer. Where the media can be touched,
           being absent from its own stack means it is not painted there: that is what catches
           wikipedia's article thumbnails still claiming a box at the foot of the page, which is
           where the phantom buttons over its licence text came from. */
        var pe = '';
        try{ pe = (window.getComputedStyle(el).pointerEvents || ''); }catch(eP){ pe = ''; }
        if(pe === 'none' || !document.elementsFromPoint) return true;
        var L = Math.max(r.left, 0), T = Math.max(r.top, 0);
        var R = Math.min(r.right, window.innerWidth), B = Math.min(r.bottom, window.innerHeight);
        if(R <= L || B <= T) return true;
        var x = Math.round((L + R) / 2), y = Math.round((T + B) / 2);
        if(x < 0 || y < 0 || x >= window.innerWidth || y >= window.innerHeight) return true;
        var stack = document.elementsFromPoint(x, y);
        /* The document's hit tester stops at shadow roots and answers with their host: pexels
           plays its video inside mux-player > mux-video, two shadow roots down, so the stack
           held mux-player and never the video, and a button built for the one video on the page
           was hidden as "not painted". The hosts the media sits in stand for it. Only hosts:
           an ordinary ancestor - body, the page's wrapper - is in every stack. */
        var hosts = mksShadowHostsAbove(el);
        /* And the player's own overlay: its controls are laid over the video as a sibling, not
           inside it. Vimeo's are, and every button on its videos was hidden as "not painted".
           The player is the box around the video that is no bigger than it. */
        var player = el.tagName === 'VIDEO' ? mksPlayerBox(el) : null;
        for(var k = 0; k < stack.length; k++){
            if(stack[k] === el) return true;
            if(el.contains && el.contains(stack[k])) return true;
            if(hosts.indexOf(stack[k]) >= 0) return true;
            if(player && player.contains(stack[k])) return true;
        }
        return false;
    }catch(e){ return true; }
}

/* The shadow hosts el sits inside, innermost first: where a walk up leaves a shadow root. */
function mksShadowHostsAbove(el){
    var hosts = [];
    var node = el;
    for(var i = 0; i < 60 && node; i++){
        if(node.parentElement){ node = node.parentElement; continue; }
        var root = null;
        try{ root = node.getRootNode ? node.getRootNode() : null; }catch(e){ root = null; }
        if(!root || !root.host) break;
        hosts.push(root.host);
        node = root.host;
    }
    return hosts;
}

function place(t){
    var r = t.v.getBoundingClientRect();
    /* A player that has not been started yet can leave its <video> at no size at all - video.js
       does, and paints the poster on the wrapper around it - so the button was built and then
       hidden for being too small, and bitchute's video page ended up with no button on a video
       that was plainly there. Where the element itself is too small, the box it sits in is
       used instead. */
    /* Only for a player. The fallback exists because video.js leaves its <video> at no size
       at all and paints the cover on the wrapper around it; an <img> or a background that has
       no size is simply not on screen, and borrowing an ancestor's box for one put a button on
       the empty white space below rumble's player, offering media that was not there.
       A frame is not a player either once it has no size: 9gag keeps a 300x46 advert frame far
       below the feed, and with a borrowed box its button was drawn across the top of the page -
       pressing it downloaded the advert. A real player frame has its own size and needs none of
       this. */
    /* A player whose own element has no size is standing on its wrapper's box, and what is
       painted there is the wrapper's poster rather than the player - so the check below is not
       asked of it. */
    var mksBorrowed = false;
    if((r.width < 95 || r.height < 65) && t.v.tagName === 'VIDEO'){
        var box = t.v;
        for(var up = 0; up < 3 && box; up++){
            box = mksUp(box);
            if(!box || !box.getBoundingClientRect) break;
            var br = box.getBoundingClientRect();
            if(br.width >= 95 && br.height >= 65){ r = br; mksBorrowed = true; break; }
        }
    }
    /* The same floor the scan uses, not a higher one. The scan accepts a picture from
       160x90 and this hid anything under 100 tall, so bitchute - whose thumbnails measure 96 -
       had a button built for every card and then hidden on every one of them: no button at all,
       and nothing in the log to say why. */
    /* And enough of it on screen to be worth offering. A sliver is not something the reader
       is looking at, and its button does not stay where the sliver is: place() clamps it to the
       edge, so it lands on top of the button belonging to the media they are looking at. Both
       stacked pairs on reddit are this - the next picture in a gallery, 43 pixels of 422 past
       the right edge, and the post above pinned under the toolbar on the way out.

       Half of it, or 200px, whichever is less. Plain "half" would never be satisfied by a
       picture taller than the screen, and those are exactly the ones a reader scrolls through
       slowly - their button would blink out halfway down. */
    var mksSeenW = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
    var mksSeenH = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
    var hide = r.width < 95 || r.height < 65
        || mksSeenW < Math.min(r.width * 0.5, 200)
        || mksSeenH < Math.min(r.height * 0.5, 200);
    if(!hide && !mksBorrowed && !mksReallyThere(t.v, r)){ hide = true; }
    if(hide){
        t.b.style.setProperty('display', 'none', 'important');
        t.r = null;
        return;
    }
    t.b.style.setProperty('display', 'block', 'important');
    /* What this button ended up standing on - not always the element's own box, see above.
       Kept so that two buttons drawn on one piece of media can be told apart from two buttons
       on two pieces of it. */
    t.r = r;
    /* Inside the media, and inside the screen. A card wider than the viewport - reddit lays its
       pictures out that way - put the button past the right edge, where half of it was off the
       device; and a card scrolled halfway up put it on the row of vote buttons above the
       picture, because the top was only ever clamped to the toolbar. Both ends are clamped now,
       to the screen and to the media's own box. */
    /* The button is smaller on a small picture. At one size it swamped a thumbnail in a two
       column grid, covering most of what it was offering to download. */
    var size = Math.max(34, Math.min(55, Math.round(Math.min(r.width, r.height) * 0.32)));
    t.b.style.setProperty('width', size + 'px', 'important');
    t.b.style.setProperty('height', size + 'px', 'important');
    size = size + 10;
    var left = Math.min(r.right - size, window.innerWidth - size - 6);
    left = Math.max(6, Math.max(r.left + 6, left));
    var top = Math.min(r.bottom - size - 6, window.innerHeight - size - 6);
    top = Math.max(66, Math.max(r.top + 6, Math.min(top, r.top + 10)));
    t.b.style.setProperty('left', left + 'px', 'important');
    t.b.style.setProperty('top', top + 'px', 'important');
}

/* The card's own permalink, found from the poster outwards: the anchor may wrap the image, or
   sit beside it on the title only, so both the element itself and each ancestor are checked. */
/* The next element up, crossing out of a shadow root when it runs out of parents inside one.
   Reddit keeps a post's player in a shadow root and the post's link and heading outside it, so
   a plain parentElement walk stopped at the boundary and found neither. */
function mksUp(node){
    if(!node) return null;
    if(node.parentElement) return node.parentElement;
    try{
        var root = node.getRootNode ? node.getRootNode() : null;
        if(root && root.host) return root.host;
    }catch(e){}
    return null;
}

function findCardLink(el){
    /* The nearest link this media sits inside, however deep the markup nests it. Pinterest keeps
       a pin's <a> sixteen levels above the picture - one past where the walk below gives up - so
       a pin had no card at all: its button offered the grid thumbnail instead of the pin, and
       once a card was required for a post it lost its button altogether. A card's link wraps the
       card, so one far taller than the media is the page around it rather than this card's. */
    try{
        var near = el.closest ? el.closest('a[href]') : null;
        if(near && isCardHref(near.href) &&
           near.getBoundingClientRect().height <= el.getBoundingClientRect().height + 900){
            return near;
        }
    }catch(eNear){}
    var node = el;
    /* Deep enough to reach the card's link. Eight levels was not: pinterest lays a pin out as
       a stack of bare divs and keeps the <a href="/pin/..."> eleven above the image, so every
       pin came back as the feed's own url and could not be asked for by name. */
    for(var i = 0; i < 16 && node; i++){
        /* An anchor the poster sits inside is that card's own link, whatever its url looks
           like. Requiring a readable slug meant 9gag cards - /gag/aO86oWv - matched nothing, so
           every post that had not started playing yet went without a button. A link this image
           is inside cannot belong to another card, which is what makes it safe to take. */
        if(node.tagName === 'A' && isCardHref(node.href)) return node;
        node = mksUp(node);
    }
    /* Nothing wraps the poster - some listings put the link on the title beside it instead.
       Those are searched for by shape, since a link found this way could be anything on the
       card, a tag or an author among them. */
    /* And only as far up as the card itself goes. This search looks *inside* whatever it has
       climbed to, so once the walk leaves the card the whole page is in range - on rumble it
       reached the footer and came back with "Digital Accessibility Statement", which is what
       the download was then named and the page the browser was sent to. A card is its poster
       plus a title and a byline; anything much taller than that is the page around it. */
    var mksCardH = 0;
    try{ mksCardH = el.getBoundingClientRect().height; }catch(e0){}
    node = el;
    for(var j = 0; j < 16 && node; j++){
        try{
            if(j > 0 && mksCardH > 0 &&
               node.getBoundingClientRect().height > mksCardH + 900){ break; }
        }catch(e1){}
        /* Height alone is not enough when the rest of the page is hidden rather than scrolled
           away. Wikipedia's full screen viewer hides the article behind it, so the document
           collapses to one screen, the walk reached <body> inside the height it was allowed,
           and the first file link it found there belonged to a different photograph - which is
           the name the download was saved under. A node covering two cards is the listing. */
        if(j > 0 && mksCardsUnder(node) > 1){ break; }
        if(node.querySelectorAll){
            var as = node.querySelectorAll('a[href]');
            for(var k = 0; k < as.length; k++){
                /* This site's own pages only, the same as the walk above. Vimeo hangs a link to
                   its Creative Commons help article beside the player, and that url's slug
                   reads exactly like a title - so a vimeo download was named
                   "12427652203153 What do the different Creative Comm". */
                if(!isCardHref(as[k].href)) continue;
                /* A permalink does not have to read like a title. Pinterest names every pin
                   /pin/128423026863890947 - a number and nothing else - so the link sitting
                   beside the picture was found and then refused for having no slug, and the
                   tap handed over the search page instead of the pin. A long run of digits is
                   an id, which is exactly what a card link is for. */
                if(isSlugHref(as[k].href) || isIdHref(as[k].href)) return as[k];
            }
        }
        node = mksUp(node);
    }
    return null;
}

/* Whether [card] leads to a post on a site the parser reads. Only asked on those sites' feeds,
   where the post's own page is what a press hands over; everywhere else any card will do. */
function mksPostCard(card){
    if(!window.mksPostPath || !window.mksParserSite || window.mksSingle) return true;
    var href = card && card.href;
    return !!href && href.indexOf(location.origin + window.mksPostPath) === 0;
}

/* A link to another page of this same site, rather than the page itself or somewhere else. */
function isCardHref(h){
    if(!h || typeof h !== 'string') return false;
    if(h.indexOf(location.origin) !== 0) return false;
    if(h.split('#')[0] === location.href.split('#')[0]) return false;
    var path = h.substring(location.origin.length).split('?')[0].split('#')[0];
    var parts = path.split('/').filter(function(x){ return !!x; });
    if(parts.length >= 2) return true;
    /* One segment can still be a video's own page. Rumble names them
       /v6xyz-a-dead-slave-cost-1000.html - a single segment - so the walk above never
       recognised the anchor the card is actually wrapped in, climbed past it, and took a link
       from the page furniture instead. A lone segment counts when it reads like a title and
       not like a section: "about", "help" and "login" have nothing to spell out. */
    /* A bare number is deliberately NOT a card link. Vimeo names every video /76979871, and
       recognising that as the card turned out to cost more than it gave: its cards carry a
       hidden preview <video>, so once the anchor counted as a card the poster was skipped as
       "playing card, handled above" and the feed lost every button it had. Measured both ways -
       8 buttons without this, none with it. */
    return parts.length === 1 && isSlugHref(h);
}

/* Media the reader did not come for. A promoted post is built exactly like a post - same
   markup, same size, its picture is real media - so nothing about the picture itself separates
   them; what does is the box it sits in. Reddit names it: shreddit-ad-post. Matched a segment at
   a time so that "shreddit-async-loader" is not read as an advert for the letters in "loader". */
/* The host a frame points at, for the checks that care which site owns the player. */
function fsrcHost(fr){
    try{
        var src = fr.getAttribute('src') || '';
        if(!src) return '';
        var a = document.createElement('a');
        a.href = src;
        return (a.hostname || '').toLowerCase();
    }catch(e){ return ''; }
}

/* An advert disclosure, matched as a line of its own so an article *about* advertising is
   not caught by the word in its prose. */
var mksAdLabel = new RegExp('(^|\\n)[ \\t]*(promoted|sponsored|advertisement|advertising|3rd party ad content|ad content)[ \\t]*(\\n|$)', 'i');

/* The same disclosure written as an attribute instead of as text. An ad slot is usually an
   iframe, and an iframe has no innerText at all, so the line test above can never see it -
   cnn labels its slot title="3rd party ad content" and nothing else, and a button was drawn
   on it. Matched against the whole attribute value, so a headline that merely contains one
   of these words is not caught. */
/* "banner" is in here as a whole attribute value only, never as part of a class name:
   tamashaweb labels its house banner alt="Banner" and the sheet offered 88 kB of it as
   the download, while a class test would also catch "hero-banner" on a real article
   picture and cost that picture its button. */
/* The word anywhere in a box that holds hardly any text. Used only with a short length
   limit, so prose is never caught by it. */
var mksAdWord = /(^|[^a-z])(advertisement|sponsored|promoted)([^a-z]|$)/i;

var mksAdAttr = new RegExp('^(ad|ads|advert|banner|promoted|sponsored|advertisement|advertising|3rd party ad content|ad content)$', 'i');
var mksAdFrameId = /(google_ads|ad[_-]?iframe|adsystem|doubleclick|^aswift)/i;

function mksAttrSaysAdvert(node){
    if(!node || !node.getAttribute) return false;
    /* alt as well: a picture says what it is there, and a house banner says "Banner". */
    var names = ['title', 'aria-label', 'alt'];
    for(var a = 0; a < names.length; a++){
        var v = node.getAttribute(names[a]);
        if(v && v.length < 60 && mksAdAttr.test(('' + v).trim())) return true;
    }
    var ident = (node.id || '') + ' ' + (node.getAttribute('name') || '');
    if(ident.length < 200 && mksAdFrameId.test(ident)) return true;
    return false;
}

/* The networks an advert is served from, and the folders a creative is filed under. Kept in
   step with isAdvertMediaUrl on the app side: the app refuses these when the download is asked
   for, and this refuses to draw a button on them in the first place, so the reader is never
   offered one. */
var mksAdHost = /(^|\.)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|2mdn\.net|amazon-adsystem\.com|adnxs\.com|adsrvr\.org|taboola\.com|outbrain\.com|media\.net|criteo\.(com|net)|pubmatic\.com|rubiconproject\.com|openx\.net|adform\.net|smartadserver\.com|casalemedia\.com|zedo\.com|teads\.tv|connatix\.com|adsafeprotected\.com|moatads\.com|revcontent\.com|mgid\.com|adsterra\.com|propellerads\.com|exoclick\.com|juicyads\.com|trafficjunky\.net)$/i;
var mksAdPathSeg = /\/(ads?|advert|adverts|advertising|advertisement|adserver|adservice|creatives?|banners?|adimages|adimg)(\/|$)/i;

/* Does this url belong to an advert rather than to the page's own media. */
function mksAdvertUrl(u){
    if(!u) return false;
    var raw = '' + u;
    if(raw.indexOf('http') !== 0) return false;
    try{
        var a = document.createElement('a');
        a.href = raw;
        if(mksAdHost.test((a.hostname || '').toLowerCase())) return true;
        if(mksAdPathSeg.test((a.pathname || '').toLowerCase())) return true;
    }catch(e){}
    return false;
}

function mksIsAdvert(el){
    try{
        /* The file itself, before anything about the markup around it. */
        if(mksAdvertUrl(el.currentSrc || el.src || '')) return true;
        if(el.tagName !== 'VIDEO' && mksAdvertUrl(mksBgUrl(el))) return true;
        var mksElH = 0;
        try{ mksElH = el.getBoundingClientRect().height; }catch(eE){}
        var node = el;
        for(var d = 0; d < 12 && node; d++){
            var tag = (node.tagName || '').toLowerCase();
            if(tag === 'ins') return true;
            if(/(^|-)(ad|ads|advert|advertisement|promoted|sponsored)(-|$)/.test(tag)) return true;
            var cls = '' + (node.className || '');
            if(typeof cls === 'string' &&
               /(^|[-_\s])(advert|advertisement|promoted|sponsored)([-_\s]|$)/i.test(cls)) return true;
            if(node.getAttribute && node.getAttribute('data-promoted') === 'true') return true;
            if(mksAttrSaysAdvert(node)) return true;
            /* A "banner" in the markup, and the file it shows. A site's own promotional strip
               carries no advert disclosure at all - pixabay's reads "LIMITED DEAL 20% off",
               udemy's "Save 30% on a year of learning" - so nothing above this recognised it and
               both were offered as downloads. The strip still says what it is somewhere: in the
               class it is laid out with, or in the folder the image is served from. */
            if(typeof cls === 'string' &&
               /(^|[-_\s])(banner|banners|promo|promotion|house-ad|housead)([-_\s]|$)/i.test(cls)){
                return true;
            }
            var mksId = '' + ((node.getAttribute && node.getAttribute('id')) || '');
            if(/(^|[-_])(banner|promo|house-?ad)([-_]|$)/i.test(mksId)) return true;
            if(node.tagName === 'A' && mksAdvertUrl(node.href)) return true;
            /* The label the reader sees, when the markup says nothing. Unsplash marks its
               advert cards with the single word "Promoted" and nothing else - no class, no
               custom element - and a button was drawn on one, which then did nothing at all
               because an advert player has no media this app can name. Matched as a line of
               its own, so an article *about* advertising is not caught by the word in its
               prose. */
            /* Only on the advert's own card: the media and a caption, not much more. Upornia's
               page column holds the player and, beside it, a banner labelled "Advertisement" -
               under 400 characters in all - and the real player was skipped as that advert. */
            if(mksElH > 0 && node !== el){
                var mksNodeH = 0;
                try{ mksNodeH = node.getBoundingClientRect().height; }catch(eH){}
                if(mksNodeH > mksElH + 200){ node = mksUp(node); continue; }
            }
            try{
                var own = (node.innerText || '');
                if(own.length < 400 && mksAdLabel.test(own)){
                    return true;
                }
                /* And the same word where the player writes its countdown beside it, so it is
                   not a line of its own: fandom's advert is a Connatix player whose whole
                   caption reads "Advertisement 0:15", and the button on it offered a download
                   named "Advertisement". Only where there is almost no other text - a box this
                   empty is a label, not an article that happens to mention advertising. */
                if(own.length < 60 && mksAdWord.test(own)){
                    return true;
                }
            }catch(eT){}
            node = mksUp(node);
        }
    }catch(e){}
    return false;
}

/* Gives a tracked poster the card link it did not have when its button was built. */
function mksRefreshCard(el){
    for(var k = 0; k < tracked.length; k++){
        if(tracked[k].v !== el || tracked[k].c) continue;
        tracked[k].c = findCardLink(el);
        return;
    }
}

/* Drops the button built for [el] and forgets it, so whatever is painted in its place can claim
   the media instead: a player hidden again after it was tracked kept the poster without one. */
function mksUntrack(el){
    for(var k = tracked.length - 1; k >= 0; k--){
        if(tracked[k].v !== el) continue;
        if(tracked[k].b && tracked[k].b.parentNode){ tracked[k].b.parentNode.removeChild(tracked[k].b); }
        tracked.splice(k, 1);
    }
}

/* Whether anything inside [node] is a video the scan has already given a button to. */
function mksTrackedVideoIn(node){
    if(!node.querySelectorAll){ return false; }
    var vs = node.querySelectorAll('video');
    for(var i = 0; i < vs.length; i++){
        if(isTracked(vs[i])){ return true; }
    }
    return false;
}

function isTracked(el){
    for(var k = 0; k < tracked.length; k++){
        if(tracked[k].v === el){ return true; }
    }
    return false;
}

/* One button per piece of media, not per element that happens to look like one.
   A card can hold several: reddit's promoted posts wrap the picture in a second <img> of the
   same size, and both passed every test below, so two buttons ended up stacked on the one
   image. Nesting is the tell - an element inside another element that already has a button, or
   one that now contains it, is the same media seen twice - and so is a card, since a card is
   one post whatever it is built out of. */
function hasButtonNear(el, card){
    for(var k = 0; k < tracked.length; k++){
        var t = tracked[k].v;
        if(t === el){ return true; }
        /* Something that is not laid out cannot be the media the reader is looking at, so it
           does not get to hold the place. Wikipedia's full screen viewer hides the whole
           article behind it - measured on the device, all 29 tracked pictures drop to 0x0 -
           and one of them still answered for the picture now filling the screen, which is why
           the viewer had no button on it at all and the full size image could not be saved. */
        try{
            var tr0 = t.getBoundingClientRect();
            if(tr0.width < 1 || tr0.height < 1){ continue; }
        }catch(e0){}
        try{
            if(t.contains && t.contains(el)){ return true; }
            if(el.contains && el.contains(t)){ return true; }
        }catch(e){}
        if(card && tracked[k].c && tracked[k].c === card){ return true; }
        /* Not related in the dom and still the same picture: a promoted post on reddit lays a
           second image of the same size over the first, and neither contains the other. Two
           boxes sitting on top of each other are one thing as far as the reader is concerned. */
        try{
            var a = t.getBoundingClientRect();
            var b = el.getBoundingClientRect();
            var w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
            var h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
            if(w > 0 && h > 0){
                var small = Math.min(a.width * a.height, b.width * b.height);
                if(small > 0 && (w * h) / small > 0.6){ return true; }
            }
        }catch(e4){}
    }
    return false;
}

/* Media a plain querySelector cannot see.
   Reddit builds every post as a web component and keeps the player inside a shadow root, so
   document.querySelectorAll('video') finds nothing on a reddit feed and no button was ever drawn
   there - the app fell back to its own floating button, which knows only what the sniffer caught
   and so opened with the page's url for a name and no artwork.
   The walk that finds shadow hosts is the expensive part - it touches every element - so the
   hosts are collected at most once every two seconds and reused in between. */
var mksHosts = [];
var mksHostsAt = 0;
function mksShadowRoots(){
    var now = Date.now();
    if(now - mksHostsAt < 2000){ return mksHosts; }
    mksHostsAt = now;
    var found = [];
    try{
        var all = document.querySelectorAll('*');
        /* Bounded on purpose: a long feed can hold tens of thousands of elements and this runs
           on the page's own thread. */
        var lim = Math.min(all.length, 4000);
        for(var i = 0; i < lim; i++){
            var r = all[i].shadowRoot;
            if(r){
                found.push(r);
                try{
                    var inner = r.querySelectorAll('*');
                    var lim2 = Math.min(inner.length, 400);
                    for(var j = 0; j < lim2; j++){
                        if(inner[j].shadowRoot){ found.push(inner[j].shadowRoot); }
                    }
                }catch(e2){}
            }
        }
    }catch(e){}
    mksHosts = found;
    return mksHosts;
}

function mksDeepAll(sel){
    var out = [];
    try{
        var top = document.querySelectorAll(sel);
        for(var i = 0; i < top.length; i++){ out.push(top[i]); }
    }catch(e){}
    var roots = mksShadowRoots();
    for(var k = 0; k < roots.length; k++){
        try{
            var els = roots[k].querySelectorAll(sel);
            for(var m = 0; m < els.length; m++){ out.push(els[m]); }
        }catch(e3){}
    }
    return out;
}

/* A video wins the place it shares with a still. The poster stays in the dom behind the player,
   and whichever of the two was tracked first would otherwise keep the button - on bitchute's
   video page that was the poster, which is hidden the moment the video starts, so the page ended
   up showing no button at all. */
function mksDropStillsUnder(el){
    try{
        var box = el.getBoundingClientRect();
        if(box.width < 2 || box.height < 2) return;
        for(var k = tracked.length - 1; k >= 0; k--){
            var t = tracked[k];
            if(t.v === el) continue;
            /* Any still, not only an <img>. A player paints its cover on a div behind the
               video - video.js calls it vjs-poster - and that div is tracked like any other
               picture, so the video found the place taken and went without a button; then the
               cover was hidden the moment it played, and its button went with it. The page ended
               up with a playing video and nothing on it. */
            if(!t.v.tagName || t.v.tagName === 'VIDEO') continue;
            var r = t.v.getBoundingClientRect();
            var w = Math.min(box.right, r.right) - Math.max(box.left, r.left);
            var h = Math.min(box.bottom, r.bottom) - Math.max(box.top, r.top);
            if(w > 0 && h > 0){
                var small = Math.min(box.width * box.height, r.width * r.height);
                if(small > 0 && (w * h) / small > 0.6){
                    if(t.b.parentNode){ t.b.parentNode.removeChild(t.b); }
                    tracked.splice(k, 1);
                }
            }
        }
    }catch(e){}
}

function scan(){
    /* What a page holding one piece of media says about itself. The buttons below are drawn
       here as well: the app's own button is not shown on these sites any more, since a button
       sitting on the media - on each picture and each video - says which one it will fetch,
       and a single button floating over the page does not. */
    if(window.mksSingle){
        /* The native button here downloads whatever the sniffer catches, and a sniffed stream
           carries no name and no artwork - a rumble video opened with the slug for a title and
           an empty grey square. The page says both in its own og tags, so hand them over once
           and let them label it. */
        var mksV = document.querySelector('video');
        var mksSrc = mksV ? directSrc(mksV) : '';
        if(mksLabelled !== location.href){
            var mksT = document.querySelector('meta[property="og:title"]');
            var mksI = document.querySelector('meta[property="og:image"]');
            var mksTv = mksT ? (mksT.getAttribute('content') || '') : '';
            var mksIv = mksI ? (mksI.getAttribute('content') || '') : '';
            if(mksTv || mksIv){
                mksLabelled = location.href;
                MediaDownloaderBridge.feedCardChanged(mksTv.slice(0, 120), mksIv, mksSrc);
                if(mksSrc){ mksMediaSent = location.href; }
            }
        }
        /* The page a poster tap opened. 9gag builds no <video> at all until the post is
           played - measured on the device, the page holds a still jpg and nothing else - so
           there is no stream to read and none to sniff, and the sheet the tap opened would wait
           for ever. Pressing play is the same trick the button uses on a feed: make the player
           ask for its stream. Only ever after a tap of ours, and once per page, so a page the
           user merely opened is never started behind their back. */


        /* A page whose post is a picture: no video anywhere, so the picture is the download.
           The app's own button here has only the sniffer to go on, and the picture arrived with
           the page and is never requested again, so it had nothing and sat on "Parsing Media". */
        if(!mksV && mksPicSent !== location.href){
            var mksBig = null;
            var mksArea = 0;
            var mksAll = document.querySelectorAll('img');
            for(var mksI = 0; mksI < mksAll.length; mksI++){
                var mksR = mksAll[mksI].getBoundingClientRect();
                if(mksR.width < 200 || mksR.height < 150) continue;
                if(!stillImageOf(mksAll[mksI]) || looksLikeVideoCard(mksAll[mksI])) continue;
                if(mksR.width * mksR.height > mksArea){ mksArea = mksR.width * mksR.height; mksBig = mksAll[mksI]; }
            }
            if(mksBig){
                mksPicSent = location.href;
                var mksPicUrl = stillImageOf(mksBig);
                MediaDownloaderBridge.feedCardChanged(
                    findTitle(mksBig) || pageTitle(), mksPicUrl, mksPicUrl, true);
            }
        }

        /* The stream on its own, once it turns up. The labels above are read as soon as the page
           settles, and on a single page app the <video> is often not built yet at that moment -
           on 9gag it never was, so a card opened from the feed sat on "Parsing Media" with its
           stream sitting right there in the dom. */
        if(mksSrc && mksMediaSent !== location.href){
            mksMediaSent = location.href;
            MediaDownloaderBridge.feedCardChanged('', '', mksSrc);
        }
    }

    var vids = mksDeepAll('video');
    for(var i = 0; i < vids.length; i++){
        if(mksIsAdvert(vids[i])) continue;
        /* A player kept hidden until it is hovered or played is not what is on screen: imdb lays
           its trailer's <video> under the poster with visibility hidden, and a button claimed for
           it left the poster - the thing a reader actually sees - without one. It is picked up by
           a later scan once it is shown. */
        try{ if(window.getComputedStyle(vids[i]).visibility === 'hidden'){ mksUntrack(vids[i]); continue; } }catch(eV){}
        mksDropStillsUnder(vids[i]);
        if(!hasButtonNear(vids[i], null)){ makeBtn(vids[i], null); }
    }

    /* A feed only builds a <video> for the card that is playing; every other card is just a
       poster image inside its link. Without these the button appeared on one video only, and
       only after it had been started. */
    /* Start from the poster image, not from the link. Some listings put the thumbnail inside the
       card's anchor, others keep the anchor on the title only - "My Feed" is the second shape, so
       looking for an <img> inside every <a> found nothing there at all. Walking up from the image
       covers both layouts. */
    /* A player the script cannot see into. Bitchute, dailymotion and imdb all hand their video
       to an iframe, and a script in the page above it can read nothing inside - so the media had
       no button at all and the app's own floating one was the only offer. The button is drawn on
       the frame instead, and the tap hands over the page, which the app can resolve. Ad frames
       are left alone: they are not what the reader came for. */
    var frames = mksDeepAll('iframe');
    var player = null, playerArea = 0;
    for(var f = 0; f < frames.length; f++){
        try{
            var fr = frames[f].getBoundingClientRect();
            if(fr.width < 240 || fr.height < 135) continue;
            /* A banner is not a player. An advert strip is wide and shallow - 9gag's runs the
               width of the page and about a fifth as tall - while a player is near enough to
               16:9. With only the host list to go on, the strip at the top of 9gag was the
               largest frame on a feed that had not finished loading, and its button downloaded
               the advert. */
            if(fr.width / fr.height > 3) continue;
            if(mksIsAdvert(frames[f])) continue;
            /* Youtube is never offered, wherever it is met. The app turns away a youtube page,
               but an embed is somebody else's page with youtube's player inside it - and a
               button on that frame hands the page over as if the video were the site's own. */
            if(/(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)([:/]|$)/.test(
                   (fsrcHost(frames[f]) || ''))) continue;
            var fsrc = (frames[f].getAttribute('src') || '').toLowerCase();
            if(fsrc && (fsrc.indexOf('doubleclick') >= 0 || fsrc.indexOf('googlesyndication') >= 0 ||
                        fsrc.indexOf('mgid') >= 0 || fsrc.indexOf('/ads') >= 0 ||
                        fsrc.indexOf('adservice') >= 0 || fsrc.indexOf('media.net') >= 0 ||
                        fsrc.indexOf('amazon-adsystem') >= 0 || fsrc.indexOf('criteo') >= 0 ||
                        fsrc.indexOf('taboola') >= 0 || fsrc.indexOf('outbrain') >= 0 ||
                        fsrc.indexOf('pubmatic') >= 0 || fsrc.indexOf('rubiconproject') >= 0 ||
                        fsrc.indexOf('adnxs') >= 0 || fsrc.indexOf('openx') >= 0 ||
                        fsrc.indexOf('teads') >= 0 || fsrc.indexOf('revcontent') >= 0 ||
                        fsrc.indexOf('moatads') >= 0 || fsrc.indexOf('adsafeprotected') >= 0)) continue;
            /* One frame, the biggest. A page has one player and any number of advert frames,
               and several of those are large enough to pass the test above - bitchute's video
               page carried a second button on an advert sitting in the white space below the
               video. The player is the largest frame on the page; the rest are not asked. */
            if(fr.width * fr.height > playerArea){ playerArea = fr.width * fr.height; player = frames[f]; }
        }catch(fE){}
    }
    if(player && !hasButtonNear(player, null)){ makeBtn(player, null); }

    var imgs = mksDeepAll('img').concat(mksBgElements());
    for(var n = 0; n < imgs.length; n++){
        /* Per card, so one odd element cannot cost every card below it its button */
        try{
            var img = imgs[n];
            /* A poster tracked before its card's link existed keeps looking for one: imdb hydrates
               the anchor over its slate after the first scan, and without this the press handed
               over the film's page instead of the trailer's. */
            if(isTracked(img)){ mksRefreshCard(img); continue; }
            var ir = img.getBoundingClientRect();
            /* Big enough to be the thing on the page, not an icon beside it. 160 wide was too
               much: wikipedia lays its photographs out two and three to a row, from 108 wide and
               75 tall, so every picture on an article was passed over and the page looked as if
               it held nothing to download. An icon or an avatar is well under this, so the floor
               sits between them - and the button shrinks to fit a small picture, below. */
            if(ir.width < 100 || ir.height < 70)continue;
            /* A picture lying over a player is that player's poster, never the download.
               Pinterest builds a video pin as a <video> with the still beside it rather than
               inside it, so neither the ancestor walk nor the card test saw a video there: the
               image loop claimed the poster and offered 13 kB of thumbnail for a nine second
               video while the player sat underneath it. A poster covers its own video and
               nothing else does, so the boxes settle it. The video loop above owns that media. */
            var mksOverVideo = false;
            var mksVids2 = mksDeepAll('video');
            for(var mksW = 0; mksW < mksVids2.length; mksW++){
                try{
                    var mksVr = mksVids2[mksW].getBoundingClientRect();
                    if(mksVr.width < 40 || mksVr.height < 40) continue;
                    /* Only a video that has a button of its own owns the media here. Imdb lays
                       its trailer's <video> under the poster with visibility hidden, and the
                       video loop passes over it - so the poster was skipped for a player that
                       had no button either, and a film's page offered nothing at all. */
                    if(!isTracked(mksVids2[mksW])) continue;
                    var mksOw = Math.min(ir.right, mksVr.right) - Math.max(ir.left, mksVr.left);
                    var mksOh = Math.min(ir.bottom, mksVr.bottom) - Math.max(ir.top, mksVr.top);
                    if(mksOw <= 0 || mksOh <= 0) continue;
                    var mksSmall = Math.min(ir.width * ir.height, mksVr.width * mksVr.height);
                    if(mksSmall > 0 && (mksOw * mksOh) / mksSmall > 0.6){ mksOverVideo = true; break; }
                }catch(mksVe){}
            }
            if(mksOverVideo)continue;
            if(mksIsAdvert(img))continue;
            var card = findCardLink(img);
            /* A picture is the download itself, so it needs nothing else - tumblr's photos sit
               in no link at all and so never got a button. A card link is only needed for the
               other kind, a poster standing in for a video that has not started. */
            /* On a page holding one video the poster is that video, and there is no card to
               open - so a poster that looks like a video card still gets a button here, where on
               a feed it would be skipped in favour of the card. Bitchute's video page is exactly
               this shape: a still with a play badge over it and nothing else to press. */
            /* A background with nothing linking away from it is decoration, not a picture.
               Backgrounds are read at all for the sites that paint a card's poster that way -
               odysee does, inside the card's own link - so a link is what makes one media. With
               no link, what is left is the page's own wallpaper: imgur hangs
               desktop-assets/homebg.png behind its feed, and that is what its button offered. */
            if(!card && !window.mksSingle && img.tagName !== 'IMG')continue;
            /* A poster that stands for a video and has no card to open is still worth a button:
               there is nothing to prefer it to. dw's media centre is laid out exactly this way -
               an <img> cover with a running time on it and no anchor anywhere around it - and
               requiring "not a video card" here left its whole page of videos without one. The
               tap presses the poster where it stands, which starts the player, which is what
               the sniffer is waiting for. Where a card does exist the card still wins, above,
               and a background still needs one - that is the line before this. */
            if(!card && !window.mksSingle && !stillImageOf(img))continue;
            /* A card whose video the loop above took: that button is the media's. A card holding
               a video with no button - imdb's poster covers a trailer kept hidden - is still the
               reader's only offer, so it gets one here. */
            if(card && mksTrackedVideoIn(card))continue;
            /* On a site the parser reads, a picture is offered by way of the post it belongs to -
               so a card leading anywhere else is not one. Pinterest's "Browse by category" tiles
               are pictures inside links to /ideas/..., and their buttons could only hand over a
               listing: the press read as a card, found nothing, and opened the category page. */
            if(!mksPostCard(card))continue;
            /* And nothing of its own to go on: a picture on a site the parser reads is offered by
               way of the post it belongs to, so it needs a link it actually sits inside. Imdb's
               home is a poster per film with no link around any of them - a link found lying
               beside one is the section's, and pressing it only opened that section. Where the
               site's posts live at a known path the tile is pressed instead, which opens the post
               itself; that is what pinterest's grid needs. */
            var mksOwnCard = false;
            if(card){
                if(card.contains && card.contains(img)){
                    mksOwnCard = true;
                }else{
                    /* Or a link laid over it, which is how imdb hangs a video's page on its
                       slate: the same box as the picture rather than an ancestor of it. A link
                       far taller is the section around it, not this picture's. */
                    var mksCr = card.getBoundingClientRect();
                    var mksOw = Math.min(ir.right, mksCr.right) - Math.max(ir.left, mksCr.left);
                    var mksOh = Math.min(ir.bottom, mksCr.bottom) - Math.max(ir.top, mksCr.top);
                    mksOwnCard = mksOw > 0 && mksOh > 0 && mksCr.height <= ir.height + 200 &&
                        (mksOw * mksOh) >= ir.width * ir.height * 0.6;
                }
            }
            if(window.mksParserSite && !window.mksSingle && !window.mksPostPath && !mksOwnCard)continue;
            if(hasButtonNear(img, card))continue;
            makeBtn(img, card);
        }catch(err){ /* skip this card */ }
    }
    for(var j = tracked.length - 1; j >= 0; j--){
        var t = tracked[j];
        /* isConnected, not document.contains: a node inside a shadow root is not a descendant
           of the document as contains() understands it, so every reddit video - the player sits
           in a shadow root, which is the whole reason this script pierces them - had its button
           built by the loop above and thrown away again by this one, in the same pass. A reddit
           video post never showed a button, and nothing in the log said why. */
        if(!t.v.isConnected){
            if(t.b.parentNode){ t.b.parentNode.removeChild(t.b); }
            tracked.splice(j, 1);
            continue;
        }
        place(t);
    }
    /* One button per piece of media as the reader sees it. hasButtonNear compares the elements
       themselves, and place() may stand a button on an ancestor's box instead - rumble builds
       its player out of several elements and the page ended up with two buttons side by side on
       the one video. What is drawn is what counts, so the boxes the buttons were actually given
       get the last word. The earlier entry keeps the button: videos are tracked before posters
       and frames, so the video wins. */
    for(var a = tracked.length - 1; a > 0; a--){
        var ta = tracked[a];
        if(!ta.r) continue;
        for(var c = 0; c < a; c++){
            var tc = tracked[c];
            if(!tc.r) continue;
            var ow = Math.min(ta.r.right, tc.r.right) - Math.max(ta.r.left, tc.r.left);
            var oh = Math.min(ta.r.bottom, tc.r.bottom) - Math.max(ta.r.top, tc.r.top);
            if(ow <= 0 || oh <= 0) continue;
            /* Measured against the bigger of the two, not the smaller. Two buttons on one
               piece of media stand on very nearly the same box. A big box that merely covers
               a small one is a different thing entirely - wikipedia's full screen viewer opens
               over the article, so the picture it shows swallows the thumbnails still laid out
               behind it, and against the smaller box every one of those counted as the same
               media: the viewer's own button was removed and the full size picture could not
               be downloaded at all. */
            var big = Math.max(ta.r.width * ta.r.height, tc.r.width * tc.r.height);
            if(big > 0 && (ow * oh) / big > 0.6){
                if(ta.b.parentNode){ ta.b.parentNode.removeChild(ta.b); }
                tracked.splice(a, 1);
                break;
            }
        }
    }
}

/* What the app needs to know to put its own button away. The script's buttons sit on the media
   itself and say which one they mean, so where they are drawn the floating one is a second
   button that says less - but where the script cannot reach, a dailymotion video page keeps its
   player in an iframe this script cannot see into, the app's button is the only one there is.
   Reported only when the answer changes. */
var mksReported = -1;
var mksReportedAt = 0;
var mksZeroSince = 0;
function reportButtons(){
    /* What the app is told is whether this page has media the script is holding - not how much
       of it happens to be on screen. The count of visible buttons used to be what was sent, and
       the app reads any number above zero as "the script has this page". So scrolling the video
       off screen hid its button, reported nothing, and the app put its own floating button up
       over the comments; scrolling back reported one again and took it away. That is the button
       appearing and disappearing while the page is scrolled. A button that is merely off screen
       is still this page's button, and comes back on its own. */
    /* A button place() has put away does not count. Tracking outlives placement: an element can
       stay connected while what it showed is gone - a card swapped out under an SPA navigation, a
       player torn down and left behind - and place() then hides its button with display:none.
       Counting it said "the script has this page" while the reader could see no button at all,
       and the app put its own away on the strength of it. Measured on a dailymotion video page,
       whose player sits in an iframe this script cannot reach: one hidden leftover left the page
       with no button of either kind. Scrolling is untouched - a button off screen keeps its size,
       and the point above still stands. */
    var has = 0;
    for(var mksI = 0; mksI < tracked.length; mksI++){
        try{
            var mksR = tracked[mksI].b.getBoundingClientRect();
            if(mksR.width > 0 && mksR.height > 0){ has = 1; break; }
        }catch(e){ /* button gone mid-scan */ }
    }
    var mksNow = Date.now();
    /* And "nothing here" has to still be true two seconds later before it is said out loud.
       Every script starts holding nothing and needs a scan or two to find the page's media,
       and bitchute builds itself a whole new document while the reader scrolls - measured on
       the device, a script 200ms old, several times over - which starts that clock again from
       the beginning each time. Answering inside that gap is what put the app's own button on
       screen for half a second at a time, on a page whose video already had one. A page that
       really has no media is no worse off: it says so two seconds in, and the app's button was
       waiting on the sniffer to find something anyway. */
    if(has){
        mksZeroSince = 0;
    }else{
        if(!mksZeroSince){ mksZeroSince = mksNow; }
        if(mksNow - mksZeroSince < 2000){ return; }
    }
    /* Said again every couple of seconds, not only when it changes. The app hung its own button
       on this one message, and a single missed or stale answer - a moment while a feed rebuilt
       itself and the script held nothing - left the app believing there were no buttons for as
       long as the page stayed open, so its own button sat there beside the script's. Repeating
       costs one bridge call every two seconds and leaves nothing to miss. */
    if(has !== mksReported || mksNow - mksReportedAt > 2000){
        mksReported = has;
        mksReportedAt = mksNow;
        try{ MediaDownloaderBridge.genericButtonsDrawn(has); }catch(e){}
    }
}

scan();
reportButtons();
setInterval(function(){ if(document.hidden){ return; } scan(); reportButtons(); }, 400 * (window.mksScanScale || 1));

/* Only now: an injection that threw on the way here must not stop the next one from trying */
window.mksGenInit = true;
console.log('[mks] generic script ready');
}catch(e){ console.log('[mks] generic script failed: ' + e); }
})();
