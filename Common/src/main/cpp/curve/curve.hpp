/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:20:04 CET 2023                                                 */


#pragma once

#include "nanovg.h"
#include "settings/settings.hpp"
constexpr const NVGcolor nvgRGBAf2(const float r, const float g, const float b, const float a)
{
return  {{{r,g,b,a}}};
}

#define constcol static inline constexpr const
constcol NVGcolor darkgrayin= nvgRGBAf2(0,0,0,0.4);
constcol NVGcolor blue=nvgRGBAf2(0, 0,1,1);
constcol NVGcolor lightblue=nvgRGBAf2(0.2, 0.2,1,1);
constcol NVGcolor green=nvgRGBAf2(0,1,0,1);
constcol NVGcolor greenblue=nvgRGBAf2(0,1,1,1);
constcol NVGcolor pink= nvgRGBAf2(1.0,0,1,1.0);
constcol NVGcolor redinit= nvgRGBAf2(1.0,0,0,1.0);
#define red redinit


#define lowlinecolor red



constcol NVGcolor white=nvgRGBAf2(1.0,1.0,1.0,1.0);

constcol NVGcolor black={{{0,0,0,1.0}}};

constcol NVGcolor yellow=nvgRGBAf2(1,1,0,1);
#define mkcolor(r,g,b) {{{r/255.0f,g/255.0f,b/255.0f,1.0f}}}
 constcol  NVGcolor  blue1={{{0x66/255.0f,0x07/255.0f,0xf5/255.0f,1.0f}}};
constexpr const NVGcolor hexcolor(const uint32_t get) {
	return {{{(((get>>16)&0xFF)/255.0f),(0xFF&(get>>8))/255.0f,(get&0xFF)/255.0f,1}}};
	}
constexpr const NVGcolor hexcoloralpha(const uint32_t get) {
	return {{{(((get>>16)&0xFF)/255.0f),(0xFF&(get>>8))/255.0f,(get&0xFF)/255.0f,(get>>24)/255.0f}}};
	}

constcol NVGcolor  mediumseagreen= hexcolor(0x3CB371);
constcol NVGcolor orange=hexcolor(0xFFA500);
constcol NVGcolor brown=hexcolor(0xA52A2A);
constcol NVGcolor blackbean=hexcolor(0x3D0C02);
constexpr const int fromNVGcolor(const NVGcolor *col) {
	return (((int)(col->r*255))<<16)+((int)(col->g*255)<<8)+((int)(col->b*255))+(((int)(col->a*255))<<24);
	}
 constcol auto red1=hexcolor(0xfc1408);
 constcol auto lightblue2=hexcolor(0x08a3fc);
 constcol auto green1=hexcolor(0x08fce0);
 constcol auto green2=hexcolor(0x08fcf4);
 constcol auto green3=hexcolor(0x14fc08);
 constcol auto green4=hexcolor(0x0c9908);
 constcol auto paars=hexcolor(0xfc08dc);
 constcol auto kleur1=hexcolor(0x732a57);
 constcol auto kleur2=hexcolor(0xd6b085);
 constcol auto kleur3=hexcolor(0xe070a8);
 constcol auto kleur4=hexcolor(0x5f6c87);
 constcol auto green5=hexcolor(0x19429);
 constcol auto brown2=hexcolor(0x8a3119);
extern int lasttouchedcolor;
constexpr const int lightredoffset=startbackground-1;
constexpr const int grayoffset=startbackground-2;
constexpr const int dooryellowoffset=startbackground-3;
constexpr const int threehouroffset=startbackground-4;
constexpr const int darkgrayoffset=11;

inline constexpr const NVGcolor allcolors[]={green5, blue1,kleur4,green,kleur2,lightblue2,red1,kleur3,paars,green1,kleur4, darkgrayin ,green4, greenblue, blue,kleur1, green2, lightblue,green3,brown};
inline constexpr const int oldnrcolors=std::size(allcolors);
inline constexpr const int nrcolors=threehouroffset;
constexpr const auto darkmenu=hexcolor(0x07518e);
inline const NVGcolor *getmenuforegroundcolor() {
	return &white;
	}


constcol NVGcolor 	foregroundgray=  nvgRGBAf2(0,0,0,0.1);
constcol NVGcolor 	backgroundgray= {{{1.0f,1.0f,1.0f,.4f}}}; 

constcol NVGcolor backgrounddarkgray=   nvgRGBAf2(.8,.8,.8,.8);


constexpr const  NVGcolor invertcolor(const NVGcolor *colin)  {
   constexpr const auto invertcolor=[](float c) -> float {
	return ((uint8_t)roundf(c*255.0f)^0xFF)/255.0f;
	};
	NVGcolor coluit;
	for(int i=0;i<3;i++) 
		coluit.rgba[i]=invertcolor(colin->rgba[i]);
	coluit.a=colin->a;
	return coluit;
	}
constexpr const  NVGcolor invertcolor(const NVGcolor colin)  {
   constexpr const auto invertcolor=[](float c) -> float {
	return ((uint8_t)roundf(c*255.0f)^0xFF)/255.0f;
	};
	NVGcolor coluit;
	for(int i=0;i<3;i++)
		coluit.rgba[i]=invertcolor(colin.rgba[i]);
	coluit.a=colin.a;
	return coluit;
	}
 const auto yellowinvert=invertcolor(yellow);

 const auto pinkinvert=invertcolor(pink);
inline const NVGcolor getoldcolor() {
		return pink;
	}

constcol NVGcolor  foregroundthreehour=  nvgRGBAf2(1.0,0,1,0.5);
constcol NVGcolor  backgroundthreehour=  nvgRGBAf2(1.0,0,1,1);

#include "jugglucotext.hpp"
extern NVGcontext* genVG;
inline int mk12hourmin(int hour,int min,char *buf) {
      int hour12 = hour % 12;
      if(!hour12) hour12 = 12;
      return sprintf(buf,"%d:%02d",hour12,min);
      }
inline int mk12time(int hour,int min,char *buf) {
      const int len=mk12hourmin(hour,min,buf);
      if(hour>=12) {
         memcpy(buf+len,"pm",3);
         }
       else {
          memcpy(buf + len, "am", 3);
        }
       return len+2;
       }

inline int mk24time(int hour,int min,char *buf) {
	return sprintf(buf,"%02d:%02d", hour,min);
      }
inline bool hour24() {
   extern bool hour24clock;
   return hour24clock;
   }
inline int mktime(int hour,int min, char *buf) {
   if(hour24()) 
      return  mk24time(hour,min,buf);
   return  mk12time(hour,min,buf);
   }

inline int mkhourminstr(int hour,int min, char *buf) {
   if(hour24()) 
      return  mk24time(hour,min,buf);
   return  mk12hourmin(hour,min,buf);
   }




constcol NVGcolor foregroundlightred=  nvgRGBAf2(1, 0.95, 0.95, 1); 
//constcol NVGcolor foregroundlightred=  hexcolor(0xffe5e5); //a little darker
//constcol NVGcolor foregroundlightred=  hexcolor(0xffcccb); //much darker

//constcol NVGcolor redblack=hexcolor(0x3D2022);
//constcol NVGcolor redblack=hexcolor(0x4c1210);
constcol NVGcolor redblack=hexcolor(0x35100b);
//constcol NVGcolor redblack=hexcolor(0x200b06);
//constcol NVGcolor redblack=hexcolor(0x270f0c);
#ifdef WEAROS
constcol NVGcolor backgroundlightred=   redblack; 
#else
constcol NVGcolor backgroundlightred=    blackbean;
#endif

//#define lightred  *(settings->data()->colors+startincolors+lightredoffset)
#define unsavecolor (startincolors?backgroundlightred:foregroundlightred)

constcol NVGcolor dooryellow=nvgRGBAf2(0.9,0.9,0.1,0.3); 

// Phone presentation palette. Wear OS retains the compact legacy rendering;
// every phone-native canvas (graph, statistics and records) shares this
// stable surface and contrast system.
constcol NVGcolor modernGraphSurface=hexcolor(0x0C0F10);
constcol NVGcolor modernGraphText=hexcolor(0xE2E7E4);
constcol NVGcolor modernGraphMuted=nvgRGBAf2(0xAA/255.0f,0xB2/255.0f,0xAE/255.0f,0.92f);
constcol NVGcolor modernGraphGrid=nvgRGBAf2(0x76/255.0f,0x80/255.0f,0x7B/255.0f,0.14f);
constcol NVGcolor modernGraphGridStrong=nvgRGBAf2(0xA0/255.0f,0xA9/255.0f,0xA4/255.0f,0.32f);
// Clinical state palette: green is in range, amber is high and coral is low.
constcol NVGcolor modernGraphGlucose=hexcolor(0x4ECB83);
constcol NVGcolor modernGraphHigh=hexcolor(0xF2A93B);
constcol NVGcolor modernGraphLow=hexcolor(0xF06B65);
constcol NVGcolor modernGraphGlucoseGlow=nvgRGBAf2(0x4E/255.0f,0xCB/255.0f,0x83/255.0f,0.14f);
constcol NVGcolor modernGraphHighGlow=nvgRGBAf2(0xF2/255.0f,0xA9/255.0f,0x3B/255.0f,0.13f);
constcol NVGcolor modernGraphLowGlow=nvgRGBAf2(0xF0/255.0f,0x6B/255.0f,0x65/255.0f,0.14f);
constcol NVGcolor modernGraphGlucoseAreaTop=nvgRGBAf2(0x4E/255.0f,0xCB/255.0f,0x83/255.0f,0.075f);
constcol NVGcolor modernGraphGlucoseAreaBottom=nvgRGBAf2(0x4E/255.0f,0xCB/255.0f,0x83/255.0f,0.006f);
constcol NVGcolor modernGraphScan=hexcolor(0x70A8D8);
constcol NVGcolor modernGraphHistory=nvgRGBAf2(0x77/255.0f,0xA8/255.0f,0x92/255.0f,0.70f);
constcol NVGcolor modernGraphTargetFill=nvgRGBAf2(0x4E/255.0f,0xCB/255.0f,0x83/255.0f,0.082f);
constcol NVGcolor modernGraphTargetFillBottom=nvgRGBAf2(0x4E/255.0f,0xCB/255.0f,0x83/255.0f,0.036f);
constcol NVGcolor modernGraphTargetBorder=nvgRGBAf2(0x4E/255.0f,0xCB/255.0f,0x83/255.0f,0.32f);
constcol NVGcolor modernGraphNow=hexcolor(0xD7DBE0);
constcol NVGcolor modernGraphNowGlow=nvgRGBAf2(0xD7/255.0f,0xDB/255.0f,0xE0/255.0f,0.12f);
constcol NVGcolor modernGraphNowFade=nvgRGBAf2(0xD7/255.0f,0xDB/255.0f,0xE0/255.0f,0.01f);
constcol NVGcolor modernGraphAxisBackdrop=nvgRGBAf2(0x0C/255.0f,0x0F/255.0f,0x10/255.0f,0.92f);
constcol NVGcolor modernGraphCrosshair=nvgRGBAf2(0xB8/255.0f,0xC0/255.0f,0xC8/255.0f,0.48f);
constcol NVGcolor modernGraphTooltip=hexcolor(0x171C1A);
constcol NVGcolor modernGraphTooltipBorder=nvgRGBAf2(0xA8/255.0f,0xB0/255.0f,0xB8/255.0f,0.30f);
//extern int whitefont,blackfont;


union bounds_t{
    float array[4];
    struct {float xmin,ymin, xmax,ymax;};
    } ;

inline int mktmmin(const struct tm *tmptr) {
    return tmptr->tm_min;
    }
