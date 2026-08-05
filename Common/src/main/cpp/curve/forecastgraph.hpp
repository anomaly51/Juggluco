#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace forecastgraph {

enum class ActivityKind : std::int32_t {
    Meal=1,
    RapidInsulin=2,
    LongInsulin=3
};

struct Point {
    std::uint32_t time;
    float medianMgDl;
    float lowMgDl;
    float highMgDl;
};

struct ActivitySample {
    std::uint32_t time;
    float level;
};

struct Activity {
    ActivityKind kind;
    std::uint32_t start;
    std::uint32_t peak;
    std::uint32_t end;
    float strength;
    float confidence;
    // Optional model-sampled instantaneous activity. Samples normally begin at
    // the latest real CGM anchor. The renderer deliberately keeps the summary
    // curve for earlier history and as a compatibility fallback.
    std::vector<ActivitySample> samples;
    // Optional effective-action metadata. Zero keeps the legacy representative
    // timestamp. Identity is stable across refreshes and is presentation-only.
    std::uint32_t onset=0U;
    std::uint32_t peakLow=0U;
    std::uint32_t peakHigh=0U;
    std::uint32_t endLow=0U;
    std::uint32_t endHigh=0U;
    std::int32_t identity=0;
    std::int32_t overlapCount=0;
    float attributionConfidence=-1.0f;
};

struct Snapshot {
    std::vector<Point> points;
    std::vector<Activity> activities;
    float confidence=0.0f;
};

constexpr bool valid_kind(const std::int32_t kind) {
    return kind>=static_cast<std::int32_t>(ActivityKind::Meal)&&
           kind<=static_cast<std::int32_t>(ActivityKind::LongInsulin);
}

constexpr float clamp01(const float value) {
    return value<0.0f?0.0f:(value>1.0f?1.0f:value);
}

// Strength may be a normalized model score or a positive physical amount.
// This maps both to a stable visual weight without letting a large dose/meal
// dominate the clinical glucose trace.
constexpr float visual_strength(const float value) {
    return value<=0.0f?0.0f:(value<=1.0f?value:value/(value+1.0f));
}

// Activity time always advances left-to-right on the graph. Keep the shape in
// one deterministic helper so rendering can never leak a meal/insulin effect
// into time before the event. A backend peak at either boundary is pulled one
// second inside the interval, preserving both a real peak and an end fade.
constexpr std::uint32_t activity_peak(const std::uint32_t start,
                                      const std::uint32_t suggestedPeak,
                                      const std::uint32_t end) {
    if(end<=start||end-start<=1U)
        return start;
    return suggestedPeak<=start?start+1U:
           (suggestedPeak>=end?end-1U:suggestedPeak);
}

constexpr float activity_level(const std::uint32_t time,
                               const std::uint32_t start,
                               const std::uint32_t suggestedPeak,
                               const std::uint32_t end) {
    if(end<=start||end-start<=1U||time<start||time>=end)
        return 0.0f;
    const std::uint32_t peak=activity_peak(start,suggestedPeak,end);
    if(time<=peak)
        return static_cast<float>(time-start)/
               static_cast<float>(peak-start);
    return static_cast<float>(end-time)/
           static_cast<float>(end-peak);
}

constexpr float interpolate_activity_level(const std::uint32_t time,
                                           const std::uint32_t leftTime,
                                           const float leftLevel,
                                           const std::uint32_t rightTime,
                                           const float rightLevel) {
    if(rightTime<=leftTime)
        return clamp01(rightLevel);
    if(time<=leftTime)
        return clamp01(leftLevel);
    if(time>=rightTime)
        return clamp01(rightLevel);
    const float fraction=static_cast<float>(time-leftTime)/
                         static_cast<float>(rightTime-leftTime);
    return clamp01(leftLevel+(rightLevel-leftLevel)*fraction);
}

// Use the non-parametric model curve only inside its sampled causal window.
// Before the first sample we retain the historical summary shape. Beyond the
// final sample, the summary tail is scaled to meet the final model level so the
// line remains continuous on graph ranges longer than the 120-minute forecast.
inline float activity_level(const Activity &activity,const std::uint32_t time) {
    const std::uint32_t actionStart=activity.onset>=activity.start&&
                                    activity.onset<=activity.peak?
                                    activity.onset:activity.start;
    if(time<=actionStart||time>=activity.end)
        return 0.0f;
    const float fallback=activity_level(time,actionStart,activity.peak,
                                        activity.end);
    if(activity.samples.size()<2U||time<activity.samples.front().time)
        return fallback;
    if(time>activity.samples.back().time) {
        const auto &last=activity.samples.back();
        const float lastFallback=activity_level(last.time,actionStart,
                                                activity.peak,activity.end);
        return lastFallback>0.0001f?
               clamp01(fallback*clamp01(last.level)/lastFallback):fallback;
    }
    const auto right=std::lower_bound(activity.samples.begin(),
                                      activity.samples.end(),time,
            [](const ActivitySample &sample,const std::uint32_t value) {
                return sample.time<value;
            });
    if(right==activity.samples.begin())
        return clamp01(right->level);
    if(right==activity.samples.end())
        return clamp01(activity.samples.back().level);
    if(right->time==time)
        return clamp01(right->level);
    const auto &rawLeft=*(right-1);
    const std::uint32_t leftTime=std::max(rawLeft.time,actionStart);
    const float leftLevel=rawLeft.time<=actionStart?0.0f:rawLeft.level;
    return interpolate_activity_level(time,leftTime,leftLevel,
                                      right->time,right->level);
}

constexpr bool historical_activity_segment(const std::uint32_t segmentEnd,
                                            const std::uint32_t now) {
    return segmentEnd<=now;
}

constexpr std::uint32_t future_padding(const std::uint32_t duration) {
    return std::min<std::uint32_t>(10U*60U,duration/20U);
}

constexpr std::uint32_t live_start(const std::uint32_t now,
                                   const std::uint32_t duration,
                                   const std::uint32_t forecastEnd) {
    if(!duration)
        return now;
    if(forecastEnd<=now)
        return now>duration*9U/10U?now-duration*9U/10U:0U;
    const std::uint32_t horizon=forecastEnd-now;
    const std::uint32_t padding=future_padding(duration);
    const std::uint32_t minimumHistory=std::min<std::uint32_t>(30U*60U,
                                                               duration/6U);
    const std::uint32_t maximumFuture=duration>minimumHistory?
                                      duration-minimumHistory:duration;
    const std::uint32_t wantedFuture=std::min<std::uint32_t>(
            maximumFuture,horizon>UINT32_MAX-padding?UINT32_MAX:
                                                  horizon+padding);
    const std::uint32_t history=duration>wantedFuture?
                                duration-wantedFuture:0U;
    return now>history?now-history:0U;
}

constexpr std::uint32_t maximum_start(const std::uint32_t legacyMaximum,
                                      const std::uint32_t duration,
                                      const std::uint32_t forecastEnd) {
    const std::uint32_t padding=future_padding(duration);
    const std::uint64_t paddedEnd=static_cast<std::uint64_t>(forecastEnd)+
                                  padding;
    if(paddedEnd<=duration)
        return legacyMaximum;
    const std::uint32_t forecastMaximum=static_cast<std::uint32_t>(
            std::min<std::uint64_t>(UINT32_MAX,paddedEnd-duration));
    return std::max(legacyMaximum,forecastMaximum);
}

// Forecasts are canonical mg/dL. These helpers cap only the uncertainty width;
// the central trajectory itself remains free to represent a large real move.
constexpr float bounded_low(const Point &point) {
    const float center=std::clamp(point.medianMgDl,20.0f,600.0f);
    return std::min(center,
                    std::max(20.0f,std::max(point.lowMgDl,center-90.0f)));
}

constexpr float bounded_high(const Point &point) {
    const float center=std::clamp(point.medianMgDl,20.0f,600.0f);
    return std::max(center,
                    std::min(600.0f,std::min(point.highMgDl,center+90.0f)));
}

static_assert(valid_kind(1)&&valid_kind(2)&&valid_kind(3));
static_assert(!valid_kind(0)&&!valid_kind(4));
static_assert(activity_peak(100U,80U,200U)==101U);
static_assert(activity_peak(100U,220U,200U)==199U);
static_assert(activity_level(99U,100U,150U,200U)==0.0f);
static_assert(activity_level(100U,100U,150U,200U)==0.0f);
static_assert(activity_level(125U,100U,150U,200U)==0.5f);
static_assert(activity_level(150U,100U,150U,200U)==1.0f);
static_assert(activity_level(175U,100U,150U,200U)==0.5f);
static_assert(activity_level(200U,100U,150U,200U)==0.0f);
static_assert(interpolate_activity_level(125U,100U,0.0f,150U,1.0f)==0.5f);
static_assert(historical_activity_segment(120U,120U));
static_assert(!historical_activity_segment(121U,120U));
static_assert(live_start(10'000U,3U*60U*60U,10'000U+2U*60U*60U)==
              10'000U-60U*60U+9U*60U);
static_assert(maximum_start(100U,3'600U,7'200U)==3'780U);
static_assert(bounded_low({0U,120.0f,1.0f,400.0f})==30.0f);
static_assert(bounded_high({0U,120.0f,1.0f,400.0f})==210.0f);

} // namespace forecastgraph

void replaceForecastGraph(std::vector<forecastgraph::Point> points,
                          float confidence);
void replaceForecastActivities(std::vector<forecastgraph::Activity> activities);
forecastgraph::Snapshot forecastGraphSnapshot();
std::uint32_t forecastGraphEndTime();
