
//#define DONTTALK WEAROS

#ifdef WEAROS
#define NOLEFT
#define NOCUTOFF 1
#endif

#ifndef NOLOG
//#define TEST 1
#endif
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



//#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <algorithm>
//#include <filesystem>
#include <math.h>
#include <cstdint>
#include <cinttypes>
#include <charconv>
#include <mutex>
#include <string>
#include <new>
using namespace std::literals;
//#include "glucose.hpp"
//ScanData   *glucosenow=nullptr;
#ifdef JUGGLUCO_APP
#define FATAL(...)  LOGAR(__VA_ARGS__)
#else
#define FATAL(...)  fprintf(stderr,__VA_ARGS__)
#endif
#define CURVELOGGER(...) LOGGER("curve: " __VA_ARGS__)
#define CURVELOGAR(...) LOGAR("curve: " __VA_ARGS__)


#include "curve.hpp"
#include "graphpoints.hpp"
#include "forecastgraph.hpp"
#include "intakemarkers.hpp"
#include "intakeevents.hpp"
#include "scriptFonts.hpp"
#include "config.h"
//#define FILEDIR "/sdcard/libre2/"
//#include "Glucograph.h"
#include "logs.hpp"
//#define CURVELOGGER(...)  __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#include "settings/settings.hpp"

#include "SensorGlucoseData.hpp"
#include "sensoren.hpp"
#include "nums/numdata.hpp"
#include "nfcdata.hpp"

#include "error_codes.h"
#include "jugglucotext.hpp"
#include "JCurve.hpp"
#include "misc.hpp"
#include "calibrate/Calibrator.hpp"
#include "calibrate/Calibrate.hpp"

namespace {
struct IntakeTimelineHit {
    std::vector<std::int32_t> keys;
    float left;
    float top;
    float right;
    float bottom;
};

std::mutex intakeTimelineMutex;
std::vector<IntakeTimelineEvent> intakeTimelineEvents;
std::vector<IntakeTimelineHit> intakeTimelineHits;
std::uint64_t intakeTimelineRevision=0U;

std::mutex forecastGraphMutex;
std::vector<forecastgraph::Point> forecastGraphPoints;
std::vector<forecastgraph::Activity> forecastActivities;
float forecastGraphConfidence=0.0f;
}

void replaceForecastGraph(std::vector<forecastgraph::Point> points,
                          const float confidence) {
    std::stable_sort(points.begin(),points.end(),[](const auto &left,
                                                    const auto &right) {
        return left.time<right.time;
    });
    std::vector<forecastgraph::Point> unique;
    unique.reserve(points.size());
    for(const auto &point:points) {
        if(!unique.empty()&&unique.back().time==point.time)
            unique.back()=point;
        else
            unique.push_back(point);
    }
    std::lock_guard<std::mutex> guard(forecastGraphMutex);
    forecastGraphPoints=std::move(unique);
    forecastGraphConfidence=forecastgraph::clamp01(confidence);
}

void replaceForecastActivities(
        std::vector<forecastgraph::Activity> activities) {
    for(auto &activity:activities) {
        if(activity.onset<activity.start||activity.onset>activity.peak)
            activity.onset=0U;
        if(activity.peakLow||activity.peakHigh) {
            const uint32_t actionStart=activity.onset?activity.onset:
                                                       activity.start;
            const uint32_t rawLow=activity.peakLow?activity.peakLow:
                                                    activity.peakHigh;
            const uint32_t rawHigh=activity.peakHigh?activity.peakHigh:
                                                      activity.peakLow;
            activity.peakLow=std::clamp(std::min(rawLow,rawHigh),
                                        actionStart,activity.end);
            activity.peakHigh=std::clamp(std::max(rawLow,rawHigh),
                                         activity.peakLow,activity.end);
            activity.peakLow=std::min(activity.peakLow,activity.peak);
            activity.peakHigh=std::max(activity.peakHigh,activity.peak);
        }
        if(activity.endLow||activity.endHigh) {
            const uint32_t rawLow=activity.endLow?activity.endLow:
                                                  activity.endHigh;
            const uint32_t rawHigh=activity.endHigh?activity.endHigh:
                                                    activity.endLow;
            const uint32_t latestPeak=activity.peakHigh?
                    activity.peakHigh:activity.peak;
            activity.endLow=std::max(latestPeak,
                                     std::min(rawLow,rawHigh));
            activity.endHigh=std::max(activity.endLow,
                                      std::max(rawLow,rawHigh));
            activity.endLow=std::min(activity.endLow,activity.end);
            activity.endHigh=std::max(activity.endHigh,activity.end);
        }
        activity.overlapCount=std::clamp(activity.overlapCount,0,999);
        if(!std::isfinite(activity.attributionConfidence))
            activity.attributionConfidence=-1.0f;
        else if(activity.attributionConfidence>=0.0f)
            activity.attributionConfidence=forecastgraph::clamp01(
                    activity.attributionConfidence);
        auto &samples=activity.samples;
        samples.erase(std::remove_if(samples.begin(),samples.end(),
                [](const forecastgraph::ActivitySample &sample) {
                    return !sample.time||!std::isfinite(sample.level);
                }),samples.end());
        std::stable_sort(samples.begin(),samples.end(),[](const auto &left,
                                                          const auto &right) {
            return left.time<right.time;
        });
        std::vector<forecastgraph::ActivitySample> unique;
        unique.reserve(samples.size());
        for(auto sample:samples) {
            sample.level=forecastgraph::clamp01(sample.level);
            if(!unique.empty()&&unique.back().time==sample.time)
                unique.back()=sample;
            else
                unique.push_back(sample);
        }
        samples=std::move(unique);
    }
    std::stable_sort(activities.begin(),activities.end(),
            [](const auto &left,const auto &right) {
                if(left.start!=right.start)
                    return left.start<right.start;
                if(left.end!=right.end)
                    return left.end<right.end;
                return left.identity<right.identity;
            });
    std::lock_guard<std::mutex> guard(forecastGraphMutex);
    forecastActivities=std::move(activities);
}

forecastgraph::Snapshot forecastGraphSnapshot() {
    std::lock_guard<std::mutex> guard(forecastGraphMutex);
    return {forecastGraphPoints,forecastActivities,forecastGraphConfidence};
}

std::uint32_t forecastGraphEndTime() {
    std::lock_guard<std::mutex> guard(forecastGraphMutex);
    return forecastGraphPoints.empty()?0U:forecastGraphPoints.back().time;
}

void replaceIntakeTimelineEvents(std::vector<IntakeTimelineEvent> events) {
    std::stable_sort(events.begin(),events.end(),[](const auto &left,
                                                    const auto &right) {
        if(left.time!=right.time)
            return left.time<right.time;
        return left.key<right.key;
    });
    std::lock_guard<std::mutex> guard(intakeTimelineMutex);
    intakeTimelineEvents=std::move(events);
    intakeTimelineHits.clear();
    ++intakeTimelineRevision;
}

int intakeTimelineEventAt(float x,float y) {
    std::lock_guard<std::mutex> guard(intakeTimelineMutex);
    for(auto it=intakeTimelineHits.rbegin();it!=intakeTimelineHits.rend();++it) {
        if(x>=it->left&&x<=it->right&&y>=it->top&&y<=it->bottom)
            return it->keys.empty()?0:it->keys.front();
    }
    return 0;
}

std::vector<std::int32_t> intakeTimelineEventsAt(float x,float y) {
    std::lock_guard<std::mutex> guard(intakeTimelineMutex);
    for(auto it=intakeTimelineHits.rbegin();it!=intakeTimelineHits.rend();++it) {
        if(x>=it->left&&x<=it->right&&y>=it->top&&y<=it->bottom)
            return it->keys;
    }
    return {};
}
static bool getLevelLeft() {
#ifdef NOLEFT
    return false;
#else
    return settings->data()->levelleft;
#endif
    }
#ifdef DONTTALK
extern const bool speakout;
const bool speakout=false;
#else
extern std::vector<shownglucose_t> shownglucose;
std::vector<shownglucose_t> shownglucose;
extern bool speakout;
bool speakout=false;
#endif
        extern bool hasnetwork();

extern int bluePermission();
extern bool bluetoothEnabled();
//extern NVGcolor invertcolor(const NVGcolor *colin) ;

    
void cpcolors(NVGcolor *foreground) {
    int wholes=nrcolors/oldnrcolors;
    for(int i=1;i<wholes;i++) 
        memcpy(foreground+i*oldnrcolors,foreground,oldnrcolors*sizeof(foreground[0]));
    if(int left=nrcolors%oldnrcolors) {
        memcpy(foreground+wholes*oldnrcolors,foreground,left*sizeof(foreground[0]));
        }
    }


void createcolors() {
    NVGcolor *foreground=settings->data()->colors;
    NVGcolor *background=settings->data()->colors+startbackground;
    if(!settings->data()->colorscreated) {
        memcpy(foreground,allcolors,sizeof(allcolors));
    //    foreground[darkgrayoffset]=  nvgRGBAf(0,0,0,0.4);
        foreground[dooryellowoffset]=  nvgRGBAf2(0.9,0.9,0.1,0.3); 
        foreground[lightredoffset]=  nvgRGBAf2(1, 0.95, 0.95, 1); 
        foreground[grayoffset]=  nvgRGBAf2(0,0,0,0.1);

//        for(int i=0;i<std::size(allcolors);i++)  
        for(int i=0;i<oldnrcolors;i++)  {
            background[i]=invertcolor(foreground+i);
            }
        background[darkgrayoffset]=   nvgRGBAf2(.8,.8,.8,.8);
        background[dooryellowoffset]=  nvgRGBAf2(0.9,0.9,0.1,0.3);
//        background[lightredoffset]=   nvgRGBA(65, 65, 65, 255); 
        background[grayoffset]= {{{1.0f,1.0f,1.0f,.4f}}}; 
//        background[redoffset]= nvgRGBAf2(1.0,0,0,1.0);
        }
    if(settings->data()->colorscreated<3) {
        foreground[threehouroffset]=  nvgRGBAf2(1.0,0,1,0.5);
        background[threehouroffset]=  nvgRGBAf2(1.0,0,1,1);
        }
    if(settings->data()->colorscreated<5) {
        cpcolors(foreground);
        cpcolors(background);
        }
    if(settings->data()->colorscreated<15) {
        background[lightredoffset]=   blackbean; 
        settings->data()->colorscreated=15;
        }
    }


#ifdef MENUARROWS
// pyftsubset <font-file> --unicodes=  --output-file=<path>
#include "fonts.h"
#endif
NVGcontext* genVG=nullptr;


extern bool fixatex,fixatey;
int showui=false;

static enum FontType {
    CJK,
    HEBREW,
    ARABIC,
    HINDI,
    REST
    } chfontset=REST;
//static bool chfontset=false;

bool hebrew() ;
#ifdef JUGGLUCO_APP
#define fontpath "/system/fonts/"
#else
#define fontpath "/home/jka/Android/Sdk/platforms/android-29/data/fonts/"
#endif
extern jugglucotext zhtext; 
extern jugglucotext jatext; 
extern jugglucotext artext; 


#ifdef JUGGLUCO_APP
static int getWhiteFont(NVGcontext* avg) {
    return nvgCreateFont(avg, "regular", 
#ifdef JUGGLUCO_APP
    fontpath "Roboto-Regular.ttf"
#else
"/usr/share/fonts/truetype/roboto-fontface/roboto/Roboto-Regular.ttf"
//"/usr/share/fonts/truetype/noto/NotoSerif-Regular.ttf"
//"/usr/local/Wolfram/Wolfram/14.2/SystemFiles/Fonts/TrueType/Roboto-Regular.ttf"
#endif
    );
    }
static int getMenuFont(NVGcontext* avg) {
        constexpr const char menufonts[][sizeof(fontpath "SourceSansPro-SemiBold.ttf")]={
        fontpath "Roboto-Medium.ttf",
        fontpath "SourceSansPro-SemiBold.ttf",
        fontpath "NotoSerif.ttf",
        fontpath "SourceSansPro-Regular.ttf",
        fontpath "Roboto-Regular.ttf",
        fontpath "DroidSans.ttf"
        };
            int onefont;
            for(const char *name:menufonts)  {
                if((onefont = nvgCreateFont(avg, "regular", name))!=-1) {
                    CURVELOGGER("menufont %s succeeded\n",name);
                    break;
                    }
                CURVELOGGER("menufont %s failed\n",name);
                }
        return onefont;
        }
#endif
static int getBlackFont(NVGcontext* avg) {
        int blackfont=-1;
        constexpr const char standardfonts[][sizeof(
        #ifdef JUGGLUCO_APP
        fontpath "SourceSansPro-SemiBold.ttf"
        #else
        "/usr/share/fonts/truetype/roboto-fontface/roboto/Roboto-Regular.ttf"

        #endif
        )]= {
        #ifndef JUGGLUCO_APP
        "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/truetype/roboto-fontface/roboto/Roboto-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSerif-Regular.ttf",
        #else
        fontpath "Roboto-Black.ttf",
        fontpath "SourceSansPro-Bold.ttf",
        fontpath "NotoSerif-Bold.ttf",
        fontpath "DroidSans-Bold.ttf",
        fontpath "SourceSansPro-SemiBold.ttf",
        fontpath "Roboto-Regular.ttf",
        #endif
        };


            for(const char *name:standardfonts)  {
                if((blackfont = nvgCreateFont(avg, "dance-bold", name))!=-1) {
                    CURVELOGGER("blackfont %s succeeded\n",name);
                    break;
                    }
                CURVELOGGER("blackfont %s failed\n",name);
                }
        if(blackfont==-1) {
            FATAL("all fonts failed: tried: ");
        #ifndef  JUGGLUCO_APP
            for(const char *name:standardfonts)  {
                    FATAL("%s\n",name);
                    }
        #endif
            }

        return blackfont;
        }

/*
static int getArabicRegular(NVGcontext* avg) {
        constexpr const char fonts[][sizeof(fontpath "NotoNaskhArabic-Regular.ttf")]
        {
        fontpath "NotoNaskhArabic-Regular.ttf",
        fontpath "NotoNaskh-Regular.ttf",
        fontpath  "DroidSansArabic.ttf"
        };
    for(const char *name:fonts)  {
        if(int font = nvgCreateFont(avg, "regular", name);font!=-1) 
                return font;
        }
    return  -1;
    }
static int getArabicBold(NVGcontext* avg) {
        constexpr const char fonts[][sizeof(fontpath "NotoNaskhArabic-Bold.ttf")]
        {
        fontpath "NotoNaskhArabic-Bold.ttf",
        fontpath "NotoNaskh-Bold.ttf",
        fontpath  "DroidSansArabic.ttf"
        };
    for(const char *name:fonts)  {
        if(int font = nvgCreateFont(avg, "dance-bold", name);font!=-1) 
                return font;
        }
    return  -1;
    }
    */

#ifdef JUGGLUCO_APP
static int getArabicMenu(NVGcontext* avg) {
        constexpr const char fonts[][sizeof(
#ifdef JUGGLUCO_APP
        fontpath "NotoNaskhArabicUI-Regular.ttf"
#else
        "/usr/share/fonts/truetype/noto/NotoNaskhArabicUI-Regular.ttf"
#endif
        )]
        {
#ifdef JUGGLUCO_APP
        fontpath "NotoNaskhArabicUI-Regular.ttf",
        fontpath "NotoNaskhUI-Regular.ttf",
        fontpath  "DroidSansArabic.ttf"
#else
"/usr/share/fonts/truetype/noto/NotoNaskhArabicUI-Regular.ttf"
#endif
        };
    for(const char *name:fonts)  {
        if(int font = nvgCreateFont(avg, "regular", name);font!=-1) 
                return font;
        }
    return  -1;
    }

#endif 

#ifdef JUGGLUCO_APP
static int getHindiMenu(NVGcontext* avg) {
        constexpr const char fonts[][sizeof(
#ifdef JUGGLUCO_APP
        fontpath "NotoSansDevanagariUI-Regular.ttf"
#else
        "/usr/share/fonts/truetype/noto/NotoSansDevanagariUI-Regular.ttf"
#endif
        )]
        {
        #ifdef JUGGLUCO_APP
        fontpath "NotoSansDevanagariUI-VF.ttf",
        fontpath "NotoSansDevanagariUI-Regular.ttf",
        fontpath   "NotoSansDevanagari-VF.ttf",
        fontpath "NotoSerifDevanagari-Regular.ttf",
        fontpath "NotoSerifDevanagari-VF.ttf",
        fontpath  "DroidSansDevanagari.ttf"
#else
        "/usr/share/fonts/truetype/noto/NotoSansDevanagariUI-Regular.ttf"
#endif
        };
    for(const char *name:fonts)  {
        if(int font = nvgCreateFont(avg, "regular", name);font!=-1) 
                return font;
        }
    return  -1;
    }

#endif 


bool usedScript[SCR_COUNT]{};

extern const jugglucotext hitext;

bool initScriptFonts(const FontUse &fontuse) {
   bool ret=false;
   auto doinit{[](const FontUse &fontuse,bool&ret){
            LOGAR("initScriptFonts");
            int len=settings->getlabelcount();
            for(int i=0;i<len;i++) {
                   useFontsForName(fontuse, settings->getlabel(i).data());
                   }
            ret=true;
            return true;
            }};
   const static bool init=doinit(fontuse,ret);;
   return ret;
   }
void    JCurve::initfont(NVGcontext* avg) { 
CURVELOGAR("initfont");
if(!avg) {
    CURVELOGAR("avg==null");
    return;
    }
thevg=avg;

    font=blackfont = getBlackFont(avg);
#ifdef JUGGLUCO_APP
    if((whitefont=getWhiteFont(avg))==-1) {
        CURVELOGAR("white font failed");
        whitefont=blackfont;
        }
#else
        whitefont=blackfont;
#endif
const FontUse fontuse{.vg=avg,.whitefont=whitefont,.blackfont=blackfont,.scriptEnabled=usedScript};
if(usedtext==&hitext) {
     enableScript(fontuse,SCR_DEVANAGARI);
#ifdef JUGGLUCO_APP
    menufont=nvgCreateFontMem(avg, "regular", (unsigned char *)fontfile, sizeof(fontfile), 0);
    int fallback2 =getHindiMenu(avg);

    nvgAddFallbackFontId(avg,menufont, getMenuFont(avg));
    nvgAddFallbackFontId(avg, menufont,fallback2);
#endif
     chfontset=HINDI;
      }
else
if(usedtext==&artext) {

//    nvgAddFallbackFontId(avg, blackfont,getArabicBold(avg)); 


 //   nvgAddFallbackFontId(avg, whitefont,getArabicRegular(avg));
     enableScript(fontuse, SCR_ARABIC);

#ifdef JUGGLUCO_APP
    menufont=nvgCreateFontMem(avg, "regular", (unsigned char *)fontfile, sizeof(fontfile), 0);
    int fallback2 =getArabicMenu(avg);
    nvgAddFallbackFontId(avg,menufont, getMenuFont(avg));
    nvgAddFallbackFontId(avg, menufont,fallback2);
#endif

   chfontset=ARABIC;


}
else
if(usedtext==&zhtext||usedtext==&jatext) {
     enableScript(fontuse, SCR_CJK);
#ifdef JUGGLUCO_APP
    if(-1==(menufont = nvgCreateFont(avg, "regular",

#ifdef JUGGLUCO_APP
    fontpath "NotoSerifCJK-Regular.ttc"
#else
"/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc"
#endif


    ))) {
      CURVELOGAR("menufont NotoSerifCJK-Regular failed");
       if(-1==(menufont = nvgCreateFont(avg, "regular",

#ifdef JUGGLUCO_APP
       fontpath "NotoSansCJK-Regular.ttc"
#else
"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
#endif
       ))) {
         CURVELOGAR("menufont NotoSansCJK-Regular failed");
         if(-1==(menufont = nvgCreateFont(avg, "regular",
#ifdef JUGGLUCO_APP
         fontpath "DroidSansFallback.ttf"
#else
        "/usr/share/fonts-droid-fallback/truetype/DroidSansFallback.ttf"
#endif
         )))  {
               CURVELOGAR("font DroidSansFallback failed");
               }
         }

      }
#endif
//TODO free font ???
    chfontset=CJK;
    }
else  {

#ifdef USE_HEBREW
     enableScript(fontuse, SCR_HEBREW);
#ifdef JUGGLUCO_APP
    menufont=nvgCreateFontMem(avg, "regular", (unsigned char *)fontfile, sizeof(fontfile), 0);
    int fallback2 = nvgCreateFont(avg, "regular", fontpath "NotoSerifHebrew-Regular.ttf");
    nvgAddFallbackFontId(avg,menufont, fallback);
    nvgAddFallbackFontId(avg, menufont,fallback2);

#endif

    chfontset=HEBREW;
}

else  
#endif
{
    chfontset=REST;

#ifdef JUGGLUCO_APP
int fallback=getMenuFont(avg);
#ifdef MENUARROWS
    menufont=nvgCreateFontMem(avg, "regular", (unsigned char *)fontfile, sizeof(fontfile), 0);
    nvgAddFallbackFontId(avg,menufont, fallback);
#endif
#endif //JUGGLUCO_APP

    if(invertcolors)
        font=whitefont;
    else
        font=blackfont;
        }
        }
    if(!initScriptFonts(fontuse)) {
        applyfonts(fontuse);
        }

    nvgFontFaceId(avg,font);

    nvgFontSize(avg, headsize);
    constexpr const char smaller[]="<";
    bounds_t bounds;
    nvgTextBounds(avg, 0,  0, smaller,smaller+sizeof(smaller)-1, bounds.array);
    smallerlen=bounds.xmax-bounds.xmin;

    nvgTextMetrics(avg, nullptr,nullptr, &headheight);
    headheight*=0.7;
    nvgFontSize(avg, smallsize);
    nvgTextMetrics(avg, nullptr,nullptr, &smallfontlineheight);
    constexpr const char timestring[]="29:59";
    nvgTextBounds(avg, 0,  0, timestring,timestring+sizeof(timestring)-1, bounds.array);
    timelen=bounds.xmax-bounds.xmin;
    timeheight=bounds.ymax-bounds.ymin;
   CURVELOGGER("timeheight=%f timelen=%f\n",timeheight,timelen);

    const char listitem[]="39-08-2028 09-59 RRRRRRRRRRR 999.9";     
    nvgTextBounds(avg, 0,  0, listitem,listitem+sizeof(listitem)-1, bounds.array);
    listitemlen=bounds.xmax-bounds.xmin+smallsize;

    constexpr const char exampl[]="0M0063KNUJ0";
    float xhalf=dwidth/2;
    float yhalf=dheight/2;
    nvgFontSize(avg, mediumfont);
    nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_TOP);
     nvgTextBounds(avg, xhalf,  yhalf,exampl, exampl+sizeof(exampl)-1,(float *)&sensorbounds);
     sensorbounds.right-=sensorbounds.left;
     sensorbounds.bottom-=sensorbounds.top;
     sensorbounds.left-=xhalf;
     sensorbounds.top-=yhalf;
     CURVELOGGER("sensorbounds.left=%.1f\n",sensorbounds.left);
    valuesize=sensorbounds.right*2;
#ifdef JUGGLUCO_APP
     fixatex=settings->data()->fixatex;
     fixatey=settings->data()->fixatey;
     if(fixatex)
         duration=settings->data()->duration;
#endif
    createcolors();
     }





//s/^\([^=]*\)=.*;/static float \1;/g

void JCurve::setfontsize(float small,float menu,float density,float headin) {

const float head=headin
#ifdef WEAROS
*0.7
#endif
;
CURVELOGGER("setfontsize density=%.1f, head=%.1f, small=%.1f menu=%.1f\n",(double)density,(double)head,(double)small,menu); 
smallsize=small;
menusize=menu;
this->density=density;
headsize=head;
midsize=head/3;
mediumfont= headsize/6;

timefontsize=smallsize;
historyStrokeWidth=3*density;
numcircleStrokeWidth=5/2*density;
lowGlucoseStrokeWidth=2.5*density;
pollCurveStrokeWidth=3*density;
hitStrokeWidth=10*density;
TrendStrokeWidth=15/2*density;
glucoseLinesStrokeWidth=1.5*density;
timeLinesStrokeWidth=glucoseLinesStrokeWidth;
dayEndStrokeWidth=2*density;
nowLineStrokeWidth=density*2;
pointRadius=4*density;
foundPointRadius=8*density;
arrowstrokewidth=5*density;
}
void calccurvegegs();

#include "searchgegs.hpp"
extern Searchgegs searchdata;




pair<const ScanData*,const ScanData*> getScanRange(const ScanData *scan,const int len,const uint32_t start,const uint32_t end) {
    ScanData scanst{.t=start};
    const ScanData *endscan= scan+len;
    auto comp=[](const ScanData &el,const ScanData &se ){return el.t<se.t;};
      const ScanData *low=lower_bound(scan,endscan, scanst,comp);
    if(low==endscan) {
        return {endscan,endscan};
        }
    scanst.t=end;
      const ScanData *high=lower_bound(low,endscan, scanst,comp);

    return {low,high};
    }

extern Sensoren *sensors;
/*
extern std::vector<pair<const ScanData*,const ScanData*>> getsensorranges(uint32_t start,uint32_t endt) ;

std::vector<pair<const ScanData*,const ScanData*>> getsensorranges(uint32_t start,uint32_t endt) {
    auto hists= sensors->sensorsInPeriod(start,endt) ;
    vector<pair<const ScanData*,const ScanData*>> polldata;
    polldata.reserve(hists.size());
    uint32_t timeiter=start;
    CURVELOGAR("start getsensorranges: ");
    for(int i=hists.size()-1;i>=0&&timeiter<endt;i--)  {
        auto his=sensors->getSensorData(hists[i]);
        CURVELOGGER("sensor %s\n",his->showsensorname().data());
        std::span<const ScanData>     poll=his->getPolldata();
#if !defined(NDEBUG)&&defined(JUGGLUCO_APP)
        auto wastimeiter=timeiter;
#endif
        auto ran=getScanRange(poll.data(),poll.size(),timeiter,endt);
        if(ran.first==ran.second)
            continue;

        for(const ScanData *striter=ran.second-1;striter>=ran.first;striter--) {
            if(striter->valid())    {
                timeiter=striter->t;
                ran.second=striter+1;
                break;
                }
            }

#if !defined(NDEBUG)&&defined(JUGGLUCO_APP)
        constexpr const int  maxbuf=150;
        char buf[maxbuf];
        int len=appcurve.datestr(wastimeiter,buf);

        const char tus1[]=" : ";
        constexpr const int tus1len=sizeof(tus1)-1;
        memcpy(buf+len,tus1,tus1len);

        len+=tus1len;
        len+=appcurve.datestr(poll.data()->t,buf+len);

        memcpy(buf+len,tus1,tus1len);
        len+=tus1len;
        len+=appcurve.datestr(ran.first->t,buf+len);
        const char tus[]=" - ";
        constexpr const int tuslen=sizeof(tus)-1;
        memcpy(buf+len,tus,tuslen);
        len+=tuslen;
        len+=appcurve.datestr((ran.second-1)->t,buf+len);
        buf[len++]='\n';
        logwriter(buf,len);
#endif
        polldata.push_back(ran);
        }

    CURVELOGAR("end getsensorranges: ");
    return polldata;
    }
    */

#include "GlucoseDataType.hpp" 
/*
std::vector<GlucoseDataType<const ScanData*>> getsensorranges(uint32_t start,uint32_t endt,bool calibrated,bool allvalues,bool calibratePast,std::vector<std::unique_ptr<ScanData []>> &calibrates ) {
    auto hists= sensors->sensorsInPeriod(start,endt) ;
    std::vector<GlucoseDataType<const ScanData*>>  polldata;
    polldata.reserve(hists.size());
    uint32_t timeiter=start;
    CURVELOGAR("start getsensorranges: ");
    typedef decltype(make_calibrator<ScanData>( sensors->getSensorData(0))) CaliType;
    auto califunc=calibratePast?(&CaliType::makecalibratedback):(&CaliType::makecalibrated);
    for(int i=hists.size()-1;i>=0&&timeiter<endt;i--)  {
        auto his=sensors->getSensorData(hists[i]);
        CURVELOGGER("sensor %s\n",his->showsensorname().data());
        std::span<const ScanData>     poll=his->getPolldata();
        auto ran=getScanRange(poll.data(),poll.size(),timeiter,endt);
        if(ran.first==ran.second)
            continue;
        for(const ScanData *striter=ran.second-1;striter>=ran.first;striter--) {
            if(striter->valid())    {
                timeiter=striter->t;
                ran.second=striter+1;
                break;
                }
            }
        if(calibrated) {
            int len=ran.second-ran.first;
            ScanData *calibuf=new ScanData[len];
            calibrates.emplace_back(calibuf);
            auto cali=make_calibrator<ScanData>(his);
            auto res=(cali.*califunc)(ran.first,calibuf,len,allvalues);

            if(res.first&&res.first!=res.second)
                polldata.push_back({res.first,res.second,his->getStreamIdDistance(),false,{his,calibratePast}});
            }
        else
            polldata.push_back({ran.first,ran.second,his->getStreamIdDistance(),false,{his,calibratePast} });
        }

    CURVELOGAR("end getsensorranges: ");
    return polldata;
    }
*/

template <typename IterType>
void getsensorranges(uint32_t start,uint32_t endt,bool calibrated,bool calibratePast,std::vector<GlucoseDataType<IterType>> *polldataptr);
template<> void getsensorranges<const ScanData*>(uint32_t start,uint32_t endt,bool calibrated,bool calibratePast,std::vector<GlucoseDataType<const ScanData*>> *polldataptr) {
    auto &polldata=*polldataptr;
    auto hists= sensors->sensorsInPeriod(start,endt) ;
    polldata.reserve(hists.size());
    uint32_t timeiter=start;
    CURVELOGAR("start getsensorranges: ");
    for(int i=hists.size()-1;i>=0&&timeiter<endt;i--)  {
        auto his=sensors->getSensorData(hists[i]);
        CURVELOGGER("sensor %s\n",his->showsensorname().data());
        std::span<const ScanData>     poll=his->getPolldata();
        auto ran=getScanRange(poll.data(),poll.size(),timeiter,endt);
        if(ran.first==ran.second)
            continue;
        for(const ScanData *striter=ran.second-1;striter>=ran.first;striter--) {
            if(striter->valid())    {
                timeiter=striter->t;
                ran.second=striter+1;
                break;
                }
            }
            polldata.push_back({ran.first,ran.second,his->getStreamIdDistance(),calibrated,{his,calibratePast} });
        }

    CURVELOGAR("end getsensorranges: ");
    }
pair<int32_t,int32_t> histPositions(const SensorGlucoseData  * hist, const uint32_t starttime, const uint32_t endtime) ;
template<> void getsensorranges<HistoryIterator>(uint32_t start,uint32_t endt,bool calibrated,bool calibratePast,std::vector<GlucoseDataType<HistoryIterator>> *historydataptr ) {
    auto &historydata=*historydataptr;
    auto hists= sensors->sensorsInPeriod(start,endt) ;
    historydata.reserve(hists.size());
    uint32_t timeiter=start;
    CURVELOGAR("start getsensorHistoryranges: ");
    for(int i=hists.size()-1;i>=0&&timeiter<endt;i--)  {
        auto his=sensors->getSensorData(hists[i]);
        if(!his->hasRealHistory())
            continue;
        auto ran= histPositions(his, timeiter,  endt); 
        CURVELOGGER("getsensorHistoryranges %s %d-%d\n",his->showsensorname().data(),ran.first,ran.second);
        if(ran.first==ran.second)
            continue;
        for( int striter=ran.second-1;striter>=ran.first;striter--) {
            Glucose *gl=his->getglucose(striter);
            if(gl->valid())    {
                timeiter=gl->gettime();
                ran.second=striter+1;
                break;
                }
            }
        historydata.push_back({{his,ran.first},{his,ran.second},his->getmininterval() ,calibrated,{his,calibratePast}});
        }

    CURVELOGAR("end getsensorHistoryranges");
    }


//static uint32_t pollgapdist=5*60;
static uint32_t pollgapdist=330;
pair<const ScanData*,const ScanData*> getScanRangeRuim(const ScanData *scan,const int len,const uint32_t start,const uint32_t end) {
    return getScanRange(scan,len,start-pollgapdist,end+pollgapdist);
   }    
/*
pair<const ScanData*,const ScanData*> getScanRangeRuim(const ScanData *scan,const int len,const uint32_t start,const uint32_t end) {
    auto [low,high]= getScanRange(scan,len,start,end);
    const ScanData *endscan= scan+len;
    if(low>scan&&(low->t-(low-1)->t)<=pollgapdist)
        low--;
    if(high<endscan&&((high+1)->t-high->t)<=pollgapdist)
        high++;
    return {low,high};
    } */

 void           JCurve::sidenum(NVGcontext* avg,const float posx,const float posy,const char *buf,const int len,const bool hit) {
        int align= NVG_ALIGN_MIDDLE;
        float valx=posx;
        const float afw=hit?1.14:0.64;;
         if((posx-dleft)>(dwidth/2)) {
            align|=NVG_ALIGN_RIGHT;
            valx-=smallsize*afw;
            }
        else {
            align|=NVG_ALIGN_LEFT;
            valx+=smallsize*afw;
            }
        nvgTextAlign(avg,align);
        nvgText(avg, valx,posy, buf, buf+len);
        }


void JCurve::drawforecastactivities(NVGcontext* avg,const uint32_t now,
                                    const uint32_t visibleStart,
                                    const uint32_t visibleEnd,
                                    const std::vector<forecastgraph::Activity>
                                            &activities) {
    if(!modernnormal||activities.empty()||visibleEnd<=visibleStart)
        return;

    const auto [transx,unusedTransy]=gettrans(visibleStart,visibleEnd);
    (void)unusedTransy;
    const float plotTop=dtop+density*3.0f;
    const float plotBottom=dtop+dheight-smallfontlineheight*1.60f;
    const float plotHeight=plotBottom-plotTop;
    if(!(plotHeight>density*8.0f))
        return;
    const auto alphaColor=[](NVGcolor color,const float alpha) {
        color.a=forecastgraph::clamp01(alpha);
        return color;
    };
    const std::array<float,3> curveHeightFractions{.31f,.38f,.25f};

    // Reuse the lowest free lane for non-overlapping records. Simultaneous
    // NovoRapid/Tresiba entries therefore remain individually traceable even
    // when their timestamps and central action curves are identical.
    std::vector<int> activityLanes(activities.size(),0);
    std::array<std::vector<uint32_t>,3> laneEnds;
    for(std::size_t index=0;index<activities.size();++index) {
        const auto &activity=activities[index];
        const auto rawKind=static_cast<std::int32_t>(activity.kind);
        if(!forecastgraph::valid_kind(rawKind))
            continue;
        auto &ends=laneEnds[rawKind-1];
        const uint32_t visualEnd=activity.endHigh?
                std::max(activity.end,activity.endHigh):activity.end;
        std::size_t lane=0U;
        while(lane<ends.size()&&ends[lane]>activity.start)
            ++lane;
        if(lane==ends.size())
            ends.push_back(visualEnd);
        else
            ends[lane]=visualEnd;
        activityLanes[index]=static_cast<int>(lane);
    }

    nvgSave(avg);
    nvgScissor(avg,dleft,plotTop,dwidth,plotHeight);
    nvgLineCap(avg,NVG_ROUND);
    nvgLineJoin(avg,NVG_ROUND);
    std::array<std::vector<std::pair<float,int>>,3> peakGlyphXs;
    for(std::size_t activityIndex=0;activityIndex<activities.size();
            ++activityIndex) {
        const auto &activity=activities[activityIndex];
        const auto rawKind=static_cast<std::int32_t>(activity.kind);
        const uint32_t visualEnd=activity.endHigh?
                std::max(activity.end,activity.endHigh):activity.end;
        if(!forecastgraph::valid_kind(rawKind)||
           visualEnd<=visibleStart||activity.start>=visibleEnd||
           activity.end<=activity.start||activity.end-activity.start<=1U)
            continue;
        const int kindIndex=rawKind-1;
        const int lane=activityLanes[activityIndex];
        const NVGcolor base=activity.kind==forecastgraph::ActivityKind::Meal?
                            hexcolor(0xF2A93B):
                            (activity.kind==forecastgraph::ActivityKind::RapidInsulin?
                             hexcolor(0x55C8F2):hexcolor(0xB69AF5));
        const float confidence=std::isfinite(activity.confidence)?
                forecastgraph::clamp01(activity.confidence):0.0f;
        const float strength=std::isfinite(activity.strength)?
                forecastgraph::visual_strength(activity.strength):0.0f;
        const float weight=(.42f+.58f*strength)*(.42f+.58f*confidence);
        const uint32_t peak=forecastgraph::activity_peak(
                activity.start,activity.peak,activity.end);
        const uint32_t clippedStart=std::max(activity.start,visibleStart);
        const uint32_t clippedEnd=std::min(activity.end,visibleEnd);
        if(clippedEnd<=clippedStart)
            continue;
        const float clippedLeft=transx(clippedStart);
        const float clippedRight=transx(clippedEnd);
        if(!(clippedRight>clippedLeft))
            continue;

        const auto drawRangeBand=[&](const uint32_t rawStart,
                                     const uint32_t rawEnd,
                                     const float alpha) {
            if(!rawStart&&!rawEnd)
                return;
            const uint32_t first=rawStart?rawStart:rawEnd;
            const uint32_t last=rawEnd?rawEnd:rawStart;
            const uint32_t rangeStart=std::max(visibleStart,
                                               std::min(first,last));
            const uint32_t rangeEnd=std::min(visibleEnd,
                                             std::max(first,last));
            if(rangeEnd<rangeStart)
                return;
            float left=transx(rangeStart),right=transx(rangeEnd);
            const float minimum=std::max(density*1.7f,1.2f);
            if(right-left<minimum) {
                const float center=(left+right)*.5f;
                left=center-minimum*.5f;
                right=center+minimum*.5f;
            }
            nvgBeginPath(avg);
            nvgRect(avg,left,plotTop,right-left,plotHeight);
            nvgFillColor(avg,alphaColor(base,alpha));
            nvgFill(avg);
        };
        drawRangeBand(activity.peakLow,activity.peakHigh,
                      .035f+.035f*weight);
        drawRangeBand(activity.endLow,activity.endHigh,
                      .016f+.018f*weight);

        // The per-event scissor is intentionally anchored to start time. It
        // also clips NanoVG antialiasing, so neither a wide glow nor a rounded
        // stroke can suggest insulin/meal activity to the left of injection.
        nvgSave(avg);
        nvgIntersectScissor(avg,clippedLeft,plotTop,
                            clippedRight-clippedLeft,plotHeight);

        std::vector<uint32_t> sampleTimes;
        constexpr uint32_t sampleSegments=32U;
        sampleTimes.reserve(sampleSegments+4U);
        const uint64_t span=static_cast<uint64_t>(clippedEnd)-clippedStart;
        for(uint32_t part=0U;part<=sampleSegments;++part) {
            sampleTimes.push_back(clippedStart+static_cast<uint32_t>(
                    span*part/sampleSegments));
        }
        if(peak>clippedStart&&peak<clippedEnd)
            sampleTimes.push_back(peak);
        if(now>clippedStart&&now<clippedEnd)
            sampleTimes.push_back(now);
        for(const auto &sample:activity.samples) {
            if(sample.time>=clippedStart&&sample.time<=clippedEnd)
                sampleTimes.push_back(sample.time);
        }
        std::sort(sampleTimes.begin(),sampleTimes.end());
        sampleTimes.erase(std::unique(sampleTimes.begin(),sampleTimes.end()),
                          sampleTimes.end());

        const float curveFloor=plotBottom-density*(
                5.0f+kindIndex*2.25f+std::min(lane,8)*3.25f);
        const float curveHeight=plotHeight*curveHeightFractions[kindIndex]*
                (.72f+.28f*strength);
        const float corePeakAlpha=.50f+.38f*weight;
        const auto curveY=[&](const float level) {
            return curveFloor-curveHeight*forecastgraph::clamp01(level);
        };

        for(std::size_t index=1;index<sampleTimes.size();++index) {
            const uint32_t leftTime=sampleTimes[index-1];
            const uint32_t rightTime=sampleTimes[index];
            if(rightTime<=leftTime)
                continue;
            const float leftLevel=forecastgraph::activity_level(activity,
                                                                 leftTime);
            const float rightLevel=forecastgraph::activity_level(activity,
                                                                  rightTime);
            if(leftLevel<=0.0f&&rightLevel<=0.0f)
                continue;
            const bool past=forecastgraph::historical_activity_segment(
                    rightTime,now);
            const float timeEmphasis=past?.28f:1.0f;
            const float x1=transx(leftTime),x2=transx(rightTime);
            const float y1=curveY(leftLevel),y2=curveY(rightLevel);
            // A soft halo plus crisp core remains readable when several
            // activity windows overlap at the same time.
            const NVGpaint halo=nvgLinearGradient(avg,x1,0,x2,0,
                    alphaColor(base,.10f*timeEmphasis*leftLevel),
                    alphaColor(base,.10f*timeEmphasis*rightLevel));
            nvgBeginPath(avg);
            nvgMoveTo(avg,x1,y1);
            nvgLineTo(avg,x2,y2);
            nvgStrokeWidth(avg,std::max(density*4.2f,2.4f));
            nvgStrokePaint(avg,halo);
            nvgStroke(avg);

            const NVGpaint core=nvgLinearGradient(avg,x1,0,x2,0,
                    alphaColor(base,corePeakAlpha*timeEmphasis*leftLevel),
                    alphaColor(base,corePeakAlpha*timeEmphasis*rightLevel));
            nvgBeginPath(avg);
            nvgMoveTo(avg,x1,y1);
            nvgLineTo(avg,x2,y2);
            nvgStrokeWidth(avg,std::max(density*1.15f,1.05f));
            nvgStrokePaint(avg,core);
            nvgStroke(avg);
        }

        if(activity.start>=visibleStart&&activity.start<visibleEnd) {
            const float startX=transx(activity.start);
            const float markerRadius=std::max(density*3.0f,2.2f);
            nvgBeginPath(avg);
            nvgMoveTo(avg,startX,curveFloor-markerRadius);
            nvgLineTo(avg,startX+markerRadius*1.75f,curveFloor);
            nvgLineTo(avg,startX,curveFloor+markerRadius);
            nvgClosePath(avg);
            nvgFillColor(avg,alphaColor(base,.64f+.28f*weight));
            nvgFill(avg);
            nvgBeginPath(avg);
            nvgMoveTo(avg,startX,plotTop);
            nvgLineTo(avg,startX,plotBottom);
            nvgStrokeWidth(avg,std::max(density*.65f,.7f));
            nvgStrokeColor(avg,alphaColor(base,.07f+.06f*weight));
            nvgStroke(avg);
        }

        if(activity.onset>activity.start&&activity.onset<=activity.peak&&
           activity.onset>=visibleStart&&activity.onset<visibleEnd) {
            const float onsetX=transx(activity.onset);
            nvgBeginPath(avg);
            nvgMoveTo(avg,onsetX,curveFloor-density*5.0f);
            nvgLineTo(avg,onsetX,curveFloor+density*1.0f);
            nvgStrokeWidth(avg,std::max(density*.9f,.8f));
            nvgStrokeColor(avg,alphaColor(base,.34f+.25f*weight));
            nvgStroke(avg);
        }

        if(peak>=visibleStart&&peak<=visibleEnd) {
            const float peakX=transx(peak);
            const float peakY=curveY(forecastgraph::activity_level(activity,
                                                                    peak));
            const float pastEmphasis=peak<now?.42f:1.0f;
            nvgBeginPath(avg);
            nvgMoveTo(avg,peakX,peakY+density*4.0f);
            nvgLineTo(avg,peakX,curveFloor);
            nvgStrokeWidth(avg,std::max(density*.65f,.7f));
            nvgStrokeColor(avg,alphaColor(base,
                    (.12f+.10f*weight)*pastEmphasis));
            nvgStroke(avg);
            nvgBeginPath(avg);
            nvgCircle(avg,peakX,peakY,std::max(density*3.1f,2.4f));
            nvgFillColor(avg,alphaColor(base,.14f*pastEmphasis));
            nvgFill(avg);
            nvgBeginPath(avg);
            nvgCircle(avg,peakX,peakY,std::max(density*1.55f,1.25f));
            nvgFillColor(avg,alphaColor(base,
                    (.68f+.25f*weight)*pastEmphasis));
            nvgFill(avg);

            auto &glyphs=peakGlyphXs[kindIndex];
            const bool hasSpace=std::none_of(glyphs.begin(),glyphs.end(),
                    [&](const auto &other) {
                        return other.second==lane&&
                               std::fabs(other.first-peakX)<density*15.0f;
                    });
            if(hasSpace) {
                glyphs.emplace_back(peakX,lane);
                if(activity.kind!=forecastgraph::ActivityKind::Meal) {
                    char glyph[12];
                    const char prefix=activity.kind==
                            forecastgraph::ActivityKind::RapidInsulin?'N':'T';
                    const int glyphLength=(activity.overlapCount>0||lane>0)?
                            std::snprintf(glyph,sizeof(glyph),"%c%d",prefix,
                                          lane+1):
                            std::snprintf(glyph,sizeof(glyph),"%c",prefix);
                    nvgFontSize(avg,std::max(smallsize*.52f,density*7.0f));
                    nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_BOTTOM);
                    nvgFillColor(avg,alphaColor(base,
                            (.54f+.32f*weight)*pastEmphasis));
                    nvgText(avg,peakX,peakY-density*4.2f,glyph,
                            glyph+std::max(0,glyphLength));
                }
            }
        }

        if(now>=clippedStart&&now<clippedEnd) {
            const float currentLevel=forecastgraph::activity_level(activity,
                                                                    now);
            if(currentLevel>0.0f) {
                const float currentX=transx(now);
                const float currentY=curveY(currentLevel);
                nvgBeginPath(avg);
                nvgCircle(avg,currentX,currentY,
                          std::max(density*2.35f,1.8f));
                nvgFillColor(avg,alphaColor(base,.20f+.18f*weight));
                nvgFill(avg);
                nvgBeginPath(avg);
                nvgCircle(avg,currentX,currentY,
                          std::max(density*.92f,.85f));
                nvgFillColor(avg,alphaColor(base,.86f));
                nvgFill(avg);
            }
        }
        nvgRestore(avg);
    }
    nvgRestore(avg);
}

void JCurve::drawforecast(NVGcontext* avg,const uint32_t now,
                          const uint32_t visibleStart,
                          const uint32_t visibleEnd,
                          const std::vector<forecastgraph::Point> &source,
                          const float confidenceIn,
                          const forecastgraph::Point *actualAnchor) {
    if(!modernnormal||source.empty()||visibleEnd<=visibleStart)
        return;
    const uint32_t clipStart=std::max(now,visibleStart);
    if(clipStart>=visibleEnd)
        return;

    std::vector<forecastgraph::Point> timeline=source;
    if(actualAnchor&&actualAnchor->time<=now&&
       now-actualAnchor->time<=15U*60U) {
        const auto position=std::lower_bound(timeline.begin(),timeline.end(),
                actualAnchor->time,
                [](const forecastgraph::Point &point,const uint32_t value) {
                    return point.time<value;
                });
        if(position!=timeline.end()&&position->time==actualAnchor->time)
            *position=*actualAnchor;
        else
            timeline.insert(position,*actualAnchor);
        }
    if(timeline.size()<2)
        return;

    const auto interpolate=[](const forecastgraph::Point &left,
                              const forecastgraph::Point &right,
                              const uint32_t time) {
        if(right.time<=left.time)
            return left;
        const float ratio=static_cast<float>(time-left.time)/
                          static_cast<float>(right.time-left.time);
        return forecastgraph::Point{
                time,
                left.medianMgDl+(right.medianMgDl-left.medianMgDl)*ratio,
                left.lowMgDl+(right.lowMgDl-left.lowMgDl)*ratio,
                left.highMgDl+(right.highMgDl-left.highMgDl)*ratio};
    };
    const auto sampleAt=[&](const uint32_t time,
                            forecastgraph::Point &result) {
        const auto after=std::lower_bound(timeline.begin(),timeline.end(),time,
                [](const forecastgraph::Point &point,const uint32_t value) {
                    return point.time<value;
                });
        if(after!=timeline.end()&&after->time==time) {
            result=*after;
            return true;
            }
        if(after==timeline.begin()||after==timeline.end())
            return false;
        result=interpolate(*std::prev(after),*after,time);
        return true;
    };

    std::vector<forecastgraph::Point> points;
    points.reserve(timeline.size()+2U);
    forecastgraph::Point boundary{};
    if(sampleAt(clipStart,boundary))
        points.push_back(boundary);
    for(const auto &point:timeline) {
        if(point.time>=clipStart&&point.time<=visibleEnd&&
           (points.empty()||points.back().time!=point.time))
            points.push_back(point);
        }
    if(sampleAt(visibleEnd,boundary)&&
       (points.empty()||points.back().time!=boundary.time))
        points.push_back(boundary);
    if(points.size()<2)
        return;

    const auto [transx,transy]=gettrans(visibleStart,visibleEnd);
    const auto yFor=[&](const float mgdl) {
        const float safe=std::clamp(mgdl,20.0f,600.0f);
        return static_cast<float>(transy(static_cast<uint32_t>(
                std::lround(safe*10.0f))));
    };
    const float targetLow=settings->targetlow()/10.0f;
    const float targetHigh=settings->targethigh()/10.0f;
    const auto stateColor=[&](const float mgdl) {
        return mgdl<targetLow?modernGraphLow:
               (mgdl>targetHigh?modernGraphHigh:modernGraphGlucose);
    };
    const auto alphaColor=[](NVGcolor color,const float alpha) {
        color.a=forecastgraph::clamp01(alpha);
        return color;
    };
    const float confidence=forecastgraph::clamp01(confidenceIn);
    const uint32_t firstTime=points.front().time;
    const uint32_t lastTime=points.back().time;
    const float horizon=std::max(1.0f,static_cast<float>(lastTime-firstTime));

    nvgSave(avg);
    nvgScissor(avg,dleft,dtop,dwidth,dheight-smallfontlineheight*1.60f);
    nvgLineCap(avg,NVG_ROUND);
    nvgLineJoin(avg,NVG_ROUND);

    // Join a recent measured endpoint to the exact Now boundary. This avoids
    // a misleading visual gap when backend horizons begin at +5 minutes, yet
    // keeps uncertainty shading strictly on the future side of Now.
    if(actualAnchor&&actualAnchor->time<clipStart&&
       actualAnchor->time>=visibleStart&&
       clipStart-actualAnchor->time<=15U*60U&&
       points.front().time==clipStart) {
        const NVGcolor connector=stateColor(
                (actualAnchor->medianMgDl+points.front().medianMgDl)*.5f);
        nvgBeginPath(avg);
        nvgMoveTo(avg,transx(actualAnchor->time),
                      yFor(actualAnchor->medianMgDl));
        nvgLineTo(avg,transx(points.front().time),
                      yFor(points.front().medianMgDl));
        nvgStrokeWidth(avg,std::max(density*1.35f,1.1f));
        nvgStrokeColor(avg,alphaColor(connector,.38f+.34f*confidence));
        nvgStroke(avg);
        }

    // Segment paints keep the uncertainty surface continuous while fading it
    // gently with horizon. The bound helpers prevent one uncertain outlier
    // from flattening both the forecast and the measured glucose trace.
    for(size_t index=1;index<points.size();++index) {
        const auto &left=points[index-1],&right=points[index];
        const float progressLeft=(left.time-firstTime)/horizon;
        const float progressRight=(right.time-firstTime)/horizon;
        const float alphaBase=.055f+.115f*confidence;
        const float alphaLeft=alphaBase*(1.0f-.48f*progressLeft);
        const float alphaRight=alphaBase*(1.0f-.48f*progressRight);
        const float middle=(left.medianMgDl+right.medianMgDl)*.5f;
        const NVGcolor base=stateColor(middle);
        const float xLeft=transx(left.time),xRight=transx(right.time);
        const float upperLeft=yFor(forecastgraph::bounded_high(left));
        const float upperRight=yFor(forecastgraph::bounded_high(right));
        const float lowerLeft=yFor(forecastgraph::bounded_low(left));
        const float lowerRight=yFor(forecastgraph::bounded_low(right));
        nvgBeginPath(avg);
        nvgMoveTo(avg,xLeft,upperLeft);
        nvgLineTo(avg,xRight,upperRight);
        nvgLineTo(avg,xRight,lowerRight);
        nvgLineTo(avg,xLeft,lowerLeft);
        nvgClosePath(avg);
        const NVGpaint band=nvgLinearGradient(avg,xLeft,0,xRight,0,
                alphaColor(base,alphaLeft),alphaColor(base,alphaRight));
        nvgFillPaint(avg,band);
        nvgFill(avg);
        }

    // A soft continuous trajectory supports shape perception; the crisp
    // dashed core makes future values unmistakable beside solid CGM history.
    for(size_t index=1;index<points.size();++index) {
        const auto &left=points[index-1],&right=points[index];
        const NVGcolor base=stateColor(
                (left.medianMgDl+right.medianMgDl)*.5f);
        nvgBeginPath(avg);
        nvgMoveTo(avg,transx(left.time),yFor(left.medianMgDl));
        nvgLineTo(avg,transx(right.time),yFor(right.medianMgDl));
        nvgStrokeWidth(avg,pollCurveStrokeWidth+density*2.6f);
        nvgStrokeColor(avg,alphaColor(base,.08f+.10f*confidence));
        nvgStroke(avg);
        }

    const float dashLength=std::max(density*5.0f,3.0f);
    const float gapLength=std::max(density*3.5f,2.0f);
    const float pattern=dashLength+gapLength;
    float travelled=0.0f;
    nvgStrokeWidth(avg,std::max(density*1.65f,1.35f));
    for(size_t index=1;index<points.size();++index) {
        const auto &left=points[index-1],&right=points[index];
        const float x1=transx(left.time),y1=yFor(left.medianMgDl);
        const float x2=transx(right.time),y2=yFor(right.medianMgDl);
        const float dx=x2-x1,dy=y2-y1;
        const float length=std::hypot(dx,dy);
        if(length<=0.01f)
            continue;
        const NVGcolor base=stateColor(
                (left.medianMgDl+right.medianMgDl)*.5f);
        nvgStrokeColor(avg,alphaColor(base,.58f+.34f*confidence));
        float position=0.0f;
        while(position<length) {
            const float inPattern=std::fmod(travelled+position,pattern);
            const bool dash=inPattern<dashLength;
            const float remaining=dash?(dashLength-inPattern):
                                           (pattern-inPattern);
            const float next=std::min(length,position+remaining);
            if(dash&&next>position) {
                nvgBeginPath(avg);
                nvgMoveTo(avg,x1+dx*(position/length),
                              y1+dy*(position/length));
                nvgLineTo(avg,x1+dx*(next/length),y1+dy*(next/length));
                nvgStroke(avg);
                }
            position=next;
            }
        travelled+=length;
        }

    const auto &last=points.back();
    const NVGcolor endpoint=stateColor(last.medianMgDl);
    nvgBeginPath(avg);
    nvgCircle(avg,transx(last.time),yFor(last.medianMgDl),
              std::max(density*2.2f,1.8f));
    nvgFillColor(avg,alphaColor(endpoint,.72f+.24f*confidence));
    nvgFill(avg);
    nvgRestore(avg);
}


void JCurve::drawintakeevents(NVGcontext* avg,uint32_t visibleStart,
                              uint32_t visibleEnd,
                              const std::vector<intakemarkers::GlucosePoint>
                                      &glucosePoints) {
    std::vector<IntakeTimelineEvent> events;
    std::uint64_t renderRevision=0U;
    {
        std::lock_guard<std::mutex> guard(intakeTimelineMutex);
        events=intakeTimelineEvents;
        renderRevision=intakeTimelineRevision;
    }
    std::vector<IntakeTimelineHit> hits;
    std::vector<IntakeTimelineHit> broadHits;
    if(!modernnormal||events.empty()||visibleEnd<=visibleStart) {
        std::lock_guard<std::mutex> guard(intakeTimelineMutex);
        if(renderRevision==intakeTimelineRevision)
            intakeTimelineHits.clear();
        return;
    }

    const NVGcolor mealFill=hexcolor(0x2A2114);
    const NVGcolor mealBorder=hexcolor(0xF2A93B);
    const NVGcolor mealText=hexcolor(0xFFD28C);
    const NVGcolor rapidFill=hexcolor(0x122A34);
    const NVGcolor rapidBorder=hexcolor(0x55C8F2);
    const NVGcolor rapidText=hexcolor(0xC9F1FF);
    const NVGcolor longFill=hexcolor(0x251D35);
    const NVGcolor longBorder=hexcolor(0xB69AF5);
    const NVGcolor longText=hexcolor(0xE8DCFF);
    const NVGcolor otherInsulinFill=hexcolor(0x1A222A);
    const NVGcolor otherInsulinBorder=hexcolor(0x91A5B8);
    const NVGcolor otherInsulinText=hexcolor(0xD8E2EB);
    const float chipHeight=std::max(density*21.0f,smallfontlineheight*.80f);
    const float chipGap=density*3.0f;
    const float horizontalPadding=density*7.0f;
    const float chipTapPadding=density*5.0f;
    const float markerTapRadius=density*21.0f;
    const float textSize=std::max(density*9.2f,smallsize*.58f);
    const float plotTop=dtop+density*5.0f;
    const float plotBottom=dtop+dheight-smallfontlineheight*1.62f;

    nvgSave(avg);
    nvgScissor(avg,dleft,dtop,dwidth,dheight);
    nvgFontFaceId(avg,font);
    nvgFontSize(avg,textSize);
    nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_MIDDLE);

    const auto amountText=[](char *buffer,size_t size,float value,
                             const char *suffix) {
        const float rounded=roundf(value);
        if(fabsf(value-rounded)<.05f) {
            return snprintf(buffer,size,"%.0f%s",rounded,suffix);
        }
        return snprintf(buffer,size,"%.1f%s",value,suffix);
    };

    struct Marker {
        IntakeTimelineEvent event;
        bool hasMeal;
        bool hasInsulin;
        IntakeInsulinKind insulinKind;
        bool glucoseAnchored;
        float anchorX;
        float anchorY;
        float visualWidth;
        std::string mealText;
        std::string insulinText;
    };
    std::vector<Marker> markers;
    markers.reserve(events.size());

    for(const auto &event:events) {
        if(event.time<visibleStart||event.time>visibleEnd)
            continue;
        const bool hasMeal=(event.flags&IntakeTimelineMeal)!=0U;
        const bool hasCarbs=hasMeal
                &&(event.flags&IntakeTimelineCarbsPresent)!=0U
                &&std::isfinite(event.carbs);
        const bool hasInsulin=std::isfinite(event.insulin)&&event.insulin>0.0f;
        const IntakeInsulinKind insulinKind=intakeInsulinKind(
                event.flags,event.insulin);
        if(!hasMeal&&!hasInsulin)
            continue;

        const float timeRatio=static_cast<float>(event.time-visibleStart)/
                static_cast<float>(visibleEnd-visibleStart);
        const float anchorX=dleft+dwidth*timeRatio;
        char mealBuffer[24],insulinBuffer[24];
        int mealLength=0,insulinLength=0;
        float visualWidth=0.0f;
        float bounds[4];
        if(hasMeal) {
            if(hasCarbs) {
                mealLength=amountText(mealBuffer,sizeof(mealBuffer),event.carbs,"g");
            }
            else {
#ifdef WEAROS
                constexpr std::string_view mealLabel="Meal";
#else
                const std::string_view mealLabel=usedtext->menustr2[5];
#endif
                mealLength=snprintf(mealBuffer,sizeof(mealBuffer),"%.*s",
                        static_cast<int>(mealLabel.size()),mealLabel.data());
            }
            nvgTextBounds(avg,0,0,mealBuffer,mealBuffer+mealLength,bounds);
            visualWidth=std::max(density*40.0f,
                    bounds[2]-bounds[0]+horizontalPadding*2.0f+
                            density*5.0f);
        }
        if(hasInsulin) {
            char amountBuffer[24];
            const int amountLength=amountText(amountBuffer,
                    sizeof(amountBuffer),event.insulin,"U");
            const char *prefix=insulinKind==IntakeInsulinKind::Rapid?"N":
                               insulinKind==IntakeInsulinKind::Long?"T":"I";
            insulinLength=snprintf(insulinBuffer,sizeof(insulinBuffer),
                    "%s  %.*s",prefix,amountLength,amountBuffer);
            nvgTextBounds(avg,0,0,insulinBuffer,
                          insulinBuffer+insulinLength,bounds);
            visualWidth=std::max(visualWidth,std::max(density*40.0f,
                    bounds[2]-bounds[0]+horizontalPadding*2.0f+
                            density*5.0f));
        }
        float anchorY=(plotTop+plotBottom)*.5f;
        const bool glucoseAnchored=intakemarkers::anchor_y(
                glucosePoints,event.time,anchorY);
        anchorY=std::clamp(anchorY,plotTop,plotBottom);
        markers.push_back({event,hasMeal,hasInsulin,insulinKind,
                           glucoseAnchored,anchorX,anchorY,visualWidth,
                           std::string(mealBuffer,mealLength),
                           std::string(insulinBuffer,insulinLength)});
    }

    struct MarkerCluster {
        std::vector<std::size_t> indices;
    };
    std::vector<MarkerCluster> clusters;
    // 42dp matches the combined forgiving touch radii of two source markers;
    // keeping them separate below that distance would create ambiguous taps.
    // The independent 68dp span limit keeps a dense chain from swallowing a
    // visually separate marker at its far edge.
    const float minimumClusterDistance=std::max(42.0f,density*42.0f);
    const float clusterMaximumSpan=std::max(68.0f,density*68.0f);
    for(std::size_t index=0;index<markers.size();++index) {
        if(clusters.empty()) {
            clusters.push_back({{index}});
            continue;
        }
        const Marker &first=markers[clusters.back().indices.front()];
        const Marker &previous=markers[clusters.back().indices.back()];
        const Marker &current=markers[index];
        const float visualCollisionDistance=(previous.visualWidth+
                current.visualWidth)*.5f+density*4.0f;
        const float adjacentDistance=std::min(clusterMaximumSpan,
                std::max(minimumClusterDistance,visualCollisionDistance));
        if(intakemarkers::joins_cluster(first.anchorX,previous.anchorX,
                current.anchorX,adjacentDistance,
                clusterMaximumSpan)) {
            clusters.back().indices.push_back(index);
        }
        else {
            clusters.push_back({{index}});
        }
    }

    enum class ClusterRowKind : std::uint8_t {
        Meal,
        Rapid,
        Long,
        OtherInsulin
    };
    struct ClusterRow {
        ClusterRowKind kind;
        std::string text;
        float width;
        float top;
    };
    for(const MarkerCluster &cluster:clusters) {
        if(cluster.indices.size()>1U) {
            std::vector<std::int32_t> keys;
            keys.reserve(cluster.indices.size());
            float centerX=0.0f,centerY=0.0f;
            bool hasClusterMeal=false,hasClusterRapid=false;
            bool hasClusterLong=false,hasClusterOther=false;
            for(const std::size_t index:cluster.indices) {
                const Marker &marker=markers[index];
                keys.push_back(marker.event.key);
                centerX+=marker.anchorX;
                centerY+=marker.anchorY;
                hasClusterMeal=hasClusterMeal||marker.hasMeal;
                hasClusterRapid=hasClusterRapid||
                        marker.insulinKind==IntakeInsulinKind::Rapid;
                hasClusterLong=hasClusterLong||
                        marker.insulinKind==IntakeInsulinKind::Long;
                hasClusterOther=hasClusterOther||
                        marker.insulinKind==IntakeInsulinKind::Other;
            }
            centerX/=static_cast<float>(cluster.indices.size());
            centerY/=static_cast<float>(cluster.indices.size());

            char countBuffer[32];
            const int countLength=snprintf(countBuffer,sizeof(countBuffer),
                    "%zu\xC3\x97",cluster.indices.size());
            float countBounds[4];
            nvgTextBounds(avg,0,0,countBuffer,countBuffer+countLength,
                          countBounds);
            int kindCount=static_cast<int>(hasClusterMeal)+
                          static_cast<int>(hasClusterRapid)+
                          static_cast<int>(hasClusterLong)+
                          static_cast<int>(hasClusterOther);
            const float dotDiameter=density*3.8f;
            const float dotsWidth=kindCount>0
                    ?kindCount*dotDiameter+(kindCount-1)*density*2.0f:0.0f;
            const float badgeWidth=std::max(density*48.0f,
                    countBounds[2]-countBounds[0]+dotsWidth+
                    horizontalPadding*2.0f+density*5.0f);
            const float badgeLeft=std::clamp(centerX-badgeWidth*.5f,
                    dleft+density*2.0f,
                    dleft+dwidth-badgeWidth-density*2.0f);
            const float connectorGap=density*8.0f;
            const bool above=centerY-plotTop>=chipHeight+connectorGap||
                    centerY-plotTop>=plotBottom-centerY;
            float badgeTop=above?centerY-chipHeight-connectorGap:
                                 centerY+connectorGap;
            badgeTop=std::clamp(badgeTop,plotTop,
                                std::max(plotTop,plotBottom-chipHeight));
            const float badgeCenterX=badgeLeft+badgeWidth*.5f;
            const float connectorY=badgeTop>centerY?badgeTop:
                                   badgeTop+chipHeight;

            nvgBeginPath(avg);
            nvgMoveTo(avg,centerX,centerY);
            nvgLineTo(avg,badgeCenterX,connectorY);
            nvgStrokeWidth(avg,std::max(1.0f,density*.72f));
            nvgStrokeColor(avg,modernGraphGridStrong);
            nvgStroke(avg);

            // One stacked anchor signals that the badge opens a list. Source
            // event keys remain attached to the hit target, not to tiny and
            // ambiguous sub-regions of the badge.
            const float anchorRadius=density*3.0f;
            nvgBeginPath(avg);
            nvgCircle(avg,centerX-density*1.5f,centerY-density*1.0f,
                      anchorRadius);
            nvgFillColor(avg,modernGraphSurface);
            nvgFill(avg);
            nvgStrokeColor(avg,modernGraphGridStrong);
            nvgStroke(avg);
            nvgBeginPath(avg);
            nvgCircle(avg,centerX+density*1.5f,centerY+density*1.0f,
                      anchorRadius);
            nvgFillColor(avg,modernGraphSurface);
            nvgFill(avg);
            nvgStrokeColor(avg,hasClusterMeal?mealBorder:
                    hasClusterRapid?rapidBorder:
                    hasClusterLong?longBorder:otherInsulinBorder);
            nvgStroke(avg);

            nvgBeginPath(avg);
            nvgRoundedRect(avg,badgeLeft,badgeTop,badgeWidth,chipHeight,
                           chipHeight*.48f);
            nvgFillColor(avg,hexcolor(0x172028));
            nvgFill(avg);
            nvgStrokeWidth(avg,std::max(1.0f,density*.78f));
            nvgStrokeColor(avg,modernGraphGridStrong);
            nvgStroke(avg);

            float dotX=badgeLeft+horizontalPadding+dotDiameter*.5f;
            const float dotY=badgeTop+chipHeight*.5f;
            const auto drawKindDot=[&](const bool shown,
                                       const NVGcolor color) {
                if(!shown)
                    return;
                nvgBeginPath(avg);
                nvgCircle(avg,dotX,dotY,dotDiameter*.5f);
                nvgFillColor(avg,color);
                nvgFill(avg);
                dotX+=dotDiameter+density*2.0f;
            };
            drawKindDot(hasClusterMeal,mealBorder);
            drawKindDot(hasClusterRapid,rapidBorder);
            drawKindDot(hasClusterLong,longBorder);
            drawKindDot(hasClusterOther,otherInsulinBorder);
            const float textLeft=badgeLeft+horizontalPadding+dotsWidth+
                    (dotsWidth>0.0f?density*5.0f:0.0f);
            nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
            nvgFillColor(avg,modernGraphText);
            nvgText(avg,textLeft,badgeTop+chipHeight*.52f,
                    countBuffer,countBuffer+countLength);
            nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_MIDDLE);

            const float markerRadius=markerTapRadius+density*3.0f;
            broadHits.push_back({keys,
                    std::max(dleft,centerX-markerRadius),
                    std::max(plotTop,centerY-markerRadius),
                    std::min(dleft+dwidth,centerX+markerRadius),
                    std::min(plotBottom,centerY+markerRadius)});
            hits.push_back({keys,
                    std::max(dleft,badgeLeft-chipTapPadding),
                    std::max(plotTop,badgeTop-chipTapPadding),
                    std::min(dleft+dwidth,
                             badgeLeft+badgeWidth+chipTapPadding),
                    std::min(plotBottom,
                             badgeTop+chipHeight+chipTapPadding)});
            continue;
        }

        // Multi-event clusters returned above, so the established one-record
        // marker rendering below stays visually and behaviorally unchanged.
        const Marker &single=markers[cluster.indices.front()];
        std::vector<ClusterRow> rows;
        const float centerX=single.anchorX;
        const float minAnchorY=single.anchorY;
        const float maxAnchorY=single.anchorY;

        const auto addRow=[&](const ClusterRowKind kind,
                              const std::string &text) {
            float bounds[4];
            nvgTextBounds(avg,0,0,text.data(),text.data()+text.size(),bounds);
            ClusterRow row{kind,text,
                    std::max(density*40.0f,
                             bounds[2]-bounds[0]+horizontalPadding*2.0f+
                                     density*5.0f),
                    0.0f};
            rows.push_back(std::move(row));
        };
        if(single.hasMeal)
            addRow(ClusterRowKind::Meal,single.mealText);
        if(single.hasInsulin) {
            const ClusterRowKind kind=single.insulinKind==
                    IntakeInsulinKind::Rapid?ClusterRowKind::Rapid:
                    single.insulinKind==IntakeInsulinKind::Long?
                            ClusterRowKind::Long:
                            ClusterRowKind::OtherInsulin;
            addRow(kind,single.insulinText);
        }
        if(rows.empty())
            continue;

        float groupWidth=0.0f;
        for(const ClusterRow &row:rows)
            groupWidth=std::max(groupWidth,row.width);
        const float groupHeight=rows.size()*chipHeight+
                (rows.size()-1)*chipGap;
        float groupLeft=std::clamp(centerX-groupWidth*.5f,
                dleft+density*2.0f,
                dleft+dwidth-groupWidth-density*2.0f);
        const float connectorGap=density*8.0f;
        const float spaceAbove=minAnchorY-plotTop;
        const float spaceBelow=plotBottom-maxAnchorY;
        const bool above=spaceAbove>=groupHeight+connectorGap||
                spaceAbove>=spaceBelow;
        float groupTop=above?minAnchorY-groupHeight-connectorGap:
                             maxAnchorY+connectorGap;
        groupTop=std::clamp(groupTop,plotTop,
                            std::max(plotTop,plotBottom-groupHeight));

        // Every source event keeps its own CGM anchor. Dense events converge
        // into one compact summary without moving their measured time/value.
        nvgStrokeWidth(avg,std::max(1.0f,density*.72f));
        nvgStrokeColor(avg,modernGraphGridStrong);
        const auto drawCircleSymbol=[&](const float x,const float y,
                                        const NVGcolor color,
                                        const bool anchored) {
            nvgBeginPath(avg);
            nvgCircle(avg,x,y,density*2.55f);
            if(anchored) {
                nvgFillColor(avg,modernGraphSurface);
                nvgFill(avg);
            }
            nvgStrokeWidth(avg,std::max(1.0f,density*.9f));
            nvgStrokeColor(avg,color);
            nvgStroke(avg);
            if(anchored) {
                nvgBeginPath(avg);
                nvgCircle(avg,x,y,density*.92f);
                nvgFillColor(avg,color);
                nvgFill(avg);
            }
        };
        const auto drawDiamondSymbol=[&](const float x,const float y,
                                         const NVGcolor color,
                                         const bool anchored) {
            const float radius=density*3.05f;
            nvgBeginPath(avg);
            nvgMoveTo(avg,x,y-radius);
            nvgLineTo(avg,x+radius,y);
            nvgLineTo(avg,x,y+radius);
            nvgLineTo(avg,x-radius,y);
            nvgClosePath(avg);
            if(anchored) {
                nvgFillColor(avg,modernGraphSurface);
                nvgFill(avg);
            }
            nvgStrokeWidth(avg,std::max(1.0f,density*.9f));
            nvgStrokeColor(avg,color);
            nvgStroke(avg);
            if(anchored) {
                nvgBeginPath(avg);
                nvgCircle(avg,x,y,density*.82f);
                nvgFillColor(avg,color);
                nvgFill(avg);
            }
        };
        for(const std::size_t index:cluster.indices) {
            const Marker &marker=markers[index];
            const float attachX=std::clamp(marker.anchorX,
                    groupLeft+chipHeight*.35f,
                    groupLeft+groupWidth-chipHeight*.35f);
            const float attachY=groupTop>marker.anchorY?groupTop:
                                groupTop+groupHeight;
            nvgBeginPath(avg);
            nvgMoveTo(avg,marker.anchorX,marker.anchorY);
            nvgLineTo(avg,attachX,attachY);
            nvgStroke(avg);

            const float symbolOffset=marker.hasMeal&&marker.hasInsulin
                    ?density*2.9f:0.0f;
            if(marker.hasMeal) {
                drawCircleSymbol(marker.anchorX-symbolOffset,marker.anchorY,
                                 mealBorder,marker.glucoseAnchored);
            }
            if(marker.hasInsulin) {
                const float insulinX=marker.anchorX+symbolOffset;
                if(marker.insulinKind==IntakeInsulinKind::Long) {
                    // Tresiba uses a diamond at the measured glucose point.
                    drawDiamondSymbol(insulinX,marker.anchorY,longBorder,
                                      marker.glucoseAnchored);
                }
                else {
                    // NovoRapid stays circular; colour and shape therefore
                    // distinguish both insulin kinds even in dense clusters.
                    const NVGcolor color=marker.insulinKind==
                            IntakeInsulinKind::Rapid?rapidBorder:
                            otherInsulinBorder;
                    drawCircleSymbol(insulinX,marker.anchorY,color,
                                      marker.glucoseAnchored);
                }
            }
            // The visible anchor is deliberately compact, but its touch area
            // is forgiving. This affects single taps only; drag/pinch gesture
            // handling remains owned by the Java graph surface.
            broadHits.push_back({{marker.event.key},
                    std::max(dleft,marker.anchorX-markerTapRadius-symbolOffset),
                    std::max(plotTop,marker.anchorY-markerTapRadius),
                    std::min(dleft+dwidth,
                             marker.anchorX+markerTapRadius+symbolOffset),
                    std::min(plotBottom,marker.anchorY+markerTapRadius)});
        }

        float rowTop=groupTop;
        for(ClusterRow &row:rows) {
            row.top=rowTop;
            const float rowLeft=std::clamp(centerX-row.width*.5f,
                    dleft+density*2.0f,
                    dleft+dwidth-row.width-density*2.0f);
            NVGcolor fill=otherInsulinFill;
            NVGcolor border=otherInsulinBorder;
            NVGcolor textColor=otherInsulinText;
            float cornerRadius=chipHeight*.32f;
            switch(row.kind) {
                case ClusterRowKind::Meal:
                    fill=mealFill;
                    border=mealBorder;
                    textColor=mealText;
                    cornerRadius=chipHeight*.48f;
                    break;
                case ClusterRowKind::Rapid:
                    fill=rapidFill;
                    border=rapidBorder;
                    textColor=rapidText;
                    cornerRadius=chipHeight*.48f;
                    break;
                case ClusterRowKind::Long:
                    fill=longFill;
                    border=longBorder;
                    textColor=longText;
                    cornerRadius=chipHeight*.18f;
                    break;
                case ClusterRowKind::OtherInsulin:
                    break;
            }
            nvgBeginPath(avg);
            nvgRoundedRect(avg,rowLeft,rowTop,row.width,chipHeight,
                           cornerRadius);
            nvgFillColor(avg,fill);
            nvgFill(avg);
            nvgStrokeWidth(avg,std::max(1.0f,density*.70f));
            nvgStrokeColor(avg,border);
            nvgStroke(avg);
            if(row.kind==ClusterRowKind::Rapid) {
                nvgBeginPath(avg);
                nvgCircle(avg,rowLeft+density*7.0f,
                          rowTop+chipHeight*.5f,density*1.7f);
                nvgFillColor(avg,rapidBorder);
                nvgFill(avg);
            }
            else if(row.kind==ClusterRowKind::Long) {
                const float iconX=rowLeft+density*7.0f;
                const float iconY=rowTop+chipHeight*.5f;
                const float iconRadius=density*2.2f;
                nvgBeginPath(avg);
                nvgMoveTo(avg,iconX,iconY-iconRadius);
                nvgLineTo(avg,iconX+iconRadius,iconY);
                nvgLineTo(avg,iconX,iconY+iconRadius);
                nvgLineTo(avg,iconX-iconRadius,iconY);
                nvgClosePath(avg);
                nvgFillColor(avg,longBorder);
                nvgFill(avg);
            }
            nvgFillColor(avg,textColor);
            nvgText(avg,rowLeft+row.width*.5f+density*1.5f,
                    rowTop+chipHeight*.52f,
                    row.text.data(),row.text.data()+row.text.size());

            rowTop+=chipHeight+chipGap;
        }

        const auto &event=single.event;
        // A single marker owns only its compact chip group; its connector
        // never steals taps from a nearby meal or insulin value.
        hits.push_back({{event.key},groupLeft,groupTop,
                        groupLeft+groupWidth,groupTop+groupHeight});
        broadHits.push_back({{event.key},
                std::max(dleft,groupLeft-chipTapPadding),
                std::max(plotTop,groupTop-chipTapPadding),
                std::min(dleft+dwidth,
                         groupLeft+groupWidth+chipTapPadding),
                std::min(plotBottom,
                         groupTop+groupHeight+chipTapPadding)});
    }
    nvgRestore(avg);
    {
        std::lock_guard<std::mutex> guard(intakeTimelineMutex);
        // Broad accessibility targets come first; reverse hit-testing then
        // gives exact chip cells deterministic priority when targets overlap.
        broadHits.insert(broadHits.end(),hits.begin(),hits.end());
        if(renderRevision==intakeTimelineRevision)
            intakeTimelineHits=std::move(broadHits);
    }
}

int shownlabels;

float tapx=-700,tapy;
bool selshown=false;

#include "numdisplayfuncs.hpp"
extern vector<NumDisplay*> numdatas;






 bool    JCurve::glucosepointinfo(NVGcontext* avg,time_t tim,uint32_t value,   float posx, float posy) {
    if((!selshown&&nearby(posx-tapx,posy-tapy,density))) {
        constexpr int maxbuf=60;
        char buf[maxbuf];
        struct tm tmbuf;
         struct tm *tms=localtime_r(&tim,&tmbuf);

//        int len=snprintf(buf,maxbuf,"%02d:%02d", tms->tm_hour,mktmmin(tms));
      int len=mktime(tms->tm_hour,mktmmin(tms),buf);
        char *buf2=buf+len;
        *buf2++='\n';
        const int valuelen=snprintf(buf2,maxbuf-len-1,gformat, ::gconvert(value,glunit));

        if(modernnormal) {
            graphselectionactive=true;
            graphselectiontime=static_cast<uint32_t>(tim);
            graphselectionvalue=value;
            }
        else {
            nvgFontSize(avg, smallsize);
            nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_MIDDLE);
            const float cor=((posy-dtop)<(dheight/2))?smallsize:-smallsize;
            nvgText(avg, posx,posy+cor*.92, buf, buf+len);
            sidenum(avg,posx,posy,buf2,valuelen,false);
            }
        
    //    nvgText(avg, posx,posy+cor*.92*2, buf, buf+len);
#ifndef DONTTALK
        if(speakout) {
            speak(buf);
            }
#endif

        selshown=true;
        CURVELOGGER("glucosepointinfo %s %ud %f\n",buf,tim,posx);
        return true;
        }
    return false;
    }

void JCurve::drawgraphselection(NVGcontext* avg,float posx,float posy) {
    if(!modernnormal||!graphselectionactive)
        return;

    const float right=dleft+dwidth;
    const float bottom=dtop+dheight-smallfontlineheight*1.45f;
    if(posx<dleft||posx>right||posy<dtop||posy>bottom)
        return;

    const float selectedvalue=static_cast<float>(graphselectionvalue);
    const float targetlow=settings->targetlow();
    const float targethigh=settings->targethigh();
    const NVGcolor &selectioncolor=selectedvalue<targetlow?modernGraphLow:
                                   (selectedvalue>targethigh?modernGraphHigh:
                                                                    modernGraphGlucose);
    const NVGcolor &selectionglow=selectedvalue<targetlow?modernGraphLowGlow:
                                  (selectedvalue>targethigh?modernGraphHighGlow:
                                                                   modernGraphGlucoseGlow);

    nvgSave(avg);
    nvgScissor(avg,dleft,dtop,dwidth,dheight);
    nvgLineCap(avg,NVG_ROUND);

    nvgStrokeWidth(avg,std::max(density*.65f,.75f));
    nvgStrokeColor(avg,modernGraphCrosshair);
    nvgBeginPath(avg);
    nvgMoveTo(avg,posx,dtop);
    nvgLineTo(avg,posx,bottom);
    nvgMoveTo(avg,dleft,posy);
    nvgLineTo(avg,right,posy);
    nvgStroke(avg);

    nvgBeginPath(avg);
    nvgCircle(avg,posx,posy,pointRadius*2.35f);
    nvgFillColor(avg,selectionglow);
    nvgFill(avg);
    nvgBeginPath(avg);
    nvgCircle(avg,posx,posy,pointRadius*1.25f);
    nvgFillColor(avg,modernGraphSurface);
    nvgFill(avg);
    nvgStrokeWidth(avg,std::max(density*1.35f,1.0f));
    nvgStrokeColor(avg,selectioncolor);
    nvgStroke(avg);
    nvgBeginPath(avg);
    nvgCircle(avg,posx,posy,pointRadius*.56f);
    nvgFillColor(avg,selectioncolor);
    nvgFill(avg);
    nvgRestore(avg);

    constexpr int textcapacity=48;
    char valuestr[textcapacity];
    char numberstr[24];
    const int numberlen=snprintf(numberstr,sizeof(numberstr),gformat,
                                 ::gconvert(graphselectionvalue,glunit));
    const char *unit=glunit==1?"mmol/L":"mg/dL";
    const int valuelen=snprintf(valuestr,sizeof(valuestr),"%.*s  %s",
                                numberlen,numberstr,unit);

    char timestr[24];
    time_t selectedtime=graphselectiontime;
    struct tm tmbuf;
    struct tm *tms=localtime_r(&selectedtime,&tmbuf);
    const int timelen=tms?mktime(tms->tm_hour,mktmmin(tms),timestr):0;

    nvgSave(avg);
    nvgFontFaceId(avg,font);
    nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
    nvgFontSize(avg,smallsize*1.05f);
    float valuebounds[4];
    nvgTextBounds(avg,0,0,valuestr,valuestr+valuelen,valuebounds);
    nvgFontSize(avg,smallsize*.88f);
    float timebounds[4];
    nvgTextBounds(avg,0,0,timestr,timestr+timelen,timebounds);

    const float padding=density*11.0f;
    const float contentwidth=std::max(valuebounds[2]-valuebounds[0],
                                      timebounds[2]-timebounds[0]);
    const float cardwidth=std::max(density*108.0f,contentwidth+padding*2.0f);
    const float cardheight=std::max(density*58.0f,smallsize*2.65f);
    const float margin=density*6.0f;
    const float markerGap=pointRadius*2.8f;
    float cardx=posx-cardwidth*.5f;
    cardx=std::max(dleft+margin,std::min(cardx,right-cardwidth-margin));
    float cardy=posy-cardheight-markerGap;
    if(cardy<dtop+margin)
        cardy=posy+markerGap;
    cardy=std::max(dtop+margin,std::min(cardy,bottom-cardheight-margin));

    const float corner=density*12.0f;
    NVGpaint shadow=nvgBoxGradient(avg,cardx,cardy+density*3.0f,cardwidth,
                                   cardheight,corner,density*12.0f,
                                   nvgRGBA(0,0,0,105),nvgRGBA(0,0,0,0));
    nvgBeginPath(avg);
    nvgRoundedRect(avg,cardx-density*7.0f,cardy-density*7.0f,
                   cardwidth+density*14.0f,cardheight+density*17.0f,
                   corner+density*7.0f);
    nvgFillPaint(avg,shadow);
    nvgFill(avg);

    nvgBeginPath(avg);
    nvgRoundedRect(avg,cardx,cardy,cardwidth,cardheight,corner);
    nvgFillColor(avg,modernGraphTooltip);
    nvgFill(avg);
    nvgStrokeWidth(avg,std::max(density*.8f,.75f));
    nvgStrokeColor(avg,selectioncolor);
    nvgStroke(avg);

    const float textx=cardx+padding;
    nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
    nvgFontSize(avg,smallsize*1.05f);
    nvgFillColor(avg,nvgRGBA(241,245,249,255));
    nvgText(avg,textx,cardy+cardheight*.37f,valuestr,valuestr+valuelen);
    nvgFontSize(avg,smallsize*.88f);
    nvgFillColor(avg,modernGraphText);
    nvgText(avg,textx,cardy+cardheight*.72f,timestr,timestr+timelen);
    nvgRestore(avg);
    }
 bool    JCurve::glucosepoint(NVGcontext* avg,time_t tim,uint32_t value,   float posx, float posy) {
    nvgCircle(avg, posx,posy,pointRadius);
    return glucosepointinfo(avg,tim,value,posx,posy);
    }



void endstep(NVGcontext* avg) ;

static const NVGcolor &modernGraphStateColor(const graphpoints::RangeState state,
                                             const bool glow=false) {
    switch(state) {
        case graphpoints::RangeState::low:
            return glow?modernGraphLowGlow:modernGraphLow;
        case graphpoints::RangeState::high:
            return glow?modernGraphHighGlow:modernGraphHigh;
        case graphpoints::RangeState::in_range:
        default:
            return glow?modernGraphGlucoseGlow:modernGraphGlucose;
        }
}

static void drawModernGraphSample(NVGcontext* avg,const float x,const float y,
                                  const float radius,
                                  const graphpoints::RangeState state) {
    // A slim surface ring separates a sample from the line while the larger
    // inner core keeps its range state readable without adding another glow.
    nvgBeginPath(avg);
    nvgCircle(avg,x,y,radius);
    nvgFillColor(avg,modernGraphSurface);
    nvgFill(avg);
    nvgBeginPath(avg);
    nvgCircle(avg,x,y,radius*.68f);
    nvgFillColor(avg,modernGraphStateColor(state));
    nvgFill(avg);
}

template <class TX,class TY>   void  JCurve::showScan(NVGcontext* avg,const ScanData *low,const ScanData *high,  const TX &transx,  const TY &transy,const int colorindex) {

    if(modernnormal) {
        const float targetlow=settings->targetlow();
        const float targethigh=settings->targethigh();
        const float radius=graphpoints::sample_radius(density,duration,true);
        const float minspacing=graphpoints::minimum_spacing(density,duration);
        const ScanData *lastvalid=nullptr;
        for(const ScanData *it=high;it!=low;) {
            --it;
            if(it->valid()) {
                lastvalid=it;
                break;
                }
            }
        nvgSave(avg);
        nvgScissor(avg,dleft,dtop,dwidth,dheight);
#ifdef DOESSEARCH
        const bool search=scansearchtype==(scansearchtype&searchdata.type);
#endif
        bool first=true,hasdrawn=false,haspreviousstate=false;
        float lastdrawx=0.0f;
        graphpoints::RangeState previousstate=graphpoints::RangeState::in_range;
        for(const ScanData *it=low;it!=high;it++) {
            if(!it->valid())
                continue;
            const uint32_t tim=it->t;
            const uint32_t glu=it->g*10;
            const float posx=transx(tim),posy=transy(glu);
            const graphpoints::RangeState state=
                    graphpoints::range_state(glu,targetlow,targethigh);
            const bool statechanged=haspreviousstate&&state!=previousstate;
            bool found=false;
#ifdef DOESSEARCH
            found=search&&searchdata(it);
            if(found) {
                nvgBeginPath(avg);
                nvgCircle(avg,posx,posy,foundPointRadius);
                nvgFillColor(avg,modernGraphNow);
                nvgFill(avg);
                }
#endif
            if(!found) {
                const bool draw=graphpoints::should_draw_sample(
                        posx,lastdrawx,minspacing,hasdrawn,first,
                        it==lastvalid,statechanged,false);
                if(draw) {
                    drawModernGraphSample(avg,posx,posy,radius,state);
                    lastdrawx=posx;
                    hasdrawn=true;
                    }
                if(glucosepointinfo(avg,tim,glu,posx,posy))
                    lasttouchedcolor=colorindex;
                }
            previousstate=state;
            haspreviousstate=true;
            first=false;
            }
        nvgRestore(avg);
        return;
        }

    nvgFillColor(avg,*getcolor(colorindex));
    nvgBeginPath(avg);
#ifdef DOESSEARCH
    bool search=scansearchtype==(scansearchtype&searchdata.type);
#endif
    for(const ScanData *it=low;it!=high;it++) {
        if(it->valid()) {
            const uint32_t tim= it->t;
            const auto glu=it->g*10;
            const auto posx= transx(tim),posy=transy(glu);
#ifdef DOESSEARCH
            if(search&&searchdata(it)) 
                nvgCircle(avg, posx,posy,foundPointRadius);
            else 
#endif
            {
                if(glucosepoint(avg,tim,glu,posx,posy))
                    lasttouchedcolor=colorindex;
                }
            }
        }
    nvgFill(avg);
    }
    //            nvgCircle(avg, posx,posy,;
    
 void    JCurve::makecircle(NVGcontext* avg,float posx,float posy) {
    nvgBeginPath(avg);
    nvgCircle(avg, posx,posy,pointRadius);
    nvgFill(avg);

    }

template <class TX,class TY> void JCurve::showlineScan(NVGcontext* avg,const ScanData *low,const ScanData *high,  const TX &transx,  const TY &transy,const int colorindex,bool search
) { 
#ifdef SI5MIN
   uint32_t dif=isSibionics?8*60:pollgapdist;
#else
   uint32_t dif=pollgapdist;
#endif


#ifdef DOESSEARCH
    if(search) {
        nvgBeginPath(avg);
        nvgStrokeColor(avg, *getyellow()); nvgFillColor(avg, *getyellow());
        nvgStrokeWidth(avg, hitStrokeWidth);
        bool restart=true,first;
        uint32_t late=0;
        bool washit=false;
        float prevx=-1.0f,prevy;
        for(const ScanData *it=low;it!=high;it++) {
            if(it->valid()&&searchdata(it)) {
                    const uint32_t tim= it->t;
                    const auto glu=it->g*10;
                    const auto posx= transx(tim),posy=transy(glu);
                    if(washit) {
                        if(!restart&&tim>late) {
                            nvgStroke(avg);
                            if(first)
                                makecircle(avg,prevx,prevy);
                            restart=true;
                            }
                        }
                    else {
                        washit=true;    
                        restart=true;
                        }
                    if(restart) {
                        nvgBeginPath(avg);
                         nvgMoveTo(avg, posx,posy);
                         restart=false;
                         first=true;
                         }
                    else {
                        first=false;
                        nvgLineTo( avg,posx,posy);
                        }
                    late=tim+dif;
                    prevx=posx;
                    prevy=posy;
                    }
            else {
                if(washit&&!restart) {
                    nvgStroke(avg);
                    if(first)
                        makecircle(avg,prevx,prevy);
                    }
                else
                    washit=false;
                restart=true;
                }
            }
        if(washit) {    
            if(!restart)
                nvgStroke(avg);
            if(first)
                makecircle(avg,prevx,prevy);
            }
        }
#endif
    if(modernnormal) {
        nvgSave(avg);
        nvgScissor(avg,dleft,dtop,dwidth,dheight);
        nvgLineCap(avg,NVG_ROUND);
        nvgLineJoin(avg,NVG_ROUND);

        // Give every uninterrupted stream segment a quiet depth cue without
        // changing which samples are connected or how gaps are detected.
        nvgBeginPath(avg);
        bool areasegment=false;
        bool hasarea=false;
        uint32_t arealate=0;
        float arealastx=0.0f;
        const float baseline=dtop+dheight;
        for(const ScanData *it=low;it!=high;it++) {
            if(!it->valid())
                continue;
            const uint32_t tim=it->t;
            const float posx=transx(tim);
            const float posy=transy(it->g*10);
            if(areasegment&&tim>arealate) {
                nvgLineTo(avg,arealastx,baseline);
                nvgClosePath(avg);
                areasegment=false;
                }
            if(!areasegment) {
                nvgMoveTo(avg,posx,baseline);
                nvgLineTo(avg,posx,posy);
                areasegment=true;
                hasarea=true;
                }
            else
                nvgLineTo(avg,posx,posy);
            arealastx=posx;
            arealate=tim+dif;
            }
        if(areasegment) {
            nvgLineTo(avg,arealastx,baseline);
            nvgClosePath(avg);
            }
        if(hasarea) {
            NVGpaint area=nvgLinearGradient(avg,0,dtop,0,baseline,
                                             modernGraphGlucoseAreaTop,
                                             modernGraphGlucoseAreaBottom);
            nvgFillPaint(avg,area);
            nvgFill(avg);
            }

        const float targetlow=settings->targetlow();
        const float targethigh=settings->targethigh();
        const auto statecolor=[&](float glucose,bool glow)->const NVGcolor & {
            return modernGraphStateColor(
                    graphpoints::range_state(glucose,targetlow,targethigh),glow);
            };

        // Split a segment exactly where it crosses a configured target. This
        // makes the clinical meaning of every colour unambiguous instead of
        // colouring a whole interval by only its newest sample.
        const auto drawstates=[&](bool glow) {
            bool haveprevious=false;
            uint32_t previouslate=0;
            float previousx=0.0f,previousy=0.0f,previousvalue=0.0f;
            nvgStrokeWidth(avg,glow?pollCurveStrokeWidth+density*3.2f:
                                    pollCurveStrokeWidth+density*.35f);
            for(const ScanData *it=low;it!=high;it++) {
                if(!it->valid())
                    continue;
                const uint32_t tim=it->t;
                const float value=it->g*10;
                const float posx=transx(tim);
                const float posy=transy(value);
                if(haveprevious&&tim<=previouslate) {
                    float stops[4]={0.0f,1.0f,0.0f,0.0f};
                    int stopcount=2;
                    const float delta=value-previousvalue;
                    if(delta!=0.0f) {
                        for(const float threshold:{targetlow,targethigh}) {
                            const float crossing=(threshold-previousvalue)/delta;
                            if(crossing>0.0f&&crossing<1.0f)
                                stops[stopcount++]=crossing;
                            }
                        }
                    std::sort(stops,stops+stopcount);
                    for(int part=0;part<stopcount-1;part++) {
                        const float from=stops[part],to=stops[part+1];
                        const float mid=(from+to)*.5f;
                        const float midvalue=previousvalue+delta*mid;
                        nvgBeginPath(avg);
                        nvgMoveTo(avg,previousx+(posx-previousx)*from,
                                     previousy+(posy-previousy)*from);
                        nvgLineTo(avg,previousx+(posx-previousx)*to,
                                     previousy+(posy-previousy)*to);
                        nvgStrokeColor(avg,statecolor(midvalue,glow));
                        nvgStroke(avg);
                        }
                    }
                haveprevious=true;
                previouslate=tim+dif;
                previousx=posx;
                previousy=posy;
                previousvalue=value;
                }
            };
        drawstates(true);
        drawstates(false);

        float lastx=-1.0f,lasty=-1.0f;
        uint32_t lastglu=0;
        bool haslast=false;
        const ScanData *lastvalid=nullptr;
        for(const ScanData *it=low;it!=high;it++) {
            if(!it->valid())
                continue;
            const uint32_t tim=it->t;
            const uint32_t glu=it->g*10;
            const float posx=transx(tim);
            const float posy=transy(glu);
            if(glucosepointinfo(avg,tim,glu,posx,posy))
                lasttouchedcolor=colorindex;
            lastx=posx;
            lasty=posy;
            lastglu=glu;
            haslast=true;
            lastvalid=it;
            }

        // Visual density is adaptive, but every reading above still runs
        // through glucosepointinfo so scrubbing/hit-testing remains exact.
        const float sampleradius=graphpoints::sample_radius(density,duration,false);
        const float minspacing=graphpoints::minimum_spacing(density,duration);
        bool firstsample=true,hasdrawn=false,haspreviousstate=false;
        float lastdrawx=0.0f;
        graphpoints::RangeState previousstate=graphpoints::RangeState::in_range;
        for(const ScanData *it=low;it!=high;it++) {
            if(!it->valid())
                continue;
            const uint32_t glu=it->g*10;
            const float posx=transx(it->t);
            const float posy=transy(glu);
            const graphpoints::RangeState state=
                    graphpoints::range_state(glu,targetlow,targethigh);
            const bool statechanged=haspreviousstate&&state!=previousstate;
            if(graphpoints::should_draw_sample(
                    posx,lastdrawx,minspacing,hasdrawn,firstsample,
                    it==lastvalid,statechanged,true)) {
                drawModernGraphSample(avg,posx,posy,sampleradius,state);
                lastdrawx=posx;
                hasdrawn=true;
                }
            previousstate=state;
            haspreviousstate=true;
            firstsample=false;
            }

        if(haslast&&lastx>=dleft&&lastx<=dleft+dwidth&&
           lasty>=dtop&&lasty<=dtop+dheight) {
            nvgBeginPath(avg);
            nvgCircle(avg,lastx,lasty,pointRadius*2.15f);
            nvgFillColor(avg,statecolor(lastglu,true));
            nvgFill(avg);
            nvgBeginPath(avg);
            nvgCircle(avg,lastx,lasty,pointRadius*1.02f);
            nvgFillColor(avg,statecolor(lastglu,false));
            nvgFill(avg);
            nvgBeginPath(avg);
            nvgCircle(avg,lastx,lasty,pointRadius*.38f);
            nvgFillColor(avg,modernGraphSurface);
            nvgFill(avg);
            }
        nvgRestore(avg);
        return;
        }

    bool restart=true;
    nvgBeginPath(avg);
    const NVGcolor *col=modernnormal?&modernGraphGlucose:getcolor(colorindex);
    nvgStrokeColor(avg, *col);
    nvgFillColor(avg,*col);
    nvgStrokeWidth(avg, pollCurveStrokeWidth);
    uint32_t late=0;
    float startx=-1000,starty=-1000;
    for(const ScanData *it=low;it!=high;it++) {
        if(it->valid()) {
            const uint32_t tim= it->t;
            const auto glu=it->g*10;
            const auto posx= transx(tim),posy=transy(glu);
/*#ifndef NOLOG
time_t ttim=tim;
            CURVELOGGER("showlineScan posx=%f tim=%ud %s",posx,tim,ctime(&ttim));
#endif */

            if(!restart&&tim>late) {
                nvgStroke(avg);
                if(startx>=0) {
                    nvgBeginPath(avg);
                    nvgCircle(avg, startx,starty,pollCurveStrokeWidth);
                    nvgFill(avg);
                     }
                restart=true;
                }
            if(restart) {
                nvgBeginPath(avg);
                 nvgMoveTo(avg, posx,posy);
                 startx=posx,starty=posy;
                 restart=false;
                 }
            else {
                 startx=starty=-1000.0f;
                nvgLineTo( avg,posx,posy);
                }

            late=tim+dif;

            if(glucosepointinfo(avg,tim,glu, posx, posy) ) {
                nvgLineTo( avg,posx,posy);
                nvgStroke(avg);
                nvgBeginPath(avg);
                nvgCircle(avg, posx,posy,pointRadius*1.3);
                nvgFill(avg);
                nvgBeginPath(avg);
                nvgMoveTo(avg, posx,posy);
                lasttouchedcolor=colorindex;
                }
            }
        }

        nvgStroke(avg);
        if(startx>=0) {
            nvgBeginPath(avg);
            nvgCircle(avg, startx,starty,pollCurveStrokeWidth);
            nvgFill(avg);
             }
        }

pair<int32_t,int32_t> histPositions(const SensorGlucoseData  * hist, const uint32_t starttime, const uint32_t endtime) {
    int32_t firstmog=hist->getstarthistory();
    int32_t lastmog= hist->getAllendhistory()-1;
    CURVELOGGER("histPositions first=%d last=%d\n",firstmog,lastmog);
    if(firstmog>=lastmog)
        return {firstmog,lastmog};
    uint32_t begin=hist->getstarttime();
    int sdisp=starttime-begin;
    int period=hist->getinterval();
    int off=sdisp/period;    
    int32_t    firstpos=firstmog+(uint32_t)((off>0)?off:0);
    if(firstpos>lastmog)
        firstpos=lastmog;
    for(;firstpos>firstmog;--firstpos) {
        auto tim=hist->timeatpos(firstpos);
        if(tim&&tim<=starttime)
            break;
        }
    for(;firstpos<lastmog&&!hist->timeatpos(firstpos);++firstpos) {
        }
    uint32_t firsttime=hist->timeatpos(firstpos);

    int lastscreen=firstpos+(endtime-firsttime)/period;
    int32_t lastpos=(lastscreen>lastmog)?lastmog:lastscreen;
    while(lastpos<lastmog&&hist->timeatpos(lastpos)<endtime)
        lastpos++;

    return {firstpos,lastpos};
    }

template <class TX,class TY> void    JCurve::calihistcurve(NVGcontext* avg,const SensorGlucoseData  * hist, const int32_t firstpos, const int32_t lastpos,const TX &xtrans,const TY &ytrans,const int colorindex) {
    if(hist->isDexcom()&&!settings->data()->dexcomPredict)
        return;

    const NVGcolor *col=modernnormal?&modernGraphHistory:getcolor(colorindex);
    nvgStrokeColor(avg, *col);
    nvgFillColor(avg,*col);
     bool restart=true;
     float startx=-3000.0f,starty=-3000.0f;
    CalibrateBackward<Glucose>  cali(hist,CalibratePast);
    for(auto pos=lastpos;pos>=firstpos;--pos) {
        const Glucose *histglu=hist->getglucose(pos);
        if(histglu->valid()) {
            const uint32_t tim=histglu->gettime();
            auto mgdL= histglu->getmgdL();
            double calibrated=cali.backvalue(tim,mgdL);
            if(isnan(calibrated)) {
                if(!allvalues) {
                        break;
                        }
                calibrated=mgdL;
                }
            const uint32_t glu=std::round(calibrated*10.0);
            auto posx=xtrans(tim),posy=ytrans( glu);
            bool oncurve=glucosepointinfo(avg,tim,glu, posx, posy);
            if(restart) {
                if(oncurve) {
                    nvgBeginPath(avg);
                    nvgCircle(avg, posx,posy,pointRadius*1.3);
                    nvgFill(avg);
                    lasttouchedcolor=colorindex;
                    }
                nvgBeginPath(avg);
                 nvgMoveTo(avg, posx,posy);
                 startx=posx,starty=posy;
                 restart=false;
                 }
            else {
                nvgLineTo( avg, posx,posy);
                 startx=-3000.0f,starty=-3000.0f;
                if(oncurve) {
                    nvgStroke(avg);
                    nvgBeginPath(avg);
                    nvgCircle(avg, posx,posy,pointRadius*1.3);
                    nvgFill(avg);
                    nvgBeginPath(avg);
                    nvgMoveTo(avg, posx,posy);
                    lasttouchedcolor=colorindex;
                    }
                }

            }
        else {
            if(!restart) {
                nvgStroke(avg);
                if(startx>=0.0f) {
                    nvgBeginPath(avg);
                    nvgCircle(avg, startx,starty,historyStrokeWidth);
                    nvgFill(avg);
                    }
                restart=true;
                }
            }
        }
    if(!restart) {
        nvgStroke(avg);
        if(startx>=0.0f) {
            nvgBeginPath(avg);
            nvgCircle(avg, startx,starty,historyStrokeWidth);
            nvgFill(avg);
            }
        }
    if(modernnormal) {
        const float targetlow=settings->targetlow();
        const float targethigh=settings->targethigh();
        const float sampleradius=graphpoints::sample_radius(density,duration,false);
        const float minspacing=graphpoints::minimum_spacing(density,duration);
        bool firstsample=true,hasdrawn=false,haspreviousstate=false;
        bool hascandidate=false,candidatedrawn=false;
        float lastdrawx=0.0f,candidatex=0.0f,candidatey=0.0f;
        graphpoints::RangeState previousstate=graphpoints::RangeState::in_range;
        graphpoints::RangeState candidatestate=graphpoints::RangeState::in_range;
        const auto flushsegment=[&]() {
            if(hascandidate&&!candidatedrawn)
                drawModernGraphSample(avg,candidatex,candidatey,sampleradius,
                                      candidatestate);
            hascandidate=false;
            };

        nvgSave(avg);
        nvgScissor(avg,dleft,dtop,dwidth,dheight);
        CalibrateBackward<Glucose> markercalibration(hist,CalibratePast);
        for(auto pos=lastpos;pos>=firstpos;--pos) {
            const Glucose *histglu=hist->getglucose(pos);
            if(!histglu->valid()) {
                flushsegment();
                firstsample=true;
                haspreviousstate=false;
                continue;
                }
            const uint32_t tim=histglu->gettime();
            const auto mgdL=histglu->getmgdL();
            double calibrated=markercalibration.backvalue(tim,mgdL);
            if(isnan(calibrated)) {
                if(!allvalues)
                    break;
                calibrated=mgdL;
                }
            const uint32_t glu=std::round(calibrated*10.0);
            const float posx=xtrans(tim),posy=ytrans(glu);
            const graphpoints::RangeState state=
                    graphpoints::range_state(glu,targetlow,targethigh);
            const bool statechanged=haspreviousstate&&state!=previousstate;
            const bool draw=graphpoints::should_draw_sample(
                    posx,lastdrawx,minspacing,hasdrawn,firstsample,false,
                    statechanged,false);
            if(draw) {
                drawModernGraphSample(avg,posx,posy,sampleradius,state);
                lastdrawx=posx;
                hasdrawn=true;
                }
            candidatex=posx;
            candidatey=posy;
            candidatestate=state;
            hascandidate=true;
            candidatedrawn=draw;
            previousstate=state;
            haspreviousstate=true;
            firstsample=false;
            }
        flushsegment();
        nvgRestore(avg);
        }
#ifdef DOESSEARCH
    if((searchdata.type&calibratedHistorysearchtype)==calibratedHistorysearchtype) {
        CalibrateBackward<Glucose>  calibrate(hist,CalibratePast);
        nvgBeginPath(avg);
        for(auto pos=lastpos;pos>=firstpos;--pos) {
            const Glucose *glu=hist->getglucose(pos);
            const auto tim=glu->gettime();
            const auto mgdL=glu->getmgdL();
            double calibrated=calibrate.backvalue(tim,mgdL);
            if(isnan(calibrated)) {
                if(!allvalues) {
                        break;
                        }
                calibrated=mgdL;
                }
            const int mgL=std::round(calibrated*10.0);
            if(searchdata(tim,mgL)) {
                if(tim) {
                    auto xc=xtrans(tim);
                    auto yc= ytrans(mgL);
                    nvgCircle(avg,xc,yc,foundPointRadius);
                    }
                }
            }
        nvgFill(avg);
        }
#endif
    }
template <class TX,class TY> void    JCurve::histcurve(NVGcontext* avg,const SensorGlucoseData  * hist, const int32_t firstpos, const int32_t lastpos,const TX &xtrans,const TY &ytrans,const int colorindex) {
    if(hist->isDexcom()&&!settings->data()->dexcomPredict)
        return;

    const NVGcolor *col=modernnormal?&modernGraphHistory:getcolor(colorindex);
    nvgStrokeColor(avg, *col);
    nvgFillColor(avg,*col);
     bool restart=true;
     float startx=-3000.0f,starty=-3000.0f;
    for(auto pos=firstpos;pos<=lastpos;pos++) {
        const Glucose *histglu=hist->getglucose(pos);
        if(histglu->valid()) {
            const uint32_t tim=histglu->gettime(),glu=histglu->getsputnik();
            auto posx=xtrans(tim),posy=ytrans( glu);
            bool oncurve=glucosepointinfo(avg,tim,glu, posx, posy);
            if(restart) {
                if(oncurve) {
                    nvgBeginPath(avg);
                    nvgCircle(avg, posx,posy,pointRadius*1.3);
                    nvgFill(avg);
                    lasttouchedcolor=colorindex;
                    }
                nvgBeginPath(avg);
                 nvgMoveTo(avg, posx,posy);
                 startx=posx,starty=posy;
                 restart=false;
                 }
            else {
                nvgLineTo( avg, posx,posy);
                 startx=-3000.0f,starty=-3000.0f;
                if(oncurve) {
                    nvgStroke(avg);
                    nvgBeginPath(avg);
                    nvgCircle(avg, posx,posy,pointRadius*1.3);
                    nvgFill(avg);
                    nvgBeginPath(avg);
                    nvgMoveTo(avg, posx,posy);
                    lasttouchedcolor=colorindex;
                    }
                }

            }
        else {
            if(!restart) {
                nvgStroke(avg);
                if(startx>=0.0f) {
                    nvgBeginPath(avg);
                    nvgCircle(avg, startx,starty,historyStrokeWidth);
                    nvgFill(avg);
                    }
                restart=true;
                }
            }
        }
    if(!restart) {
        nvgStroke(avg);
        if(startx>=0.0f) {
            nvgBeginPath(avg);
            nvgCircle(avg, startx,starty,historyStrokeWidth);
            nvgFill(avg);
            }
        }
    if(modernnormal) {
        const float targetlow=settings->targetlow();
        const float targethigh=settings->targethigh();
        const float sampleradius=graphpoints::sample_radius(density,duration,false);
        const float minspacing=graphpoints::minimum_spacing(density,duration);
        bool firstsample=true,hasdrawn=false,haspreviousstate=false;
        bool hascandidate=false,candidatedrawn=false;
        float lastdrawx=0.0f,candidatex=0.0f,candidatey=0.0f;
        graphpoints::RangeState previousstate=graphpoints::RangeState::in_range;
        graphpoints::RangeState candidatestate=graphpoints::RangeState::in_range;
        const auto flushsegment=[&]() {
            if(hascandidate&&!candidatedrawn)
                drawModernGraphSample(avg,candidatex,candidatey,sampleradius,
                                      candidatestate);
            hascandidate=false;
            };

        nvgSave(avg);
        nvgScissor(avg,dleft,dtop,dwidth,dheight);
        for(auto pos=firstpos;pos<=lastpos;pos++) {
            const Glucose *histglu=hist->getglucose(pos);
            if(!histglu->valid()) {
                flushsegment();
                firstsample=true;
                haspreviousstate=false;
                continue;
                }
            const uint32_t tim=histglu->gettime();
            const uint32_t glu=histglu->getsputnik();
            const float posx=xtrans(tim),posy=ytrans(glu);
            const graphpoints::RangeState state=
                    graphpoints::range_state(glu,targetlow,targethigh);
            const bool statechanged=haspreviousstate&&state!=previousstate;
            const bool draw=graphpoints::should_draw_sample(
                    posx,lastdrawx,minspacing,hasdrawn,firstsample,false,
                    statechanged,false);
            if(draw) {
                drawModernGraphSample(avg,posx,posy,sampleradius,state);
                lastdrawx=posx;
                hasdrawn=true;
                }
            candidatex=posx;
            candidatey=posy;
            candidatestate=state;
            hascandidate=true;
            candidatedrawn=draw;
            previousstate=state;
            haspreviousstate=true;
            firstsample=false;
            }
        flushsegment();
        nvgRestore(avg);
        }
#ifdef DOESSEARCH
    if((searchdata.type&historysearchtype)==historysearchtype) {
        nvgBeginPath(avg);
        for(auto pos=firstpos;pos<=lastpos;pos++) {
            const Glucose *glu=hist->getglucose(pos);
            if(searchdata(glu)) {
                const auto tim=glu->gettime();
                if(tim) {
                    const auto sput=glu->getsputnik();
                    auto xc=xtrans(tim);
                    auto yc= ytrans(sput);
                    nvgCircle(avg,xc,yc,foundPointRadius);
                    }
                }
            }
        nvgFill(avg);
        }
#endif
    }


extern uint32_t getnumlasttime();
//uint32_t maxstarttime() ;
uint32_t maxtime() {
    const uint32_t numt=getnumlasttime();
    const uint32_t sent= sensors->timelastdata(); 
    #ifndef NOLOG
    time_t tim=sent;
    CURVELOGGER("sensors->timelastdata()=%u %s",sent,ctime(&tim));
    #endif
    return max(numt,sent);
    }
    
static uint32_t getnumfirsttime() {
    uint32_t first=UINT32_MAX;

    for(auto el:numdatas)  {
        auto mog=el->getfirsttime();
        if(mog<first)
            first=mog;    
        }
    return first;
    }
    
uint32_t mintime() {
    uint32_t sent= sensors?sensors->timefirstdata():UINT32_MAX;
    uint32_t numt=getnumfirsttime();
   uint32_t tim= min(numt,sent);
   #ifndef NOLOG
   time_t t=tim;
   CURVELOGGER("mintime=%d %s",tim,ctime(&t));
   #endif

   if(tim==UINT32_MAX)
        tim=time(nullptr);
   return tim;
    }
uint32_t JCurve::minstarttime() {
    uint32_t mini=mintime();
    return mini-duration;
    }

//uint32_t starttime;
//int duration=8*60*60;

//extern void setstarttime(uint32_t);
void JCurve::setdiffcurrent() {
    //diffcurrent=(uint64_t)time(nullptr)-starttime;
    auto now=time(nullptr);
    diffcurrent=now-starttime;
    if(diffcurrent>(duration*5/6)) {
        doclamp=false;
        }
     else
        doclamp=true;
    CURVELOGGER("now=%u starttime=%u diffcurrent=%d doclamp=%d\n",now,starttime,diffcurrent,doclamp);
     return;
    }
void JCurve::setstarttime(uint32_t newstart) {
    CURVELOGGER("setstarttime(%u) nowclamp=%d\n",newstart,nowclamp);
    starttime=newstart;
    if(modernui)
        graphselectionactive=false;
    if(nowclamp) {
        setdiffcurrent();
        }
    }
uint32_t JCurve::maxstarttime() {
    float duraf=((float)valuesize/dwidth);
    CURVELOGGER("dwidth=%f valuesize=%f duraf=%f\n",(double)dwidth,(double)valuesize,(double)duraf);
    float subtr=0.91 - duraf*1.2f;
    const uint32_t legacyMaximum=static_cast<uint32_t>(
            time(nullptr)-subtr*duration);
    return forecastgraph::maximum_start(legacyMaximum,
            static_cast<uint32_t>(duration),forecastGraphEndTime());
    }
void JCurve::begrenstijd() {
    auto maxstart= maxstarttime();
    if(starttime>maxstart)
        setstarttime(maxstart);
    else {
        auto minstart= minstarttime();
        if(starttime<minstart)
            setstarttime(minstart);
        }
    }

#include <memory>

pair<float,float>    JCurve::drawtrender(NVGcontext* avg,const std::array<uint16_t,16> &trend,const float x,const float y,const float w,const float h) {
    auto minel=std::min_element(trend.begin(),trend.end());
    auto maxel=std::max_element(trend.begin(),trend.end());
     const int low=minel-trend.begin();
     const int high=maxel-trend.begin();
     if(low<0||high<0)
         return {0,dtop+dheight/2};
    const float lowval=*minel;
    const float highval=*maxel;
    const float mid=(lowval+highval)/2.0;
    CURVELOGGER("width=%.0f, height=%.0f\n",w,h);
    CURVELOGGER("low=%.0f,high=%.0f,mid=%.0f\n",lowval,highval,mid);
    constexpr float hglurange=2*convfactor;
    const auto gety=[y,h,mid](const short val)->float  { return y+h/2.0-(((val-mid)/hglurange)*h);};
    const int step=w/(trend::num-1);
    nvgBeginPath(avg);
     nvgStrokeWidth(avg, TrendStrokeWidth);
//    nvgStrokeColor(avg, white);
    nvgStrokeColor(avg, *getblack());
    int i=0;
    unsigned short glu0;
    for(;!(glu0=trend[i]);i++)
        if(i>=(trend.size()-3))
            return {0,dtop+dheight/2};
    float pos0=gety(glu0);
    float posx= x+i*step;
     nvgMoveTo(avg,posx ,pos0);
    CURVELOGGER("%.1f (%hi) (%.0f,%.0f)\n",glu0/convfactor,glu0,posx,pos0);
    posx+=step;
    float posy=0.0f;
    i++;
    for(;i<trend.size();i++,posx+=step) {
        short glu=trend[i];
        if(glu) {
            posy=gety(glu);
            CURVELOGGER("%.1f (%hi) (%.0f,%.0f)\n",glu/convfactor,glu,posx,posy);
            nvgLineTo( avg,posx ,posy);
            }
        }
    nvgStroke(avg);
    return std::pair<float,float>({pos0,posy});
    }
void startstep(NVGcontext* avg,const NVGcolor &col);



void JCurve::setextremes(pair<int,int> extr) {
    auto [gminin,gmaxin]=extr;
    setend=0;
    const uint32_t gmaxmax=ghigh>=0.0f?ghigh:settings->graphhigh();
    const uint32_t gminmin=glow>=0.0f?glow:settings->graphlow();
    if(gmaxin<gmaxmax)
        gmaxin=gmaxmax;
    if(gminin>gminmin)
        gminin=gminmin;
    grange=gmaxin-gminin;
    gmin=gminin;
    }

pair<int,int> JCurve::getextremes(const vector<int> &hists, const pair<const ScanData *,const ScanData*> **scanranges, int scannr,const pair<int32_t,int32_t> *histpositions) {
    int gmax=0;
    int gmin=6000;
    const int histlen=hists.size();
    for(int i=0;i<histlen;i++) {
     const auto his=sensors->getSensorData(hists[i]);
      if(!his->isDexcom()||(showhistories&&settings->data()->dexcomPredict)) {
            for(auto pos=histpositions[i].first,last=histpositions[i].second;pos<=last;pos++) {
                int glu=his->sputnikglucose(pos);
                if(glu) {
                    if(glu>gmax)
                         gmax=glu;
                    if(glu<gmin)
                         gmin=glu;
                    }
                }
            }
        for(int j=0;j<scannr;j++) {
            const pair<const ScanData *,const ScanData*> *srange=scanranges[j];
            for(const ScanData *it=srange[i].first,*last=srange[i].second;it<last;it++) {
                if(it->valid()) {
                    auto mgdL=it->g;
                    int glu=mgdL*10;
                    if(glu>gmax)
                        gmax=glu;
                     if(glu<gmin)
                         gmin=glu;
                    }
                }
            }
        }
    return {gmin,gmax};
    }
template <class LT> void    JCurve::glucoselines(NVGcontext* avg,const float last,const float smallfontlineheight,const int gmax,const LT &transy,bool showlevels) {
    nvgStrokeWidth(avg, modernnormal?std::max(density*.58f,.65f):glucoseLinesStrokeWidth);
    nvgStrokeColor(avg, *getgray());
    const double yscale=transy(1)-transy(0);
    const float mindisunit=smallsize*1.5;
    const float minst=abs(mindisunit/yscale);
    const bool ismmolL=glunit==1;//settings->usemmolL();
    const double unit=ismmolL?0.5*convfactor:100;
    const double unit2=unit*2;

    uint32_t step=minst<=unit?unit:ceilf(minst/unit2)*unit2;
    if(modernnormal&&step<unit2*2.0)
        step=static_cast<uint32_t>(unit2*2.0);
    float startld;
    if(modernnormal) {
        nvgTextAlign(avg,NVG_ALIGN_RIGHT|NVG_ALIGN_MIDDLE);
        startld=dleft+dwidth-density*5.0f;
        }
    else {
        nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_MIDDLE);
        if(getLevelLeft()) {
            startld = timelen*.4;
            }
        else  {
            startld =  dwidth/2+dleft;
            }
        }

  const    uint32_t startl=0;
  
    const float endline=modernnormal?dleft+dwidth:last;
//    CURVELOGGER("glucoselines: unit=%f unit2=%f step=%d (%g) startl=%d (%g)\n",unit,unit2,step,::gconvert(step,glunit),startl,::gconvert(startl,glunit));
#ifdef WEAROS
   const auto endlevel=dheight-smallfontlineheight;
   const auto startlevel=2.5*smallfontlineheight;
#endif
    
    for(auto y=startl+step;y<gmax;y+=step) {
        float dy=transy(y);
        if(modernnormal?(dy<=dtop||dy>=dtop+dheight):(dy<=0))
            continue;
        nvgBeginPath(avg);
         nvgMoveTo(avg,dleft ,dy) ;
        nvgLineTo( avg, endline,dy);
        nvgStroke(avg);
#ifdef WEAROS
        if(showlevels&&dy>startlevel&&dy<endlevel) 
#else
        if(modernnormal?(dy>dtop+smallfontlineheight&&dy<dtop+dheight-smallfontlineheight)
                       :(dy>smallfontlineheight))
#endif
        {
            constexpr const int  bufsize=50;
            char buf[bufsize];
#ifdef CONV18
            int len=snprintf(buf,bufsize,"%g",::gconvert(y,glunit));
#else
            int len=snprintf(buf,bufsize,gformat,::gconvert(y,glunit));
            if(ismmolL)  {
                if(buf[len-1]=='0') 
                    len-=2;
                }
#endif
            if(len>bufsize)
                len=bufsize;
            if(modernnormal) {
                float bounds[4];
                nvgTextBounds(avg,startld,dy,buf,buf+len,bounds);
                const float horizontal=density*3.5f;
                const float vertical=density*1.5f;
                nvgBeginPath(avg);
                nvgRoundedRect(avg,bounds[0]-horizontal,bounds[1]-vertical,
                               bounds[2]-bounds[0]+horizontal*2.0f,
                               bounds[3]-bounds[1]+vertical*2.0f,
                               density*4.0f);
                nvgFillColor(avg,modernGraphAxisBackdrop);
                nvgFill(avg);
                nvgFillColor(avg,modernGraphText);
                }
            nvgText(avg, startld,dy, buf, buf+len);
            }

        }
    }
struct displaytime {
    const uint32_t tstep;
    const uint32_t first;
    const uint32_t last;
    };
template <class LT> const displaytime JCurve::getdisplaytime(const uint32_t nu,const uint32_t starttime,const uint32_t endtime, const LT &transx) {
    const float xscale=transx(1)-transx(0);
    const float mindisunit=smallsize*(hour24()?3:4.5);
    const  float minst=abs(mindisunit/xscale);
    const uint32_t tstep=(minst<=60*15)?60*15:((minst<=60*30)?60*30:ceilf((minst/(60.0*60)))*(60*60));
    const uint32_t first=uint32_t(ceilf(starttime/(double)tstep))*tstep;    
    const uint32_t endhier=(nu<endtime)?(nu+tstep-59):(endtime-1);
    uint32_t last=uint32_t(floorf(endhier/double(tstep)))*tstep;    
    if((last>nu)&&(2*(last-nu))>tstep)
        last=nu;

    CURVELOGGER("getdisplaytime xscale=%f %u %u %u\n",xscale,tstep,first,last);
    return {tstep,first,last};
}

static bool timemiddle() {
   return false;
   }
#ifdef NOCUTOFF
static bool nocutoff=true;
#endif
template <class LT>
void    JCurve::timelines(NVGcontext* avg,const displaytime *disp, const LT &transx ,uint32_t nu) {

    const uint32_t tstep=disp->tstep;
    const uint32_t first=disp->first;
    const uint32_t last= disp->last;
    #ifdef WEAROS
    const uint32_t numlast= (disp->last>nu)?(disp->last-tstep):disp->last;
    #endif
    nvgFillColor(avg, *getblack());
    nvgFontSize(avg, timefontsize);
    float timeY
#ifdef NOCUTOFF
   ,lower,upper
#endif
   ;
   if(timemiddle()) {
       nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_MIDDLE);
       timeY=(dheight-statusbarheight-dbottom)*.5f+statusbarheight;
#ifdef NOCUTOFF
if(nocutoff&&!modernnormal) {
      lower=timelen*.5f;
      upper=dwidth-lower;
      }
#endif
      }
   else {
       nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_MIDDLE);
     timeY=
    #ifdef WEAROS
        smallfontlineheight*1.45f + //MODIFIED
//        smallfontlineheight*1.7f +
    #endif
   (modernnormal?dtop+dheight-smallfontlineheight*.70f:statusbarheight)
      ;
#ifdef NOCUTOFF
   if(nocutoff&&!modernnormal) {
         float straal=dwidth*.5f;
         float over=straal-timeY;
         lower=straal-sqrt(pow(straal,2)-pow(over,2))+timelen*.4f;
         upper=dwidth-lower;
         CURVELOGGER("lower=%f upper=%f over=%f\n",lower,upper,over);
      }
#endif
      }
    const float lowY=modernnormal
            ?dtop+dheight-smallfontlineheight*1.45f
            :dheight+dtop+dbottom;
    for(auto tim=first;tim<=last;tim+=tstep) {
        float dtim=transx(tim);
        char buf[20];
        struct tm tmbuf;
        time_t tmptime=tim;
         struct tm *stm=localtime_r(&tmptime,&tmbuf);

         if(modernnormal) {
            const bool dayboundary=!(stm->tm_hour||stm->tm_min);
            nvgStrokeWidth(avg,std::max(density*(dayboundary?.9f:.52f),.6f));
            nvgStrokeColor(avg,dayboundary?modernGraphGridStrong:modernGraphGrid);
            }
         else if(stm->tm_hour||stm->tm_min) {
            if(stm->tm_min||stm->tm_hour%3) {
                nvgStrokeWidth(avg, timeLinesStrokeWidth);
                nvgStrokeColor(avg, *getgray());
                }
            else {
                nvgStrokeWidth(avg, timeLinesStrokeWidth);
                nvgStrokeColor(avg, *getthreehour());
                }
            }
        else {
            nvgStrokeWidth(avg, dayEndStrokeWidth);
            nvgStrokeColor(avg, *getblack());
            }
    #ifdef WEAROS
         if(tim<=numlast
#ifdef NOCUTOFF
      &&(!nocutoff||(dtim>lower&&dtim<upper))
#endif
       )  
    #endif
         {
        int len=mktime(stm->tm_hour,mktmmin(stm),buf);
            nvgText(avg, dtim,timeY, buf, buf+len);
            }
        nvgBeginPath(avg);
        nvgMoveTo(avg,dtim,modernnormal?dtop+density*5.0f:0) ;
        nvgLineTo( avg, dtim,lowY);
        nvgStroke(avg);
        }
    nvgFontSize(avg, smallsize);
    }

template <class LT> void    JCurve::epochlines(NVGcontext* avg,uint32_t first,uint32_t last, const LT &transx) {
        time_t startin=first;

        struct tm tmbuf;
         struct tm *stm=localtime_r(&startin,&tmbuf);
        auto hour=stm->tm_hour;
        if(stm->tm_min) {
            startin+=(60-stm->tm_min)*60;
            hour++;
            }
        
        time_t start=startin+(24-hour)*60*60;
        nvgStrokeWidth(avg, dayEndStrokeWidth);
        nvgStrokeColor(avg, *getblack());
        for(time_t t=start;t<last;t+=(24*60*60)) {
            float dtim=transx(t);
        //    CURVELOGGER("%ld\n",t);
            nvgBeginPath(avg);
            nvgMoveTo(avg,dtim ,0) ;
            nvgLineTo( avg, dtim,dheight);
            nvgStroke(avg);
            }
        nvgStrokeWidth(avg, timeLinesStrokeWidth);
        nvgStrokeColor(avg, *getthreehour());
        const int inthree=hour%3;
        start=startin+(inthree?((3-inthree)*60*60):0);
        CURVELOGGER("startin=%ld start=%ld last=%d inthree=%d\n",startin,start,last, inthree);
        for(time_t t=start;t<last;t+=(3*60*60)) {
            float dtim=transx(t);
            nvgBeginPath(avg);
            nvgMoveTo(avg,dtim ,0) ;
            nvgLineTo( avg, dtim,dheight);
            nvgStroke(avg);
            }
    }
extern std::vector<int> usedsensors;
extern void setusedsensors() ;
extern void setusedsensors(uint32_t nu) ;
void setmaxsensors(size_t sensornr) {
    setusedsensors();
    }


uint32_t lastsensorends() {
    if(const SensorGlucoseData *hist = sensors->getSensorData()) {
               return hist->expectedEndTime();
               }
          return 0u;
          }
#include "gluconfig.hpp"
void    JCurve::drawarrow(NVGcontext* avg, float rate,float getx,float gety) {
        if(!isnan(rate)) {
            if(glnearnull(rate))
                rate=.0f;
            if(rate<=0.0f)
                gety-=headheight/12.5f;
            float x1=getx-density*40;
            float y1=gety+rate*density*30;

            long double rx=getx-x1;
            long double ry=gety-y1;
            double rlen= sqrt(pow(rx,2) + pow(ry,2));
             rx/=rlen;
             ry/=rlen;

            long double l=density*12;

            double addx= l* rx;
            double addy= l* ry;
            double tx1=getx-2*addx;
            double ty1=gety-2*addy;
            double xtus=getx-1.5*addx;
            double ytus=gety-1.5*addy;
            double hx=ry;
            double hy=-rx;
            double sx1=tx1+l*hx;
            double sy1=ty1+l*hy;
            double sx2=tx1-l*hx;
            double sy2=ty1-l*hy;
            nvgBeginPath(avg);
            nvgStrokeColor(avg, *getblack());
            nvgStrokeWidth(avg, arrowstrokewidth);
            nvgMoveTo(avg,x1,y1) ;
            nvgLineTo( avg, xtus,ytus);
            nvgStroke(avg);
            nvgBeginPath(avg);
            nvgFillColor(avg, *getblack());
            nvgMoveTo(avg,sx1,sy1) ;
            nvgLineTo( avg, getx,gety);
            nvgLineTo( avg, sx2,sy2);
            nvgLineTo( avg, xtus,ytus);
            nvgClosePath(avg);
            nvgFill(avg);

            }
    }
#ifndef NOLOG
//#define TESTVALUE
#endif

    
//static bool    streamvalueshown=false;

//#define DOTEST 1




int JCurve::largedaystr(const time_t tim,char *buf) {
        CURVELOGAR("largedaystr");
    struct tm stmbuf;
    localtime_r(&tim,&stmbuf);
   int len=mkhourminstr(stmbuf.tm_hour,mktmmin(&stmbuf),buf);
#ifdef WEAROS
     len+=sprintf(buf+len," %s %02d %s",usedtext->daylabel[stmbuf.tm_wday],stmbuf.tm_mday,usedtext->monthlabel[stmbuf.tm_mon]);
#else
     len+=sprintf(buf+len," %s %02d %s %d",usedtext->daylabel[stmbuf.tm_wday],stmbuf.tm_mday,usedtext->monthlabel[stmbuf.tm_mon],1900+stmbuf.tm_year);
#endif
   return len;
    }



void       JCurve::showbluevalue(NVGcontext* avg,const time_t nu,const int xpos,std::vector<int> &used) {
CURVELOGGER("showbluevalue %zd\n",used.size());
        nvgFontSize(avg, smallsize);
        nvgFillColor(avg, *getblack());

        if(modernnormal) {
            nvgSave(avg);
            nvgScissor(avg,dleft,dtop,dwidth,dheight);
            nvgLineCap(avg,NVG_ROUND);
            const float lineTop=dtop+smallfontlineheight*1.30f;
            const float lineBottom=dtop+dheight-smallfontlineheight*1.45f;
            NVGpaint nowGlow=nvgLinearGradient(avg,xpos,lineTop,xpos,lineBottom,
                                                modernGraphNowGlow,
                                                modernGraphNowFade);
            nvgBeginPath(avg);
            nvgStrokePaint(avg,nowGlow);
            nvgStrokeWidth(avg,std::max(density*3.2f,1.5f));
            nvgMoveTo(avg,xpos,lineTop);
            nvgLineTo(avg,xpos,lineBottom);
            nvgStroke(avg);
            NVGpaint nowCore=nvgLinearGradient(avg,xpos,lineTop,xpos,lineBottom,
                                                modernGraphNow,
                                                modernGraphNowFade);
            nvgBeginPath(avg);
            nvgStrokePaint(avg,nowCore);
            nvgStrokeWidth(avg,std::max(density*.95f,.9f));
            nvgMoveTo(avg,xpos,lineTop);
            nvgLineTo(avg,xpos,lineBottom);
            nvgStroke(avg);
            nvgBeginPath(avg);
            nvgRoundedRect(avg,xpos-density*4.0f,lineTop-density*1.8f,
                           density*8.0f,density*3.6f,density*1.8f);
            nvgFillColor(avg,modernGraphNow);
            nvgFill(avg);
            nvgRestore(avg);
            }
        else {
            nvgBeginPath(avg);
            nvgStrokeColor(avg,dooryellow);
            nvgStrokeWidth(avg,nowLineStrokeWidth);
            nvgMoveTo(avg,xpos,dtop);
            nvgLineTo(avg,xpos,dheight+dtop+dbottom);
            nvgStroke(avg);
            }
        const float getx= xpos+headsize*.9f+8*dwidth/headsize;
        if(modernnormal) {
            // The Java dashboard owns the fresh glucose hero. Still run the
            // native status path so alarms, warm-up/error state and viewed
            // bookkeeping retain their legacy behavior.
            showlastsstream(avg,nu,getx,used);
            return;
            }
#ifndef WEAROS
        if(const auto *sens=sensors->getSensorData()) {
            if(!(sens->isDexcom()||sens->isSibionics())||!sens->unused()) {
                if(time_t enddate=sens->expectedEndTime()) {
                    float down=0;

                    const float timex=xpos+nowLineStrokeWidth;
                    constexpr int maxhead=80;
                    char head[maxhead];
                    int tstart,end;

                    if(isRTL()) {
                            end= datestr(enddate,head); 
                            memcpy(head+end,usedtext->sensorexpectedend.data(),usedtext->sensorexpectedend.size());
                            tstart=usedtext->sensorexpectedend.size();
                        }
                    else {
                            memcpy(head,usedtext->sensorexpectedend.data(),usedtext->sensorexpectedend.size());
                            tstart=usedtext->sensorexpectedend.size();
                            char *endstr=head+tstart;
                            end= datestr(enddate,endstr); 
                            }
                    nvgTranslate(avg, timex,down);
                    nvgRotate(avg,-NVG_PI/2.0);
                    nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_BOTTOM);
                    nvgText(avg, -dheight/2+down-smallfontlineheight,dwidth-timex, std::begin(head), head+end+tstart);
                    nvgResetTransform(avg);
                    }
                }
            }
#else
    if( settings->data()->IOB) {
        float down=0;
        const float timex=xpos+nowLineStrokeWidth;
        nvgTranslate(avg, timex,down);
        nvgRotate(avg,-NVG_PI/2.0);
        double getiob(uint32_t);
        int maxbuf=20;
        char tbuf[maxbuf];
        int len=snprintf(tbuf,maxbuf,"IOB: %.1f",getiob(nu));
        nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_BOTTOM);
        nvgText(avg, -dheight*.40f+down-smallfontlineheight,dwidth*.984f-timex, tbuf,tbuf+len);
        nvgResetTransform(avg);
        }
#endif
constexpr const bool showcurrentdate=true;

if(showcurrentdate) {
        const float datehigh=smallfontlineheight*.72;
        
        nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_TOP);
        {
        constexpr int maxbuf=120;
        char tbuf[maxbuf];
         const int datlen=largedaystr(nu,tbuf) ;
        const float timex =
            getx
        #ifdef WEAROS
            -timelen*.85f
        #endif
        ;
        const float timey = (datehigh+statusbarheight)
        #ifdef WEAROS
        *(used.size()<2?2.5f:1.0f);
        #endif
        ;

        nvgText(avg, timex,timey, tbuf, tbuf+datlen);

#ifndef WEAROS    
    if( settings->data()->IOB) {
        double getiob(uint32_t);
        int len=snprintf(tbuf,maxbuf,"IOB: %.2f",getiob(nu));
        nvgText(avg, timex,2*smallfontlineheight+statusbarheight, tbuf,tbuf+len);
        }
#endif

        CURVELOGGER("xpos=%d dwidth=%.1f headsize=%.1f density=%.1f getx=%.1f timex=%.1f\n",xpos,dwidth,headsize, density,getx,timex);
        }
      }
    showlastsstream(avg,nu, getx,used) ;
    }

 void       JCurve::showsavedomain(NVGcontext* avg,const float last, const float dlow,const float dhigh) {
    nvgBeginPath(avg);
    nvgFillColor(avg, unsavecolor);
    nvgRect(avg, dleft, dtop, last-dleft, dhigh);
    nvgFill(avg);

    nvgBeginPath(avg);
    nvgFillColor(avg, unsavecolor);
    nvgRect(avg, dleft, dlow, last-dleft, dheight+dtop);
    nvgFill(avg);
    }
 void    JCurve::showunsaveredline(NVGcontext* avg,const float last,const float dlow) {
    nvgBeginPath(avg);
    nvgStrokeWidth(avg, lowGlucoseStrokeWidth);

    nvgStrokeColor(avg, lowlinecolor);
    nvgMoveTo(avg, dleft,dlow) ;
    nvgLineTo( avg,last ,dlow);
    nvgStroke(avg);
    }


 void       JCurve::showsaverange(NVGcontext* avg,const float last, const float dlow,const float dhigh) {
    if(modernnormal) {
        const float top=dlow<dhigh?dlow:dhigh;
        const float bottom=dlow>dhigh?dlow:dhigh;
        const float right=dleft+dwidth;

        nvgBeginPath(avg);
        nvgRect(avg,dleft,top,dwidth,bottom-top);
        NVGpaint targetpaint=nvgLinearGradient(avg,0,top,0,bottom,
                                                modernGraphTargetFill,
                                                modernGraphTargetFillBottom);
        nvgFillPaint(avg,targetpaint);
        nvgFill(avg);

        nvgStrokeWidth(avg,std::max(density*.7f,.7f));
        nvgStrokeColor(avg,modernGraphTargetBorder);
        nvgBeginPath(avg);
        nvgMoveTo(avg,dleft,top);
        nvgLineTo(avg,right,top);
        nvgMoveTo(avg,dleft,bottom);
        nvgLineTo(avg,right,bottom);
        nvgStroke(avg);

        // A restrained inline label explains the band without adding a
        // separate legend or stealing vertical space from the plot.
        if(bottom-top>density*26.0f) {
            char targetlabel[48];
            const double lowvalue=::gconvert(settings->targetlow(),glunit);
            const double highvalue=::gconvert(settings->targethigh(),glunit);
            const int targetlen=snprintf(targetlabel,sizeof(targetlabel),
                    glunit==1?"%.1f - %.1f mmol/L":"%.0f - %.0f mg/dL",
                    lowvalue,highvalue);
            nvgFontSize(avg,smallsize*.68f);
            nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_TOP);
            nvgFillColor(avg,nvgRGBAf2(0x68/255.0f,0xC9/255.0f,0x98/255.0f,.78f));
            nvgText(avg,dleft+density*8.0f,top+density*6.0f,
                    targetlabel,targetlabel+targetlen);
            }
        return;
        }
    showsavedomain(avg,last,dlow,dhigh) ;
    showunsaveredline(avg,last,dlow) ;
    }
        

void        JCurve::showdates(NVGcontext* avg,time_t nu,uint32_t starttime,time_t endtime) {
   CURVELOGGER("duration=%d\n",duration);
    int32_t timdis=nu-starttime;
constexpr const int grens=
#ifdef WEAROS
1
#else
3
#endif
;
CURVELOGGER("timdis=%d duration=%d grens=%d\n",timdis,duration,grens);
if(timdis>0&&((duration/timdis)<grens)) {
       CURVELOGGER("timdis=%d larger than zero\n",timdis);
        const float datehigh=smallfontlineheight*
#ifdef WEAROS
        //.71;
        .68;
#else
        1.5;
        #endif

        char tbuf[70];

        nvgFillColor(avg, *
        #ifdef WEAROS
        getdarkgray()
        #else
        getblack()
        #endif
        );
    float xpos;
   int timelen;
#ifdef WEAROS
//        xpos= dwidth/2+dleft;
        xpos= dwidth*.495f+dleft;

     time_t showtime= (endtime+starttime)/2;
    struct tm tmbuf;
     struct tm *stm=localtime_r(&showtime,&tmbuf);
        nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_TOP);
      timelen=strlen(usedtext->daylabel[stm->tm_wday]);
      memcpy(tbuf,usedtext->daylabel[stm->tm_wday],timelen);
        nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_BOTTOM);
        nvgText(avg,xpos ,dheight+datehigh*.15f, tbuf, tbuf+timelen);

       timelen=sprintf(tbuf,"%02d-%02d-%d",stm->tm_mday,stm->tm_mon+1,1900+stm->tm_year);
   
        nvgTextAlign(avg,NVG_ALIGN_CENTER|NVG_ALIGN_TOP);
        nvgText(avg,xpos ,datehigh*.80f+statusbarheight, tbuf, tbuf+timelen);
#else
       const time_t showstarttime=starttime+2*60;
        struct tm tmbufstart;
        localtime_r(&showstarttime,&tmbufstart);
        timelen=sprintf(tbuf,"%s %02d-%02d-%d",usedtext->daylabel[tmbufstart.tm_wday],tmbufstart.tm_mday,tmbufstart.tm_mon+1,1900+tmbufstart.tm_year);
//        timelen=daystr(showstarttime,tbuf);
        xpos= getLevelLeft()?timelen*.75:0;
        nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_TOP);
        nvgText(avg,xpos ,datehigh+statusbarheight, tbuf, tbuf+timelen);
#endif

        CURVELOGGER("displaytime %s\n",tbuf);
#ifndef WEAROS
       const auto showendtime=endtime-2*60;
        if(nu>=endtime) {
            struct tm tmbufend;
            localtime_r(&showendtime,&tmbufend);
 #define equalday(x) (tmbufend.x==tmbufstart.x)
            if(!(equalday(tm_wday)&& equalday(tm_mday)&& equalday(tm_mon)&& equalday(tm_year))) {
                timelen=sprintf(tbuf,"%s %02d-%02d-%d",usedtext->daylabel[tmbufend.tm_wday],tmbufend.tm_mday,tmbufend.tm_mon+1,1900+tmbufend.tm_year);
                nvgTextAlign(avg,NVG_ALIGN_RIGHT|NVG_ALIGN_TOP);
                nvgText(avg, dwidth+dleft,datehigh+statusbarheight, tbuf, NULL);
                }
#undef equalday
            }
#endif
        }
    }



void    JCurve::showlines(NVGcontext* avg,int gm,int gmax) {
    const uint32_t endtime=starttime+duration;
    gmin=gm;
    grange=gmax-gmin;
    const auto [transx,transy]= gettrans(starttime, endtime);
    displaytime disp=getdisplaytime(UINT_MAX,starttime,endtime, transx);
    const float dlast=dleft+dwidth;
    timelines(avg,&disp,  transx,UINT32_MAX);
    if(disp.tstep>(60*60))
        epochlines(avg,starttime,disp.last,transx);
    glucoselines(avg,dlast,smallfontlineheight,gmax,transy,true) ;
    showunsaveredline(avg,dlast,transy(settings->targetlow()));
    int yhigh=transy(settings->targethigh());
    nvgBeginPath(avg);
    nvgStrokeWidth(avg, lowGlucoseStrokeWidth);
    nvgStrokeColor(avg, dooryellow);
    nvgMoveTo(avg, dleft,yhigh) ;
    nvgLineTo( avg,dwidth,yhigh);
    nvgStroke(avg);
    }
        


extern bool hascalibrations;
bool hascalibrations=false;
int    JCurve::displaycurve(NVGcontext* avg,time_t nu) {
    starttime=(doclamp)?(nu-diffcurrent):(starttime);
    const uint32_t starttime2=starttime;
    const uint32_t endtime=starttime2+duration;
    // Stored/backend values are invariant raw sensor mg/dL. This frame-local
    // copy receives the current calibration only for presentation, so changing
    // a calibration never mutates forecast training identities or history.
    forecastgraph::Snapshot forecastSnapshot=forecastGraphSnapshot();
    // Follow the glucose layer that is visually on top. Raw stream/scan layers
    // are drawn after their calibrated counterparts, so calibration is applied
    // to the forecast only when a calibrated layer is the sole active source.
    const bool calibrateForecastDisplay=settings->data()->DoCalibrate&&
            ((showcalibratedscans&&!showscans)||
             (!showcalibratedscans&&!showscans&&
              showcalibratedstream&&!showstream));
    mealpos.clear();
    hidden.clear(); 
    hists.clear(); 
    sensors->sensorsInPeriod(hists,starttime2,endtime,[this,starttime2](const SensorGlucoseData *s){
        if(s->hide) {
            if(s->lastused()>=starttime2) {
                hidden.push_back(s->sensorIndex);
                }
            return true; 
            }
        return false;
        }
            );
            
    histlen=hists.size();
    if(calibrateForecastDisplay&&!forecastSnapshot.points.empty()) {
        const SensorGlucoseData *displaySensor=nullptr;
        uint32_t newestPollTime=0U;
        for(const int sensorIndex:hists) {
            const SensorGlucoseData *candidate=
                    sensors->getSensorData(sensorIndex);
            if(!candidate)
                continue;
            const auto polls=candidate->getPolldata();
            for(auto iterator=polls.rbegin();iterator!=polls.rend();++iterator) {
                if(!iterator->t||iterator->t>static_cast<uint32_t>(nu)||
                   !iterator->valid(0))
                    continue;
                if(iterator->t>newestPollTime) {
                    newestPollTime=iterator->t;
                    displaySensor=candidate;
                    }
                break;
                }
            }
        if(displaySensor) {
            auto calibrator=make_calibrator<ScanData>(displaySensor);
            for(auto &point:forecastSnapshot.points) {
                const double center=calibrator.calibrateNow(
                        point.time,point.medianMgDl);
                const double lower=calibrator.calibrateNow(
                        point.time,point.lowMgDl);
                const double upper=calibrator.calibrateNow(
                        point.time,point.highMgDl);
                if(std::isfinite(center)&&std::isfinite(lower)&&
                   std::isfinite(upper)) {
                    point.medianMgDl=static_cast<float>(center);
                    point.lowMgDl=static_cast<float>(
                            std::min({center,lower,upper}));
                    point.highMgDl=static_cast<float>(
                            std::max({center,lower,upper}));
                    }
                }
            }
        }
    CURVELOGGER("displaycurve histlen=%d doclamp=%d starttime=%u\n",histlen,doclamp,starttime2);
    delete[] scanranges;
    scanranges=new pair<const ScanData *,const ScanData*> [histlen];
    delete[] pollranges;
    pollranges=new pair<const ScanData *,const ScanData*> [histlen];
    delete[] histpositions;
    histpositions=new std::remove_reference_t<decltype(histpositions[0])>[histlen];
#ifdef SI5MIN
   bool sibionics[histlen];
#endif
    CURVELOGAR("before getranges");
#ifdef NOCUTOFF
   if(histlen)
      nocutoff=false;
#endif
//   int  maxStreamels=0;
        hascalibrations=false;
    for(int i=histlen-1;i>=0;--i) {
        auto his=sensors->getSensorData(hists[i]);
        if(!his)  {
            CURVELOGAR("getSensorData==null");
            sleep(1);
            return 0;
            }
        if(allvalues||his->getinfo()->calis[0].caliNr||his->getinfo()->calis[1].caliNr)
            hascalibrations=true;
        CURVELOGGER("sensor %s\n",his->showsensorname().data());
            //CURVELOGGER("%s\n",his->othershortsensorname()->data());
        std::span<const ScanData>     scan;
        //if(showscans) 
        {
            scan=his->getScandata();
            scanranges[i] =getScanRange(scan.data(),scan.size(),starttime2,endtime) ;
            }
        //if(showstream) 
        {
            scan=his->getPolldata();
            pollranges[i] =getScanRangeRuim(scan.data(),scan.size(),starttime2,endtime) ;

            }

      const auto senso=his;
      if((showcalibratedhistories||showhistories)&&senso->hasHistory())
            histpositions[i]= histPositions(his, starttime2,  endtime); 
       else
            histpositions[i]= {0,0}; 
         }
    std::unique_ptr<ScanData []> calibratedStream[histlen];
    std::pair<const ScanData*,const ScanData*> caliStreamSpans[histlen];
    if(showcalibratedstream)   {
        typedef decltype(make_calibrator<ScanData>( (const SensorGlucoseData*)nullptr)) CaliType;
        auto califunc=settings->data()->CalibratePast?(&CaliType::makecalibratedback):(&CaliType::makecalibrated);
        for(int i=histlen-1;i>=0;i--) {
            const int index= hists[i];
            const auto *sens=sensors->getSensorData(index);
            if(const int nr=pollranges[i].second-pollranges[i].first;nr>0) {
                LOGGER("pollranges nr=%d\n",nr);
                ScanData*calidata=new ScanData[nr];
                calibratedStream[i].reset(calidata);
                auto cali=make_calibrator<ScanData>(sens);
                caliStreamSpans[i]=(cali.*califunc)(pollranges[i].first,calidata,nr,allvalues);
                }
             else {
                caliStreamSpans[i]={};
                }
            }
         }
     else {
        for(auto &el:caliStreamSpans) {
                el={};
                }
        }
    std::unique_ptr<ScanData []> calibratedScans[histlen];
    std::pair<const ScanData*,const ScanData*> caliScansSpans[histlen];
    if(showcalibratedscans)   {
        typedef decltype(make_calibrator<ScanData>( (const SensorGlucoseData*)nullptr)) CaliType;
        auto califunc=settings->data()->CalibratePast?(&CaliType::makecalibratedback):(&CaliType::makecalibrated);
        for(int i=histlen-1;i>=0;i--) {
            const int index= hists[i];
            const auto *sens=sensors->getSensorData(index);
            if(const int nr=scanranges[i].second-scanranges[i].first;nr>0) {
                ScanData*calidata=new ScanData[nr];
                calibratedScans[i].reset(calidata);
                auto cali=make_calibrator<ScanData>(sens);
                caliScansSpans[i]=(cali.*califunc)(scanranges[i].first,calidata,nr,allvalues);
                }
             else {
                caliScansSpans[i]={};
                }
            }
         }
     else {
        for(auto &el:caliScansSpans) {
                el={};
                }
        }
    CURVELOGGER("Before numdatas[i]->getInRange(%u,%u)\n",starttime2,endtime);
    for(int i=0;i< numdatas.size();i++) 
        extrums[i]=numdatas[i]->getInRange(starttime2, endtime) ;

    const pair<const ScanData *,const ScanData*> *scanpoll[]= {scanranges,pollranges,caliStreamSpans,caliScansSpans};
    CURVELOGAR("Before getextremes");
    // Keep preview eligibility tied to the glucose renderer itself as a
    // second safety net: even if the Java state update is one frame late,
    // preview data can never cover real glucose samples in this time window.
    const auto glucoseextr=getextremes(hists,scanpoll,std::size(scanpoll),histpositions);
    const bool hasrealglucose=glucoseextr.second>0&&glucoseextr.first<=glucoseextr.second;
    const uint32_t futureStart=std::max<uint32_t>(
            starttime2,static_cast<uint32_t>(nu));
    const bool hasVisibleForecast=std::any_of(
            forecastSnapshot.points.begin(),forecastSnapshot.points.end(),
            [&](const forecastgraph::Point &point) {
                return point.time>=futureStart&&point.time<=endtime;
            });
    const bool showpreview=modernnormal&&graphpreview&&!hasrealglucose&&
                           !hasVisibleForecast;
    if(!graphPanYRangeLocked&&(setend<starttime2||settime>=endtime)) {
       auto extr=glucoseextr;
       bool hasForecastExtremes=false;
       int forecastMinimum=6000;
       int forecastMaximum=0;
       for(const auto &point:forecastSnapshot.points) {
           if(point.time<futureStart||point.time>endtime)
               continue;
           const float center=std::clamp(point.medianMgDl,20.0f,600.0f);
           const int low=static_cast<int>(std::lround(
                   std::min(center,forecastgraph::bounded_low(point))*10.0f));
           const int high=static_cast<int>(std::lround(
                   std::max(center,forecastgraph::bounded_high(point))*10.0f));
           forecastMinimum=std::min(forecastMinimum,low);
           forecastMaximum=std::max(forecastMaximum,high);
           hasForecastExtremes=true;
           }
       if(hasForecastExtremes) {
           if(hasrealglucose) {
               extr.first=std::min(extr.first,forecastMinimum);
               extr.second=std::max(extr.second,forecastMaximum);
               }
           else {
               extr={forecastMinimum,forecastMaximum};
               }
           }
       if(showpreview) {
           const int low=settings->targetlow();
           const int high=settings->targethigh();
           const int targetspan=std::max(high-low,180);
           extr={std::max(0,low-targetspan/3),high+targetspan/3};
           }
       for(int i=0;i<numdatas.size();i++)  {
            CURVELOGGER("%d before extremenums \n",i);
            extr  = numdatas[i]->extremenums(*this,extr);
            }
       setextremes(extr) ;
       }
    CURVELOGAR("before gettrans");
    int  gmax = gmin+grange;
    const auto [transx,transy]= gettrans(starttime2, endtime);
displaytime disp=getdisplaytime(nu,starttime2,endtime, transx);
    const float dlast=nu<endtime?transx(disp.last):dleft+dwidth;
    CURVELOGAR("before showsaverange");
    showsaverange(avg,dlast,transy(settings->targetlow()),transy(settings->targethigh()));
    drawforecastactivities(avg,static_cast<uint32_t>(nu),starttime2,endtime,
                           forecastSnapshot.activities);

    nvgFontSize(avg, smallsize);
    CURVELOGAR("before showNums");
    const int catnr=settings->getlabelcount();

    if(!modernnormal)
        showdates(avg,nu,starttime2,endtime) ;

    int nupos=transx(nu); 
    timelines(avg,&disp,  transx,nu);
    if(!modernnormal&&disp.tstep>(60*60))
        epochlines(avg,starttime2,endtime<nu?endtime:disp.last,transx);
    glucoselines(avg,dlast,smallfontlineheight,gmax,transy,(starttime2+duration/3)<nu) ;

    forecastgraph::Point forecastActualAnchor{};
    bool hasForecastActualAnchor=false;
    for(const int sensorIndex:hists) {
        const SensorGlucoseData *sensor=sensors->getSensorData(sensorIndex);
        if(!sensor)
            continue;
        auto forecastCalibrator=make_calibrator<ScanData>(sensor);
        const auto polls=sensor->getPolldata();
        for(auto iterator=polls.rbegin();iterator!=polls.rend();++iterator) {
            if(!iterator->t||iterator->t>static_cast<uint32_t>(nu)||
               !iterator->valid(0))
                continue;
            if(!hasForecastActualAnchor||
               iterator->t>forecastActualAnchor.time) {
                float mgdl=static_cast<float>(iterator->g);
                if(calibrateForecastDisplay) {
                    const double calibrated=forecastCalibrator.calibrateNow(
                            *iterator);
                    if(std::isfinite(calibrated))
                        mgdl=static_cast<float>(calibrated);
                    }
                forecastActualAnchor={iterator->t,mgdl,mgdl,mgdl};
                hasForecastActualAnchor=true;
                }
            break;
            }
        }
    drawforecast(avg,static_cast<uint32_t>(nu),starttime2,endtime,
                 forecastSnapshot.points,forecastSnapshot.confidence,
                 hasForecastActualAnchor?&forecastActualAnchor:nullptr);

    if(showpreview) {
        constexpr int previewcount=57;
        struct previewpoint { float x; float y; float value; };
        previewpoint points[previewcount];
        const float low=settings->targetlow();
        const float high=settings->targethigh();
        const float targetspan=std::max(high-low,180.0f);
        const float center=(low+high)*.5f;
        const uint32_t visibleend=std::min<uint32_t>(endtime,nu>60?nu-60:nu);
        const uint32_t previews=static_cast<uint32_t>(duration*.90f);
        const uint32_t visiblebegin=visibleend>previews?visibleend-previews:starttime2;
        for(int i=0;i<previewcount;i++) {
            const float phase=static_cast<float>(i)/(previewcount-1);
            const float tail=phase>.84f?(1.0f-phase)/.16f:1.0f;
            const float highdistance=(phase-.22f)/.085f;
            const float lowdistance=(phase-.61f)/.075f;
            const float highbump=expf(-(highdistance*highdistance));
            const float lowdip=expf(-(lowdistance*lowdistance));
            // A calm, plausible day shape is easier to read than a decorative
            // wave while still demonstrating every configured range state.
            float value=center+tail*targetspan*(.070f*sinf(i*.39f)+
                                                .035f*sinf(i*.13f));
            value+=targetspan*.68f*highbump;
            value-=targetspan*.72f*lowdip;
            const uint32_t tim=visiblebegin+static_cast<uint32_t>((visibleend-visiblebegin)*phase);
            points[i]={static_cast<float>(transx(tim)),
                       static_cast<float>(transy(static_cast<uint32_t>(std::max(value,1.0f)))),
                       value};
            }
        const auto statecolor=[&](float value,bool glow)->const NVGcolor & {
            return modernGraphStateColor(
                    graphpoints::range_state(value,low,high),glow);
            };
        const auto drawpreview=[&](bool glow) {
            nvgStrokeWidth(avg,glow?pollCurveStrokeWidth+density*3.2f:
                                    pollCurveStrokeWidth+density*.35f);
            for(int i=1;i<previewcount;i++) {
                const previewpoint &a=points[i-1],&b=points[i];
                float stops[4]={0.0f,1.0f,0.0f,0.0f};
                int stopcount=2;
                const float delta=b.value-a.value;
                if(delta!=0.0f) {
                    for(const float threshold:{low,high}) {
                        const float crossing=(threshold-a.value)/delta;
                        if(crossing>0.0f&&crossing<1.0f)
                            stops[stopcount++]=crossing;
                        }
                    }
                std::sort(stops,stops+stopcount);
                for(int part=0;part<stopcount-1;part++) {
                    const float from=stops[part],to=stops[part+1];
                    nvgBeginPath(avg);
                    nvgMoveTo(avg,a.x+(b.x-a.x)*from,a.y+(b.y-a.y)*from);
                    nvgLineTo(avg,a.x+(b.x-a.x)*to,a.y+(b.y-a.y)*to);
                    nvgStrokeColor(avg,statecolor(a.value+delta*(from+to)*.5f,glow));
                    nvgStroke(avg);
                    }
                }
            };
        nvgSave(avg);
        nvgScissor(avg,dleft,dtop,dwidth,dheight);
        nvgLineCap(avg,NVG_ROUND);
        nvgLineJoin(avg,NVG_ROUND);
        drawpreview(true);
        drawpreview(false);

        const float sampleradius=graphpoints::sample_radius(density,duration,false);
        const float minspacing=graphpoints::minimum_spacing(density,duration);
        bool hasdrawn=false;
        float lastdrawx=0.0f;
        graphpoints::RangeState previousstate=graphpoints::RangeState::in_range;
        for(int i=0;i<previewcount;i++) {
            const graphpoints::RangeState state=
                    graphpoints::range_state(points[i].value,low,high);
            const bool statechanged=i>0&&state!=previousstate;
            if(graphpoints::should_draw_sample(
                    points[i].x,lastdrawx,minspacing,hasdrawn,i==0,
                    i==previewcount-1,statechanged,true)) {
                drawModernGraphSample(avg,points[i].x,points[i].y,
                                      sampleradius,state);
                lastdrawx=points[i].x;
                hasdrawn=true;
                }
            previousstate=state;
            }

        const previewpoint &last=points[previewcount-1];
        nvgBeginPath(avg);
        nvgCircle(avg,last.x,last.y,pointRadius*2.15f);
        nvgFillColor(avg,statecolor(last.value,true));
        nvgFill(avg);
        nvgBeginPath(avg);
        nvgCircle(avg,last.x,last.y,pointRadius*1.05f);
        nvgFillColor(avg,statecolor(last.value,false));
        nvgFill(avg);
        nvgBeginPath(avg);
        nvgCircle(avg,last.x,last.y,pointRadius*.38f);
        nvgFillColor(avg,modernGraphSurface);
        nvgFill(avg);

        char previewvalue[32];
        const char *previewunit=glunit==1?"mmol/L":"mg/dL";
        const int previewvaluelen=snprintf(previewvalue,sizeof(previewvalue),
                glunit==1?"%.1f  %s":"%.0f  %s",
                ::gconvert(static_cast<uint32_t>(std::max(last.value,1.0f)),glunit),
                previewunit);
        nvgFontSize(avg,smallsize*.82f);
        nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
        float valuebounds[4];
        nvgTextBounds(avg,0,0,previewvalue,previewvalue+previewvaluelen,valuebounds);
        const float valuew=valuebounds[2]-valuebounds[0]+density*16.0f;
        const float valueh=std::max(smallfontlineheight*.92f,density*26.0f);
        const float valuex=std::max(dleft+density*6.0f,last.x-valuew-density*10.0f);
        const float valuey=std::max(dtop+density*6.0f,
                std::min(last.y-valueh*.5f,dtop+dheight-valueh-density*6.0f));
        nvgBeginPath(avg);
        nvgRoundedRect(avg,valuex,valuey,valuew,valueh,density*7.0f);
        nvgFillColor(avg,nvgRGBA(24,27,30,246));
        nvgFill(avg);
        nvgStrokeWidth(avg,std::max(density*.8f,.8f));
        nvgStrokeColor(avg,statecolor(last.value,false));
        nvgStroke(avg);
        nvgFillColor(avg,nvgRGBA(238,241,244,255));
        nvgText(avg,valuex+density*8.0f,valuey+valueh*.52f,
                previewvalue,previewvalue+previewvaluelen);

        const char previewlabel[]="DEMO  /  PREVIEW";
        nvgFontSize(avg,smallsize*.76f);
        nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
        float bounds[4];
        nvgTextBounds(avg,0,0,previewlabel,nullptr,bounds);
        const float labelw=bounds[2]-bounds[0]+density*16.0f;
        const float labelh=std::max(smallfontlineheight*.82f,density*22.0f);
        const float labelx=dleft+density*8.0f;
        const float labely=dtop+density*8.0f;
        nvgBeginPath(avg);
        nvgRoundedRect(avg,labelx,labely,labelw,labelh,labelh*.5f);
        nvgFillColor(avg,nvgRGBA(31,35,39,238));
        nvgFill(avg);
        nvgStrokeWidth(avg,std::max(density*.7f,.7f));
        nvgStrokeColor(avg,modernGraphGridStrong);
        nvgStroke(avg);
        nvgFillColor(avg,modernGraphText);
        nvgText(avg,labelx+density*8.0f,labely+labelh*.52f,previewlabel,nullptr);
        nvgRestore(avg);
        }

//        nvgCircle(avg, posx,posy,foundPointRadius);

    CURVELOGAR("before showhistories");
    const int colorsleft=nrcolors-catnr;
    const auto segcolor=[catnr,colorsleft,colorseg=colorsleft/4](int index,int seg) {
         return catnr+(index+colorseg*seg)%colorsleft;
         };
    if(showhistories) {
        nvgStrokeWidth(avg, historyStrokeWidth);
        for(int i=histlen-1;i>=0;i--) {
            int index= hists[i];
            int colorindex=segcolor(index,2);
             histcurve(avg,sensors->getSensorData(index), histpositions[i].first, histpositions[i].second,transx,transy,colorindex); 
             }
        }

    if(showcalibratedhistories) {
        nvgStrokeWidth(avg, historyStrokeWidth);
        for(int i=histlen-1;i>=0;i--) {
            int index= hists[i];
            int colorindex=segcolor(index,4);
             calihistcurve(avg,sensors->getSensorData(index), histpositions[i].first, histpositions[i].second,transx,transy,colorindex); 
             }
        }
    CURVELOGGER("before showcalibratedstream %d\n",showcalibratedstream);
    if(showcalibratedstream)   {
        nvgStrokeWidth(avg, pollCurveStrokeWidth);
        for(int i=histlen-1;i>=0;i--) {
            const auto cali=caliStreamSpans[i];
            if(cali.second>cali.first) {
                 const int index= hists[i];
                 const int colorindex=segcolor(index,3);

#ifdef DOESSEARCH
                bool search=calibratedStreamsearchtype==(calibratedStreamsearchtype&searchdata.type);
            #else 
                bool search=false;
            #endif
                showlineScan(avg,cali.first,cali.second,transx,transy,colorindex,search);
                }
            }
        }
    CURVELOGGER("before showstream %d\n",showstream);
    if(showstream)   {
        nvgStrokeWidth(avg, pollCurveStrokeWidth);
        for(int i=histlen-1;i>=0;i--) {
            const int index= hists[i];
            int colorindex=segcolor(index,0);
#ifdef DOESSEARCH
                bool search=streamsearchtype==(streamsearchtype&searchdata.type);
            #else 
                bool search=false;
            #endif
            showlineScan(avg,pollranges[i].first,pollranges[i].second,transx,transy,colorindex,search);
             }
        }
    CURVELOGGER("before showcalibratedscans %d\n",showcalibratedscans);
    if(showcalibratedscans)   {
        nvgStrokeWidth(avg, pollCurveStrokeWidth);
        for(int i=histlen-1;i>=0;i--) {
            const auto cali=caliScansSpans[i];
            if(cali.second>cali.first) {
                const int index= hists[i];
                const int colorindex=segcolor(index,5);
                showScan(avg,cali.first,cali.second,transx,transy,colorindex);
                }
            }
        }
    CURVELOGGER("before showscans %d\n",showscans);
    if(showscans) {
        for(int i=histlen-1;i>=0;i--) {
            const int index=hists[i];
            int colorindex=segcolor(index,1);
            showScan(avg,scanranges[i].first,scanranges[i].second,transx,transy,colorindex);
            }
         }

    CURVELOGGER("before showsnums catnr=%d\n",catnr);
    if(catnr>0&&(shownumbers||showmeals))  {
        bool was[catnr];
        memset(was,'\0',sizeof(was));
        for(auto el:numdatas) 
            el->showNums(*this, transx,  transy,was) ;
        }

    // Build the event anchor surface from the glucose layers that are actually
    // visible in this frame. Later-rendered layers replace an equal timestamp,
    // matching what the user sees when stream/scan sources overlap.
    std::vector<intakemarkers::GlucosePoint> intakeGlucosePoints;
    const auto appendScanPoints=[&](const std::pair<const ScanData*,
                                     const ScanData*> &range) {
        if(!range.first||!range.second)
            return;
        for(const ScanData *point=range.first;point<range.second;++point) {
            if(!point->valid())
                continue;
            const float y=transy(point->g*10);
            if(std::isfinite(y))
                intakeGlucosePoints.push_back({point->t,y});
        }
    };
    if(showhistories||showcalibratedhistories) {
        for(int i=histlen-1;i>=0;--i) {
            const SensorGlucoseData *sensor=sensors->getSensorData(hists[i]);
            if(!sensor)
                continue;
            for(int32_t position=histpositions[i].first;
                position<=histpositions[i].second;++position) {
                const Glucose *point=sensor->getglucose(position);
                if(!point||!point->valid())
                    continue;
                const float y=transy(point->getsputnik());
                if(std::isfinite(y))
                    intakeGlucosePoints.push_back({point->gettime(),y});
            }
        }
    }
    if(showcalibratedstream) {
        for(int i=histlen-1;i>=0;--i)
            appendScanPoints(caliStreamSpans[i]);
    }
    if(showstream) {
        for(int i=histlen-1;i>=0;--i)
            appendScanPoints(pollranges[i]);
    }
    if(showcalibratedscans) {
        for(int i=histlen-1;i>=0;--i)
            appendScanPoints(caliScansSpans[i]);
    }
    if(showscans) {
        for(int i=histlen-1;i>=0;--i)
            appendScanPoints(scanranges[i]);
    }
    std::stable_sort(intakeGlucosePoints.begin(),intakeGlucosePoints.end(),
            [](const auto &left,const auto &right) {
                return left.time<right.time;
            });
    std::vector<intakemarkers::GlucosePoint> uniqueGlucosePoints;
    uniqueGlucosePoints.reserve(intakeGlucosePoints.size());
    for(const auto &point:intakeGlucosePoints) {
        if(!uniqueGlucosePoints.empty()&&
           uniqueGlucosePoints.back().time==point.time) {
            uniqueGlucosePoints.back()=point;
        }
        else {
            uniqueGlucosePoints.push_back(point);
        }
    }

    // Backend-owned records stay transient in native code. Their marker dot is
    // now attached to the interpolated CGM Y at the event time; only the small
    // readable chip is offset from the line, and dense chips are clustered.
    drawintakeevents(avg,starttime2,endtime,uniqueGlucosePoints);

    if(nu<endtime&&(dwidth-smallfontlineheight)>nupos) {
        showbluevalue(avg,nu, nupos,usedsensors);
        CURVELOGAR("end display curve value");
        }
    else  {
        CURVELOGAR("end display no value");
#ifndef DONTTALK
        shownglucose.resize(0);
#endif
        }

    if(modernnormal&&graphselectionactive)
        drawgraphselection(avg,transx(graphselectiontime),
                           transy(graphselectionvalue));

#ifdef JUGGLUCO_APP
    if(hidden.size()) {
        hasHidden=true;
        showHideButton(avg);
        }
    else
       hasHidden=false;
#endif
 return 0;
}


extern void mkheights() ;

//__attribute__((__visibility__("default"))) extern bool skipdisplay;
//bool skipdisplay=true;

//#define WEAROS


//static void shownumlist(NVGcontext* avg);




       #include <unistd.h>
          #include <sys/types.h>
       #include <sys/stat.h>
       #include <fcntl.h>

#include <string.h>



extern int *numheights;
int *numheights=nullptr;
void mkheights() {
    if(!settings)
        return;
    CURVELOGAR("mkheights() ");
    const int maxl= settings->getlabelcount();
    delete[] numheights;
    numheights=new int[maxl];
    int nr=0;
    for(int i=0;i<maxl;i++) {
        if(settings->getlabelweightmgperL(i)==0.0f) {
            numheights[i]=nr++;
            }
        else
            numheights[i]=-1;
        }
    shownlabels=nr;
    }
#include "net/backup.hpp"
#include "datbackup.hpp"
/*
extern void setuseit();
extern void setusenl();
extern void setusesv();
extern void setuseru() ;
extern void setusees();

extern void setusepl();
extern void setusede();

extern void setusezh() ;
extern void setuseuk() ;
extern void setusebe();
extern void setusefr();

extern void setusept() ;
extern void setuseiw() ;
extern void setuseeng() ;
extern void setusetr();
*/
extern std::string_view localestr;
extern bool hour24clock;
char localestrbuf[10]="en";
std::string_view localestr;
bool hour24clock=true;

#define mklanguagenum2(a,b) a|b<<8
#define mklanguagenum(lang) mklanguagenum2(lang[0],lang[1])
/*
bool chinese() {
    const int16_t lannum=mklanguagenum(localestrbuf);
    switch(lannum) {
        case mklanguagenum("ZH"):
        case mklanguagenum("zh"):
        return true;
        }
    return false;
    }
*/
#ifdef USE_HEBREW
bool hebrew() {
    const int16_t lannum=mklanguagenum(localestrbuf);
    switch(lannum) {
        case mklanguagenum("IW"):
        case mklanguagenum("iw"):
        return true;
        }
    return false;
    }
#endif

#include "destruct.hpp"
extern void      removemenus(const jugglucotext* text);
void     JCurve::setlocale(NVGcontext* avg,const char *localestrbuf,const size_t len,int sdk) {
    CURVELOGGER("locale=%s\n",localestrbuf);
    localestr={localestrbuf,len};
    uint16_t langid=mklanguagenumlow(localestrbuf);
    auto *text=language::gettext(langid);
#ifdef JUGGLUCO_APP
    #ifndef WEAROS
    if(sdk<24)
          removemenus(text);
    #endif
#endif
    ::usedtext=usedtext=text;
    switch(langid) {
#ifdef USE_HEBREW
        case mklanguagenum("IW"):
        case mklanguagenum("iw"):
            if(chfontset!=HEBREW) {
                initfont(avg);
                }
            return;
#endif
        case mklanguagenum("AR"):
        case mklanguagenum("ar"):
            if(chfontset!=ARABIC) {
                initfont(avg);
                }
            return; 
        case mklanguagenum("ZH"):
        case mklanguagenum("zh"):
        case mklanguagenum("JA"):
        case mklanguagenum("ja"):
            if(chfontset!=CJK) {
                initfont(avg);
                }
            return;
        };
    if(chfontset!=REST) {
        initfont(avg);
         } 
    }


void  JCurve::calccurvegegs() {
    CURVELOGAR("start calccurvegegs");
    mkheights(); 
    starttime=maxtime()-4*duration/5;
    setusedsensors();
    CURVELOGAR("end calccurvegegs");
    }













#include "numhit.hpp"
extern NumHit newhit;
extern Num newnum;

extern Numdata *getherenums();
Numdata *getherenums() {
    return newhit.numdisplay;
    }
Num newnum;
#include "numhit.hpp"
NumHit newhit={nullptr,&newnum};
#include <assert.h>
int64_t openNums(std::string_view numpath,int64_t ident) {
     const int index=numdatas.size();
     assert(index<maxnumsources);
     NumDisplay* numdata=NumDisplay::getnumdisplay(index, numpath,ident,nummmaplen);
     if(numdata) {
        numdatas.push_back(numdata);
        if(ident==0LL)
            newhit.numdisplay=numdata;
        
        }
    
    CURVELOGGER("index=%d numdir=%s ptr=%p\n",index,numpath.data(),numdata);
    return reinterpret_cast<int64_t>(numdata);
    }

#ifdef WEAROS
#define hourtext "00:00                        "
#else
#define hourtext "00:00                 "
#endif
char hourminstr[hourminstrlen]=hourtext;

void    JCurve::startstepNVG(NVGcontext* avg,int width, int height) {
        nvgBeginFrame(avg, width, height, 1.0);
        const int font=modernnormal?whitefont:(invertcolors?whitefont:blackfont);
        this->font=font;
        nvgFontFaceId(avg,font);
        nvgLineCap(avg, NVG_ROUND);
         nvgLineJoin(avg, NVG_ROUND);
         }


 void    JCurve::showlastsstream(NVGcontext* avg,const time_t nu,const float getx,std::vector<int> &used ) {
//CURVELOGGER("showlaststream %d\n",used.size());
    const auto usedsize=used.size();
#ifdef JUGGLUCO_APP
    int success=false;
    bool neterror=false,usebluetoothoff=false,bluetoothoff=false,otherproblem=false;
    int blueperm=2;
    static int failures=0;
    ++failures;

#ifndef DONTTALK
    shownglucose.resize(usedsize);
    #endif
#endif

    for(int i=0;i<usedsize;i++) {
#ifdef JUGGLUCO_APP
#ifndef DONTTALK
        shownglucose[i].glucosevaluex=-1;
#endif
#endif

        const int sensorindex=used[i];
        SensorGlucoseData *hist=sensors->getSensorData(sensorindex);
        int yh=i*2+1;
#ifdef WEAROS
        float gety=smallsize*.5f+dtop+dheight*yh/(usedsize*2.0f);
#else
        float gety=smallsize*1.4f+dtop+(dheight-smallsize*.8f)*yh/(usedsize*2.0f);
#endif

        uint8_t manualwarmup=hist->getinfo()->manualwarmup;
        if(manualwarmup) {
            time_t starttime=hist->getstarttime();
            int waited=nu-starttime;

            if(waited<(manualwarmup*60)) {
                float usegetx=getx-headsize/3;
                static char buf[256];
                const char *bufptr=buf;
                int waitedminutes=waited/60;
                int ends=sprintf(buf,usedtext->readysec.data(),manualwarmup-waitedminutes);
                 nvgTextBox(avg,  usegetx, gety, getboxwidth(usegetx), bufptr,bufptr+ends);
    #ifndef DONTTALK
                 shownglucose[i].errortext=bufptr;
                 shownglucose[i].glucosevalue=0;
                 shownglucose[i].glucosevaluex=usegetx;
                 shownglucose[i].glucosevaluey=gety+headsize*.5;
    #endif
                continue; 
                 }
                 
            }

        const ScanData *poll=hist->lastValidStream();
        if(poll) {
            CURVELOGAR("poll!=null");
            int age=nu-poll->t;
            if(age<maxbluetoothage) {
                CURVELOGAR("age<maxbluetoothage");
#ifdef JUGGLUCO_APP
                failures=0;
#endif
                nvgBeginPath(avg);
                 nvgFillColor(avg,modernnormal?modernGraphMuted:getoldcolor());
                float relage=(float)age/(float)maxbluetoothage;
                float sensory= gety+headsize/3.1f;
                nvgRect(avg, getx+sensorbounds.left, sensorbounds.top+sensory, relage*sensorbounds.width, sensorbounds.height);
                nvgFill(avg);
                showvalue(avg,poll,hist,getx,gety,i,nu);
#ifdef JUGGLUCO_APP
                success=true;
                if(hist->isLibre2()) {
                     if(settings->data()->libreIsViewed&&!hist->getinfo()->libreviewsendall) {
#ifdef NOTALLVIES
                        if(poll->t>nexttimeviewed) 
#endif
                        {

                            const int addnum= hist->pollcount()-1;
                            if(hist->viewed.empty()||hist->viewed.back()!=addnum) {
                                hist->viewed.push_back(addnum);

#ifdef NOTALLVIES
                                nexttimeviewed=poll->t+betweenviews;
                                CURVELOGGER("add %d nextime=%s",addnum,ctime(&nexttimeviewed));
#endif
                                }
                            }
                        }
                    }
#endif
                }
#ifdef JUGGLUCO_APP
            else {
                CURVELOGAR("age>=maxbluetoothage");
                switch(showerrorvalue(avg,hist,nu,getx,gety,i)) {
                    case 1: neterror=true;break;
                    case 2: usebluetoothoff=true;break;
                    case 3: 
                    blueperm=bluePermission();
                    bluetoothoff=true;
                    break;
                    default:  {
                        blueperm=bluePermission();
                        if(blueperm>1)
                            otherproblem=true;
                        }
                    };
                CURVELOGAR("After showerrorvalue(hist,nu,getx,gety)) ");
                }
            }
        else {
            CURVELOGAR("poll==null");

#ifndef NEWSIBIONICS
         if(hist->newSI()) {
             const auto eusibinics=usedtext->unsupportedSibionics;
             nvgText(avg,getx ,gety, eusibinics.data(), eusibinics.data()+eusibinics.size());
             otherproblem=true;
            }
       else 
#endif
       {
           time_t starttime=hist->getstarttime();
           auto wait= nu-starttime;
           const int warmup=hist->getWarmupMIN(); 
           blueperm=bluePermission();
           CURVELOGGER("waited=%lu warmup=%d starttime=%lu %s blueperm=%d\n",wait,warmup,starttime,ctime(&starttime),blueperm);
           bool bluescanner=hist->isSibionics()||hist->isDexcom();
           if(bluescanner&&blueperm<2&&!hasnetwork()) { 
                 float usegetx=getx-headsize/3;
                 nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
                 nvgFontSize(avg,headsize/6 );
                 getboxwidth(usegetx);
                  const std::string_view perm=blueperm==1?usedtext->nolocationpermission:usedtext->nonearbydevicespermission;
                 const auto *bufptr=perm.data();
                 const auto ends= perm.size();
                  otherproblem=true;
                 nvgTextBox(avg,  usegetx, gety, getboxwidth(usegetx), bufptr,bufptr+ends);
#ifndef DONTTALK
                 shownglucose[i].errortext=bufptr;
                 shownglucose[i].glucosevalue=0;
                 shownglucose[i].glucosevaluex=usegetx;
                 shownglucose[i].glucosevaluey=gety+headsize*.5;
#endif
                 }
        else {
          if(wait<(warmup*60)&&((blueperm>0&&!settings->data()->nobluetooth&&bluetoothEnabled())||hasnetwork())) {
             float usegetx=getx-headsize/3;
             nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
             nvgFontSize(avg,headsize/6 );
             getboxwidth(usegetx);
             const char *bufptr;
             int ends;
             if((hist->isAccuChek()||hist->isAir()||hist->isSibionics()||hist->isDexcom())&&!hist->sensorerror){
                  const auto siwait=usedtext->waitingforconnection;
                  bufptr=siwait.data();
                  ends=siwait.size();
                }
             else {
                const bool isInitialised=(!hist->isLibre2())||sensors->getsensor(sensorindex)->initialized;
                CURVELOGGER("wait<(%d*60) isInitialised=%d\n",warmup,isInitialised);
                static char buf[256];
                int minutes=warmup-(wait/60);
                ends=sprintf(buf,isInitialised?usedtext->readysec.data():usedtext->readysecEnable.data(),minutes);
                bufptr=buf;
                }
             nvgTextBox(avg,  usegetx, gety, getboxwidth(usegetx), bufptr,bufptr+ends);
#ifndef DONTTALK
             shownglucose[i].errortext=bufptr;
             shownglucose[i].glucosevalue=0;
             shownglucose[i].glucosevaluex=usegetx;
             shownglucose[i].glucosevaluey=gety+headsize*.5;
#endif
             }
           else   {
               CURVELOGAR("age>=maxbluetoothage");
               switch(showerrorvalue(avg,hist,nu,getx,gety,i)) { //TODO: integrate with same above
                   case 1: neterror=true;break;
                   case 2: usebluetoothoff=true;break;
                   case 3: bluetoothoff=true;break;
                   default: otherproblem=true;
                   };
               CURVELOGAR("Afgter showerrorvalue(hist,nu,getx,gety)) ");
               }
             }
            }
            }

        }

    if(!success&&!otherproblem) {
        int i=0;
#ifndef DONTTALK
        shownglucose.resize(1);
#endif
        CURVELOGAR("showlastsstream: !success&&!otherproblem");
        int newgetx=getx-headsize/3;
        nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
        nvgFontSize(avg,headsize/4 );
        float gety=smallsize*.5f+dtop+dheight/2.0f;
        if(neterror) {
//            nvgText(avg,newgetx ,gety, usedtext->networkproblem.begin(), usedtext->networkproblem.end());
             nvgTextBox(avg,  newgetx, gety, getboxwidth(newgetx), usedtext->networkproblem.begin(), usedtext->networkproblem.end());
#ifndef DONTTALK
             shownglucose[i].glucosevalue=0;
             shownglucose[i].glucosevaluex=newgetx;
             shownglucose[i].glucosevaluey=gety+headsize*.5;
             shownglucose[i].errortext=usedtext->networkproblem.data();
#endif
            }
        else { 
             if(usebluetoothoff) {
                CURVELOGAR("showlastsstream: usebluetoothoff");
                nvgTextBox(avg,newgetx ,gety, getboxwidth(newgetx),usedtext->useBluetoothOff.begin(), usedtext->useBluetoothOff.end());
#ifndef DONTTALK
                shownglucose[i].glucosevalue=0;
                shownglucose[i].glucosevaluex=newgetx;
                shownglucose[i].glucosevaluey=gety+headsize*.5;
                shownglucose[i].errortext=usedtext->useBluetoothOff.data();
#endif
           }
           else {
             CURVELOGGER("blueperm=%d\n",blueperm);
                if(blueperm<1) { 
                    const std::string_view perm=blueperm==1?usedtext->nolocationpermission:usedtext->nonearbydevicespermission;
                    nvgTextBox(avg,newgetx ,gety, getboxwidth(newgetx),perm.begin(), perm.end());
#ifndef DONTTALK
                    shownglucose[i].glucosevalue=0;
                    shownglucose[i].glucosevaluex=newgetx;
                    shownglucose[i].glucosevaluey=gety+headsize*.5;
                    shownglucose[i].errortext=perm.data();
        #endif
                    }
                else {
                   if(bluetoothoff) {
                        nvgTextBox(avg,newgetx ,gety, getboxwidth(newgetx),usedtext->enablebluetooth.begin(), usedtext->enablebluetooth.end());
#ifndef DONTTALK
                        shownglucose[i].glucosevalue=0;
                        shownglucose[i].glucosevaluex=newgetx;
                        shownglucose[i].glucosevaluey=gety+headsize*.5;
                        shownglucose[i].errortext=usedtext->enablebluetooth.data();
#endif
                        }
                    }
                }
                }
        }
    if(failures>2) {
        CURVELOGAR("failures>3" );
        for(int i=0;i<used.size();i++) {
            if(SensorGlucoseData *hist=sensors->getSensorData(used[i])) {
                CURVELOGAR("set waiting=true");
            hist->waiting=true;
                }
            }
        }
#else
        }
    }
#endif

    CURVELOGAR(" end showlastsstream");
    }
/*
int    JCurve::showLargevalue(NVGcontext* avg, int index,float getx,float gety,float convglucose,const ScanData *poll) {
        constexpr const int maxhead=11;
        char head[maxhead];
#ifdef JUGGLUCO_APP
#ifndef DONTTALK
        shownglucose[index].glucosevalue=convglucose;
        shownglucose[index].glucosetrend=poll->tr;
#endif
#endif
         float valuex=getx-(convglucose>=10.0f?density*20.0f:0.0f);
         char *value=head+1;
         int gllen=snprintf(value,maxhead-1,gformat,convglucose);
         if(gllen<3) {
            value=head;
            *value=' ';
            ++gllen;
            }
        nvgText(avg,valuex ,gety, value, value+gllen);
        const float rate=poll->ch;
        drawarrow(avg,rate,valuex-10*density,gety);
        return valuex;
        }
*/
 void    JCurve::showvalue(NVGcontext* avg, const ScanData *poll,const SensorGlucoseData *hist, float getx,float gety,int index,uint32_t nu) {
    const auto sensorname=hist->othershortsensorname();
    CURVELOGGER("showvalue %s\n",sensorname.data());
    float sensory= gety+headsize/3.1;
    nvgFillColor(avg, *getblack());
    nvgFontSize(avg,mediumfont );
    nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_TOP);
    nvgText(avg, getx,sensory, sensorname.begin(), sensorname.end());
    constexpr const int maxhead=11;
    char head[maxhead];

    nvgTextAlign(avg,NVG_ALIGN_LEFT|NVG_ALIGN_MIDDLE);
    const int nonconvert=poll->getmgdL();
  //  Calibrator<ScanData> cali(hist);
   auto cali=make_calibrator<ScanData>(hist);
    double calibrated=cali.calibrateNow(*poll);
    nvgFontSize(avg, headsize*.8);
#ifdef JUGGLUCO_APP
#ifndef DONTTALK
    shownglucose[index].glucosevaluex=getx;
    shownglucose[index].glucosevaluey=sensory;
#endif
#endif
    const int  glucoselowest=hist->getminmgdL();
    if(isnan(calibrated)) {
        if(nonconvert<glucoselowest) {
            const  float valuex=getx;
            int gllen=mkshowlow(head, maxhead,glucoselowest) ;
            nvgText(avg,valuex,gety, head, head+gllen);
            return;
            }
        else {
            int glucosehighest=hist->getmaxmgdL();
            if(nonconvert>glucosehighest) {
                float valuex=getx-density*14.0f;
                int gllen=mkshowhigh(head, maxhead,glucosehighest) ;
                nvgText(avg,valuex ,gety, head, head+gllen);
                return;
                }
              }
           calibrated=nonconvert;
           }
        const float convglucose= gconvert(calibrated*10.0);
    #ifdef JUGGLUCO_APP
    #ifndef DONTTALK
        shownglucose[index].glucosevalue=convglucose;
        shownglucose[index].glucosetrend=poll->tr;
#endif
#endif
         // The Java hero already renders a valid numeric reading and trend.
         // Keep the sensor label and the LO/HI branches above visible so a
         // modern graph never suppresses native diagnostic state.
         if(modernnormal)
             return;
         float valuex=getx-(convglucose>=10.0f?density*20.0f:0.0f);
         char *value=head+1;
         int gllen=snprintf(value,maxhead-1,gformat,convglucose);
         if(gllen<3) {
            value=head;
            *value=' ';
            ++gllen;
            }
         nvgText(avg,valuex ,gety, value, value+gllen);
        const float rate=poll->ch;
        drawarrow(avg,rate,valuex-10*density,gety);
        if(calibrated!=nonconvert) {
            bounds_t bounds;
            nvgTextBounds(avg, valuex,  gety,value,value+gllen, bounds.array);
              nvgFontSize(avg,mediumfont );
                float nextx=valuex+bounds.xmax-bounds.xmin+density*6;
                if(nonconvert<glucoselowest) {
                    int gllen=mkshowlow(head, maxhead,glucoselowest) ;
                    nvgText(avg,nextx,gety, head, head+gllen);
                    }
                else {
                    int glucosehighest=hist->getmaxmgdL();
                    if(nonconvert>glucosehighest) {
                        int gllen=mkshowhigh(head, maxhead,glucosehighest) ;
                        nvgText(avg,nextx ,gety, head, head+gllen);
                        }
                    else {
                        const float rawconv=gconvert(nonconvert*10.0);
                        int gllen=snprintf(head,maxhead,gformat,rawconv);
                        nvgText(avg,nextx,gety, head, head+gllen);
                        }
                    }
         }

    }



float                JCurve::getboxwidth(const float x) {
                    return std::max((float)(dwidth-x-smallsize),dwidth*.25f);
                    }
