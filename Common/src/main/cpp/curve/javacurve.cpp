#include <sys/prctl.h>
#include "curve.hpp"
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <utility>
#include <vector>
#include <string_view>
#include <string>
#include "share/logs.hpp"
#include "sensoren.hpp"
#include "fromjava.h"
//#include "curve.hpp"
//#include "nanovg_gl.h"
//#include "nanovg_gl_utils.h"
//#define OLDEVERSENSE
#include "JCurve.hpp"
#include "forecastgraph.hpp"
#include "intakeevents.hpp"

extern Sensoren *sensors;
#define NANOVG_GLES2_IMPLEMENTATION

extern "C" JNIEXPORT jint JNICALL fromjava(openglversion)(JNIEnv* env, jclass obj) {
#ifdef NANOVG_GLES2_IMPLEMENTATION
 return 2;
#else
 return 3;
#endif
}

/*
extern "C" JNIEXPORT void JNICALL fromjava(initopengl)(JNIEnv* env, jclass obj,jboolean started) {
 initopengl(started);
 } */


extern bool alarmongoing;
extern "C" JNIEXPORT jboolean JNICALL fromjava(getisalarm)(JNIEnv* env, jclass obj) {
 return alarmongoing;
 }
extern "C" JNIEXPORT void JNICALL fromjava(setisalarm)(JNIEnv* env, jclass obj,jboolean val) {
 alarmongoing=val;
 }
extern "C" JNIEXPORT jboolean JNICALL fromjava(turnoffalarm)(JNIEnv* env, jclass obj) {
 return alarmongoing;
 }

extern "C" JNIEXPORT void JNICALL fromjava(resize)(JNIEnv* env, jclass obj, jint widthin, jint heightin,jint initscreenwidth) {
 appcurve.resizescreen(widthin,heightin,initscreenwidth);
 }

extern "C" JNIEXPORT void JNICALL fromjava(setmodernui)(JNIEnv* env, jclass obj,jboolean enabled) {
 appcurve.setmodernui(enabled);
 if(enabled) {
     const auto now=time(nullptr);
     appcurve.setLiveWindow(static_cast<uint32_t>(now));
     }
 }

extern "C" JNIEXPORT void JNICALL fromjava(setgraphpreview)(JNIEnv* env, jclass obj,jboolean enabled) {
 appcurve.setgraphpreview(enabled);
 }

extern "C" JNIEXPORT void JNICALL fromjava(setgraphhours)(JNIEnv* env, jclass obj,jint hours) {
 appcurve.setgraphhours(hours);
 const auto now=time(nullptr);
 appcurve.setLiveWindow(static_cast<uint32_t>(now));
 }

extern "C" JNIEXPORT jint JNICALL fromjava(getgraphhours)(JNIEnv* env, jclass obj) {
 return appcurve.getgraphhours();
 }

extern "C" JNIEXPORT void JNICALL fromjava(setTimelineEvents)(JNIEnv* env,
        jclass obj,jintArray keys,jlongArray timesMs,jfloatArray carbs,
        jfloatArray insulin,jintArray flags) {
 if(!keys||!timesMs||!carbs||!insulin||!flags) {
     replaceIntakeTimelineEvents({});
     return;
     }
 const jsize count=std::min({env->GetArrayLength(keys),
                             env->GetArrayLength(timesMs),
                             env->GetArrayLength(carbs),
                             env->GetArrayLength(insulin),
                             env->GetArrayLength(flags),
                             static_cast<jsize>(1000)});
 std::vector<jint> keyValues(count);
 std::vector<jlong> timeValues(count);
 std::vector<jfloat> carbValues(count);
 std::vector<jfloat> insulinValues(count);
 std::vector<jint> flagValues(count);
 if(count>0) {
     env->GetIntArrayRegion(keys,0,count,keyValues.data());
     env->GetLongArrayRegion(timesMs,0,count,timeValues.data());
     env->GetFloatArrayRegion(carbs,0,count,carbValues.data());
     env->GetFloatArrayRegion(insulin,0,count,insulinValues.data());
     env->GetIntArrayRegion(flags,0,count,flagValues.data());
     }
 std::vector<IntakeTimelineEvent> events;
 events.reserve(count);
 for(jsize index=0;index<count;index++) {
     const jlong millis=timeValues[index];
     if(millis<=0)
         continue;
     const auto seconds=static_cast<uint64_t>(millis/1000LL);
     if(seconds>UINT32_MAX)
         continue;
     events.push_back({static_cast<int32_t>(keyValues[index]),
                       static_cast<uint32_t>(seconds),
                       static_cast<float>(carbValues[index]),
                       static_cast<float>(insulinValues[index]),
                       static_cast<uint32_t>(flagValues[index])});
     }
 replaceIntakeTimelineEvents(std::move(events));
 }

extern "C" JNIEXPORT void JNICALL fromjava(setForecast)(JNIEnv* env,
        jclass obj,jlongArray timesMs,jfloatArray median,jfloatArray low,
        jfloatArray high,jfloat confidence) {
 const auto now=static_cast<uint32_t>(time(nullptr));
 const uint32_t oldForecastEnd=forecastGraphEndTime();
 const uint32_t oldLiveStart=forecastgraph::live_start(
         now,static_cast<uint32_t>(appcurve.duration),oldForecastEnd);
 const uint32_t liveTolerance=std::max<uint32_t>(5U*60U,
                         static_cast<uint32_t>(appcurve.duration)/10U);
 const int64_t distance=static_cast<int64_t>(appcurve.starttime)-
                        static_cast<int64_t>(oldLiveStart);
 const bool wasViewingLive=appcurve.doclamp||appcurve.nowclamp||
                           std::llabs(distance)<=liveTolerance;

 if(!timesMs||!median||!low||!high) {
     replaceForecastGraph({},0.0f);
     if(wasViewingLive)
         appcurve.setLiveWindow(now);
     return;
     }
 const jsize count=std::min({env->GetArrayLength(timesMs),
                             env->GetArrayLength(median),
                             env->GetArrayLength(low),
                             env->GetArrayLength(high),
                             static_cast<jsize>(1024)});
 std::vector<jlong> timeValues(count);
 std::vector<jfloat> medianValues(count),lowValues(count),highValues(count);
 if(count>0) {
     env->GetLongArrayRegion(timesMs,0,count,timeValues.data());
     env->GetFloatArrayRegion(median,0,count,medianValues.data());
     env->GetFloatArrayRegion(low,0,count,lowValues.data());
     env->GetFloatArrayRegion(high,0,count,highValues.data());
     }
 std::vector<forecastgraph::Point> points;
 points.reserve(count);
 for(jsize index=0;index<count;++index) {
     const jlong millis=timeValues[index];
     const float center=medianValues[index];
     const float lowValue=lowValues[index];
     const float highValue=highValues[index];
     if(millis<=0||!std::isfinite(center)||!std::isfinite(lowValue)||
        !std::isfinite(highValue)||center<=0.0f)
         continue;
     const uint64_t seconds=static_cast<uint64_t>(millis/1000LL);
     if(seconds>UINT32_MAX)
         continue;
     const float lower=std::min({lowValue,highValue,center});
     const float upper=std::max({lowValue,highValue,center});
     points.push_back({static_cast<uint32_t>(seconds),center,lower,upper});
     }
 const float safeConfidence=std::isfinite(confidence)?confidence:0.0f;
 replaceForecastGraph(std::move(points),safeConfidence);
 if(wasViewingLive)
     appcurve.setLiveWindow(now);
 }

extern "C" JNIEXPORT void JNICALL fromjava(setForecastActivities)(JNIEnv* env,
        jclass obj,jintArray kinds,jlongArray startsMs,jlongArray peaksMs,
        jlongArray endsMs,jfloatArray strengths,jfloatArray confidences) {
 if(!kinds||!startsMs||!peaksMs||!endsMs||!strengths||!confidences) {
     replaceForecastActivities({});
     return;
     }
 const jsize count=std::min({env->GetArrayLength(kinds),
                             env->GetArrayLength(startsMs),
                             env->GetArrayLength(peaksMs),
                             env->GetArrayLength(endsMs),
                             env->GetArrayLength(strengths),
                             env->GetArrayLength(confidences),
                             static_cast<jsize>(1024)});
 std::vector<jint> kindValues(count);
 std::vector<jlong> startValues(count),peakValues(count),endValues(count);
 std::vector<jfloat> strengthValues(count),confidenceValues(count);
 if(count>0) {
     env->GetIntArrayRegion(kinds,0,count,kindValues.data());
     env->GetLongArrayRegion(startsMs,0,count,startValues.data());
     env->GetLongArrayRegion(peaksMs,0,count,peakValues.data());
     env->GetLongArrayRegion(endsMs,0,count,endValues.data());
     env->GetFloatArrayRegion(strengths,0,count,strengthValues.data());
     env->GetFloatArrayRegion(confidences,0,count,confidenceValues.data());
     }
 std::vector<forecastgraph::Activity> activities;
 activities.reserve(count);
 for(jsize index=0;index<count;++index) {
     if(!forecastgraph::valid_kind(kindValues[index])||
        startValues[index]<=0||endValues[index]<=startValues[index])
         continue;
     const uint64_t start=static_cast<uint64_t>(startValues[index]/1000LL);
     const uint64_t end=static_cast<uint64_t>(endValues[index]/1000LL);
     if(start>UINT32_MAX||end>UINT32_MAX)
         continue;
     uint64_t peak=peakValues[index]>0?
                   static_cast<uint64_t>(peakValues[index]/1000LL):start;
     peak=std::clamp<uint64_t>(peak,start,end);
     const float rawStrength=strengthValues[index];
     const float rawConfidence=confidenceValues[index];
     const float safeStrength=std::isfinite(rawStrength)?
                              std::max(0.0f,rawStrength):0.0f;
     const float safeConfidence=std::isfinite(rawConfidence)?
                                forecastgraph::clamp01(rawConfidence):0.0f;
     activities.push_back({
             static_cast<forecastgraph::ActivityKind>(kindValues[index]),
             static_cast<uint32_t>(start),static_cast<uint32_t>(peak),
             static_cast<uint32_t>(end),safeStrength,safeConfidence});
     }
 replaceForecastActivities(std::move(activities));
 }

extern "C" JNIEXPORT void JNICALL fromjava(setForecastActivitiesSampled)(
        JNIEnv* env,jclass obj,jintArray kinds,jlongArray startsMs,
        jlongArray peaksMs,jlongArray endsMs,jfloatArray strengths,
        jfloatArray confidences,jintArray sampleCounts,
        jlongArray sampleTimesMs,jfloatArray sampleLevels) {
 constexpr jsize maximumActivities=1024;
 constexpr jint maximumSamplesPerActivity=256;
 constexpr uint64_t maximumTotalSamples=65'536U;
 if(!kinds||!startsMs||!peaksMs||!endsMs||!strengths||!confidences) {
     replaceForecastActivities({});
     return;
     }
 const jsize count=env->GetArrayLength(kinds);
 const bool metadataLengthsValid=count>=0&&count<=maximumActivities&&
         env->GetArrayLength(startsMs)==count&&
         env->GetArrayLength(peaksMs)==count&&
         env->GetArrayLength(endsMs)==count&&
         env->GetArrayLength(strengths)==count&&
         env->GetArrayLength(confidences)==count;
 if(!metadataLengthsValid) {
     replaceForecastActivities({});
     return;
     }
 std::vector<jint> kindValues(count);
 std::vector<jlong> startValues(count),peakValues(count),endValues(count);
 std::vector<jfloat> strengthValues(count),confidenceValues(count);
 if(count>0) {
     env->GetIntArrayRegion(kinds,0,count,kindValues.data());
     env->GetLongArrayRegion(startsMs,0,count,startValues.data());
     env->GetLongArrayRegion(peaksMs,0,count,peakValues.data());
     env->GetLongArrayRegion(endsMs,0,count,endValues.data());
     env->GetFloatArrayRegion(strengths,0,count,strengthValues.data());
     env->GetFloatArrayRegion(confidences,0,count,confidenceValues.data());
     }

 // Sample arrays are an additive contract. Reject a malformed sampled payload
 // as a whole, but keep every valid summary activity on the historical
 // triangle fallback instead of dropping insulin/meal context from the graph.
 bool samplesValid=sampleCounts&&sampleTimesMs&&sampleLevels&&
         env->GetArrayLength(sampleCounts)==count&&
         env->GetArrayLength(sampleTimesMs)==env->GetArrayLength(sampleLevels);
 std::vector<jint> countValues;
 uint64_t totalSamples=0U;
 if(samplesValid) {
     countValues.resize(count);
     if(count>0)
         env->GetIntArrayRegion(sampleCounts,0,count,countValues.data());
     for(const jint value:countValues) {
         if(value<0||value==1||value>maximumSamplesPerActivity||
            totalSamples>maximumTotalSamples-static_cast<uint64_t>(value)) {
             samplesValid=false;
             break;
             }
         totalSamples+=static_cast<uint64_t>(value);
         }
     if(samplesValid&&
        static_cast<uint64_t>(env->GetArrayLength(sampleTimesMs))!=totalSamples)
         samplesValid=false;
     }
 std::vector<jlong> sampleTimeValues;
 std::vector<jfloat> sampleLevelValues;
 if(samplesValid&&totalSamples>0U) {
     const jsize flatCount=static_cast<jsize>(totalSamples);
     sampleTimeValues.resize(flatCount);
     sampleLevelValues.resize(flatCount);
     env->GetLongArrayRegion(sampleTimesMs,0,flatCount,sampleTimeValues.data());
     env->GetFloatArrayRegion(sampleLevels,0,flatCount,sampleLevelValues.data());
     }

 std::vector<forecastgraph::Activity> activities;
 activities.reserve(count);
 std::size_t sampleOffset=0U;
 for(jsize index=0;index<count;++index) {
     const std::size_t eventSampleCount=samplesValid?
             static_cast<std::size_t>(countValues[index]):0U;
     std::vector<forecastgraph::ActivitySample> samples;
     bool eventSamplesValid=samplesValid;
     if(eventSamplesValid) {
         samples.reserve(eventSampleCount);
         uint32_t previousTime=0U;
         for(std::size_t point=0U;point<eventSampleCount;++point) {
             const std::size_t flatIndex=sampleOffset+point;
             const jlong millis=sampleTimeValues[flatIndex];
             const float level=sampleLevelValues[flatIndex];
             const uint64_t seconds=millis>0?
                     static_cast<uint64_t>(millis/1000LL):0U;
             if(!seconds||seconds>UINT32_MAX||!std::isfinite(level)||
                level<0.0f||level>1.0f||
                (point>0U&&seconds<=previousTime)) {
                 eventSamplesValid=false;
                 samples.clear();
                 break;
                 }
             previousTime=static_cast<uint32_t>(seconds);
             samples.push_back({previousTime,level});
             }
         sampleOffset+=eventSampleCount;
         }
     if(!forecastgraph::valid_kind(kindValues[index])||
        startValues[index]<=0||endValues[index]<=startValues[index])
         continue;
     const uint64_t start=static_cast<uint64_t>(startValues[index]/1000LL);
     const uint64_t end=static_cast<uint64_t>(endValues[index]/1000LL);
     if(!start||start>UINT32_MAX||end>UINT32_MAX)
         continue;
     uint64_t peak=peakValues[index]>0?
                   static_cast<uint64_t>(peakValues[index]/1000LL):start;
     peak=std::clamp<uint64_t>(peak,start,end);
     const float rawStrength=strengthValues[index];
     const float rawConfidence=confidenceValues[index];
     const float safeStrength=std::isfinite(rawStrength)?
                              std::max(0.0f,rawStrength):0.0f;
     const float safeConfidence=std::isfinite(rawConfidence)?
                                forecastgraph::clamp01(rawConfidence):0.0f;
     activities.push_back({
             static_cast<forecastgraph::ActivityKind>(kindValues[index]),
             static_cast<uint32_t>(start),static_cast<uint32_t>(peak),
             static_cast<uint32_t>(end),safeStrength,safeConfidence,
             eventSamplesValid?std::move(samples):
                               std::vector<forecastgraph::ActivitySample>{}});
     }
 replaceForecastActivities(std::move(activities));
 }

extern "C" JNIEXPORT void JNICALL fromjava(
        setForecastActivitiesRangedSampled)(JNIEnv* env,jclass obj,
        jintArray kinds,jintArray identityHashes,jlongArray startsMs,
        jlongArray onsetsMs,jlongArray peaksMs,jlongArray peakLowsMs,
        jlongArray peakHighsMs,jlongArray endsMs,jlongArray endLowsMs,
        jlongArray endHighsMs,jfloatArray strengths,jfloatArray confidences,
        jfloatArray attributionConfidences,jintArray overlapCounts,
        jintArray sampleCounts,jlongArray sampleTimesMs,
        jfloatArray sampleLevels) {
 constexpr jsize maximumActivities=1024;
 constexpr jint maximumSamplesPerActivity=256;
 constexpr uint64_t maximumTotalSamples=65'536U;
 if(!kinds||!identityHashes||!startsMs||!onsetsMs||!peaksMs||
    !peakLowsMs||!peakHighsMs||!endsMs||!endLowsMs||!endHighsMs||
    !strengths||!confidences||!attributionConfidences||!overlapCounts) {
     replaceForecastActivities({});
     return;
     }
 const jsize count=env->GetArrayLength(kinds);
 const auto sameLength=[&](jarray values) {
     return values&&env->GetArrayLength(values)==count;
     };
 const bool metadataLengthsValid=count>=0&&count<=maximumActivities&&
         sameLength(identityHashes)&&sameLength(startsMs)&&
         sameLength(onsetsMs)&&sameLength(peaksMs)&&
         sameLength(peakLowsMs)&&sameLength(peakHighsMs)&&
         sameLength(endsMs)&&sameLength(endLowsMs)&&
         sameLength(endHighsMs)&&sameLength(strengths)&&
         sameLength(confidences)&&sameLength(attributionConfidences)&&
         sameLength(overlapCounts);
 if(!metadataLengthsValid) {
     replaceForecastActivities({});
     return;
     }

 std::vector<jint> kindValues(count),identityValues(count),
                   overlapValues(count);
 std::vector<jlong> startValues(count),onsetValues(count),peakValues(count),
                    peakLowValues(count),peakHighValues(count),endValues(count),
                    endLowValues(count),endHighValues(count);
 std::vector<jfloat> strengthValues(count),confidenceValues(count),
                     attributionValues(count);
 if(count>0) {
     env->GetIntArrayRegion(kinds,0,count,kindValues.data());
     env->GetIntArrayRegion(identityHashes,0,count,identityValues.data());
     env->GetIntArrayRegion(overlapCounts,0,count,overlapValues.data());
     env->GetLongArrayRegion(startsMs,0,count,startValues.data());
     env->GetLongArrayRegion(onsetsMs,0,count,onsetValues.data());
     env->GetLongArrayRegion(peaksMs,0,count,peakValues.data());
     env->GetLongArrayRegion(peakLowsMs,0,count,peakLowValues.data());
     env->GetLongArrayRegion(peakHighsMs,0,count,peakHighValues.data());
     env->GetLongArrayRegion(endsMs,0,count,endValues.data());
     env->GetLongArrayRegion(endLowsMs,0,count,endLowValues.data());
     env->GetLongArrayRegion(endHighsMs,0,count,endHighValues.data());
     env->GetFloatArrayRegion(strengths,0,count,strengthValues.data());
     env->GetFloatArrayRegion(confidences,0,count,confidenceValues.data());
     env->GetFloatArrayRegion(attributionConfidences,0,count,
                              attributionValues.data());
     }

 bool samplesValid=sampleCounts&&sampleTimesMs&&sampleLevels&&
         env->GetArrayLength(sampleCounts)==count&&
         env->GetArrayLength(sampleTimesMs)==env->GetArrayLength(sampleLevels);
 std::vector<jint> countValues;
 uint64_t totalSamples=0U;
 if(samplesValid) {
     countValues.resize(count);
     if(count>0)
         env->GetIntArrayRegion(sampleCounts,0,count,countValues.data());
     for(const jint value:countValues) {
         if(value<0||value==1||value>maximumSamplesPerActivity||
            totalSamples>maximumTotalSamples-static_cast<uint64_t>(value)) {
             samplesValid=false;
             break;
             }
         totalSamples+=static_cast<uint64_t>(value);
         }
     if(samplesValid&&static_cast<uint64_t>(
            env->GetArrayLength(sampleTimesMs))!=totalSamples)
         samplesValid=false;
     }
 std::vector<jlong> sampleTimeValues;
 std::vector<jfloat> sampleLevelValues;
 if(samplesValid&&totalSamples>0U) {
     const jsize flatCount=static_cast<jsize>(totalSamples);
     sampleTimeValues.resize(flatCount);
     sampleLevelValues.resize(flatCount);
     env->GetLongArrayRegion(sampleTimesMs,0,flatCount,
                             sampleTimeValues.data());
     env->GetFloatArrayRegion(sampleLevels,0,flatCount,
                              sampleLevelValues.data());
     }

 const auto optionalSeconds=[](const jlong millis) -> uint32_t {
     if(millis<=0)
         return 0U;
     const uint64_t seconds=static_cast<uint64_t>(millis/1000LL);
     return seconds>0U&&seconds<=UINT32_MAX?
            static_cast<uint32_t>(seconds):0U;
     };
 std::vector<forecastgraph::Activity> activities;
 activities.reserve(count);
 std::size_t sampleOffset=0U;
 for(jsize index=0;index<count;++index) {
     const std::size_t eventSampleCount=samplesValid?
             static_cast<std::size_t>(countValues[index]):0U;
     std::vector<forecastgraph::ActivitySample> samples;
     bool eventSamplesValid=samplesValid;
     if(eventSamplesValid) {
         samples.reserve(eventSampleCount);
         uint32_t previousTime=0U;
         for(std::size_t point=0U;point<eventSampleCount;++point) {
             const std::size_t flatIndex=sampleOffset+point;
             const uint32_t seconds=optionalSeconds(
                     sampleTimeValues[flatIndex]);
             const float level=sampleLevelValues[flatIndex];
             if(!seconds||!std::isfinite(level)||level<0.0f||level>1.0f||
                (point>0U&&seconds<=previousTime)) {
                 eventSamplesValid=false;
                 samples.clear();
                 break;
                 }
             previousTime=seconds;
             samples.push_back({seconds,level});
             }
         sampleOffset+=eventSampleCount;
         }
     if(!forecastgraph::valid_kind(kindValues[index])||
        startValues[index]<=0||endValues[index]<=startValues[index])
         continue;
     const uint32_t start=optionalSeconds(startValues[index]);
     const uint32_t end=optionalSeconds(endValues[index]);
     if(!start||!end||end<=start)
         continue;
     uint32_t peak=optionalSeconds(peakValues[index]);
     peak=std::clamp(peak?peak:start,start,end);
     const float rawStrength=strengthValues[index];
     const float rawConfidence=confidenceValues[index];
     const float safeStrength=std::isfinite(rawStrength)?
                              std::max(0.0f,rawStrength):0.0f;
     const float safeConfidence=std::isfinite(rawConfidence)?
                                forecastgraph::clamp01(rawConfidence):0.0f;
     forecastgraph::Activity activity{
             static_cast<forecastgraph::ActivityKind>(kindValues[index]),
             start,peak,end,safeStrength,safeConfidence,
             eventSamplesValid?std::move(samples):
                               std::vector<forecastgraph::ActivitySample>{}};
     activity.onset=optionalSeconds(onsetValues[index]);
     activity.peakLow=optionalSeconds(peakLowValues[index]);
     activity.peakHigh=optionalSeconds(peakHighValues[index]);
     activity.endLow=optionalSeconds(endLowValues[index]);
     activity.endHigh=optionalSeconds(endHighValues[index]);
     activity.identity=identityValues[index];
     activity.overlapCount=overlapValues[index];
     activity.attributionConfidence=attributionValues[index];
     activities.push_back(std::move(activity));
     }
 replaceForecastActivities(std::move(activities));
 }

extern "C" JNIEXPORT jlongArray JNICALL fromjava(forecastReadings)(JNIEnv* env,
        jclass obj,jlong afterMs,jint requestedLimit) {
 struct Reading {
     uint32_t time;
     int32_t mgdl;
     float rate;
     int sensorIndex;
 };
 const int limit=std::clamp<int>(requestedLimit,0,20'000);
 if(limit==0||!sensors)
     return env->NewLongArray(0);
 const uint64_t after=afterMs>0?static_cast<uint64_t>(afterMs):0ULL;
 const uint32_t latestActual=static_cast<uint32_t>(time(nullptr))+60U;
 std::vector<Reading> readings;
 for(int sensorIndex=0;sensorIndex<=sensors->last();++sensorIndex) {
     const SensorGlucoseData *sensor=sensors->getSensorData(sensorIndex);
     if(!sensor)
         continue;
     const auto polls=sensor->getPolldata();
     for(const ScanData &poll:polls) {
         // Dexcom's vendor prediction is stored in history, not polls. Reading
         // only this stream plus the wall-clock guard keeps that synthetic
         // future point out of model training/backfill.
         if(!poll.t||poll.t>latestActual||!poll.valid(0))
             continue;
         const uint64_t millis=static_cast<uint64_t>(poll.t)*1000ULL;
         if(millis<=after)
             continue;
         // Backend storage is deliberately raw and invariant. Calibration is
         // a mutable presentation transform applied only while rendering the
         // forecast, so changing calibration never rewrites model history.
         readings.push_back({poll.t,poll.g,poll.getchange(),sensorIndex});
         }
     }
 std::stable_sort(readings.begin(),readings.end(),[](const Reading &left,
                                                      const Reading &right) {
     if(left.time!=right.time)
         return left.time<right.time;
     return left.sensorIndex<right.sensorIndex;
 });
 std::vector<Reading> unique;
 unique.reserve(readings.size());
 for(const Reading &reading:readings) {
     if(!unique.empty()&&unique.back().time==reading.time)
         unique.back()=reading;
     else
         unique.push_back(reading);
     }
 // The Java checkpoint advances to the newest row returned by this call.
 // Therefore pagination must return the earliest rows after `afterMs`;
 // returning the newest tail would skip the intervening history forever.
 if(unique.size()>static_cast<size_t>(limit))
     unique.resize(static_cast<size_t>(limit));

 std::vector<jlong> flat;
 flat.reserve(unique.size()*3U);
 for(const Reading &reading:unique) {
     uint32_t rateBits=0U;
     static_assert(sizeof(rateBits)==sizeof(reading.rate));
     std::memcpy(&rateBits,&reading.rate,sizeof(rateBits));
     flat.push_back(static_cast<jlong>(reading.time)*1000LL);
     flat.push_back(static_cast<jlong>(reading.mgdl));
     flat.push_back(static_cast<jlong>(static_cast<uint64_t>(rateBits)));
     }
 jlongArray result=env->NewLongArray(static_cast<jsize>(flat.size()));
 if(result&&!flat.empty())
     env->SetLongArrayRegion(result,0,static_cast<jsize>(flat.size()),flat.data());
 return result;
 }

extern "C" JNIEXPORT jint JNICALL fromjava(timelineEventAt)(JNIEnv* env,
        jclass obj,jfloat x,jfloat y) {
 return intakeTimelineEventAt(x,y);
 }

extern "C" JNIEXPORT jintArray JNICALL fromjava(timelineEventsAt)(JNIEnv* env,
        jclass obj,jfloat x,jfloat y) {
 const std::vector<std::int32_t> keys=intakeTimelineEventsAt(x,y);
 jintArray result=env->NewIntArray(static_cast<jsize>(keys.size()));
 if(result&&!keys.empty()) {
     static_assert(sizeof(jint)==sizeof(std::int32_t));
     env->SetIntArrayRegion(result,0,static_cast<jsize>(keys.size()),
                            reinterpret_cast<const jint*>(keys.data()));
     }
 return result;
 }



extern "C" JNIEXPORT jfloat JNICALL fromjava(freey) (JNIEnv *env, jclass clazz) {
 return appcurve.getfreey();
 }

extern void setusedsensors() ;
extern "C" JNIEXPORT void JNICALL fromjava(setmaxsensors) (JNIEnv *env, jclass clazz,jint nr) {
 setusedsensors();
 }

extern uint32_t lastsensorends();
extern "C" JNIEXPORT jlong JNICALL fromjava(sensorends)(JNIEnv* env, jclass obj) {
 return  lastsensorends() ;
 }


extern "C" JNIEXPORT jstring JNICALL   fromjava(getUsedSensorName)(JNIEnv *envin, jclass cl) {
 if(const SensorGlucoseData *sens=sensors->getSensorData();sens&&sens->pollcount()) {
 const char *name=sens->shortsensorname()->data();
 LOGGER("getUsedSensorName()=%s\n",name);
 return envin->NewStringUTF(name);
 }
 return nullptr;
 }


extern int badscanMessage(NVGcontext* avg,int kind) ;
extern "C" JNIEXPORT jint JNICALL fromjava(badscan)(JNIEnv* env, jclass obj,jint kind) {
 return ::appcurve.badscanMessage(::genVG, kind) ;
 }

jobject glucosecurve=0;

extern "C" JNIEXPORT void  JNICALL   fromjava(setpaused)(JNIEnv *env, jclass cl,jobject val) {
 if(glucosecurve)
 env->DeleteGlobalRef(glucosecurve);
 if(val)
 glucosecurve=env->NewGlobalRef(val);
 else
 glucosecurve=nullptr;
 }
#ifndef NOJAVA
JavaVM *vmptr;
static jmethodID summaryready=nullptr;

static jmethodID showsensorinfo=nullptr;
jmethodID jdoglucose=nullptr, jupdateDevices=nullptr, jbluetoothEnabled=nullptr,jspeak=nullptr, jresetWearOS=nullptr, jbluePermission=nullptr;
//jmethodID jchangedProfile;
jclass JNIApplic,JNIString;
#ifdef OLDEVERSENSE
#ifndef WEAROS
jclass EverSense;
jmethodID sendGlucoseBroadcast=nullptr,jtoGarmin=nullptr, jGarmindeletelast=nullptr,jswitchbluetooth=nullptr;
#endif
#endif
#ifdef WEAROS
jmethodID jsetinittext=nullptr;
jmethodID jrminitlayout=nullptr;
#endif
extern void initlibreviewjni(JNIEnv *env);

extern bool jinitmessages(JNIEnv* env);
#ifndef NOLOG
#include <sys/resource.h>
void showonelimit(const char *name, int resource) {
 struct rlimit rlim;
 if(!getrlimit(resource, &rlim)) {
 LOGGER("getrlimit %-10s cur=%-20lu max=%lu\n",name+7,rlim.rlim_cur,rlim.rlim_max);
 }
 else {
 flerror("getrlimit(%s..)",name);
 }
 }
#define onelimit(x) showonelimit(#x,x)
void showlimits() {
onelimit(RLIMIT_CPU);
onelimit(RLIMIT_FSIZE);
onelimit(RLIMIT_DATA);
onelimit(RLIMIT_STACK);
onelimit(RLIMIT_CORE);
onelimit(RLIMIT_RSS);
onelimit(RLIMIT_NPROC);
onelimit(RLIMIT_NOFILE);
onelimit(RLIMIT_MEMLOCK);
onelimit(RLIMIT_AS);
onelimit(RLIMIT_LOCKS);
onelimit(RLIMIT_SIGPENDING);
onelimit(RLIMIT_MSGQUEUE);
onelimit(RLIMIT_NICE);
onelimit(RLIMIT_RTPRIO);
onelimit(RLIMIT_RTTIME);
 }
#endif

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
 vmptr=vm;
 JNIEnv* env=nullptr;
 LOGAR("JNI_OnLoad");
/*
#ifndef NOLOG
 showlimits();
#endif 
*/
 if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
 return JNI_ERR;
 }

{
const jclass cl=env->FindClass("tk/glucodata/GlucoseCurve");
if(!cl) {
 summaryready=nullptr;
 LOGAR("Can't find GlucoseCurve");
 }
else {
 LOGAR("found GlucoseCurve");
 summaryready=env->GetMethodID(cl,"summaryready","()V");
 showsensorinfo=env->GetMethodID(cl,"showsensorinfo","(Ljava/lang/String;J)V");
 env->DeleteLocalRef(cl);
 }

}

{const static jclass cl=env->FindClass("java/lang/String");
if(cl) {
 JNIString = (jclass)env->NewGlobalRef(cl);
 env->DeleteLocalRef(cl);
 }
}
{
const static jclass cl=env->FindClass("tk/glucodata/Applic");
if(cl) {
 JNIApplic = (jclass)env->NewGlobalRef(cl);
 env->DeleteLocalRef(cl);
 if(!(jdoglucose=env->GetStaticMethodID(JNIApplic,"doglucose","(Ljava/lang/String;IFFIJZJJI)V"))) {
 LOGAR(R"(GetStaticMethodID(JNIApplic,"doglucose","(Ljava/lang/String;IFFIJZJJI)V"))) failed)" "");
 }
 if(!(jupdateDevices=env->GetStaticMethodID(JNIApplic,"updateDevices","()Z"))) {
 LOGAR(R"(jupdateDevices=env->GetStaticMethodID(JNIApplic,"updateDevices","()Z") failed)" "");
 }
 if(!(jbluetoothEnabled=env->GetStaticMethodID(JNIApplic,"bluetoothEnabled","()Z"))) {
 LOGAR(R"(jbluetoothEnabled=env->GetStaticMethodID(JNIApplic,"bluetoothEnabled","()Z") failed)" "");
 }
 if(!(jspeak=env->GetStaticMethodID(JNIApplic,"speak","(Ljava/lang/String;)V"))) {
 LOGAR(R"(jspeak=env->GetStaticMethodID(JNIApplic,"speak","(Ljava/lang/String;)V") failed)" "");
 }
 if(!(jresetWearOS=env->GetStaticMethodID(JNIApplic,"resetWearOS","()V"))) {
 LOGAR(R"(jresetWearOS=env->GetStaticMethodID(JNIApplic,"resetWearOS","()V") failed)" "");
 }
 /*  if(!(jchangedProfile=env->GetStaticMethodID(JNIApplic,"changedProfile","()V"))) {
 LOGAR(R"(jchangedProfile=env->GetStaticMethodID(JNIApplic,"changedProfile","()V") failed)" "");
 } */
#ifdef WEAROS
 if(!(jsetinittext=env->GetStaticMethodID(JNIApplic,"setinittext","(Ljava/lang/String;)V"))) {
 LOGAR(R"%(jsetinittext=env->GetStaticMethodID(JNIApplic,"setinittext","(Ljava/lang/String;)V)" failed)%" );
 }
 if(!(jrminitlayout=env->GetStaticMethodID(JNIApplic,"rminitlayout","()V"))) {
 LOGAR(R"%(jrminitlayout=env->GetStaticMethodID(JNIApplic,"rminitlayout","()V)" failed)%" );
 }
#else
 if(!(jtoGarmin=env->GetStaticMethodID(JNIApplic,"toGarmin","(I)V"))) {
 LOGAR(R"(jtoGarmin=env->GetStaticMethodID(JNIApplic,"toGarmin","(I)V") failed)" "");
 }
 if(!(jGarmindeletelast=env->GetStaticMethodID(JNIApplic,"Garmindeletelast","(III)V"))) {
 LOGAR(R"(jGarmindeletelast=env->GetStaticMethodID(JNIApplic,"Garmindeletelast","(III)V") failed)" "");
 }
 if(!(jswitchbluetooth=env->GetStaticMethodID(JNIApplic,"switchbluetooth","(Ljava/lang/String;[BZ)Z"))) {
 LOGAR(R"(jswitchbluetooth=env->GetStaticMethodID(JNIApplic,"switchbluetooth","(Ljava/lang/String;[BZ)Z") failed)" "");
 }
#endif
 if(!(jbluePermission=env->GetStaticMethodID(JNIApplic,"bluePermission","()I"))) {
 LOGAR(R"(jbluePermission=env->GetStaticMethodID(JNIApplic,"bluePermission","()I") failed)" "");
 }
 /*
 if(!(jtoCalendar=env->GetStaticMethodID(JNIApplic,"toCalendar","()V"))) {
 LOGAR(R"(jtoCalendar=env->GetStaticMethodID(JNIApplic,"toCalendar","(Ljava/lang/String;)V") failed)" "");
 } */
 }
else {
 LOGAR(R"(FindClass("tk/glucodata/Applic") failed)" "");
 }
}


#ifdef OLDEVERSENSE
#ifndef WEAROS
{
const static jclass cl=env->FindClass("tk/glucodata/EverSense");
if(cl) {

 EverSense = (jclass)env->NewGlobalRef(cl);
 env->DeleteLocalRef(cl);
// broadcastglucose(int mgdl, float rate, long timmsec)
 if(!(sendGlucoseBroadcast=env->GetStaticMethodID(EverSense,"broadcastglucose","(IFJ)V"))) {
 LOGAR(R"(GetStaticMethodID(EverSense,"broadcastglucose","(IFJ)V") failed)" "");
 }
 }
else {
 LOGAR(R"(FindClass("tk/glucodata/EverSense") failed)" "");
 }
 }
#endif
#endif
 /*
const static jclass clappl=env->FindClass("tk/glucodata/Applic");
jclass Applic=nullptr;
jmethodID jupdatescreen=nullptr;
if(clappl) {
 Applic= (jclass)env->NewGlobalRef(clappl);
 env->DeleteLocalRef(clappl);
 jupdatescreen=env->GetStaticMethodID(Applic,"updatescreen","()V");
 }
bool loadglucoseclass(JNIEnv *env);
if(loadglucoseclass(env)) {
 LOGSTRING("end JNI_OnLoad\n");
 return JNI_VERSION_1_6;
 }
return JNI_ERR;
*/
#ifndef WEAROS
initlibreviewjni(env);
#endif


#ifdef WEAROS_MESSAGES
jinitmessages(env) ;
#endif

 LOGAR("end JNI_OnLoad");
 return JNI_VERSION_1_6;
}
class attach {
JNIEnv *env;
public:
attach() {
 char buf[17];
 prctl(PR_GET_NAME, buf, 0, 0, 0);
 vmptr->AttachCurrentThreadAsDaemon(&env, nullptr);
 prctl(PR_SET_NAME, buf, 0, 0, 0);
 }
~attach() {
 vmptr->DetachCurrentThread();
 }
[[nodiscard]] JNIEnv *get()  const {
 return env;
 }
};


JNIEnv *getenv() {
 const thread_local static attach  env;
 return env.get();
 }
bool bluetoothEnabled() {
 return   getenv()->CallStaticBooleanMethod(JNIApplic,jbluetoothEnabled);
 }
int bluePermission() {
 return   getenv()->CallStaticIntMethod(JNIApplic,jbluePermission);
 }
void telldoglucose(const char *name,int32_t mgdl,float glu,float rate,int alarm,int64_t mmsec,bool wasnoblue,int64_t startmsec,intptr_t sensorptr,int sensorgen) {
 LOGGER("telldoglucose sensorptr=%p sensorgen=%d\n",sensorptr,sensorgen);
 jstring sname= getenv()->NewStringUTF(name);
 getenv()->CallStaticVoidMethod(JNIApplic,jdoglucose,sname,mgdl,glu,rate,alarm,mmsec,wasnoblue,startmsec,(jlong) sensorptr,sensorgen);
 getenv()->DeleteLocalRef(sname);
 }

extern bool javaUpdateDevices();
bool javaUpdateDevices() {
 if(!jupdateDevices)  {
 LOGAR("jupdateDevices==null");
 return false;
 }
 return   getenv()->CallStaticBooleanMethod(JNIApplic,jupdateDevices);
 }
/*
extern bool javaUpdateDevices();
bool updateDevices() {
 LOGAR("before deletelast()");
 sensors->deletelast();
 LOGAR("after deletelast()");
 return  javaUpdateDevices();
 } */
void resetWearOS() {
 if(!jresetWearOS)  {
 LOGAR("jresetWearOS==null");
 }
 else
 getenv()->CallStaticVoidMethod(JNIApplic,jresetWearOS);
 }
 /*
void changedProfile() {
 if(!jchangedProfile)  {
 LOGAR("jchangedProfile==null");
 return;
 }
 getenv()->CallStaticVoidMethod(JNIApplic,jchangedProfile);
 } */
void visiblebutton() {
     if(glucosecurve) {
         if(summaryready)  {
             JNIEnv *env =getenv(); 
             LOGAR("call summaryready");
             env->CallVoidMethod(glucosecurve,summaryready);
            }
         else
           LOGAR("didn't find GlucoseCurve");
     }
 }

void callshowsensorinfo(const char *text, SensorGlucoseData *sensorptr) {
 if(glucosecurve) {
 if(showsensorinfo)  {
 JNIEnv *env =getenv(); 
 LOGAR("call showsensorinfo");
 env->CallVoidMethod(glucosecurve,showsensorinfo,env->NewStringUTF(text),reinterpret_cast<jlong>(sensorptr));
 }
 else
 LOGAR("didn't find GlucoseCurve");
 }
 }

void render() {
 LOGAR("Render");
 if(glucosecurve) {
 struct method {
 jmethodID requestRendermeth;
 method(JNIEnv *env) {   
 jclass   cl=env->FindClass("android/opengl/GLSurfaceView");
 requestRendermeth=env->GetMethodID(cl,"requestRender","()V");
 env->DeleteLocalRef(cl);
 };
 };
 static method meth(getenv());
 getenv()->CallVoidMethod(glucosecurve,meth.requestRendermeth);
 }
// onestep();
 }
#endif
extern NVGcontext* genVG;
jint onestep(NVGcontext* genVG);
extern "C" JNIEXPORT jint JNICALL fromjava(step)(JNIEnv* env, jclass obj) {
 return appcurve.onestep(::genVG);
 }

extern char localestrbuf[15];


void setlocale(NVGcontext* avg,const char *localestrbuf,const size_t len,int sdk) ;


extern "C" JNIEXPORT void JNICALL fromjava(setlocale)(JNIEnv *env, jclass clazz,jstring jlocalestr,int sdk) {
 if(jlocalestr) {
     size_t len=env->GetStringLength(jlocalestr);
     env->GetStringUTFRegion( jlocalestr, 0,len, localestrbuf);
     localestrbuf[2]='_';
     appcurve.setlocale(genVG,localestrbuf,5,sdk);

     }
 }

extern uint32_t starttime;




extern void initopengl(float small,float menu,float density,float headin) ;


//extern void setdiffcurrent();
extern "C" JNIEXPORT void JNICALL fromjava(initopengl)(JNIEnv* env, jclass obj, jfloat small,jfloat menu,jfloat density,jfloat headin) {
 initopengl(small,menu,density,headin);

// appcurve.setdiffcurrent(settings->data()->currentRelative);
}

extern "C" JNIEXPORT void  JNICALL   fromjava(setcurrentRelative)(JNIEnv *env, jclass cl,jboolean val) {
 settings->data()->currentRelative=val;
 appcurve.setdiffcurrent(val);
 }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(getcurrentRelative)(JNIEnv *env, jclass cl) {
 return settings->data()->currentRelative;
 }


extern std::string_view libdirname;
extern int setfilesdir(const std::string_view filesdir,const char *country) ;
//#include "curve/shell.h"
extern "C" JNIEXPORT int JNICALL fromjava(setfilesdir)(JNIEnv *env, jclass clazz, jstring dir,jstring jcountry,jstring nativedir) {
 {
 size_t nativedirlen= env->GetStringUTFLength( nativedir);
 char *nativebuf=new char[nativedirlen+1];
 env->GetStringUTFRegion( nativedir, 0,nativedirlen,nativebuf);
 nativebuf[nativedirlen]='\0';
 libdirname={nativebuf,nativedirlen};
 }
 size_t filesdirlen= env->GetStringUTFLength( dir);
 jint jdirlen = env->GetStringLength( dir);
 char *filesdirbuf=new char[filesdirlen+1];
 env->GetStringUTFRegion( dir, 0,jdirlen, filesdirbuf);
 filesdirbuf[filesdirlen]='\0';
 char *country= localestrbuf+3;
 if(jcountry&& env->GetStringLength(jcountry)>=2) {
 env->GetStringUTFRegion( jcountry, 0,2,country );
 country[2]='\0';
 LOGGER("country=%s\n",country);
 }
 else {
 LOGAR("country=null\n");
 strcpy( country,"GB");
 country=(char *)"\0";
 }
 auto res= setfilesdir({filesdirbuf,filesdirlen},country);
 appcurve.setunit(settings->data()->unit);
 appcurve.showcalibratedstream=settings->data()->DoCalibrate;
 appcurve.allvalues=settings->data()->AllValues;
 return res;
 }
extern "C" JNIEXPORT void  JNICALL   fromjava(setAllValues)(JNIEnv *env, jclass cl,jboolean val) {
 settings->data()->AllValues=val;
 appcurve.allvalues=val;
 }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(getAllValues)(JNIEnv *env, jclass cl) {
 return settings->data()->AllValues;
 }
/*
extern "C" JNIEXPORT void JNICALL fromjava(calccurvegegs)(JNIEnv *env, jclass clazz) {
 calccurvegegs();
 setdiffcurrent(settings->data()->currentRelative);
 } */

extern void flingX(float vol);
extern "C" JNIEXPORT void JNICALL fromjava(flingX) (JNIEnv *env, jclass clazz,jfloat vol) {
 appcurve.flingX(vol);
 }

//extern int translate(float dx,float dy,float yold,float y);
extern "C" JNIEXPORT jint JNICALL fromjava(translate) (JNIEnv *env, jclass clazz,jfloat dx,jfloat dy,jfloat yold,jfloat y) {
 return appcurve.translate(dx,dy,yold,y);
 }
extern "C" JNIEXPORT void JNICALL fromjava(lockGraphYRangeForPan) (JNIEnv *env, jclass clazz) {
 appcurve.lockGraphYRangeForPan();
 }
extern "C" JNIEXPORT jint JNICALL fromjava(mouseScale)(JNIEnv *env, jclass clazz,jfloat dx,jfloat xold, jfloat x){
 return  appcurve.mouseScale(dx,xold,x);
 }

void xscaleGesture(float scalex,float midx);
extern "C" JNIEXPORT void JNICALL fromjava(xscale) (JNIEnv *env, jclass clazz,jfloat scalex,jfloat midx) {
 appcurve.xscaleGesture(scalex,midx);
 }

void prevscr() ;
extern "C" JNIEXPORT void JNICALL fromjava(prevscr)(JNIEnv* env, jclass obj) {
 appcurve.prevscr();
 }
void nextscr() ;
extern "C" JNIEXPORT void JNICALL fromjava(nextscr)(JNIEnv* env, jclass obj) {
 appcurve.nextscr() ;
 }

void pressedback() ;
extern "C" JNIEXPORT void JNICALL fromjava(pressedback) (JNIEnv *env, jclass clazz) {
 pressedback() ;
 }
bool isbutton(float x,float y) ;
extern "C" JNIEXPORT jboolean JNICALL fromjava(isbutton) (JNIEnv *env, jclass clazz,jfloat x,jfloat y) {
 return isbutton( x, y);
 }


extern "C" JNIEXPORT jlong JNICALL fromjava(tap) (JNIEnv *env, jclass clazz,jfloat x,jfloat y) {
 return appcurve.screentap(x,y);
 }

int64_t longpress(float x,float y);
extern "C" JNIEXPORT jlong JNICALL fromjava(longpress) (JNIEnv *env, jclass clazz,jfloat x,jfloat y) {
 return appcurve.longpress(x,y);
 }

extern "C" JNIEXPORT jlong JNICALL fromjava(graphscrub) (JNIEnv *env, jclass clazz,jfloat x,jfloat y) {
 return appcurve.graphscrub(x,y);
 }

#include "numhit.hpp"

extern NumHit newhit;
#include "numdisplay.hpp"
#include "nums/num.h"
extern "C" JNIEXPORT jlong JNICALL fromjava(newhit) (JNIEnv *env, jclass clazz) {
 return reinterpret_cast<jlong>(&newhit);
 }
extern "C" JNIEXPORT jlong JNICALL fromjava(hittime) (JNIEnv *env, jclass clazz,jlong ptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 return num->hit->time;
 }

extern "C" JNIEXPORT jfloat JNICALL fromjava(hitvalue) (JNIEnv *env, jclass clazz,jlong ptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 return num->hit->value;
 }
extern "C" JNIEXPORT jint JNICALL fromjava(hittype) (JNIEnv *env, jclass clazz,jlong ptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 return num->hit->type;
 }
extern "C" JNIEXPORT jint JNICALL fromjava(hitmeal) (JNIEnv *env, jclass clazz,jlong ptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 return num->hit->mealptr;
 }
extern "C" JNIEXPORT jint JNICALL fromjava(hitexclude) (JNIEnv *env, jclass clazz,jlong ptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 return num->hit->exclude;
 }

int hitremove(int64_t ptr);
extern "C" JNIEXPORT jint JNICALL fromjava(hitremove) (JNIEnv *env, jclass clazz,jlong ptr) {
 return hitremove(ptr);
 }
extern "C" JNIEXPORT jint JNICALL fromjava(gethitindex) (JNIEnv *env, jclass clazz,jlong ptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 const NumDisplay *dis=num->numdisplay;
 if(!dis)
 return 1;
 return dis->getindex();
}

extern "C" JNIEXPORT void JNICALL fromjava(hitchange)(JNIEnv *env, jclass thiz,jlong ptr,jlong time,jfloat value,int type,int mealptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 num->numdisplay->numchange(num->hit,time,value,type,mealptr);
 }
extern "C" JNIEXPORT void JNICALL fromjava(hitsetmealptr)(JNIEnv *env, jclass thiz,jlong ptr,jint mealptr) {
 NumHit *num=reinterpret_cast<NumHit *>(ptr);
 num->numdisplay->setmealptr(const_cast<Num *>(num->hit),mealptr);
 }

extern "C" JNIEXPORT jlong JNICALL fromjava(mkhitptr) (JNIEnv *env, jclass clazz,jlong ptr,jint pos) {
 NumDisplay *dis=reinterpret_cast<NumDisplay*>(ptr);
 NumHit *num=new NumHit({dis,dis->startdata()+pos});
 return reinterpret_cast<jlong>(num);
 }
extern "C" JNIEXPORT void JNICALL fromjava(freehitptr)(JNIEnv *env, jclass thiz,jlong ptr) {
 delete reinterpret_cast<NumHit *>(ptr);
 }
extern int duration;
extern "C" JNIEXPORT jlong JNICALL fromjava(getstarttime) (JNIEnv *env, jclass clazz) {
 return static_cast<jlong>(appcurve.starttime)*1000l;
 };

extern "C" JNIEXPORT jlong JNICALL fromjava(getendtime) (JNIEnv *env, jclass clazz) {
 auto end=   std::min(appcurve.starttime+appcurve.duration,(uint32_t)time(nullptr));
 return static_cast<jlong>(end)*1000l;
 };

extern uint32_t mintime();
extern "C" JNIEXPORT jlong JNICALL fromjava(oldestdatatime)(JNIEnv *env, jclass thiz) {
 return static_cast<jlong>(mintime())*1000l;
 }
#ifndef WEAROS
void stopsearch() ;
extern "C" JNIEXPORT void JNICALL fromjava(stopsearch) (JNIEnv *env, jclass clazz) {
 stopsearch();
 }
int nextforward() ;
extern "C" JNIEXPORT jint JNICALL fromjava(latersearch) (JNIEnv *env, jclass clazz) {
 return appcurve.nextforward();
 }
int nextpast();
extern "C" JNIEXPORT jint JNICALL fromjava(earliersearch) (JNIEnv *env, jclass clazz) {
 return appcurve.nextpast();
 }

 extern int carbotype;
extern "C" JNIEXPORT jint JNICALL fromjava(search) (JNIEnv *env, jclass clazz,jint type, jfloat under,jfloat above,jint frommin,jint tomin,jboolean forward,jstring jregingr,jfloat amount) {
 const char *regingr=nullptr;
 if(jregingr!=nullptr&&type==carbotype)  {
 regingr=env->GetStringUTFChars( jregingr, nullptr);
 if(regingr == nullptr) {

 return 3;
 }
 }
 jint res= appcurve.searchcommando(type, under,above,frommin,tomin,forward,regingr, amount);
 if(regingr)
 env->ReleaseStringUTFChars(jregingr, regingr);
 return res;
 }
#endif
extern "C" JNIEXPORT void JNICALL fromjava(movedate) (JNIEnv *env, jclass clazz,jlong milli,jint year,jint month,jint day) {
 time_t tim=milli/1000LL;
 struct tm      stm{};
 localtime_r(&tim,&stm);
 stm.tm_year=year-1900;
 stm.tm_mon=month;
 stm.tm_mday=day;
 time_t timto=mktime(&stm);
 appcurve.setstarttime(appcurve.starttime+uint32_t((int64_t)timto-(int64_t)tim));
 appcurve.begrenstijd() ;
 };
extern "C" JNIEXPORT void JNICALL fromjava(setStartTime) (JNIEnv *env, jclass clazz,jlong milli) {
 appcurve.setstarttime(milli/1000LL);
 appcurve.begrenstijd() ;
 };
extern "C" JNIEXPORT void JNICALL fromjava(prevday)(JNIEnv* env, jclass obj,jint val) {
 appcurve.prevdays(val);
 }

extern "C" JNIEXPORT void JNICALL fromjava(nextday)(JNIEnv* env, jclass obj,jint val) {
 appcurve.nextdays(val);
 }
/*
extern "C" JNIEXPORT jlong JNICALL fromjava(lastpoll)(JNIEnv *env, jclass thiz) {
 const SensorGlucoseData *hist=sensors->getSensorData(); 
 const ScanData *glu=hist->lastpoll() ;
 if(glu)
 return (((jlong)glu->g)<<32)|(jlong)glu->t;
 else
 return 0LL;
 }*/
//#include "oldest.hpp"
#ifndef WEAROS
void numfirstpage() ;
extern "C" JNIEXPORT void JNICALL fromjava(firstpage)(JNIEnv *env, jclass thiz) {
 numfirstpage();
 }

uint32_t maxtime();
extern "C" JNIEXPORT void JNICALL fromjava(lastpage)(JNIEnv *env, jclass thiz) {
 appcurve.numpagenum(maxtime());
 }

extern "C" JNIEXPORT void JNICALL fromjava(endnumlist)(JNIEnv *env, jclass thiz) {
 appcurve.endnumlist();
 }
#endif
extern int showui;
extern "C" JNIEXPORT jboolean JNICALL fromjava(getsystemUI)(JNIEnv *env, jclass thiz) {
 return showui;
 }
int64_t openNums(std::string_view numpath,int64_t ident);

extern pathconcat numbasedir;
extern "C" JNIEXPORT jlong JNICALL fromjava(openNums)(JNIEnv *env, jclass thiz,jstring jbase,jlong ident) {
 LOGAR("start openNums");
 jint len = env->GetStringUTFLength( jbase);
 int blen=numbasedir.length();
 int alllen=len+blen+1;
 char base[alllen+1];
 memcpy(base,numbasedir.data(),blen);
 base[blen++]='/';
 jint jlen = env->GetStringLength( jbase);
 env->GetStringUTFRegion(jbase, 0,jlen, base+blen);
 base[alllen]='\0';
 const auto res= openNums( string_view(base,alllen),ident);
 LOGAR("end openNums");
 return res;
 }
extern "C" JNIEXPORT void JNICALL fromjava(setlastcolor)(JNIEnv *env, jclass thiz,jint color) {
 if(appcurve.lasttouchedcolor<0)
 return;
 appcurve.setcolor(appcurve.lasttouchedcolor, hexcoloralpha((uint32_t)color));
 }
extern "C" JNIEXPORT jint JNICALL fromjava(getlastcolor)(JNIEnv *env, jclass thiz) {
 if(appcurve.lasttouchedcolor<0)
 return 0xFFFFFFFF;
 return  fromNVGcolor(appcurve.getcolor(appcurve.lasttouchedcolor));
 }
#ifndef WEAROS
extern bool makepercetages() ;
extern "C" JNIEXPORT jboolean JNICALL fromjava(makepercentages)(JNIEnv *env, jclass thiz) {
 return makepercetages();
 }
extern int numlist;
extern "C" JNIEXPORT void JNICALL fromjava(makenumbers)(JNIEnv *env, jclass thiz) {
 if(!numlist) {
 appcurve.numiterinit();
 numlist=1;
 }
 }
#endif
/*
static float screenwidthcm=0;
//extern bool iswatch;
extern "C" JNIEXPORT void JNICALL fromjava(setscreenwidthcm)(JNIEnv *env, jclass thiz,jfloat wcm) {
 screenwidthcm=wcm;
// iswatch=(wcm<5.8f);
 }
*/
#define defdisplay(kind)\
extern "C" JNIEXPORT jboolean JNICALL fromjava(getshow##kind)(JNIEnv *env, jclass thiz) {\
 return appcurve.show##kind;\
 }\
extern "C" JNIEXPORT void JNICALL fromjava(setshow##kind)(JNIEnv *env, jclass thiz,jboolean val) {\
 appcurve.show##kind=val;\
 settings->data()->show##kind=val;\
 }

defdisplay(scans)
defdisplay(meals)
defdisplay(histories)
defdisplay(stream)
defdisplay(numbers)
defdisplay(calibratedstream)
defdisplay(calibratedscans)
defdisplay(calibratedhistories)
#ifdef WEAROS
void setInitText(const char *message) {
 getenv()->CallStaticVoidMethod(JNIApplic,jsetinittext,getenv()->NewStringUTF(message));
 }
bool rmInitLayout() {
 getenv()->CallStaticVoidMethod(JNIApplic,jrminitlayout);
 return true;
 }
#endif
void speak(const char *message) {
 if(message)
 getenv()->CallStaticVoidMethod(JNIApplic,jspeak,getenv()->NewStringUTF(message));
 else {
 LOGAR("speak(null)");
 }
 }
extern void toGarmin(int base);
void toGarmin(int base) {
#ifndef WEAROS
 if(settings->data()->usegarmin)
 getenv()->CallStaticVoidMethod(JNIApplic,jtoGarmin,!base); 
#endif
 }
void Garmindeletelast(int base,int pos,int end ) {
#ifndef WEAROS
 if(settings->data()->usegarmin)
 getenv()->CallStaticVoidMethod(JNIApplic,jGarmindeletelast,!base,pos,end); 
#endif
 }

 /*
void toCalendar(const char *message) {
 if(message)
 getenv()->CallStaticVoidMethod(JNIApplic,jtoCalendar,getenv()->NewStringUTF(message));
 else {
 LOGAR("toCalendar(null)");
 }
 } */


#ifndef DONTTALK
extern bool speakout;
extern "C" JNIEXPORT void JNICALL fromjava(settouchtalk)(JNIEnv *env, jclass thiz,jboolean val) {

 settings->data()->talktouchset(val);
 speakout=val;
 }

extern "C" JNIEXPORT jboolean JNICALL fromjava(gettouchtalk)(JNIEnv *env, jclass thiz) {
 return speakout;
 }
extern "C" JNIEXPORT void JNICALL fromjava(setspeakmessages)(JNIEnv *env, jclass thiz,jboolean val) {

 settings->data()->speakmessagesset(val);
 }

extern "C" JNIEXPORT jboolean JNICALL fromjava(speakmessages)(JNIEnv *env, jclass thiz) {
 return settings->data()->speakmessagesget();
 }
extern "C" JNIEXPORT void JNICALL fromjava(setspeakalarms)(JNIEnv *env, jclass thiz,jboolean val) {

 settings->data()->speakalarmsset(val);
 }

extern "C" JNIEXPORT jboolean JNICALL fromjava(speakalarms)(JNIEnv *env, jclass thiz) {
 return settings->data()->speakalarmsget();
 }
//extern const SensorGlucoseData *getlaststream(const uint32_t nu);
extern std::pair<const SensorGlucoseData *,int> getlaststream(const uint32_t nu) ;
extern "C" JNIEXPORT jlong JNICALL fromjava(saylastglucose)(JNIEnv *env, jclass thiz) {
 if(speakout) {
 LOGAR("saylastglucose start");
 const uint32_t nu=time(nullptr);        
 const auto [hist,_]=getlaststream(nu);
 if(!hist)  {
 LOGAR("getlaststream(nu)=null");
 return 0;
 }
 const ScanData *poll=hist->lastValidStream();
 if(!poll) {
 LOGAR("no stream");
 return 0L;
 }
 if(poll->gettime()<(nu-maxbluetoothage)) {
 LOGGER("old value %d\n",poll->gettime());
 return poll->gettime()*1000L;
 }
 
 const auto nonconvert= poll->g;
 if(!nonconvert)  {
 LOGAR("glucose = 0");
 return 0L;
 }
 constexpr const int maxvalue=120;
 char value[maxvalue];
 //auto trend=usedtext->trends[poll->tr];
 const auto trend=usedtext->getTrendName(poll->tr);
 memcpy(value,trend.data(),trend.size());
 char *ptr=value+trend.size();
 *ptr++='\n';
 const float glucosevalue= gconvert(nonconvert*10);
 snprintf(ptr,maxvalue+value-ptr,gformat,glucosevalue);
 LOGGER("saylastglucose %s\n",value);
 speak(value);
 }
 return -1L;
 }
#endif
#ifndef WEAROS
//MENUS functions:
extern "C" JNIEXPORT jboolean JNICALL fromjava(getsystemui)(JNIEnv *env, jclass thiz) {
 return showui;
 }
extern "C" JNIEXPORT void JNICALL fromjava(setsystemui)(JNIEnv *env, jclass thiz,jboolean val) {
 showui=val;
 settings->setui(showui);
 }

extern "C" JNIEXPORT void JNICALL fromjava(settonow)(JNIEnv *env, jclass thiz) {
 appcurve.unlockGraphYRange();
 const auto now=static_cast<uint32_t>(time(nullptr));
 appcurve.setLiveWindow(now);
 }


extern struct lastscan_t scantoshow;
extern "C" JNIEXPORT jboolean JNICALL fromjava(showlastscan)(JNIEnv *env, jclass thiz) {
 int lastsensor=sensors->lastscanned();
 if(lastsensor>=0) {
     const SensorGlucoseData *hist=sensors->getSensorData(lastsensor);
     if(hist) {
         const ScanData *scan= hist->lastscan();
         const uint32_t nu=time(nullptr);
         if(scan&&scan->valid()&&((nu-scan->t)<(60*60*5))) {
             scantoshow={lastsensor,scan,nu,settings->data()->DoCalibrate};
             return true;
             }
         }
     }
 return false;
 }


extern int statusbarheight;
extern int statusbarleft,statusbarright;
extern "C" JNIEXPORT void JNICALL fromjava(systembar)(JNIEnv *env, jclass thiz,jint left,jint top,jint right,jint bottom) {
 appcurve.statusbarheight=top*4/5;
 appcurve.statusbarleft=left;
 appcurve.statusbarright=right;
 appcurve.dbottom=bottom;
// resizescreen(width, height,width);
 appcurve.withbottom();
 }



#include "fromjava.h"
extern bool fixatey;

extern bool showsummarygraph;
extern "C" JNIEXPORT void JNICALL fromjava(summarygraph) (JNIEnv *env, jclass clazz,jboolean val) {
	 showsummarygraph=val;
	 if(val) {
		 fixatey=false;
		 }
	else {
	 fixatey=settings->data()->fixatey;

		}
	}

extern int daystoanalyse;
extern bool showhistory;
extern bool mkpercentiles(int days,bool history);
extern "C" JNIEXPORT jboolean JNICALL fromjava(analysedays) (JNIEnv *env, jclass clazz,jint days,jboolean history) {
         if(days > 0) {
             daystoanalyse = days;
             }
         showhistory=history;
         auto ret=mkpercentiles(daystoanalyse,history);
         LOGGER("end analysedays(%d)=%d\n",days,ret);
         return ret;
	}
extern "C" JNIEXPORT jint JNICALL fromjava(getAnalysedays) (JNIEnv *env, jclass clazz) {
	return daystoanalyse;
	}
extern "C" JNIEXPORT jboolean JNICALL fromjava(getAnalysehistory) (JNIEnv *env, jclass clazz) {
	return showhistory;
	}

extern bool showpers;
extern "C" JNIEXPORT void JNICALL fromjava(endstats) (JNIEnv *env, jclass clazz) {
	showpers=0;
	appcurve.begrenstijd() ;
	}
#include "net/watchserver/Getopts.hpp"
extern int getminutes(time_t tim);
extern "C" JNIEXPORT jlong JNICALL fromjava(percentileEndtime) (JNIEnv *env, jclass clazz,jint days) {
 const uint32_t  endtime=appcurve.starttime+appcurve.duration;
 const uint32_t startday=endtime-getminutes(endtime)*60;
 const uint32_t endday=startday+daysecs-1;
 Getopts opts;
 opts.end=endday;
 opts.start=endday-days*daysecs;
 return endtime;
 }

#endif
extern "C" JNIEXPORT void  JNICALL   fromjava(setInvertColors)(JNIEnv *env, jclass cl,jboolean val) {
	appcurve.setinvertcolors(val);
	}

#if defined(JUGGLUCO_APP)&&!defined(WEAROS)
extern bool  exportdata(uint32_t starttime, uint32_t duration,int type,int fd,float days) ;
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(exportdata)(JNIEnv *env, jclass cl,jint type,jint fd,jfloat days) {
 return  exportdata(appcurve.starttime, appcurve.duration,type,fd,days);
 }
#endif

extern bool fixatex;
extern "C" JNIEXPORT void  JNICALL   fromjava(setfixatex)(JNIEnv *env, jclass cl,jboolean val) {
	settings->data()->fixatex=val;
	fixatex=val;
	settings->data()->duration=appcurve.duration;
	}
void setallunit(int unit) {
	settings->setunit(unit);
 appcurve.setunit(unit);
 }
extern "C" JNIEXPORT void  JNICALL   fromjava(setunit)(JNIEnv *env, jclass cl,jint unit) {
 setallunit(unit);
	}
