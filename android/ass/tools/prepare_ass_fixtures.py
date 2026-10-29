#!/usr/bin/env python3
"""Self-made 24-second ASS/MKV fixtures. Requires ffmpeg, Pillow and fontTools; never downloads media."""
from pathlib import Path
import hashlib
import json
import subprocess
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / 'android/app/src/androidTest/assets/ass-extension'
DEST.mkdir(parents=True, exist_ok=True)
# Each block glyph is original fixture data, not derived from a third-party font.
letters = {
 'A':['01110','10001','10001','11111','10001','10001','10001'],
 'E':['11111','10000','10000','11110','10000','10000','11111'],
 'F':['11111','10000','10000','11110','10000','10000','10000'],
 'I':['11111','00100','00100','00100','00100','00100','11111'],
 'K':['10001','10010','10100','11000','10100','10010','10001'],
 'M':['10001','11011','10101','10101','10001','10001','10001'],
 'N':['10001','11001','10101','10011','10001','10001','10001'],
 'O':['01110','10001','10001','10001','10001','10001','01110'],
 'P':['11110','10001','10001','11110','10000','10000','10000'],
 'R':['11110','10001','10001','11110','10100','10010','10001'],
 'S':['01111','10000','10000','01110','00001','00001','11110'],
 'T':['11111','00100','00100','00100','00100','00100','00100'],
 'V':['10001','10001','10001','10001','10001','01010','00100'],
}
fb = FontBuilder(1000, isTTF=True)
cmap = {code: 'g'+str(code) for code in range(32,127)}
order = ['.notdef']+list(cmap.values())
fb.setupGlyphOrder(order)
glyphs = {}
for name in order:
 pen = TTGlyphPen(None)
 if name != '.notdef':
  char = chr(int(name[1:]))
  pattern = ['00000']*7 if char == ' ' else letters.get(char, letters['O'])
  for y,row in enumerate(pattern):
   for x,bit in enumerate(row):
    if bit == '1':
     x0=60+x*105; y0=100+(6-y)*105
     pen.moveTo((x0,y0)); pen.lineTo((x0+90,y0)); pen.lineTo((x0+90,y0+90)); pen.lineTo((x0,y0+90)); pen.closePath()
 glyphs[name] = pen.glyph()
fb.setupGlyf(glyphs)
fb.setupHorizontalMetrics({name:(640,0) for name in order})
fb.setupHorizontalHeader(ascent=900,descent=-100)
fb.setupCharacterMap(cmap)
fb.setupNameTable({'familyName':'Pathfin ASS Fixture','styleName':'Regular','uniqueFontIdentifier':'PathfinAssFixture-1','fullName':'Pathfin ASS Fixture Regular','psName':'PathfinAssFixture-Regular','version':'Version 1.0','copyright':'Original geometric Pathfin test glyphs. No third-party font outlines.'})
fb.setupOS2(sTypoAscender=900,sTypoDescender=-100,usWinAscent=900,usWinDescent=100)
fb.setupPost();fb.setupMaxp()
fb.font['head'].created=3660681600;fb.font['head'].modified=3660681600
font=DEST/'PathfinAssFixture.ttf';fb.save(font)
ass = r'''[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
ScaledBorderAndShadow: yes
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Pathfin ASS Fixture,30,&H0000FFFF,&H00FF0000,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,1,0,5,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:07.00,Default,,0,0,0,,{\move(80,190,560,190,0,6000)}MOVE
Dialogue: 0,0:00:02.00,0:00:22.00,Default,,0,0,0,,{\an8\pos(320,28)\c&HFFFF00&}TOP FONT
Dialogue: 0,0:00:02.00,0:00:10.00,Default,,0,0,0,,{\an2\pos(320,330)\k100}KA{\k100}RA{\k100}O{\k100}KE
Dialogue: 0,0:00:12.00,0:00:20.00,Default,,0,0,0,,{\move(560,260,80,260,0,8000)\c&H00FF00&}SEEK
'''
(DEST/'animated.ass').write_text(ass,encoding='utf-8')
# Full external ASS also exercises its actual inline [Fonts] memory provider.
# libass's ASS attachment encoding uses four 6-bit values, each stored plus 33.
raw = font.read_bytes(); encoded=[]
for i in range(0,len(raw),3):
 block=raw[i:i+3];bits=int.from_bytes(block.ljust(3,b'\0'),'big')
 chars=''.join(chr(((bits>>shift)&63)+33) for shift in [18,12,6,0])
 encoded.extend(chars[:len(block)+1])
fonts='\n[Fonts]\nfontname: PathfinAssFixture.ttf\n'+'\n'.join(''.join(encoded[i:i+80]) for i in range(0,len(encoded),80))+'\n'
# Font section precedes events, as specified by SSA.
(DEST/'animated-inline-font.ass').write_text(ass.replace('[Events]',fonts+'\n[Events]'),encoding='utf-8')
image=Image.new('RGB',(320,180),(24,31,40));draw=ImageDraw.Draw(image)
for y in range(0,180,8):
 for x in range(0,320,8):
  if ((x//8)+(y//8))%2:draw.rectangle((x,y,x+3,y+3),fill=(90,110,130))
draw.line((15,145,290,15),fill=(220,220,220),width=1)
draw.rectangle((20,20,75,80),outline=(170,130,90),width=1)
image.save(DEST/'pattern.png')
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-loop','1','-i',str(DEST/'pattern.png'),'-t','24','-r','12','-c:v','libx264','-pix_fmt','yuv420p','-preset','fast','-crf','18','-g','24','-keyint_min','24','-sc_threshold','0','-movflags','+faststart',str(DEST/'plain.mp4')],check=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(DEST/'plain.mp4'),'-i',str(DEST/'animated.ass'),'-map','0:v:0','-map','1:0','-c','copy','-attach',str(font),'-metadata:s:t','mimetype=application/x-truetype-font','-metadata:s:t','filename=PathfinAssFixture.ttf','-metadata:s:s:0','title=Pathfin ASS fixture','-metadata:s:s:0','language=eng','-disposition:s:0','default',str(DEST/'embedded-font.mkv')],check=True)
# 缺字回退：脚本字体不存在，两段汉字必须由系统中文字体画出不同字形，而不是两个相同的缺字方框。
CJK_FALLBACK = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Pathfin Missing Font,120,&H0000FFFF,&H0000FFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,中
Dialogue: 0,0:00:05.00,0:00:09.00,Default,,0,0,0,,国
"""
(DEST/'cjk-fallback.ass').write_text(CJK_FALLBACK,encoding='utf-8')
manifest={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(DEST.iterdir()) if p.suffix in ['.mkv','.mp4','.ass','.ttf','.png']}
(DEST/'manifest.json').write_text(json.dumps({'durationMs':24000,'firstAssStartMs':1000,'origin':'Original geometric test data; no third-party media or font outlines; this manifest does not select a project license.','files':manifest},indent=2)+'\n')
print(json.dumps(manifest,indent=2))
