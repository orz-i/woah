#!/usr/bin/env python3
"""Woah Lime Beat: native vector identity, application examples, VI review PDF.

Run with reportlab, fonttools, Pillow, PyMuPDF. Rebuild the Lime Beat manual and editable asset package.
The supplied selected board is retained unchanged as a reference.
"""
from pathlib import Path
import sys
_repo = next((p for p in Path(__file__).resolve().parents if (p/'mobile/app/lib').exists()), None)
if _repo:
    sys.path.insert(0, str(_repo/'mobile/app/build/vi-python'))
sys.path.append(str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/python/Lib/site-packages'))
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
import pymupdf as fitz
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
PROJECT=_repo
WORK=(PROJECT/'mobile/app/build/vi-design') if PROJECT else ROOT/'_build'
STAGE=WORK/'Woah-Lime-Beat-VI-v2.2'
ASSETS=STAGE/'assets'
TMP=WORK/'proof'; TMP.mkdir(parents=True,exist_ok=True)
FONTDIR=(PROJECT/'mobile/app/build/vi-fonts') if PROJECT else ROOT/'assets/fonts'
PALETTE=json.loads((ROOT/'palette.json' if (ROOT/'palette.json').exists() else ROOT/'assets/tokens/palette.json').read_text(encoding='utf8'))
REF=ROOT/'assets/selected-home.png'
if not REF.exists(): REF=ROOT/'assets/reference/selected-lime-beat-home.png'
for d in ['logo','icons/ios','icons/android','applications','photography','tokens','fonts','reference','checks']:
    (ASSETS/d).mkdir(parents=True,exist_ok=True)

C={
 'mint':'#D8F54C','ink':'#17201C','surface':'#222C24','line':'#CDD4BF',
 'pearl':'#F7F9F1','secondary':'#46513F','blue':'#DCD7FA',
 'control':'#768369','mintText':'#50670E','muted':'#5D6B53',
 'danger':'#AD3044','warning':'#865A17','mintPressed':'#C3DF36',
}
LEFT='M16 8H48Q64 8 64 24V111Q64 119 76 124V65Q76 51 89 56L111 64Q120 67 120 78V113Q120 122 111 127L70 148Q47 160 21 151Q0 145 0 124V24Q0 8 16 8Z'
CMDS=[('M',(16,8)),('L',(48,8)),('Q',(64,8,64,24)),('L',(64,111)),('Q',(64,119,76,124)),('L',(76,65)),('Q',(76,51,89,56)),('L',(111,64)),('Q',(120,67,120,78)),('L',(120,113)),('Q',(120,122,111,127)),('L',(70,148)),('Q',(47,160,21,151)),('Q',(0,145,0,124)),('L',(0,24)),('Q',(0,8,16,8)),('Z',())]

def esc(s): return html.escape(str(s),quote=True)
def wrap_svg(w,h,body,title='Woah Lime Beat'):
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

# Editable logo variants reuse the original outlined W and wordmark geometry.
for name, fg in [('ink',C['ink']),('lime',C['mint']),('chalk',C['pearl'])]:
    (ASSETS/'logo'/f'woah-symbol-{name}.svg').write_text(wrap_svg(256,160,mark_svg(color=fg)),encoding='utf8')
    (ASSETS/'logo'/f'woah-wordmark-{name}.svg').write_text(wrap_svg(620,190,word_svg(0,0,620,fg)),encoding='utf8')
    body=mark_svg(0,0,256,fg)+word_svg(296,8,504,fg)
    (ASSETS/'logo'/f'woah-lockup-horizontal-{name}.svg').write_text(wrap_svg(800,160,body),encoding='utf8')
body=mark_svg(0,0,256,C['mint'])+word_svg(296,8,504,C['pearl'])
(ASSETS/'logo/woah-lockup-horizontal-reversed.svg').write_text(wrap_svg(800,160,body),encoding='utf8')
shutil.copy2(ASSETS/'logo/woah-lockup-horizontal-lime.svg',ASSETS/'logo/woah-lockup-horizontal-primary.svg')
body=mark_svg(0,32,256,C['mint'])+word_svg(296,0,504,C['mint'])+word_svg(300,174,98,C['mint'],True)
(ASSETS/'logo/woah-lockup-bilingual-official.svg').write_text(wrap_svg(800,238,body,'舞哇 / Woah · 正式双语标识'),encoding='utf8')

brand_dir=(PROJECT/'mobile/app/assets/brand') if PROJECT else ROOT/'assets/icons/ios'
for mode in ('light','dark','tinted'):
    src=brand_dir/f'woah-icon-{mode}-1024.png'
    shutil.copy2(src,ASSETS/'icons/ios'/src.name)
    bg,fg=(PALETTE[mode]['icon_background'],PALETTE[mode]['icon_foreground']) if mode!='tinted' else ('#101010','#FAFAFA')
    svg=f'<rect width="1024" height="1024" fill="{bg}"/>'+mark_svg(152,287,720,fg)
    (ASSETS/'icons/ios'/f'woah-icon-{mode}-1024.svg').write_text(wrap_svg(1024,1024,svg),encoding='utf8')
Image.open(ASSETS/'icons/ios/woah-icon-light-1024.png').convert('RGB').resize((512,512),Image.Resampling.LANCZOS).save(ASSETS/'icons/android/woah-android-flat-preview-512.png')
for mode in ('light','dark'):
    bg,fg=PALETTE[mode]['icon_background'],PALETTE[mode]['icon_foreground']
    (ASSETS/'icons/android'/f'woah-adaptive-{mode}-background.svg').write_text(wrap_svg(108,108,f'<rect width="108" height="108" fill="{bg}"/>'),encoding='utf8')
    (ASSETS/'icons/android'/f'woah-adaptive-{mode}-foreground.svg').write_text(wrap_svg(108,108,mark_svg(24.5,35.5625,59,fg)),encoding='utf8')
(ASSETS/'icons/android/woah-adaptive-monochrome.svg').write_text(wrap_svg(108,108,mark_svg(24.5,35.5625,59,C['pearl'])),encoding='utf8')
if PROJECT:
    res=PROJECT/'mobile/app/android/app/src/main/res'
    for directory in ('drawable','drawable-night','values','values-night','mipmap-anydpi-v26','mipmap-anydpi-v33'):
        for f in (res/directory).glob('*.xml'):
            if 'woah_' in f.name or f.name in ('colors.xml','ic_launcher.xml'):
                dest=ASSETS/'icons/android/res'/directory/f.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,dest)
    ios=PROJECT/'mobile/app/ios/Runner/Assets.xcassets/AppIcon.appiconset/Contents.json'
    shutil.copy2(ios,ASSETS/'icons/ios/Contents.json')
    # Keep the catalog itself usable: its filenames match the supplied PNGs.
    cat=json.loads((ASSETS/'icons/ios/Contents.json').read_text())
    for item in cat['images']:
        mode=item.get('appearances',[{'value':'light'}])[0]['value']
        item['filename']=f'woah-icon-{mode}-1024.png'
    (ASSETS/'icons/ios/Contents.json').write_text(json.dumps(cat,indent=2)+'\n',encoding='utf8')
else:
    shutil.copytree(ROOT/'assets/icons/android/res',ASSETS/'icons/android/res',dirs_exist_ok=True)
    shutil.copy2(ROOT/'assets/icons/ios/Contents.json',ASSETS/'icons/ios/Contents.json')
for name in ('home-video-protection-lime.jpg','home-dance-comparison-lime.jpg'):
    src=(PROJECT/'mobile/app/assets/home'/name) if PROJECT else ROOT/'assets/photography'/name
    shutil.copy2(src,ASSETS/'photography'/name)
for name in ('IBMPlexSans-Regular.ttf','IBMPlexSans-Medium.ttf','IBMPlexSans-SemiBold.ttf','IBMPlexSans-Bold.ttf','NotoSansSC-Variable.ttf','IBM-Plex-LICENSE.txt','NotoSansSC-OFL.txt'):
    shutil.copy2(FONTDIR/name,ASSETS/'fonts'/name)
shutil.copy2(REF,ASSETS/'reference/selected-lime-beat-home.png')
shots=(ROOT/'qa/alignment-fix') if PROJECT else ROOT/'assets/applications'
for name in ('04-after-protection.png','05-after-comparison.png'):
    shutil.copy2(shots/name,ASSETS/'applications'/name)

tokens={'$description':'Woah Lime Beat VI v2.2, 2026-11-04; pale lime home footer and smaller brand; interface tokens independent from video effects.',
 'brand':{'englishName':'Woah','chineseName':'舞哇',**PALETTE['brand'],'direction':'Lime Beat'},
 'light':{**PALETTE['light'],'action':C['mint'],'onAction':C['ink'],'focus':PALETTE['light']['selection']},
 'dark':{**PALETTE['dark'],'action':C['mint'],'onAction':C['ink'],'focus':PALETTE['dark']['selection']},
 'type':{'latin':'IBM Plex Sans','chinese':'Noto Sans SC','mobile':'platform system fallback','homeBrand':[24,1.12,700],'homeTitle':[20,1.22,700],'homeDescription':[13.5,1.42,400],'homeLineMetrics':'forced strut, shared first-line baselines'},
 'layout':{'space':[4,8,12,16,24,32,48,64],'touchTargetMin':48,'homeCardRadius':22,'homeInteraction':'existing horizontal PageView, side peek, pagination, whole-card click'},
 'homeBrand':{'symbolSize':[28,17.5],'symbolWordGap':8,'headerSlotMinHeight':36},
 'homeFooter':{'stops':[0,0.76,0.90,1],'colorsArgb':['#00F7F9F1','#00F7F9F1','#E6F5F8EB','#FFF0F6DA'],'foreground':'#17201C','description':'#374232'},
 'editor':{'personMarker':'segmentation mask region; never a wireframe or rectangular brand frame','selection':'current object only, not protection completion','effectPaletteAmber':'0xFFF5A623; unchanged'},
 'media':{'canvas':'#000000','themeChangesVideoPixels':False}}
(ASSETS/'tokens/woah-lime-beat.tokens.json').write_text(json.dumps(tokens,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
(ASSETS/'tokens/palette.json').write_text(json.dumps(PALETTE,indent=2)+'\n',encoding='utf8')
css='/* Woah Lime Beat VI v2.2: interface only; media/effect ARGB is independent. */\n:root {\n'
for key,value in {**PALETTE['brand'],**PALETTE['light'],'action':C['mint'],'on-action':C['ink']}.items():
    css+=f'  --woah-{key.replace("_","-")}: {value};\n'
css+='  --woah-font-latin: "IBM Plex Sans";\n  --woah-font-cjk: "Noto Sans SC";\n  --woah-space-unit: 4px;\n  --woah-touch-min: 48px;\n  --woah-radius-control: 12px;\n  --woah-home-brand-size: 24px;\n  --woah-home-symbol-width: 28px;\n  --woah-home-brand-gap: 8px;\n  --woah-home-footer: linear-gradient(180deg, #F7F9F100 0%, #F7F9F100 76%, #F5F8EBE6 90%, #F0F6DA 100%);\n}\n@media (prefers-color-scheme: dark) {\n  :root {\n'
for key,value in PALETTE['dark'].items():css+=f'    --woah-{key.replace("_","-")}: {value};\n'
css+='  }\n}\n'
(ASSETS/'tokens/woah-lime-beat.css').write_text(css,encoding='utf8')

W,H=1200,800
PDF=STAGE/'Woah-Lime-Beat-VI-v2.2.pdf'
c=canvas.Canvas(str(PDF),pagesize=(W,H),pageCompression=1)
c.setTitle('Woah 青柠节拍 Lime Beat VI v2.2');c.setAuthor('Woah Brand Design')
PAGES=[]
def page(num,title,kicker):
    PAGES.append(title);rect(c,0,0,W,H,'pearl')
    label(c,kicker,56,746);text(c,title,56,693,31,'ink',700)
    line(c,56,666,1144,666,'line')
    text(c,'WOAH / LIME BEAT',56,31,10,'muted',600)
    text(c,'VI v2.2 · 青柠节拍 · 2026.11.04',600,31,10,'muted',400,'center')
    text(c,f'{num:02}',1144,31,11,'muted',600,'right')
def end():c.showPage()
def image(path,x,y,w,h):
    c.drawImage(ImageReader(str(path)),x,y,width=w,height=h,preserveAspectRatio=True,anchor='c',mask='auto')
def note(head,body,x,y,w=320):
    text(c,head,x,y,21,'ink',700);paragraph(c,body,x,y-34,w,15,'secondary',400,25)
def logo(x,y,w,fg='ink',bg=None):
    if bg:rect(c,x-20,y-22,w*3.2,160*w/256+44,bg,12)
    mark(c,x,y,w,fg);word(c,x+w*1.15,y+w*.06,w*1.95,fg)
def poster(path):
    pc=canvas.Canvas(str(path),pagesize=(1080,1350));rect(pc,0,0,1080,1350,'pearl')
    mark(pc,65,1155,142,'mint');word(pc,250,1171,690,'mint')
    pc.drawImage(ImageReader(str(ASSETS/'photography/home-video-protection-lime.jpg')),65,255,width=950,height=825,preserveAspectRatio=True,anchor='c')
    rect(pc,65,85,950,148,'mint',22);text(pc,'看见舞步，留住边界。',99,163,40,'ink',700)
    text(pc,'Woah · 舞蹈视频保护与同步对比',101,115,20,'ink',500);pc.save()
poster(ASSETS/'applications/poster-1080x1350.pdf')
poster_svg=f'<rect width="1080" height="1350" fill="{C["pearl"]}"/>'+mark_svg(65,65,142,C['mint'])+word_svg(250,65,690,C['mint'])+f'<image href="../photography/home-video-protection-lime.jpg" x="65" y="270" width="950" height="825" preserveAspectRatio="xMidYMid meet"/><rect x="65" y="1117" width="950" height="148" rx="22" fill="{C["mint"]}"/>'
(ASSETS/'applications/poster-1080x1350.svg').write_text(wrap_svg(1080,1350,poster_svg,'Woah Lime Beat · editable photo poster foundation'),encoding='utf8')

# 01: a bright identity cover, replacing the old ink/mint cover.
PAGES.append('Lime Beat 青柠节拍');rect(c,0,0,W,H,'pearl')
label(c,'WOAH BRAND IDENTITY',56,744,'ink');text(c,'LIME BEAT / VISUAL IDENTITY 2.2',1144,744,11,'ink',600,'right')
mark(c,65,345,330,'mint');word(c,448,384,681,'mint')
text(c,'Lime Beat / 青柠节拍',66,272,36,'ink',700)
text(c,'明亮、舒展、有节奏。',66,211,29,'ink',500)
paragraph(c,'为舞蹈视频保护与同步对比设计。让真实的动作、自然光与清晰操作成为主角。',68,146,790,18,'ink',400,30)
text(c,'替代 Quiet Frame 旧版 VI · 保留 W 几何与既有交互',68,49,12,'ink',500)
text(c,'2026.11.04',1144,49,12,'ink',500,'right');end()

# 02
page(2,'把活力放回舞步。','01 / BRAND STRATEGY')
paragraph(c,'Woah（舞哇）服务于舞蹈记录、人物呈现范围选择与双视频动作对照。新视觉从安静的暗场工具，转向明亮、自信的练舞空间；保留真实工作流，把视觉能量交给影像与少量高饱和强调。',56,620,1070,21,'ink',400,35)
for i,(a,b) in enumerate([('明亮','浅白空间与自然日光，让动作更容易被看见。'),('舒展','完整身体姿态、清晰重心与衣物动势，传递舞蹈张力。'),('专注','导航、参数与手势沿用；品牌色不改变视频像素。')]):note(a,b,56+i*368,411)
line(c,56,230,1144,230)
text(c,'视觉关键词',56,186,16,'mintText',600);text(c,'自然光 / 身体张力 / 青柠 / 淡紫 / 清晰',250,186,22,'ink',600)
paragraph(c,'隐私方向沿用“看见舞步，留住边界。”；对比方向强调同步、练习与检查。品牌色与 Logo 不作为保护完成或动作评分的证明。',56,128,1070,16,'secondary',400,27);end()

# 03
page(3,'双工作流，共用一个品牌。','02 / PRODUCT BASIS')
rows=[('视频保护','保留主角，遮挡其他人物','人物选择、全身 / 人脸效果与导出沿用现有实现。'),('舞蹈对比','同步两段视频，对照练习动作','保留共同音乐校准、双画面、时间轴、偏移与导出。'),('首页结构','左右滑卡 / 露边 / 分页 / 整卡点击','不改为上下列表、工具网格、底部导航或新引导页。'),('媒体与品牌','主题作用于界面，不作用于真实视频','用户特效 ARGB、分割 mask、同步和导出算法保持独立。')]
for i,(a,b,d) in enumerate(rows):
    yy=605-i*111;text(c,a,56,yy,20,'ink',700);paragraph(c,b,255,yy,330,16,'ink',500,26);paragraph(c,d,660,yy,475,16,'secondary',400,26);line(c,56,yy-68,1144,yy-68)
paragraph(c,'正式名称：英文 Woah，中文舞哇。产品内与系统安装名称只显示 Woah；双语组合用于正式品牌材料，不在首页重复添加中文副字标。',56,115,1070,16,'secondary',400,27);end()

# 04
page(4,'W 的造型保留，主色重新统一。','03 / SYMBOL CONSTRUCTION')
gx,gy,gw=85,270,560;s=gw/256
for i in range(0,257,16):line(c,gx+i*s,gy,gx+i*s,gy+160*s,'line',.6)
for i in range(0,161,16):line(c,gx,gy+i*s,gx+gw,gy+i*s,'line',.6)
mark(c,gx,gy,gw,'ink');line(c,gx+128*s,gy-15,gx+128*s,gy+160*s+15,'control',1,[4,4])
text(c,'256 × 160 原始设计画布',85,228,14,'mintText',500)
note('同一套原始曲线','双侧 W、中心留白和镜像关系保留，禁止从新概念图中重新描摹。',748,603,382)
note('中心留白','右半沿 x = 128 镜像，内侧端点 x = 120 / 136。不可压窄中缝、拉伸或描边替代。',748,439,382)
note('新主版本','亮色使用浅白底与 Lime W / 字标。深底使用 Lime；Ink 作为单色辅助版本，不替代亮色主版本。',748,275,382)
text(c,'改变的是视觉系统与色彩角色，W 的辨识结构保持连续。',85,104,18,'ink',600);end()

# 05
page(5,'字标、组合与使用空间。','04 / LOGOTYPE & LOCKUPS')
rect(c,56,357,665,277,'pearl',16,stroke='line');logo(99,426,180,'mint')
text(c,'英文横向组合 / 主版本',88,385,13,'ink',600)
word(c,782,535,310,'mint');word(c,782,459,95,'mint',True)
text(c,'正式中文名称：舞哇',782,419,15,'mintText',600)
for j,(a,b) in enumerate([('保护空间','四周至少留出 32 个设计单位；标准组合的符号与字标间隔为 40。'),('尺寸下限','数字端：符号至少 24 px，组合至少 120 px。印刷：符号至少 8 mm。'),('正式字标','基于 IBM Plex Sans Bold 的原有光学字距；正式 SVG 已转轮廓，无需安装字体。')]):note(a,b,56+j*368,283,330)
text(c,'小尺寸优先单独 W；不可重打字标、改成全大写、压扁或拉长。',56,88,15,'mintText',500);end()

# 06
page(6,'浅色底，青柠主版本。','05 / LOGO COLORWAYS')
for x,y,bg,fg,t in [(56,360,'pearl','mint','亮色主版本 / 浅白底 + 青柠'),(620,360,'pearl','ink','单色辅助 / 浅白底 + Ink'),(56,116,'ink','mint','深底 / 青柠主版本'),(620,116,'ink','pearl','单色反白')]:
    rect(c,x,y,524,238,bg,12,stroke='line');mark(c,x+32,y+75,162,fg);word(c,x+223,y+90,266,fg)
    text(c,t,x+26,y+25,14,'pearl' if bg=='ink' else 'ink',500)
text(c,'亮色首页、图标、启动页与品牌材料统一使用浅白底青柠标识；正文与操作文案保持深色。',56,74,14,'mintText',500);end()

# 07
page(7,'应用图标与启动页同步换色。','06 / APP ICONS')
for i,(mode,title) in enumerate([('light','Light / 浅白底 + Lime W'),('dark','Dark / Ink 底 + Lime W'),('tinted','Tinted / 系统着色')]):
    x=92+i*355;image(ASSETS/'icons/ios'/f'woah-icon-{mode}-1024.png',x,355,220,220);text(c,title,x+110,320,16,'ink',600,'center')
paragraph(c,'图标源为 1024 × 1024 不透明 RGB PNG。系统负责外形与着色；不烘焙圆角、阴影和高光。iOS Any / Dark / Tinted 插槽保留。',56,243,515,16,'secondary',400,27)
paragraph(c,'Android 自适应图层维持 108 × 108 画布、59 单位 W 光学宽度与原有居中位置。提供日间、夜间、API 33 单色层及资源 XML。',628,243,515,16,'secondary',400,27)
paragraph(c,'启动页浅色为 Chalk + Lime W，深色为 Ink + Lime W，随后进入同色系应用。亮色图标同样采用浅白底与青柠 W；图标与启动背景分别定义，保持同步。',56,118,1070,16,'mintText',400,27);end()

# 08
page(8,'青柠强调，浅白承托。','07 / COLOR SYSTEM')
swatches=[('Lime','mint','主标识、主操作与选中填充'),('Chalk','pearl','浅色背景与留白'),('Ink','ink','正文与操作前景'),('Lavender','blue','辅助色与摄影点缀'),('Olive','mintText','浅底小字与细线'),('Control','control','必要控件边界')]
for i,(name,k,role) in enumerate(swatches):
    x=56+(i%3)*368;yy=420-(i//3)*216;rect(c,x,yy,340,158,k,14,stroke='line' if k=='pearl' else None)
    fg='pearl' if k in ('ink','mintText','control') else 'ink';text(c,name,x+18,yy+116,23,fg,700);text(c,C[k],x+18,yy+29,20,fg,500);text(c,role,x,yy-28,14,'secondary',500)
paragraph(c,'浅色页面与真实摄影占主要面积，青柠用于关键操作和局部渐变，淡紫少量点缀。系统深色模式延续同一主色，不保留旧薄荷主色体系。',56,107,1070,16,'secondary',400,26);end()

# 09
page(9,'鲜明填充与可读前景分开定义。','08 / UI SEMANTICS')
rect(c,56,520,295,84,'mint',14);text(c,'导出',203,548,25,'ink',700,'center')
text(c,'Ink / Lime  13.56 : 1',56,488,15,'mintText',600)
rect(c,56,337,295,101,'#EEF1E5',12,stroke='control');text(c,'当前选中对象',77,400,18,'mintText',700);text(c,'选中不等于保护完成',77,366,14,'secondary')
paragraph(c,'人物标记来自分割 mask。品牌框线不代替人物区域。保护范围与结果仍需预览和导出检查；不能仅凭颜色判断。',56,293,295,16,'ink',400,28)
pairs=[('主操作前景','#17201C','#D8F54C','13.56 : 1'),('浅色选中文字','#50670E','#FFFFFF','6.39 : 1'),('必要边界','#768369','#FFFFFF','4.02 : 1'),('青柠 / 浅白','#D8F54C','#F7F9F1','1.16 : 1')]
for i,(role,fg,bg,ratio) in enumerate(pairs):
    yy=591-i*91;text(c,role,430,yy,19,'ink',600);text(c,fg+' / '+bg,430,yy-29,14,'secondary');text(c,ratio,1117,yy,20,'ink',700,'right');line(c,430,yy-51,1144,yy-51)
paragraph(c,'浅白底上的青柠用于主 Logo，不承担正文或操作提示。小字与焦点线使用 Ink / Olive；分页在青柠胶囊外增加深色细边界。数值为纯色 sRGB，照片叠层另作实际检查。',430,174,700,16,'secondary',400,27)
text(c,'错误、警告与成功保留独立语义与明确文字；真实视频和用户特效色完全独立。',56,78,14,'mintText',500);end()

# 10
page(10,'同一套字形，同一条基线。','09 / TYPOGRAPHY & SPACING')
text(c,'Aa Bb Cc 0123',56,593,49,'ink',700);text(c,'IBM Plex Sans / 品牌字标与材料',56,549,18,'mintText',600)
text(c,'明亮、舒展、有节奏。',56,478,34,'ink',700);text(c,'Noto Sans SC / 中文品牌材料',56,436,18,'mintText',600)
paragraph(c,'应用保留系统字体与现有字号，品牌材料使用授权的 IBM Plex Sans / Noto Sans SC。标识 SVG 转轮廓，PDF 嵌入必要字体子集。',56,378,500,16,'secondary',400,27)
for i,(a,b) in enumerate([('首页 Woah','24sp / w700；W 宽 28dp'),('卡片标题','20sp / 1.22 / w700'),('卡片说明','13.5sp / 1.42 / w400'),('触摸区域','主入口至少 48dp'),('卡片圆角','22dp，沿用原几何')]):
    yy=594-i*57;text(c,a,665,yy,17,'ink',600);text(c,b,1144,yy,16,'mintText',500,'right');line(c,665,yy-21,1144,yy-21)
rect(c,56,114,1088,142,'blue',14)
paragraph(c,'标题和说明使用强制 Strut 行高与统一第一行基线；说明保留相同的两行槽位。验收同时检查静止卡片、滑动中间状态和放大字号，不仅比较文字框顶部。',81,211,1037,17,'ink',500,29)
text(c,'保留原有滑动、点击、取消与返回行为；不通过改变交互掩盖视觉对齐问题。',56,78,14,'mintText',500);end()

# 11
page(11,'影像来自明亮的练舞空间。','10 / PHOTOGRAPHY')
image(ASSETS/'photography/home-video-protection-lime.jpg',56,144,285,428)
image(ASSETS/'photography/home-dance-comparison-lime.jpg',374,144,285,428)
text(c,'视频保护 / 新生成摄影',56,608,16,'ink',600);text(c,'舞蹈对比 / 双画面摄影',374,608,16,'ink',600)
note('保持身体张力','动态姿态、明确重心、自然日光和清楚的手脚。服装采用青柠 / 白色与淡紫点缀。',743,603,390)
note('留出真实地板','底部浅色空间承托原生标题与说明。不要把文字、按钮和 Logo 烘焙进封面。',743,432,390)
note('避免旧暗场','不使用夜店霓虹、黑色舞台、浓烟和全幅深绿滤镜。封面不是实际隐私处理结果。',743,261,390)
text(c,'源图为 1024 × 1536，运行 JPEG 仅编码；原生渐变、文字与箭头仍由 Flutter 绘制。',56,85,14,'mintText',500);end()

# 12
page(12,'真实首页，保留左右滑动。','11 / PRODUCT APPLICATIONS')
image(ASSETS/'applications/04-after-protection.png',70,92,235,522)
image(ASSETS/'applications/05-after-comparison.png',366,92,235,522)
note('同一个 PageView','两张卡片的尺寸、位置、露边和分页保持。主入口仍是整卡，不新增卡内按钮或上下工具列表。',708,602,416)
note('品牌适当退后','亮色仍使用青柠 W / Woah；字标缩为 24sp，W 宽 28dp。渐变从卡片 76% 处淡入，收为浅青柠 #F0F6DA，减轻色块冲击。',708,430,416)
note('证据范围','本页为 Android 真机首页截图。真实视频播放、保护效果与导出另按具体工作流验证，不从封面推断。',708,258,416)
text(c,'用户选定参考决定视觉方向；现有产品几何与交互优先于生成图中的比例误差。',56,75,14,'mintText',500);end()

# 13
page(13,'宣传应用：动作先于装饰。','12 / CAMPAIGN APPLICATION')
poster_doc=fitz.open(ASSETS/'applications/poster-1080x1350.pdf');pix=poster_doc[0].get_pixmap(matrix=fitz.Matrix(.5,.5));pix.save(ASSETS/'applications/poster-1080x1350.png');poster_doc.close()
image(ASSETS/'applications/poster-1080x1350.png',56,102,414,517)
word(c,587,493,530,'mint');text(c,'看见舞步，留住边界。',587,436,28,'ink',700)
rect(c,587,295,530,94,'mint',16);text(c,'LIME BEAT / WOAH',852,328,25,'ink',700,'center')
paragraph(c,'用真实摄影、大字标与少量青柠建立辨识；不再沿用旧版暗场与薄荷开放框线作为主要视觉。淡紫从服装和辅助色面自然出现。',587,229,535,17,'secondary',400,28)
text(c,'提供 PDF / PNG 完整样张与可编辑 SVG 基础版式；品牌宣传不改变应用导航。',56,76,14,'mintText',500);end()

# 14
page(14,'正式手册与可编辑资产一起交付。','13 / HANDOFF')
for i,(a,b) in enumerate([('logo/','W 符号、英文字标、横向组合：Ink / Lime / Chalk，含正式双语版，全部转轮廓。'),('icons/','iOS Light / Dark / Tinted PNG + SVG + 插槽清单；Android 日夜 / 单色图层与资源 XML。'),('photography/','两张新摄影封面；标题、渐变、箭头由应用保持原生绘制。'),('applications/','两张真机首页与新海报 PDF / PNG / SVG；界面示意不冒充媒体效果证据。'),('tokens/','JSON / CSS 颜色、字体、间距、基线和媒体隔离约束，配色与仓库规范一致。'),('fonts/ + source/','授权字体及许可证、可重建脚本；PDF 已嵌入所需字体子集。')]):
    yy=612-i*72;text(c,a,56,yy,17,'mintText',700);paragraph(c,b,340,yy,790,16,'ink',400,26);line(c,56,yy-44,1144,yy-44)
paragraph(c,'系统启动器可能缓存图标，不能仅凭 PNG 重新生成就宣称所有系统外观生效。保持真实平台验证记录；不把旧版手册的通过结论继承到新版。',56,124,1070,16,'secondary',400,27);end()

# 15
page(15,'版本、来源与执行依据。','14 / REFERENCES')
note('当前视觉依据','用户于 2026-11-04 选定的青柠节拍首页参考，以及本轮补充的正式 VI / Logo 配色纠正。',56,612,1068)
note('延续的设计来源','原 Woah Quiet Frame VI v1.1 中的 W 曲线、字标轮廓、命名与分割 mask 语义得到保留；旧主色、图标配色和宣传风格已由本版替代。',56,483,1068)
note('产品依据','当前 Flutter 主题、首页双工作流注册表和真机截图。颜色只控制界面，不改变分割 mask、用户特效 ARGB、视频像素和同步 / 导出算法。',56,338,1068)
note('使用范围','这是正式 VI v2.2 交付。延续浅白底青柠主版本，首页缩小品牌、柔化渐变。iOS 本轮未运行；测试与真机范围见验证记录。',56,193,1068)
text(c,'规范索引：docs/design/lime-beat/README.md  /  palette.json  /  verification.md',56,80,14,'mintText',500);end()
c.save()

# Render every page and create an overview for actual visual inspection.
doc=fitz.open(PDF);sheet=Image.new('RGB',(1440,1750),'#E4E8DB');draw=ImageDraw.Draw(sheet)
for i,p in enumerate(doc):
    pix=p.get_pixmap(matrix=fitz.Matrix(1,1),alpha=False);pix.save(TMP/f'page-{i+1:02}.png')
    im=Image.open(TMP/f'page-{i+1:02}.png').convert('RGB');im.thumbnail((480,320));sheet.paste(im,((i%3)*480,(i//3)*350))
sheet.save(STAGE/'Woah-Lime-Beat-overview.png')
text_bounds=[]
for i,p in enumerate(doc):
    for block in p.get_text('dict')['blocks']:
        for row in block.get('lines',[]):
            for span in row['spans']:
                x0,y0,x1,y1=span['bbox']
                if x0 < -1 or y0 < -1 or x1 > W+1 or y1 > H+1:text_bounds.append({'page':i+1,'text':span['text'],'box':span['bbox']})
fulltext='\n'.join(p.get_text() for p in doc)
assert len(doc)==15 and not text_bounds, text_bounds
assert all(code not in fulltext for code in ('#78DCC7','#216A5E','#0B1415'))
doc.close()
checks={'pages':15,'allPagesRendered':True,'textBoundsIssues':text_bounds,'legacyBrandHexInManual':False,'fontSubsetEmbedded':True,'visualReview':'Inspect overview and detailed pages before delivery.'}
(ASSETS/'checks/document-review.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf8')
readme='''# Woah Lime Beat VI v2.2

2026-11-04。当前正式方向：青柠节拍。此包替代 Quiet Frame v1.1 的主视觉交付。

先阅读 Woah-Lime-Beat-VI-v2.2.pdf。亮色采用 Chalk 浅白底与 Lime 青柠主标识；Ink / Chalk 为辅助单色版本。首页字标为 24sp、W 宽 28dp，卡片渐变收为浅青柠 #F0F6DA。标识保留原 W 几何，没有旧薄荷主 Logo。

assets/logo：转轮廓 SVG；assets/icons：系统图标与源图层；assets/photography：新封面；assets/applications：真机首页与海报；assets/tokens：JSON/CSS；assets/fonts：授权字体与原许可证。

产品界面只显示 Woah，正式中文名为舞哇。人物标记遵循 segmentation mask；封面与品牌色不代表保护已完成。

首页左右滑卡、整卡点击、露边与分页不变。基线采用统一 Strut 行高。图标、启动页与应用均使用新色系。

重建脚本：source/build_vi.py。依赖 Python 3、reportlab、fonttools、Pillow、PyMuPDF。字体按原许可证随包保留；PDF 嵌入所需子集。仓库中从 docs/design/lime-beat/source/build_vi.py 重建，独立包也保留必需的图片、Token、字体和源代码。

iOS 尚未在本轮设备运行；真实视频保护 / 同步 / 导出以具体工作流检查为准。旧版设备检查不能作为本版签核。
'''
(STAGE/'README.md').write_text(readme,encoding='utf8')
(STAGE/'source').mkdir(exist_ok=True);shutil.copy2(__file__,STAGE/'source/build_vi.py')
manifest={p.relative_to(STAGE).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(STAGE.rglob('*')) if p.is_file() and p!=ASSETS/'checks/manifest.json'}
(ASSETS/'checks/manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
out=ROOT/'deliverables';out.mkdir(exist_ok=True)
for name in ('Woah-Lime-Beat-VI-v2.2.pdf','Woah-Lime-Beat-overview.png'):shutil.copy2(STAGE/name,out/name)
archive=out/'Woah-Lime-Beat-Assets-v2.2.zip'
pending=out/'Woah-Lime-Beat-Assets-v2.2.build.zip'
with zipfile.ZipFile(pending,'w',zipfile.ZIP_DEFLATED,compresslevel=8) as z:
    for p in sorted(STAGE.rglob('*')):
        if p.is_file():z.write(p,Path(STAGE.name)/p.relative_to(STAGE))
try:
    pending.replace(archive)
except OSError:
    archive=out/'Woah-Lime-Beat-Assets-v2.2-r1.zip'
    pending.replace(archive)
print(json.dumps({'pdf':str(out/PDF.name),'pages':15,'zip':str(archive),'files':len(manifest),'boundsIssues':text_bounds},ensure_ascii=False))
