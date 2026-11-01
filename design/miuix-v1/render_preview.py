#!/usr/bin/python3
"""Static design mockups only. All displayed numbers are labelled illustrative; no device I/O."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

ROOT=Path(__file__).parent
OUT=ROOT/'preview'
OUT.mkdir(exist_ok=True)
S=3; W=390; H=860
CJK=ROOT/'fonts/NotoSansCJK-Regular.ttc'
NUM=ROOT/'fonts/Roboto-Regular.ttf'
DARK=dict(bg='#101114',card='#1C1D21',sub='#26282E',text='#F4F5F8',muted='#A1A5B0',line='#30323B',blue='#79A8FF',green='#7CCDA9',teal='#65B9C5',purple='#BA9BE9',orange='#EDA66A',red='#F19786',pill='#24354F')
LIGHT=dict(bg='#F5F6F8',card='#FFFFFF',sub='#ECEFF4',text='#17181C',muted='#626876',line='#E4E7ED',blue='#2268DC',green='#177C58',teal='#117C8C',purple='#8661BB',orange='#B46925',red='#BE4D3B',pill='#E5EDFF')
font_cache={}
def font(size,num=False):
    key=(size,num)
    if key not in font_cache:font_cache[key]=ImageFont.truetype(str(NUM if num else CJK),round(size*S))
    return font_cache[key]

class Canvas:
    def __init__(self,theme):
        self.c=theme;self.im=Image.new('RGB',(W*S,H*S),theme['bg']);self.d=ImageDraw.Draw(self.im)
    def box(self,x,y,w,h,r=0,fill=None,outline=None,width=1):
        xy=(round(x*S),round(y*S),round((x+w)*S),round((y+h)*S))
        self.d.rounded_rectangle(xy,radius=round(r*S),fill=fill,outline=outline,width=round(width*S))
    def txt(self,x,y,text,size=14,color=None,bold=False,num=False,anchor='lt'):
        self.d.text((round(x*S),round(y*S)),text,font=font(size,num),fill=color or self.c['text'],anchor=anchor,stroke_width=1 if bold else 0)
    def line(self,points,color=None,width=1):
        self.d.line([(round(x*S),round(y*S)) for x,y in points],fill=color or self.c['line'],width=max(1,round(width*S)),joint='curve')
    def dot(self,x,y,r,color):self.d.ellipse(((x-r)*S,(y-r)*S,(x+r)*S,(y+r)*S),fill=color)
    def card(self,y,h):self.box(20,y,350,h,23,self.c['card'])
    def pill(self,x,y,w,text,color=None):
        self.box(x,y,w,25,12,self.c['pill']);self.txt(x+w/2,y+6,text,11,color or self.c['blue'],anchor='mt')
    def switch(self,x,y,on=True):
        self.box(x,y,46,27,14,self.c['blue'] if on else self.c['line'])
        self.dot(x+(33 if on else 13.5),y+13.5,10,'#FFFFFF')
    def fanicon(self,x,y,size=24,color=None):
        color=color or self.c['blue'];cx=x+size/2;cy=y+size/2
        for a in [0,120,240]:
            rr=math.radians(a);px=cx+math.cos(rr)*size*.28;py=cy+math.sin(rr)*size*.28
            self.dot(px,py,size*.18,color)
        self.dot(cx,cy,size*.11,self.c['card'])
    def statusbar(self):
        self.txt(25,16,'19:26',12,bold=True,num=True)
        self.line([(303,22),(306,18),(310,22),(314,18),(317,22)],self.c['text'],1.3)
        self.box(326,17,26,12,3,outline=self.c['muted']);self.box(329,20,18,6,1,self.c['text']);self.box(353,21,2,4,1,self.c['muted'])
    def header(self,title,subtitle):
        self.statusbar();self.txt(23,56,title,28,bold=True);self.txt(24,101,subtitle,12,self.c['muted'])
        self.box(324,57,42,42,21,self.c['card'])
        for dx in [0,5,10]:self.dot(339+dx,78,1.4,self.c['muted'])
    def nav(self,current):
        self.box(0,780,W,80,0,self.c['card']);self.line([(20,780),(370,780)],self.c['line'],.5)
        labels=['概览','趋势','设置']
        for i,label in enumerate(labels):
            x=65+i*130;color=self.c['blue'] if i==current else self.c['muted']
            if i==current:self.box(x-28,788,56,29,15,self.c['pill'])
            if i==0:
                self.box(x-9,797,7,7,2,outline=color,width=1.6);self.box(x+2,797,7,7,2,outline=color,width=1.6)
                self.box(x-9,807,7,5,2,outline=color,width=1.6);self.box(x+2,807,7,5,2,outline=color,width=1.6)
            elif i==1:self.line([(x-10,809),(x-4,802),(x+1,805),(x+10,796)],color,2)
            else:
                self.box(x-9,798,18,14,4,outline=color,width=1.5)
                self.line([(x-5,801),(x+5,801)],color,1.3);self.line([(x-5,806),(x+5,806)],color,1.3)
            self.txt(x,822,label,11,color,anchor='mt')
        self.box(145,851,100,4,2,self.c['muted'])
    def grid(self,x,y,w,h,lo,hi,step=3,integer=False):
        for i in range(step+1):
            yy=y+h*i/step;value=hi-(hi-lo)*i/step
            self.line([(x,yy),(x+w,yy)],self.c['line'],.65)
            self.txt(x-10,yy-5,f'{value:.0f}' if integer else f'{value:.0f}',10,self.c['muted'],num=True,anchor='rt')
    def series(self,x,y,w,h,values,lo,hi,color,width=1.7,dashed=False):
        pts=[]
        for i,v in enumerate(values):
            if v is None:
                if len(pts)>1:self.line(pts,color,width)
                pts=[];continue
            xx=x+w*i/(len(values)-1);yy=y+h*(hi-v)/(hi-lo)
            if dashed and i%3==0:
                if len(pts)>1:self.line(pts,color,width)
                pts=[]
            pts.append((xx,yy))
        if len(pts)>1:self.line(pts,color,width)
    def stepped(self,x,y,w,h,vals,color,dashed=False):
        pts=[]
        for i,v in enumerate(vals):
            xx=x+w*i/(len(vals)-1);yy=y+h*(20-v)/20
            if pts:pts.append((xx,pts[-1][1]))
            pts.append((xx,yy))
        if not dashed:self.line(pts,color,1.9)
        else:
            for a,b in zip(pts,pts[1:]):
                dist=math.dist(a,b);n=max(1,math.ceil(dist/5))
                for j in range(n):
                    if j%2:continue
                    p=(a[0]+(b[0]-a[0])*j/n,a[1]+(b[1]-a[1])*j/n)
                    q=(a[0]+(b[0]-a[0])*(j+1)/n,a[1]+(b[1]-a[1])*(j+1)/n)
                    self.line([p,q],color,1.9)
    def save(self,name):self.im.save(OUT/name,optimize=True)

# The deliberately illustrative temperature data only belongs to this static design file.
xs=list(range(51))
battery=[36.5+4.3*(1-math.exp(-i/11))+.11*math.sin(i/2.4) for i in xs]
board=[40+2.5*(1-math.exp(-i/9))+.25*math.sin(i/3) for i in xs]
fan=[35.1+3.2*(1-math.exp(-i/12))+.6*math.sin(i/4) for i in xs]
mos=[46+3.5*math.exp(-((i-9)/4)**2)+1.7*(1-math.exp(-i/22))+.3*math.sin(i/3) for i in xs]

def overview(theme):
    p=Canvas(theme);c=p.c;p.header('风扇助手','ACE7 Pro · 布局预览')
    p.pill(266,101,100,'● 已注入 · 示意')
    p.card(140,151)
    p.box(36,158,40,40,13,c['pill']);p.fanicon(45,167,22)
    p.txt(87,160,'息屏充电增强',17,bold=True);p.txt(87,185,'点亮锁屏，继续保持',11,c['muted']);p.switch(306,162)
    p.dot(41,229,3,c['green']);p.txt(51,222,'正在增强',13,c['green']);p.pill(259,217,93,'锁屏衔接')
    p.txt(37,259,'解锁后 2 秒交回原厂 · 后续保护保留',11.5,c['muted'])
    for x in [20,202]:p.box(x,307,168,113,22,c['card'])
    p.txt(37,325,'电池温度',12,c['muted']);p.txt(37,348,'40.8',34,num=True);p.txt(119,363,'°C',14,c['muted'])
    p.txt(37,396,'有线快充 · 充电中',10.5,c['muted'])
    p.txt(219,325,'驱动有效档位',12,c['muted']);p.txt(219,348,'18',34,num=True);p.txt(270,363,'/ 20',14,c['muted'],num=True)
    p.txt(219,396,'档位反馈，不推算转速',10.5,c['muted'])
    p.card(436,70)
    for i,(name,value) in enumerate([('板级','42.4°'),('风扇周边','38.6°'),('充电相关','47.8°')]):
        cx=78+i*117;p.txt(cx,449,name,11,c['muted'],anchor='mt');p.txt(cx,473,value,18,num=True,anchor='mt')
        if i<2:p.line([(136+i*117,452),(136+i*117,490)],c['line'],.7)
    p.card(522,191);p.txt(37,540,'电池温度趋势',16,bold=True);p.txt(351,542,'查看全部 ›',11,c['blue'],anchor='rt')
    p.grid(63,581,287,86,34,42,2)
    p.series(63,581,287,86,battery,34,42,c['blue'],2)
    for x,t in [(63,'18:55'),(207,'19:10'),(350,'19:25')]:p.txt(x,678,t,10,c['muted'],num=True,anchor='mt')
    p.txt(37,699,'最近 30 分钟 · 示意数据',10,c['muted'])
    p.dot(28,746,2.6,c['green']);p.txt(38,739,'数据状态与原因，清楚可见',11,c['muted']);p.txt(368,739,'诊断 ›',11,c['blue'],anchor='rt')
    p.nav(0);return p

def trends(theme):
    p=Canvas(theme);c=p.c;p.header('趋势','温度与控制档位 · 布局预览')
    p.box(20,133,350,42,14,c['sub']);p.box(137,137,112,34,11,c['card'])
    for x,t,active in [(78,'15 分钟',False),(193,'30 分钟',True),(308,'本次充电',False)]:p.txt(x,147,t,12,c['blue'] if active else c['muted'],anchor='mt')
    p.card(191,282);p.txt(37,210,'温度',17,bold=True);p.txt(351,214,'°C',12,c['muted'],anchor='rt')
    for i,(label,col) in enumerate([('电池',c['blue']),('板级',c['teal']),('风扇周边',c['purple']),('充电相关',c['orange'])]):
        x=40+(i%2)*168;y=246+(i//2)*23;p.dot(x,y+5,3,col);p.txt(x+11,y,label,11,c['muted'])
    p.grid(61,305,289,117,30,54,3)
    for vals,col in [(battery,c['blue']),(board,c['teal']),(fan,c['purple']),(mos,c['orange'])]:p.series(61,305,289,117,vals,30,54,col,1.7)
    for x,t in [(61,'18:55'),(206,'19:10'),(350,'19:25')]:p.txt(x,433,t,10,c['muted'],num=True,anchor='mt')
    p.txt(37,457,'缺测断线 · 不将未知值补成 0',10,c['muted'])
    p.card(490,248);p.txt(37,509,'控制档位',17,bold=True);p.txt(351,513,'诊断线 ›',11,c['blue'],anchor='rt')
    p.line([(40,550),(54,550)],c['blue'],1.8);p.txt(62,544,'模块需求',11,c['muted'])
    p.line([(196,550),(210,550)],c['green'],1.8);p.txt(218,544,'驱动有效',11,c['muted'])
    p.grid(61,580,289,97,0,20,2,True)
    p.stepped(61,580,289,97,[6,6,10,14,18,20,20,20,18,18,18],c['blue'],True)
    p.stepped(61,580,289,97,[6,6,10,14,18,20,20,18,18,18,18],c['green'])
    for x,t in [(61,'18:55'),(206,'19:10'),(350,'19:25')]:p.txt(x,688,t,10,c['muted'],num=True,anchor='mt')
    p.dot(40,723,2,c['blue']);p.txt(49,716,'点亮锁屏 · 保持衔接',10,c['muted']);p.txt(350,716,'示意事件',10,c['muted'],anchor='rt')
    p.txt(22,756,'只展示温度与档位，不读取 RPM',11,c['muted']);p.nav(1);return p

def settings(theme):
    p=Canvas(theme);c=p.c;p.header('设置','控制与外观 · 布局预览')
    def label(y,s):p.txt(25,y,s,11,c['muted'])
    def row(y,title,subtitle=None,right=None,switch=False,red=False):
        p.txt(38,y,title,14,c['red'] if red else c['text'])
        if subtitle:p.txt(38,y+24,subtitle,10.5,c['muted'])
        if switch:p.switch(307,y+2)
        elif right:p.txt(351,y+3,right,12,c['muted'],anchor='rt')
    label(137,'充电控制');p.card(158,191)
    row(178,'息屏充电增强','有线充电中，自动调速生效',switch=True);p.line([(38,231),(352,231)],c['line'],.65)
    row(247,'唤醒锁屏衔接','真正解锁后 2 秒交回原厂','已开启');p.line([(38,299),(352,299)],c['line'],.65)
    row(317,'温度—档位曲线',right='查看 ›')
    label(374,'外观');p.card(395,65);row(418,'主题模式',right='跟随系统 ›')
    label(486,'数据与诊断');p.card(507,191)
    row(529,'导出历史数据',right='CSV ›');p.line([(38,571),(352,571)],c['line'],.65)
    row(592,'模块诊断',right='查看 ›');p.line([(38,633),(352,633)],c['line'],.65)
    row(654,'清空历史',right='›',red=True)
    p.txt(25,723,'App 与运行 Hook 版本分开展示',11,c['muted'])
    p.txt(25,747,'设计稿 v1 · 尚未实施，不连接真实设备',10,c['muted'])
    p.nav(2);return p

screens=[overview(DARK),trends(DARK),settings(DARK)]
for obj,name in zip(screens,['01-概览-深色.png','02-趋势-深色.png','03-设置-深色.png']):obj.save(name)
overview(LIGHT).save('04-概览-浅色.png')
# A comparison board, with explicit prototype labels outside each phone.
BS=2;BW=1290;BH=1070
board=Image.new('RGB',(BW*BS,BH*BS),'#0B0C0F');d=ImageDraw.Draw(board)
def btxt(x,y,s,size,col='#F4F5F8'):
 f=ImageFont.truetype(str(CJK),round(size*BS));d.text((x*BS,y*BS),s,font=f,fill=col,anchor='lt')
btxt(30,27,'ACE7pro 风扇助手',29)
btxt(31,72,'Miuix 方向布局提案  /  先看设计，再实施',14,'#A1A5B0')
for i,(screen,label) in enumerate(zip(screens,['01  概览 · 状态优先','02  趋势 · 两张独立图表','03  设置 · 清晰分组'])):
 x=30+i*420;btxt(x,117,label,13,'#BAC4D4')
 im=screen.im.resize((390*BS,860*BS),Image.Resampling.LANCZOS)
 mask=Image.new('L',im.size,0);ImageDraw.Draw(mask).rounded_rectangle((0,0,im.width-1,im.height-1),radius=30*BS,fill=255)
 board.paste(im,(x*BS,154*BS),mask)
btxt(31,1033,'布局预览 · 所有读数为示意；已从设计中移除 RPM，保留温度与档位。',12,'#9299A6')
board.save(OUT/'布局总览-深色.png',optimize=True)
print('Rendered design-only previews:')
for f in sorted(OUT.glob('*.png')):print(f.name,Image.open(f).size,f.stat().st_size)
