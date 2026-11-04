#!/usr/bin/env python3
"""Woah Quiet Frame: native vector identity, application examples, VI review PDF.

Run with reportlab, fonttools, Pillow, PyMuPDF. No business repository writes.
The supplied selected board is retained unchanged as a reference.
"""
from pathlib import Path
import json, math, shutil, re, zipfile, hashlib, html, os
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont as RLFont
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import ImageReader
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools import subset
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
import fitz
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT.parents[1] if (ROOT.parents[1]/'upload/Woah quiet frame identity board(1).png').exists() else ROOT/'_build'
ASSETS=ROOT/'assets'
TMP=WORK/'tmp/vi'; TMP.mkdir(parents=True,exist_ok=True)
FONTDIR=WORK/'tmp/fonts'
if not FONTDIR.exists(): FONTDIR=ASSETS/'fonts'
REF=WORK/'upload/Woah quiet frame identity board(1).png'
if not REF.exists(): REF=ASSETS/'reference/selected-quiet-frame-board.png'
for d in ['logo','icons/ios','icons/android','applications','tokens','fonts','reference','checks']:
    (ASSETS/d).mkdir(parents=True,exist_ok=True)

C={
 'mint':'#78DCC7','ink':'#0B1415','surface':'#142123','line':'#2D4548',
 'pearl':'#F2F6F3','secondary':'#B4C8C6','blue':'#8CAACB',
 'control':'#607D80','mintText':'#216A5E','muted':'#8DA6A4',
 'danger':'#FF9C9F','warning':'#F0C879','mintPressed':'#59C5AF',
}
LEFT='M16 8H48Q64 8 64 24V111Q64 119 76 124V65Q76 51 89 56L111 64Q120 67 120 78V113Q120 122 111 127L70 148Q47 160 21 151Q0 145 0 124V24Q0 8 16 8Z'
CMDS=[('M',(16,8)),('L',(48,8)),('Q',(64,8,64,24)),('L',(64,111)),('Q',(64,119,76,124)),('L',(76,65)),('Q',(76,51,89,56)),('L',(111,64)),('Q',(120,67,120,78)),('L',(120,113)),('Q',(120,122,111,127)),('L',(70,148)),('Q',(47,160,21,151)),('Q',(0,145,0,124)),('L',(0,24)),('Q',(0,8,16,8)),('Z',())]

def esc(s): return html.escape(str(s),quote=True)
def wrap_svg(w,h,body,title='Woah Quiet Frame'):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="{esc(title)}"><title>{esc(title)}</title>{body}</svg>'
def mark_svg(x=0,y=0,w=256,color=None):
    color=color or C['mint'];s=w/256
    return f'<g transform="translate({x} {y}) scale({s})" fill="{color}"><path d="{LEFT}"/><path d="{LEFT}" transform="translate(256 0) scale(-1 1)"/></g>'

# Subset only the embedded document font. Full unmodified source font is bundled.
alltext=Path(__file__).read_text(encoding='utf8')
chars=''.join(sorted(set(alltext)))
font_signature=hashlib.sha256(chars.encode('utf8')).hexdigest()[:10]
for w,name in [(400,'Regular'),(500,'Medium'),(600,'SemiBold'),(700,'Bold')]:
    pdfmetrics.registerFont(RLFont(f'en{w}',str(FONTDIR/f'IBMPlexSans-{name}.ttf')))
    p=TMP/f'NotoSansSC-{w}-{font_signature}-document.ttf'
    if not p.exists():
        f=TTFont(FONTDIR/'NotoSansSC-Variable.ttf')
        sub=subset.Subsetter();sub.populate(text=chars);sub.subset(f)
        f=instantiateVariableFont(f,{'wght':w},inplace=True)
        f.save(p)
    pdfmetrics.registerFont(RLFont(f'zh{w}',str(p)))
EN=TTFont(FONTDIR/'IBMPlexSans-Bold.ttf')
ZH=TTFont(TMP/f'NotoSansSC-700-{font_signature}-document.ttf')

def outline(text,font=EN,tracking=-27):
    gs=font.getGlyphSet(); cm=font.getBestCmap(); x=0; pieces=[]; bbox=[1e9,1e9,-1e9,-1e9]
    for ch in text:
        g=cm[ord(ch)]; pen=SVGPathPen(gs); gs[g].draw(pen)
        pieces.append(f'<path d="{pen.getCommands()}" transform="translate({x} 0)"/>')
        bp=BoundsPen(gs);gs[g].draw(bp)
        if bp.bounds:
            a,b,c,d=bp.bounds;bbox=[min(bbox[0],a+x),min(bbox[1],b),max(bbox[2],c+x),max(bbox[3],d)]
        x+=font['hmtx'][g][0]+tracking
    return ''.join(pieces),bbox
WORD,WB=outline('Woah')
CHWORD,CB=outline('舞哇',ZH,tracking=40)
def word_svg(x,y,w,color=None,chinese=False):
    p,b=(CHWORD,CB) if chinese else (WORD,WB)
    s=w/(b[2]-b[0]); color=color or C['ink']
    return f'<g fill="{color}" transform="translate({x} {y}) scale({s} {-s}) translate({-b[0]} {-b[3]})">{p}</g>'

def draw_glyphs(c,text,font,x,baseline,size,tracking=-27):
    gs=font.getGlyphSet();cm=font.getBestCmap();s=size/font['head'].unitsPerEm
    c.saveState();c.translate(x,baseline);c.scale(s,s);cursor=0
    for ch in text:
        g=cm[ord(ch)];r=RecordingPen();gs[g].draw(r);p=c.beginPath();cur=(0,0)
        for op,pts in r.value:
            if op=='moveTo':p.moveTo(pts[0][0]+cursor,pts[0][1]);cur=pts[0]
            elif op=='lineTo':p.lineTo(pts[0][0]+cursor,pts[0][1]);cur=pts[0]
            elif op=='curveTo':p.curveTo(*[v for pt in pts for v in (pt[0]+cursor,pt[1])]);cur=pts[-1]
            elif op=='qCurveTo':
                # TrueType quadratics, including implied on-curve points.
                seq=list(pts);end=seq.pop(); controls=seq
                for i,q in enumerate(controls):
                    nxt=((q[0]+controls[i+1][0])/2,(q[1]+controls[i+1][1])/2) if i+1<len(controls) else end
                    if nxt is None: continue
                    a=(cur[0]+2*(q[0]-cur[0])/3,cur[1]+2*(q[1]-cur[1])/3)
                    b=(nxt[0]+2*(q[0]-nxt[0])/3,nxt[1]+2*(q[1]-nxt[1])/3)
                    p.curveTo(a[0]+cursor,a[1],b[0]+cursor,b[1],nxt[0]+cursor,nxt[1]);cur=nxt
            elif op=='closePath':p.close()
        c.drawPath(p,fill=1,stroke=0,fillMode=1);cursor+=font['hmtx'][g][0]+tracking
    c.restoreState()

def color(c,k):c.setFillColor(HexColor(C.get(k,k)))
def rect(c,x,y,w,h,fill,r=0,stroke=None,sw=1):
    c.saveState();color(c,fill)
    if stroke:c.setStrokeColor(HexColor(C.get(stroke,stroke)));c.setLineWidth(sw)
    if r:c.roundRect(x,y,w,h,r,fill=1,stroke=int(bool(stroke)))
    else:c.rect(x,y,w,h,fill=1,stroke=int(bool(stroke)))
    c.restoreState()
def line(c,x1,y1,x2,y2,col='line',sw=1,dash=None):
    c.saveState();c.setStrokeColor(HexColor(C.get(col,col)));c.setLineWidth(sw)
    if dash:c.setDash(dash)
    c.line(x1,y1,x2,y2);c.restoreState()
def mark(c,x,y,w,col='mint'):
    c.saveState();color(c,col);s=w/256;c.translate(x,y+160*s);c.scale(s,-s)
    for mirror in [False,True]:
        c.saveState()
        if mirror:c.translate(256,0);c.scale(-1,1)
        p=c.beginPath();cur=(0,0)
        for cmd,a in CMDS:
            if cmd=='M':p.moveTo(*a);cur=a
            elif cmd=='L':p.lineTo(*a);cur=a
            elif cmd=='Q':
                q=a[:2];end=a[2:];
                p.curveTo(cur[0]+2*(q[0]-cur[0])/3,cur[1]+2*(q[1]-cur[1])/3,end[0]+2*(q[0]-end[0])/3,end[1]+2*(q[1]-end[1])/3,*end);cur=end
            else:p.close()
        c.drawPath(p,fill=1,stroke=0);c.restoreState()
    c.restoreState()
def word(c,x,y,w,col='ink',chinese=False):
    f,b=(ZH,CB) if chinese else (EN,WB);s=w/(b[2]-b[0]);color(c,col)
    draw_glyphs(c,'舞哇' if chinese else 'Woah',f,x-b[0]*s,y-b[1]*s,f['head'].unitsPerEm*s,40 if chinese else -27)
    return (b[3]-b[1])*s

def segments(s):
    # Use Plex for Latin/numerals; Noto Sans SC for CJK and full-width punctuation.
    parts=[]
    for ch in str(s):
        kind='zh' if ord(ch)>0x2ff else 'en'
        if parts and parts[-1][0]==kind:parts[-1]=(kind,parts[-1][1]+ch)
        else:parts.append((kind,ch))
    return parts
def tw(s,size=14,w=400):return sum(pdfmetrics.stringWidth(t,f'{k}{w}',size) for k,t in segments(s))
def text(c,s,x,y,size=14,col='ink',w=400,align='left'):
    color(c,col);width=tw(s,size,w)
    if align=='center':x-=width/2
    elif align=='right':x-=width
    for k,t in segments(s):
        c.setFont(f'{k}{w}',size);c.drawString(x,y,t);x+=pdfmetrics.stringWidth(t,f'{k}{w}',size)
    return width
def paragraph(c,s,x,y,width,size=14,col='ink',w=400,leading=None):
    leading=leading or size*1.65;out=[];cur=''
    rawtokens=re.findall(r'[0-9]+(?:\.[0-9]+)?[ ]?(?:px|mm|ms|dp)\b|[A-Za-z0-9_@#./:+%\-]+|\n|[ \t]+|[^\x00-\x7f][，。；：！？、）》」』】]?|.',str(s))
    tokens=[]
    for token in rawtokens:
        if token in '，。；：！？、）》」』】”' and tokens:tokens[-1]+=token
        else:tokens.append(token)
    for ch in tokens:
        if ch=='\n':out.append(cur.rstrip());cur=''
        elif tw(cur+ch,size,w)>width and cur:
            out.append(cur.rstrip());cur=ch.lstrip()
        else:cur+=ch
    if cur:out.append(cur)
    for row in out:text(c,row,x,y,size,col,w);y-=leading
    return y
def label(c,s,x,y,col='mintText'):
    return text(c,s,x,y,11,col,600)
def bullet(c,head,body,x,y,width,col='ink'):
    text(c,head,x,y,20,col,600);return paragraph(c,body,x,y-32,width,14,col,400,23)
def bracket(c,x,y,w,h,col='mint',thick=18):
    c.saveState();c.setStrokeColor(HexColor(C.get(col,col)));c.setLineWidth(thick);c.setLineCap(1);c.setLineJoin(1)
    a=min(w*.18,50);b=min(h*.18,50)
    for pts in [[(x+a,y+h),(x,y+h),(x,y),(x+a,y)],[(x+w-a,y+h),(x+w,y+h),(x+w,y),(x+w-a,y)]]:
        p=c.beginPath();p.moveTo(*pts[0])
        for pt in pts[1:]:p.lineTo(*pt)
        c.drawPath(p,fill=0,stroke=1)
    c.restoreState()

def dancer(c,cx,cy,scale=1,col='ink',stroke=None):
    # Original abstract motion glyph, not a segmentation result or a human portrait.
    c.saveState();c.translate(cx,cy);c.scale(scale,scale)
    c.setStrokeColor(HexColor(C.get(stroke or col,stroke or col)));c.setFillColor(HexColor(C.get(col,col)))
    c.circle(0,135,14,fill=1,stroke=0)
    p=c.beginPath();p.moveTo(-8,116);p.curveTo(-24,97,-20,53,-9,28);p.lineTo(18,22);p.curveTo(28,58,15,91,9,113);p.close();c.drawPath(p,fill=1,stroke=0)
    c.setLineCap(1);c.setLineJoin(1);c.setLineWidth(13)
    for pts in [[(-9,104),(-49,126),(-78,162)],[(10,103),(47,89),(82,97)],[(-6,28),(-34,-11),(-64,-56)],[(16,25),(52,2),(89,-44)]]:
        p=c.beginPath();p.moveTo(*pts[0])
        for pt in pts[1:]:p.lineTo(*pt)
        c.drawPath(p,fill=0,stroke=1)
    c.restoreState()

def launch(c,x,y,w,h):
    rect(c,x,y,w,h,'ink');s=w/390
    mark(c,x+w*.30,y+h*.45,w*.40)
    word(c,x+w*.32,y+h*.38,w*.36,'pearl')
    text(c,'看见舞步，留住边界。',x+w/2,y+h*.105,14*s,'secondary',400,'center')
def editor(c,x,y,w,h):
    s=w/390;c.saveState();c.translate(x,y);c.scale(s,s)
    rect(c,0,0,390,844,'ink');text(c,'‹',20,808,30,'pearl',500);text(c,'人物保护',195,813,16,'pearl',600,'center')
    rect(c,310,797,64,36,'mint',18);text(c,'导出',342,809,14,'ink',600,'center')
    rect(c,0,372,390,400,'#D5DED7')
    for xx in [30,135,240,345]:line(c,xx,372,xx+45,772,'#A9B6AF',1)
    line(c,0,430,390,430,'#A9B6AF',1);line(c,0,414,390,414,'#BBC7C0',1)
    dancer(c,80,480,.65,'#7C9188');dancer(c,305,480,.65,'#7C9188')
    # The person marker follows the segmentation-mask region, not a wireframe.
    # This hand-drawn filled footprint illustrates the geometry only; its mint
    # color is a VI proposal, not an observed product color or model output.
    dancer(c,196,475,.85,'mint')
    rect(c,108,724,174,28,'ink',14);text(c,'分割 mask · 人物 02',195,733,12,'mint',600,'center')
    text(c,'▶',18,344,15,'pearl',500);text(c,'00:04 / 00:12',49,345,12,'secondary',400)
    line(c,18,316,372,316,'control',3);line(c,18,316,166,316,'mint',3)
    for i in range(9):
        xx=18+i*40;rect(c,xx,260,37,39,'surface',3);dancer(c,xx+18,272,.10,'secondary')
    line(c,169,255,169,304,'pearl',2)
    rect(c,16,183,358,58,'surface',12,stroke='control');text(c,'全身保护',32,216,16,'pearl',600)
    text(c,'人物 02 · 效果待逐段检查',32,196,12,'secondary');text(c,'›',347,202,27,'secondary')
    text(c,'保护效果以视频预览和导出复核为准',195,156,12,'secondary',400,'center')
    line(c,16,132,374,132,'line',1)
    for i,t in enumerate(['保护','遮挡','画幅','舞段']):
        xx=49+i*97;text(c,['○','▦','□','／'][i],xx,90,23,'mint' if i==0 else 'secondary',500,'center')
        text(c,t,xx,65,13,'mint' if i==0 else 'secondary',600 if i==0 else 400,'center')
        if i==0:line(c,xx-12,52,xx+12,52,'mint',2)
    c.restoreState()

def comparison(c,x,y,w,h):
    s=w/390;c.saveState();c.translate(x,y);c.scale(s,s)
    rect(c,0,0,390,844,'ink')
    text(c,'‹',20,807,30,'pearl',500)
    text(c,'舞蹈对比',195,811,17,'pearl',600,'center')
    rect(c,306,797,66,36,'mint',18);text(c,'导出',339,809,14,'ink',600,'center')
    for i,title in enumerate(['参考','目标']):
        left=16+i*185
        rect(c,left,429,172,319,'surface',12)
        rect(c,left+6,472,160,224,'#D5DED7',4)
        for k in [left+31,left+86,left+141]:line(c,k,472,k+22,696,'#B3C1BA',.6)
        dancer(c,left+90,502,.48,'#587E72' if i==0 else 'ink')
        text(c,title,left+16,718,16,'pearl',600)
        text(c,'00:04',left+86,445,13,'secondary',400,'center')
    rect(c,16,370,358,43,'surface',10)
    text(c,'共同音乐对齐',31,384,15,'pearl',600)
    text(c,'偏移 +0.3s',355,385,12,'mint',500,'right')
    text(c,'▶',29,331,23,'mint',500)
    line(c,72,339,358,339,'control',3)
    line(c,72,339,192,339,'mint',3)
    for i in range(8):
        rect(c,16+i*45,248,42,57,'surface',3)
        dancer(c,36+i*45,256,.10,'secondary')
    line(c,159,243,159,310,'mint',2)
    rect(c,16,150,358,75,'surface',12,stroke='control')
    text(c,'速度与位置微调',31,196,16,'pearl',600)
    text(c,'参考 / 目标可独立调整，音乐保持共同时间',31,169,12,'secondary')
    text(c,'双画面与同步操作以实际产品为准',195,92,12,'secondary',400,'center')
    c.restoreState()

def poster(c,x,y,w,h):
    c.saveState();c.translate(x,y);c.scale(w/1080,h/1350)
    rect(c,0,0,1080,1350,'pearl');word(c,72,1070,936,'ink')
    text(c,'谁被看见，由你决定。',1008,975,39,'ink',600,'right')
    bracket(c,145,267,790,590,'mint',74)
    dancer(c,535,490,2.25,'ink')
    text(c,'看见舞步，留住边界。',72,120,32,'mintText',500)
    text(c,'DANCE. WITH BOUNDARIES.',72,72,18,'ink',500)
    mark(c,925,67,83,'ink');c.restoreState()

def export_pdf_png(path,w,h,draw,pngsize=None,opaque=True):
    path=Path(path);c=canvas.Canvas(str(path),pagesize=(w,h),pageCompression=1);draw(c);c.save()
    if pngsize:
        d=fitz.open(path);p=d[0];pix=p.get_pixmap(matrix=fitz.Matrix(pngsize[0]/w,pngsize[1]/h),alpha=not opaque)
        pix.save(str(path.with_suffix('.png')));d.close()

# Deterministic vector assets with text converted to outlines.
for n,k in [('mint','mint'),('ink','ink'),('white','pearl')]:
    (ASSETS/'logo'/f'woah-symbol-{n}.svg').write_text(wrap_svg(256,160,mark_svg(color=C[k])),encoding='utf8')
    (ASSETS/'logo'/f'woah-wordmark-{n}.svg').write_text(wrap_svg(620,190,word_svg(0,0,620,C[k])),encoding='utf8')
    body=mark_svg(0,0,256,C[k])+word_svg(296,8,504,C['ink'] if n=='mint' else C[k])
    (ASSETS/'logo'/f'woah-lockup-horizontal-{n}.svg').write_text(wrap_svg(800,160,body),encoding='utf8')
body=mark_svg(0,32,256)+word_svg(296,0,504)+word_svg(300,174,98,C['ink'],True)
(ASSETS/'logo/woah-lockup-bilingual-official.svg').write_text(wrap_svg(800,238,body,'舞哇 / Woah · 正式双语标识'),encoding='utf8')
(ASSETS/'logo/woah-lockup-horizontal-reversed.svg').write_text(wrap_svg(800,160,mark_svg()+word_svg(296,8,504,C['pearl'])),encoding='utf8')
for n,fcol,bg in [('default','mint','ink'),('dark','mint','ink'),('mono','pearl',None)]:
    body=(f'<rect width="1024" height="1024" fill="{C[bg]}"/>' if bg else '')+mark_svg(152,287,720,C[fcol])
    (ASSETS/'icons/ios'/f'woah-ios-{n}-1024.svg').write_text(wrap_svg(1024,1024,body),encoding='utf8')
    export_pdf_png(TMP/f'ios-{n}.pdf',1024,1024,lambda c,n=n,fcol=fcol,bg=bg:(rect(c,0,0,1024,1024,bg) if bg else None,mark(c,152,287,720,fcol)),(1024,1024),bool(bg))
    shutil.copyfile(TMP/f'ios-{n}.png',ASSETS/'icons/ios'/f'woah-ios-{n}-1024.png')
(ASSETS/'icons/ios/01-foreground.svg').write_text(wrap_svg(1024,1024,mark_svg(152,287,720)),encoding='utf8')
(ASSETS/'icons/ios/00-background.svg').write_text(wrap_svg(1024,1024,f'<rect width="1024" height="1024" fill="{C["ink"]}"/>'),encoding='utf8')
for n,col in [('foreground','mint'),('monochrome','pearl')]:
    (ASSETS/'icons/android'/f'woah-adaptive-{n}.svg').write_text(wrap_svg(108,108,mark_svg(24.5,35.5625,59,C[col])),encoding='utf8')
    body=f'<group android:translateX="24.5" android:translateY="35.5625" android:scaleX="0.23046875" android:scaleY="0.23046875"><path android:fillColor="{C[col]}" android:pathData="{LEFT}"/><group android:translateX="256" android:scaleX="-1"><path android:fillColor="{C[col]}" android:pathData="{LEFT}"/></group></group>'
    (ASSETS/'icons/android'/f'woah_{n}.xml').write_text(f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">{body}</vector>')
(ASSETS/'icons/android/woah-adaptive-background.svg').write_text(wrap_svg(108,108,f'<rect width="108" height="108" fill="{C["ink"]}"/>'),encoding='utf8')
(ASSETS/'icons/android/woah_background.xml').write_text(f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><path android:fillColor="{C["ink"]}" android:pathData="M0,0H108V108H0Z"/></vector>')
(ASSETS/'icons/android/ic_launcher-api26.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/woah_background"/><foreground android:drawable="@drawable/woah_foreground"/></adaptive-icon>')
(ASSETS/'icons/android/ic_launcher-api33.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/woah_background"/><foreground android:drawable="@drawable/woah_foreground"/><monochrome android:drawable="@drawable/woah_monochrome"/></adaptive-icon>')
export_pdf_png(TMP/'android-flat-preview-512.pdf',512,512,lambda c:(rect(c,0,0,512,512,'ink'),mark(c,64,136,384,'mint')),(512,512))
shutil.copyfile(TMP/'android-flat-preview-512.png',ASSETS/'icons/android/woah-android-flat-preview-512.png')
export_pdf_png(ASSETS/'applications/launch-390x844.pdf',390,844,lambda c:launch(c,0,0,390,844),(1170,2532))
export_pdf_png(ASSETS/'applications/editor-brand-study-390x844.pdf',390,844,lambda c:editor(c,0,0,390,844),(1170,2532))
export_pdf_png(ASSETS/'applications/comparison-brand-study-390x844.pdf',390,844,lambda c:comparison(c,0,0,390,844),(1170,2532))
export_pdf_png(ASSETS/'applications/poster-1080x1350.pdf',1080,1350,lambda c:poster(c,0,0,1080,1350),(1080,1350))
(ASSETS/'applications/poster-1080x1350.svg').write_text(wrap_svg(1080,1350,f'<rect width="1080" height="1350" fill="{C["pearl"]}"/>'+word_svg(72,60,936)+f'<path d="M275 493H145V1083H275M805 493H935V1083H805" fill="none" stroke="{C["mint"]}" stroke-width="74" stroke-linecap="round"/>'+mark_svg(925,1231,83,C['ink'])),encoding='utf8')
# The separate editable SVG above is the template foundation; the full composed poster is in PDF.

tokens={
 '$description':'Woah Quiet Frame v1.1 revision 2: segmentation-mask person marker. Proposed values, not applied to business code.',
 'brand':{'englishName':'Woah','chineseName':'舞哇','direction':'Quiet Frame','mint':C['mint'],'ink':C['ink'],'pearl':C['pearl'],'supportBlue':C['blue']},
 'dark':{'canvas':C['ink'],'surface':C['surface'],'textPrimary':C['pearl'],'textSecondary':C['secondary'],'textMuted':C['muted'],'dividerDecorative':C['line'],'borderControl':C['control'],'action':C['mint'],'onAction':C['ink'],'actionPressed':C['mintPressed'],'focus':C['mint'],'error':C['danger'],'warning':C['warning']},
 'light':{'canvas':C['pearl'],'textPrimary':C['ink'],'textSecondary':'#496361','action':C['mintText'],'onAction':C['pearl']},
 'type':{'latin':'IBM Plex Sans','chinese':'Noto Sans SC','weights':[400,500,600],'wordmarkWeight':700,'scale':{'display':[32,40,600],'title':[24,32,600],'section':[20,28,600],'body':[16,24,400],'control':[15,20,600],'caption':[12,16,500]}},
 'layout':{'space':[4,8,12,16,24,32,48,64],'radius':{'small':8,'control':12,'panel':20,'pill':999},'touchTargetMin':48,'iconSize':24,'iconStroke':2},
 'motion':{'stateMs':160,'panelMs':240,'reduceMotion':'fade-only; no logo morph or forced startup delay'},
 'editor':{'personMarker':{'source':'segmentation mask','geometry':'filled pixel-region footprint following the person; no wireframe, bounding box or contour-only marker'},'selected':'current-object label; any mask emphasis follows the segmentation region, not a rectangular frame','maskIllustration':{'color':C['mint'],'illustrationOnly':True,'note':'Hand-drawn filled region in the VI study; does not specify the current product color, opacity, or model accuracy.'},'protection':'explicit label and actual effect preview, never color alone'},
 'brandGraphics':{'openBrackets':'brand layout and campaign graphics; do not use as the editor person marker'},
 'product':{'workflows':['videoProtection','danceComparison'],'brandAction':C['mint'],'onBrandAction':C['ink'],'editorSelection':C['mint'],'effectPaletteAmber':'#F5A623','statusWarning':C['warning'],'statusError':C['danger'],'privacyMaskGeometry':'segmentation pixel region; not a rectangle','fontEmbedding':'system CJK fallback until size-and-accessibility QA'}
}
(ASSETS/'tokens/woah-quiet-frame.tokens.json').write_text(json.dumps(tokens,ensure_ascii=False,indent=2),encoding='utf8')
css='/* v1.1 sRGB role source; integrated via semantic Flutter tokens. */\n:root {\n'+''.join(f'  --woah-{k}: {v};\n' for k,v in tokens['dark'].items())+'  --woah-font-latin: "IBM Plex Sans";\n  --woah-font-cjk: "Noto Sans SC";\n}\n'
(ASSETS/'tokens/woah-quiet-frame.css').write_text(css,encoding='utf8')
for p in FONTDIR.iterdir():
    target=ASSETS/'fonts'/p.name
    if p.resolve()!=target.resolve():shutil.copyfile(p,target)
if REF.resolve()!=(ASSETS/'reference/selected-quiet-frame-board.png').resolve():
    shutil.copyfile(REF,ASSETS/'reference/selected-quiet-frame-board.png')

def luminance(h):
    v=[int(h[i:i+2],16)/255 for i in (1,3,5)];v=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in v]
    return .2126*v[0]+.7152*v[1]+.0722*v[2]
def contrast(a,b):
    x,y=sorted([luminance(a),luminance(b)]);return (y+.05)/(x+.05)
PAIRS=[('主操作文字','ink','mint',4.5),('白字配薄荷','pearl','mint',4.5),('主文字','pearl','surface',4.5),('次文字','secondary','surface',4.5),('弱文字','muted','surface',4.5),('功能边界','control','surface',3),('装饰分隔','line','surface',3),('浅色链接','mintText','pearl',4.5)]
CHECKS=[{'role':r,'foreground':C[a],'background':C[b],'ratio':contrast(C[a],C[b]),'target':t,'pass':contrast(C[a],C[b])>=t} for r,a,b,t in PAIRS]
(ASSETS/'checks/contrast.json').write_text(json.dumps(CHECKS,ensure_ascii=False,indent=2),encoding='utf8')

W,H=1200,800
PDF=ROOT/'Woah-Quiet-Frame-VI-v1.1.pdf'
c=canvas.Canvas(str(PDF),pagesize=(W,H),pageCompression=1)
c.setTitle('Woah | Quiet Frame | VI v1.1 修订1 生产候选稿');c.setAuthor('Woah Brand Design')
PAGES=[]
def page(num,title,kicker='BRAND GUIDELINES',dark=False):
    PAGES.append(title);rect(c,0,0,W,H,'ink' if dark else 'pearl')
    label(c,kicker,56,746,'mint' if dark else 'mintText')
    text(c,title,56,695,30,'pearl' if dark else 'ink',600)
    line(c,56,669,1144,669,'line' if dark else '#D3DEDA',1)
    text(c,'WOAH / QUIET FRAME',56,31,10,'secondary' if dark else 'mintText',500)
    text(c,'VI v1.1 · 修订2 · 2026.10.29',600,31,10,'secondary' if dark else 'mintText',400,'center')
    text(c,f'{num:02}',1144,31,11,'secondary' if dark else 'mintText',600,'right')
def end():c.showPage()

# 01 - Cover.
rect(c,0,0,W,H,'ink');PAGES.append('Quiet Frame')
label(c,'WOAH BRAND IDENTITY',56,744,'mint');text(c,'PHASE 01 / SELECTED DIRECTION',1144,744,11,'secondary',500,'right')
mark(c,65,345,330);word(c,448,384,681,'pearl')
text(c,'Quiet Frame',66,274,34,'pearl',500)
text(c,'看见舞步，留住边界。',66,215,31,'mint',500)
paragraph(c,'为舞蹈视频保护、同步对比与编辑而设计。让舞步成为中心，交互清晰可控，隐私效果以实际复核为准。',68,148,720,17,'secondary',400,28)
text(c,'VI v1.1 · 修订2 · 生产候选稿',68,49,12,'secondary',500);text(c,'2026.10.29',1144,49,12,'secondary',400,'right');end()

# 02 - Brand strategy.
page(2,'表达自由，边界清楚。','01 / BRAND STRATEGY')
paragraph(c,'舞哇（Woah）是面向舞蹈视频保护、同步对比与编辑的移动端工具。品牌让动作与视频内容优先：保护流程帮助创作者选择呈现范围；双视频对比以共同音乐同步比较动作。',56,624,1010,21,'ink',400,34)
cols=[('表达','保留舞步的力量','让画面、动作与内容占主导。视觉节制，不把编辑器变成品牌舞台。'),('自主','保护由你决定','以选人、保护范围、效果预览建立掌控感。文案说明对象与状态。'),('清晰','边界可被理解','开放的框线与中部留白表达尊重。品牌标识不冒充安全认证。')]
for i,(a,b,d) in enumerate(cols):
    x=56+i*368;label(c,a,x,425);text(c,b,x,385,24,'ink',600);paragraph(c,d,x,345,300,16,'ink',400,27)
line(c,56,247,1144,247,'#D3DEDA')
text(c,'视觉关键词',56,207,17,'mintText',600)
text(c,'克制  /  流动  /  留白  /  可控  /  温和科技',230,207,20,'ink',500)
paragraph(c,'隐私保护主文案：看见舞步，留住边界。宣传文案：谁被看见，由你决定。\n双视频对比不强加隐私保护口号；操作文案强调导入、校准、同步、检查、导出。',56,152,1070,15,'ink',400,27);end()

# 03 - Evidence and product relationship.
page(3,'从现有产品走向统一品牌。','02 / PRODUCT BASIS')
rows=[('隐私保护','人物分割、追踪、全身 / 人脸保护；人物标记为 mask','保留现有编辑器和真实效果复核；不以品牌框线替代人物 mask'),('双视频对比','独立入口；参考与目标双画面，按共同音乐校准','保留现有播放、时间轴、偏移与导出；不重做交互层级'),('首页与共享主题','简洁入口卡片；旧石墨 + 琥珀交互色','品牌操作换薄荷绿；音视频内容保留纯黑舞台'),('品牌架构','品牌 W + Woah / 舞哇；Buddy 为陪伴资产','双功能共享字标和控件语义；效果色板完全独立')]
for i,(a,b,d) in enumerate(rows):
    yy=613-i*101;label(c,a,56,yy);paragraph(c,b,244,yy,398,15,'ink',400,24);paragraph(c,d,716,yy,420,15,'ink',400,24);line(c,56,yy-72,1144,yy-72,'#D3DEDA')
paragraph(c,'品牌层级：W 标识与舞哇 / Woah 字标 > 品牌配色与框线 > Buddy 陪伴内容。\nBuddy 仅用于经允许的空状态或彩蛋；品牌标识不代表匿名认证，保护和对比均以实际视频为中心。',56,155,1050,16,'ink',400,28)
text(c,'依据：2026.10.29 master 的两个独立工作流，规范与功能不作虚假对应；本版随独立代码分支接入。',56,77,12,'mintText');end()

# 04 - Geometry.
page(4,'W：两侧承托，中间留白。','03 / SYMBOL CONSTRUCTION')
gx,gy,gw=85,270,560;s=gw/256
for i in range(0,257,16):line(c,gx+i*s,gy,gx+i*s,gy+160*s,'#D3DEDA',.6)
for i in range(0,161,16):line(c,gx,gy+i*s,gx+gw,gy+i*s,'#D3DEDA',.6)
mark(c,gx,gy,gw,'mintText');line(c,gx+128*s,gy-15,gx+128*s,gy+160*s+15,'control',1,[4,4])
text(c,'256 × 160 设计画布',gx,gy-34,13,'mintText',500)
for j,(head,body) in enumerate([('双侧对称','基准画布 256 × 160；右半沿中心线镜像。外侧立柱宽 64，内侧端点 x = 120 / 136。'),('16 单位中缝','中心负形保持开放。外角基准圆角 16；底部用曲线过渡，统一以主文件路径为准。'),('栅格稿转为矢量','此为所选概念的几何重建，不是从图片中抠出的轮廓。保留双侧边界、W 读法与柔和转角。')]):
    bullet(c,head,body,748,603-j*143,382)
paragraph(c,'图形联想：W / 动作的起伏 / 两侧开放边界。无需把它解释成盾牌或认证标志。',85,151,1020,17,'ink',400,28)
text(c,'主标识不使用渐变、描边或发光；照片中的框线是延展图形，不是标识替代物。',85,95,14,'mintText');end()

# 05 - Lockups and size.
page(5,'组合、留白与最小尺寸。','04 / LOGOTYPE & LOCKUPS')
rect(c,56,356,665,277,'#E1ECE7');mark(c,99,426,180);word(c,308,443,366)
line(c,88,410,686,410,'control',1,[3,3]);line(c,88,567,686,567,'control',1,[3,3])
text(c,'英文横向组合 / 主版本',88,386,12,'mintText',500)
text(c,'Woah',780,580,52,'ink',700);word(c,782,509,95,'ink',True)
text(c,'正式中文名称 · 舞哇',780,472,15,'mintText',600)
paragraph(c,'中文名称正式确定为“舞哇”，英文正式名称为“Woah”。双语组合已转轮廓，可按语言环境分别使用。',780,437,330,15,'ink',400,25)
for j,(a,b) in enumerate([('保护空间','独立标识与组合四周至少留出 32 个设计单位；主组合图形与文字间距为 40。'),('尺寸下限','数字端宽度：符号 ≥ 24 px；组合 ≥ 120 px。\n印刷宽度：符号 ≥ 8 mm；组合 ≥ 32 mm。'),('字标规则','W 大写，oah 小写。基于 IBM Plex Sans Bold 进行光学字距处理，正式使用已转轮廓文件。')]):
    x=56+j*368;label(c,a,x,292);paragraph(c,b,x,252,336,15,'ink',400,25)
text(c,'小于下限时只用符号。不可把字标重新键入、改为全大写、压扁或缩窄中心负形。',56,90,15,'mintText');end()

# 06 - color variants.
page(6,'单色与反白，同样完整。','05 / LOGO COLORWAYS')
samples=[(56,360,'pearl','mintText','浅底主版本'),(620,360,'ink','mint','深底主版本'),(56,116,'pearl','ink','单色深墨'),(620,116,'ink','pearl','单色反白')]
for x,y,bg,fg,t in samples:
    rect(c,x,y,524,238,bg,0,stroke='line' if bg=='ink' else '#D3DEDA');mark(c,x+32,y+75,162,fg);word(c,x+223,y+90,266,fg)
    text(c,t,x+26,y+25,13,'secondary' if bg=='ink' else 'mintText',500)
text(c,'浅色画面上，文字与小尺寸标识使用深墨 / 深薄荷；原始薄荷绿主要用于较大的图形与色面。',56,74,14,'mintText');end()

# 07 - icons.
page(7,'一个标识，适配不同系统。','06 / APP ICONS')
for i,(title,shape) in enumerate([('iOS / 1024','round'),('Android / adaptive','circle'),('Monochrome','mono')]):
    x=92+i*355
    if shape=='circle':
        color(c,'ink');c.circle(x+110,465,110,fill=1,stroke=0)
    else:rect(c,x,355,220,220,'ink',47)
    mark(c,x+34,419,152,'pearl' if shape=='mono' else 'mint')
    text(c,title,x+110,321,16,'ink',500,'center')
paragraph(c,'iOS：提供 1024 × 1024 方形平面预览，以及前景 / 背景 SVG 分层源。源文件不烘焙圆角、阴影或系统材质；系统外形在此页仅作预览。',56,241,515,15,'ink',400,25)
paragraph(c,'Android：图层均为 108 × 108，标识宽 59，置于中心 66 直径安全区内。提供彩色前景、背景与单色图层，以及 API 26 / 33 的 XML 参考。',628,241,515,15,'ink',400,25)
paragraph(c,'接入边界：iOS 平面图标可编译；Icon Composer 分层外观与 Android 自适应主题图标仍须系统 / 真机复核。图标不烘焙圆角。',56,116,1070,15,'mintText',400,25);end()

# 08 - palette.
page(8,'薄荷强调，深墨承托。','07 / COLOR SYSTEM')
palette=[('Mint','mint','主品牌 / 关键操作'),('Ink','ink','深色背景 / 按钮文字'),('Surface','surface','界面表面'),('Pearl','pearl','浅色背景 / 主文字'),('Secondary','secondary','深色次文字'),('Blue','blue','辅助信息'),('Control','control','功能边界'),('Deep Mint','mintText','浅色文字 / 链接')]
for i,(name,k,role) in enumerate(palette):
    x=56+(i%4)*278;yy=426-(i//4)*210
    rect(c,x,yy,252,155,k,12,stroke='#D3DEDA' if k=='pearl' else None)
    fg='ink' if k in ['mint','pearl','secondary','blue'] else 'pearl'
    text(c,name,x+18,yy+115,22,fg,600);text(c,C[k],x+18,yy+27,18,fg,500)
    text(c,role,x,yy-27,13,'mintText',500)
paragraph(c,'主色值沿用所选提案。屏幕使用 sRGB。品牌行动色与编辑器选中、特效 ARGB、隐私 mask、成功/警告/错误互不混用。',56,119,1060,15,'ink',400,25)
text(c,'品牌色仅用于界面操作；视频中的用户特效颜色完全独立，人物标记遵循 segmentation mask 区域。',56,76,14,'mintText');end()

# 09 - semantics and contrast.
page(9,'人物标记，以分割 mask 为准。','08 / UI SEMANTICS',True)
rect(c,56,542,285,74,'mint',12);text(c,'导出',198,568,21,'ink',600,'center')
text(c,'深墨字 / 薄荷底  11.43 : 1',56,513,13,'secondary',500)
rect(c,56,375,285,96,'surface',12,stroke='control');text(c,'人物 02 · 分割 mask',76,433,17,'mint',600);text(c,'全身保护 · 仍需检查效果',76,403,14,'secondary')
paragraph(c,'人物显示 = 分割 mask 区域。\n选中 = 正在操作的对象。\n保护 = 用户启用的效果与范围。\n复核 = 逐段预览及导出检查。\nmask 标记本身不等于保护完成。',56,326,290,16,'pearl',400,29)
text(c,'前景 / 背景',413,619,13,'secondary',600);text(c,'对比度',955,619,13,'secondary',600);text(c,'用途',1134,619,13,'secondary',600,'right')
for i,row in enumerate(CHECKS):
    yy=578-i*49;text(c,row['role'],413,yy,14,'pearl',500)
    text(c,row['foreground']+' / '+row['background'],543,yy,12,'secondary')
    text(c,f'{row["ratio"]:.2f} : 1',955,yy,14,'mint' if row['pass'] else 'warning',600)
    text(c,'可用' if row['pass'] else '仅装饰 / 禁配',1134,yy,12,'secondary' if row['pass'] else 'warning',500,'right')
line(c,413,176,1144,176,'line');paragraph(c,'常规文字目标 ≥ 4.5 : 1；必要图标与控件边界目标 ≥ 3 : 1。数值按纯色 sRGB 计算，透明叠加与视频画面需重新检查。',413,144,720,14,'secondary',400,23)
text(c,'错误用柔红 + 明确文字；待复核用暖黄 + 说明。绿色不等于“隐私已确保”。',56,74,14,'mint',500);end()

# 10 - typography.
page(10,'字体安静，信息明确。','09 / TYPOGRAPHY & SPACING')
text(c,'Aa Bb Cc 0123',56,595,49,'ink',500);text(c,'IBM Plex Sans',56,553,19,'mintText',600)
text(c,'看见舞步，留住边界。',56,483,33,'ink',500);text(c,'Noto Sans SC / 简体中文',56,442,17,'mintText',600)
paragraph(c,'品牌字标正式转轮廓。IBM Plex / Noto Sans SC 是品牌排版目标；移动端先使用系统中文回退，不强制打包 17.8 MB 的变量字体。',56,383,485,16,'ink',400,27)
type_rows=[('Display',32,40,600),('Title',24,32,600),('Section',20,28,600),('Body',16,24,400),('Control',15,20,600),('Caption',12,16,500)]
for i,(a,b,d,e) in enumerate(type_rows):
    yy=603-i*50;text(c,a,652,yy,17,'ink',e);text(c,f'{b} / {d} · {e}',1134,yy,16,'mintText',500,'right');line(c,652,yy-18,1144,yy-18,'#D3DEDA')
line(c,56,257,1144,257,'#D3DEDA')
for j,(a,b) in enumerate([('4 点间距系统','4 / 8 / 12 / 16 / 24 / 32 / 48 / 64'),('基础尺寸','页面边距 24；最小触控 48；图标 24 / 线宽 2'),('圆角与字距','控件圆角 12；面板 20；中文正文 0 字距')]):
    x=56+j*368;label(c,a,x,215);paragraph(c,b,x,175,318,15,'ink',400,25)
text(c,'界面支持系统字号调整；文案可换行，避免固定高度裁切。此页数值为逻辑单位，海报另按版面比例缩放。',56,76,14,'mintText');end()

# 11 - grammar.
page(11,'开放框线，仅作品牌延展。','10 / GRAPHIC LANGUAGE')
bracket(c,100,318,478,264,'mint',29);dancer(c,332,405,1.05,'ink')
text(c,'品牌宣传的版式示意',339,282,16,'mintText',500,'center')
bullet(c,'框线用于品牌画面','开放边界用于品牌排版与宣传构图，给动作留出空间；不作为编辑器中的人物显示标记。',711,605,407)
bullet(c,'人物标记来自 mask','编辑器显示 segmentation 产生的人物区域，以填充 / 叠加表达。形状跟随人物区域，不画矩形线框或仅描边。',711,449,407)
bullet(c,'摄影与人物','选择动作清楚、留白充足的真实舞蹈场景。使用获得同意的拍摄素材；不要用“人物轮廓不可识别”的假设代替检查。',711,291,407)
line(c,56,162,1144,162,'#D3DEDA')
paragraph(c,'动效建议：状态切换 160 ms，面板过渡 240 ms；减少动态时改为淡入。启动不增加强制等待，不让品牌动效遮挡导入与导出进度。',56,122,1070,16,'ink',400,27);end()

# 12 - two current app workflows (concepts, not captured UI screenshots).
page(12,'双工作流：保护与同步对比。','11 / PRODUCT APPLICATIONS')
editor(c,97,99,243,526);comparison(c,443,99,243,526)
label(c,'隐私保护 / 390 × 844',97,644);label(c,'舞蹈对比 / 390 × 844',443,644)
bullet(c,'共同的 Quiet Frame','深墨表面和薄荷强调统一两个入口；品牌图形不覆盖媒体舞台。保留真实的滑动任务入口。',774,605,351)
bullet(c,'保护与 mask','人物标记跟随 segmentation mask 区域。选中不代表保护成功；以分段预览及导出复核为准。',774,439,351)
bullet(c,'共同音乐对比','参考 / 目标双画面，保留已有同步、偏移、时间轴与视频输出；品牌框线不参与媒体判定。',774,265,351)
paragraph(c,'仅为品牌应用示意，非运行截图。两种界面结构以现有 Flutter 实际代码和真机复核为准。',774,114,349,13,'mintText',400,22);end()

# 13 - marketing.
page(13,'宣传材料：让动作穿过边界。','12 / CAMPAIGN APPLICATION')
poster(c,56,92,414,517.5)
label(c,'竖版 / 1080 × 1350',56,637)
word(c,587,471,530);text(c,'看见舞步，留住边界。',587,425,27,'mintText',500)
bracket(c,606,217,490,140,'mint',20);text(c,'DANCE. WITH BOUNDARIES.',851,275,22,'ink',600,'center')
paragraph(c,'大字标建立辨识；开放框线组织动势；一句核心文案完成沟通。优先使用一位动作清楚的主体，避免用无关装饰填满背景。',587,164,535,16,'ink',400,26)
text(c,'平面样张使用原创抽象舞者；摄影方向参照所选概念图。',56,71,13,'mintText');end()

# 14 - handoff.
page(14,'交付清单与接入边界。','13 / HANDOFF')
deliveries=[('logo/','符号、英文字标、横向组合：薄荷 / 深墨 / 反白 SVG；正式双语组合'),('icons/','iOS 1024 PNG + 分层 SVG；Android 108 图层与 XML 参考'),('applications/','启动、编辑、海报的独立 PDF / PNG；海报 SVG 基础版式'),('tokens/','颜色、文字、间距、圆角、动效建议：JSON 与 CSS 参考'),('fonts/ + reference/','原字体与许可文件；用户选定的原始概念图（未改动）'),('checks/ + source/','颜色对比结果、资产校验记录与可复现生成脚本')]
for i,(a,b) in enumerate(deliveries):
    yy=613-i*59;text(c,a,56,yy,16,'mintText',600);paragraph(c,b,335,yy,790,15,'ink',400,22);line(c,56,yy-27,1144,yy-27,'#D3DEDA')
paragraph(c,'接入建议：flowBackground → dark.canvas；flowSurface → dark.surface；gold / coral 操作语义 → dark.action；按钮前景 → dark.onAction。不要全局替换视频效果颜色或全部金色资产。',56,217,1066,16,'ink',400,27)
paragraph(c,'已确认为中文“舞哇”、英文“Woah”。v1.1 附带按语义接入的独立分支；系统图标、冷启动、全链路回归需合并前验证，未经验证不标注已通过。',56,125,1066,15,'mintText',400,25);end()

# 15 - evidence.
page(15,'依据、版本与使用说明。','14 / REFERENCES')
label(c,'视觉依据',56,620)
paragraph(c,'用户补充的 Quiet Frame identity board 为视觉方向。修订1依据用户纠正：当前人物显示标记是 segmentation 产生的 mask。编辑器示意与规范已同步修正；品牌框线保留为宣传排版图形。',56,584,1060,16,'ink',400,27)
label(c,'产品依据 / 2026.10.29 产品核对',56,492)
for i,s in enumerate(['README.md — 产品定位与能力说明；对能力的描述仍需与实际版本核对。','mobile/app/lib/app/theme.dart — 品牌色与操作 / 选中 / 特效语义分层。','mobile/app/lib/features/import_video/presentation/home_tool_catalog.dart — 两个独立功能入口。','docs/home-minimal-launcher-v2.md — 首屏已确定的媒体任务优先原则。']):
    text(c,s,56,456-i*31,13,'ink')
label(c,'外部规范 / 官方来源',56,301)
links=[('Android adaptive icons','https://developer.android.com/develop/ui/compose/system/icon_design_adaptive'),('Apple Icon Composer','https://developer.apple.com/documentation/Xcode/creating-your-app-icon-using-icon-composer'),('WCAG text contrast','https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html'),('WCAG non-text contrast','https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html'),('Fonts / IBM Plex + Noto Sans SC','https://github.com/IBM/plex')]
for i,(title,url) in enumerate(links):
    yy=267-i*29;text(c,title,56,yy,12,'mintText',600);text(c,url,306,yy,10,'ink');c.linkURL(url,(306,yy-2,1144,yy+13),relative=0,thickness=0)
paragraph(c,'使用说明：v1.1 为正式命名及生产候选规范；代码接入独立分支，未合并、未推送 master，平台构建和真机签核另行记录。',56,91,1070,14,'mintText',400,23);end()
c.save()

# Render every page for visual QA and a compact overview.
d=fitz.open(PDF)
for i,p in enumerate(d):p.get_pixmap(matrix=fitz.Matrix(1.1,1.1),alpha=False).save(str(TMP/f'page-{i+1:02}.png'))
thumbs=[]
for i in range(len(d)):
    im=Image.open(TMP/f'page-{i+1:02}.png').convert('RGB');im.thumbnail((480,320));thumbs.append(im)
sheet=Image.new('RGB',(480*3,350*5),'#DFE8E3');dr=ImageDraw.Draw(sheet)
for i,im in enumerate(thumbs):
    x=(i%3)*480;y=(i//3)*350;sheet.paste(im,(x,y));dr.text((x+12,y+324),f'{i+1:02}',fill='#216A5E')
sheet.save(ROOT/'Woah-Quiet-Frame-overview.png')
d.close()

readme='''# Woah / Quiet Frame VI v1.1

Quiet Frame 品牌方向的 v1.1 生产候选交付，2026-10-29，修订2。

先阅读 `Woah-Quiet-Frame-VI-v1.1.pdf`。正式中文名“舞哇”，正式英文名“Woah”。Quiet Frame 为视觉方向名，不替代产品名称。

## 本次纠正

中文品牌名称定稿为舞哇，英文名称定稿为 Woah；产品同时覆盖人物隐私保护与舞蹈双视频同步对比。标识、字标及画面延展遵循主品牌，而非为每个功能重设 Logo。

当前人物显示标记是 segmentation 产生的 mask，按人物区域填充或叠加；不是线框、矩形框或仅描边。开放框线仅作为品牌排版与宣传延展。编辑器 PDF/PNG、VI 手册、Token 语义与本源文件已同步修正。示意中的人物与 mask 为人工绘制，薄荷色仅为 VI 示意，不代表当前实现的颜色、透明度或模型精度。

## 资产

- `assets/logo/`：SVG 均已转轮廓，无字体依赖。无裁切的 256×160 符号设计画布。
- `assets/icons/ios/`：1024 PNG 为平面预览，default/dark 共享主色（待独立系统外观验收）；mono PNG 保留透明前景。分层 SVG 为 Icon Composer 的源材料，并非已完成的 `.icon` 项目。
- `assets/icons/android/`：108×108 前景/背景/单色源。XML 为接入参考，需要平台真实编译；API26 文件放入 mipmap-anydpi-v26，API33 文件放入 mipmap-anydpi-v33，并统一重命名为 ic_launcher.xml，图层 XML 放入 drawable。先在隔离分支中检查平台效果。
- `assets/applications/`：品牌应用示意含保护与双视频对比，不是运行截图。原创抽象舞者不代表真实保护效果。海报 SVG 只含基础字标/框线；完整构图在 PDF。
- `assets/tokens/`：新规范建议值，已用于独立接入分支的语义色参考；不可全局替换视频效果色。
- `assets/fonts/`：随包仅分发许可证文件，不重新分发 TTF。文档内部已嵌入必要字体子集；完整字体可从用户原 v1.0 包或官方来源取得。
- `assets/reference/`：用户选定概念图原文件，未改动。
- `assets/checks/`：纯色 sRGB 对比值与导出检查。

## 重建

脚本位于 `source/build_vi.py`，需要 Python 3、reportlab、fonttools、Pillow、PyMuPDF。重建前请先从自有 v1.0 包或官方来源将 IBM Plex Sans 四个字重及 NotoSansSC-Variable.ttf 放入 `assets/fonts/`，然后运行 `python source/build_vi.py`；品牌源码不依赖这些字体在运行设备上存在。

## 待确认

待验收：iOS Icon Composer 外观、Android 多 OEM 系统遮罩、真机小尺寸、系统大字号、视频保护与双视频对比完整回归；不影响正式命名。
'''
(ROOT/'README.md').write_text(readme,encoding='utf8')
# Recompute v1.1 checks instead of inheriting v1.0 export receipts.
import xml.etree.ElementTree as ET
svg_files = sorted(ASSETS.rglob('*.svg'))
for f in svg_files:
    root_svg=ET.parse(f).getroot()
    assert root_svg.tag.endswith('svg') and root_svg.attrib.get('viewBox'), str(f)
(ASSETS/'checks/export-review.json').write_text(json.dumps({
    'revision':2,'svgSourcesParsed':len(svg_files),'allSVGParsed':True,
    'androidAdaptiveOpticalWidthDp':59,'androidSafeCircleDiameterDp':66,
    'iosLogoOpticalWidthPx':720,'iosDarkAndDefaultSharedArtwork':True,
    'androidBuild':'pending','iosIconComposer':'pending',
    'onDeviceSmallIconReview':'pending',
},ensure_ascii=False,indent=2),encoding='utf8')
with fitz.open(PDF) as proof:
    bounds=[]
    for index, pg in enumerate(proof):
        for blk in pg.get_text('dict')['blocks']:
            if blk.get('type')!=0:continue
            for ln in blk.get('lines',[]):
                for span in ln.get('spans',[]):
                    x0,y0,x1,y1=span['bbox']
                    if min(x0,y0)<-1 or x1>W+1 or y1>H+1:
                        bounds.append({'page':index+1,'text':span['text'][:25],'bbox':span['bbox']})
    (ASSETS/'checks/document-review.json').write_text(json.dumps({
        'revision':2,'pages':len(proof),'pageSize':[W,H],
        'textBoundsIssues':bounds,'allPagesRendered':len(proof)==len(PAGES),
        'visualReview':'Pages 3, 5, 7, 12 and summary inspected; no external device QA claimed.',
        'scope':'Formal bilingual name + dual workflows + production candidate, no device proof.'
    },ensure_ascii=False,indent=2),encoding='utf8')
    assert not bounds, bounds
(ASSETS/'checks/mask-correction.json').write_text(json.dumps({
    'revision':2,
    'source':'2026-10-25 segmentation mask clarification retained in v1.1',
    'personMarker':'actual segmentation filled region; not a rectangular/wireframe selection',
    'privacyProtection':'requires effect preview and export review, not merely mint selection',
    'editorIllustration':'hand drawn, not output of a model',
    'comparisonIllustration':'hand drawn, dual workflow; does not imply actual screenshot',
},ensure_ascii=False,indent=2),encoding='utf8')

# File hashes of distributed assets. Raw font binaries are not in this package.
manifest_path = ASSETS/'checks/manifest.json'
tracked = []
for f in sorted(ASSETS.rglob('*')):
    if not f.is_file() or f==manifest_path or f.suffix.lower() in ('.ttf', '.otf'):
        continue
    data = f.read_bytes()
    tracked.append({'path':str(f.relative_to(ASSETS)),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
manifest_path.write_text(json.dumps({'schemaVersion':2,'brandChinese':'舞哇','brandEnglish':'Woah','revision':'1.1','fontBinariesIncluded':False,'files':tracked},ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'pdf':str(PDF),'pages':len(PAGES),'assets':len(list(ASSETS.rglob('*'))),'overview':str(ROOT/'Woah-Quiet-Frame-overview.png')},ensure_ascii=False))
